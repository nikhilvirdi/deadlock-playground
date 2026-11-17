// Run after mvn package with Node 22+:
// node tests/browser/history-sync.cjs <path-to-chrome>
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { copyFile, mkdtemp, readFile, rm } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const { dirname, join, resolve } = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

const chromePath = process.argv[2];
assert.ok(chromePath, 'Pass the path to Chrome or Chromium');
const root = resolve(__dirname, '../..');

async function until(read, description) {
    const deadline = Date.now() + 12000;
    while (Date.now() < deadline) {
        const value = await read();
        if (value) return value;
        await delay(25);
    }
    throw new Error('Timed out waiting for ' + description);
}

async function run() {
    const temporaryRoot = resolve(tmpdir());
    const working = await mkdtemp(join(temporaryRoot, 'deadlock-package-test-'));
    const profile = join(working, 'chrome');
    const javaPath = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
    const jar = join(working, 'deadlock-playground.jar');
    await copyFile(join(root, 'backend/target/deadlock-playground-1.0.jar'), jar);
    const simulator = spawn(javaPath, ['-jar', jar], {
        cwd: working,
        env: { ...process.env, PORT: '0', BIND_ADDRESS: '127.0.0.1', PUBLIC_ORIGIN: '', FRONTEND_DIR: '' },
        windowsHide: true, stdio: ['ignore', 'pipe', 'inherit']
    });
    const chrome = spawn(chromePath, [
        '--headless', '--disable-background-networking', '--no-first-run', '--remote-debugging-port=0',
        '--user-data-dir=' + profile, 'about:blank'
    ], { windowsHide: true, stdio: 'ignore' });
    let simulatorOutput = '';
    let launchError;
    simulator.stdout.on('data', chunk => simulatorOutput += chunk);
    simulator.on('error', error => launchError = error);
    chrome.on('error', error => launchError = error);
    let socket;
    try {
        const port = await until(() => {
            if (launchError) throw launchError;
            return simulatorOutput.match(/127\.0\.0\.1:([0-9]+)/)?.[1];
        }, 'private simulator startup');
        const origin = 'http://127.0.0.1:' + port;
        let cookie;
        async function api(route, body) {
            const headers = {};
            if (cookie) headers.Cookie = cookie;
            const options = { headers };
            if (body !== undefined) {
                options.method = 'POST';
                options.body = JSON.stringify(body);
                headers['Content-Type'] = 'application/json';
            }
            const response = await fetch(origin + '/api/' + route, options);
            const issued = response.headers.get('Set-Cookie');
            if (issued) cookie = issued.split(';', 1)[0];
            const result = await response.json();
            assert.ok(result.ok, JSON.stringify(result.error));
            return result.data;
        }
        const operation = (type, resource) => ({ type, resource, count: 1 });
        const scenario = resource => ({
            name: 'History sync test',
            resources: [{ id: 'A', total: 1 }, { id: 'B', total: 2, shareable: true }],
            processes: [{ id: 'P', priority: 7, operations: [operation('REQUEST', resource), operation('RELEASE', resource)] }]
        });
        await api('scenario', scenario('A'));
        const devTools = await until(() => readFile(join(profile, 'DevToolsActivePort'), 'utf8')
            .then(value => value.split(String.fromCharCode(10))[0].trim()).catch(() => ''), 'Chrome DevTools port');
        const targets = await fetch('http://127.0.0.1:' + devTools + '/json/list').then(response => response.json());
        socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
        await once(socket, 'open');
        let commandId = 0;
        const callbacks = new Map();
        socket.addEventListener('message', ({ data }) => {
            const message = JSON.parse(data);
            if (message.method === 'Page.javascriptDialogOpening') {
                void send('Page.handleJavaScriptDialog', { accept: true });
            }
            const callback = callbacks.get(message.id);
            if (callback) {
                callbacks.delete(message.id);
                callback(message);
            }
        });
        function send(method, params = {}) {
            return new Promise((fulfill, reject) => {
                const id = ++commandId;
                callbacks.set(id, message => message.error ? reject(Error(message.error.message)) : fulfill(message.result));
                socket.send(JSON.stringify({ id, method, params }));
            });
        }
        async function evaluate(expression) {
            const { result, exceptionDetails } = await send('Runtime.evaluate', {
                expression, returnByValue: true, awaitPromise: true, userGesture: true
            });
            if (exceptionDetails) throw Error(result.description || exceptionDetails.text);
            return result.value;
        }
        const waitFor = (expression, description) => until(async () => {
            try {
                return await evaluate(expression);
            } catch (error) {
                if (/execution context|Cannot find context/i.test(error.message)) return false;
                throw error;
            }
        }, description);
        const click = selector => evaluate('document.querySelector(' + JSON.stringify(selector) + ').click()');
        const ready = () => waitFor("!window.previousDocument && window.cy && !document.querySelector('#btn-submit-configure')?.disabled", 'synchronized app');
        const rendered = () => evaluate(`({
            seq: getCurrentSeq(),
            selected: document.querySelector('.current-event')?.dataset.seq ?? null,
            rows: document.querySelectorAll('#history-panel tbody tr').length,
            edges: cy.edges().map(edge => [edge.source().data('label'), edge.target().data('label')]),
            pending: document.querySelector('#process-panel dd:last-child')?.textContent ?? null,
            available: [...document.querySelectorAll('#resource-panel .state-entry dd:nth-child(4)')].map(el => el.textContent),
            script: document.querySelector('#cfg-operations').value,
            stepDisabled: document.querySelector('#btn-step').disabled
        })`);
        const waitForStep = value => waitFor('getCurrentSeq() === ' + value
            + " && !document.querySelector('#btn-submit-configure').disabled", 'step ' + value);
        async function reload(expected) {
            await evaluate('window.previousDocument = true');
            await send('Page.reload');
            await ready();
            assert.deepEqual(await api('history?view=true'), expected);
        }

        await send('Page.enable');
        await send('Network.enable');
        const separator = cookie.indexOf('=');
        const stored = await send('Network.setCookie', {
            name: cookie.slice(0, separator), value: cookie.slice(separator + 1), url: origin,
            httpOnly: true, sameSite: 'Strict'
        });
        assert.equal(stored.success, true);
        await send('Emulation.setDeviceMetricsOverride', { width: 1366, height: 768, deviceScaleFactor: 1, mobile: false });
        const hook = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
            const nativeFetch = window.fetch.bind(window);
            let holdInitial = true;
            window.fetch = async (url, options) => {
                if (url !== '/api/history?view=true') return nativeFetch(url, options);
                if (window.rejectNextView) {
                    window.rejectNextView = false;
                    throw new Error('Injected view failure');
                }
                if (!holdInitial) return nativeFetch(url, options);
                holdInitial = false;
                const response = await nativeFetch(url, options);
                const payload = await response.json();
                await new Promise(resolve => { window.releaseInitial = resolve; });
                return { json: async () => payload };
            };
        ` });
        await send('Page.navigate', { url: origin });
        await waitFor("typeof window.releaseInitial === 'function'", 'initial response held after the server produced it');
        assert.equal(await evaluate("document.querySelector('#btn-step').disabled"), true);
        await api('step', {});
        await evaluate('refresh()');
        await waitForStep(1);
        await ready();
        const newer = await rendered();
        assert.deepEqual(newer, {
            seq: 1, selected: '1', rows: 2, edges: [['A', 'P']], pending: '1', available: ['0', '2'],
            script: 'P REQUEST A 1\nP RELEASE A 1', stepDisabled: false
        });
        await evaluate('releaseInitial(); new Promise(resolve => setTimeout(resolve, 0))');
        assert.deepEqual(await rendered(), newer);
        await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: hook.identifier });

        await evaluate(`
            document.querySelector('#cfg-operations').value = 'unsaved draft';
            document.querySelector('#cfg-prev-noPreemption').checked = true;
            window.rejectNextView = true;
        `);
        await click('#btn-step');
        await waitFor("document.querySelector('#action-op-error').textContent.includes('could not be refreshed')", 'post-commit synchronization warning');
        const failedRefresh = await rendered();
        assert.deepEqual(failedRefresh.edges, newer.edges);
        assert.equal(failedRefresh.seq, 1);
        assert.equal(failedRefresh.stepDisabled, true);
        const completed = await api('history?view=true');
        assert.equal(completed.state.processes[0].state, 'COMPLETED');
        assert.equal(completed.events.length, 4);
        await evaluate('refresh()');
        assert.deepEqual(await api('history?view=true'), completed);
        assert.equal((await rendered()).script, 'unsaved draft');
        assert.equal(await evaluate("document.querySelector('#cfg-prev-noPreemption').checked"), true);
        await reload(completed);
        assert.equal((await rendered()).stepDisabled, true);
        assert.equal((await rendered()).script, newer.script);

        await click('#history-panel tr[data-seq="0"] button');
        await waitForStep(0);
        assert.equal((await rendered()).stepDisabled, false);
        await reload(await api('history?view=true'));
        assert.deepEqual((await rendered()).edges, []);
        assert.equal((await rendered()).selected, '0');

        await api('scenario', scenario('B'));
        await api('step', {});
        const replacement = await api('history?view=true');
        await click('#history-panel tr[data-seq="0"] button');
        await waitForStep(1);
        assert.deepEqual(await api('history?view=true'), replacement);
        assert.match(await evaluate("document.querySelector('#action-op-error').textContent"), /History changed/);
        assert.equal((await rendered()).script, 'P REQUEST B 1\nP RELEASE B 1');
        await reload(replacement);
        assert.equal((await rendered()).script, 'P REQUEST B 1\nP RELEASE B 1');

        await click('#btn-submit-configure');
        await waitForStep(0);
        await click('#btn-step');
        await waitForStep(1);
        await reload(await api('history?view=true'));
        assert.equal((await rendered()).script, 'P REQUEST B 1\nP RELEASE B 1');
        assert.deepEqual((await rendered()).edges, [['B', 'P']]);

        const waiting = scenario('A');
        waiting.processes = [
            { id: 'P', operations: [operation('REQUEST', 'A')] },
            { id: 'Q', operations: [operation('REQUEST', 'B')] },
            { id: 'Holder' }
        ];
        await api('scenario', waiting);
        await api('request', { process: 'Holder', resource: 'A', count: 1 });
        await api('step', {});
        await evaluate('refresh()');
        assert.equal((await rendered()).stepDisabled, false);
        await click('#btn-step');
        await waitForStep(4);
        assert.equal((await rendered()).stepDisabled, true);
        await evaluate("document.querySelector('#action-proc-select').value = 'Holder'");
        await click('#btn-release');
        await waitFor("getCurrentSeq() > 4 && !document.querySelector('#btn-submit-configure').disabled", 'release wakes the last waiter');
        assert.deepEqual((await rendered()).edges, []);

        const deadlock = scenario('A');
        deadlock.processes = [{ id: 'P' }, { id: 'Q', priority: 2 }];
        await api('scenario', deadlock);
        for (const [process, resource] of [['P', 'A'], ['Q', 'B'], ['P', 'B'], ['Q', 'A']]) {
            await api('request', { process, resource, count: resource === 'B' ? 2 : 1 });
        }
        await evaluate('refresh()');
        await click('#btn-detect');
        await waitFor("document.querySelector('#detect-result-line').textContent.includes('Deadlock detected')", 'detection result');
        assert.equal(await evaluate("document.querySelectorAll('#process-panel .deadlocked').length"), 2);
        await click('#btn-recover');
        await waitFor("document.querySelector('#recover-result-line').textContent.includes('Deadlock resolved')", 'recovery result');
        const recovered = await api('history?view=true');
        assert.equal(recovered.state.processes[0].state, 'TERMINATED');
        assert.equal((await rendered()).rows, recovered.events.length);
        assert.equal((await rendered()).selected, String(recovered.state.step));

        const collision = scenario('A');
        collision.resources = [{ id: 'X', total: 1 }, { id: 'edge:1 / special', total: 1 }];
        collision.processes = [{ id: 'X' }, { id: 'e1' }];
        await api('scenario', collision);
        await api('request', { process: 'X', resource: 'X', count: 1 });
        await api('request', { process: 'e1', resource: 'edge:1 / special', count: 1 });
        await evaluate('refresh()');
        assert.deepEqual(await evaluate(`cy.nodes().map(node => [node.id(), node.data('label')])`), [
            ['process:X', 'X'], ['process:e1', 'e1'], ['resource:X', 'X'], ['resource:edge:1 / special', 'edge:1 / special']
        ]);
        assert.equal(await evaluate("cy.elements().length"), 6);

        async function reloadWithoutGraph(label) {
            await evaluate('window.previousDocument = true');
            await send('Page.reload');
            await waitFor(`!window.previousDocument && !document.querySelector('#btn-submit-configure')?.disabled
                && document.querySelector('#graph-empty')?.textContent === 'Graph visualization is unavailable.'
                && !document.querySelector('#graph-empty').hidden`, label);
        }

        await send('Network.setBlockedURLs', { urls: ['*/vendor/dagre-0.8.5.min.js'] });
        await reloadWithoutGraph('missing local Dagre');
        await click('#btn-detect');
        await waitFor("document.querySelector('#detect-result-line').textContent.includes('No deadlock detected')", 'successful Detect without graph script');
        assert.equal(await evaluate("document.querySelector('#process-panel .state-entry h3').textContent"), 'X');
        await send('Network.setBlockedURLs', { urls: [] });

        const badData = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
            const fetchReal = window.fetch.bind(window);
            window.fetch = async (url, options) => {
                const response = await fetchReal(url, options);
                if (url !== '/api/history?view=true') return response;
                const payload = await response.json();
                payload.data.state.graph.edges[0].data.target = 'missing-node';
                return { json: async () => payload };
            };
        ` });
        await reloadWithoutGraph('malformed graph response');
        assert.ok(await evaluate("getCurrentSeq() > 0 && document.querySelector('.current-event')"));
        await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: badData.identifier });

        const brokenRenderer = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
            let wrappedCytoscape;
            Object.defineProperty(window, 'cytoscape', {
                configurable: true,
                get() { return wrappedCytoscape; },
                set(original) {
                    wrappedCytoscape = new Proxy(original, {
                        apply(target, receiver, args) {
                            if (typeof args[0] === 'object') throw new Error('Injected renderer failure');
                            return Reflect.apply(target, receiver, args);
                        }
                    });
                }
            });
        ` });
        await reloadWithoutGraph('renderer startup exception');
        assert.ok(await evaluate("document.querySelector('#btn-release') && !document.querySelector('#btn-release').disabled"));
        await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: brokenRenderer.identifier });
        await reload(await api('history?view=true'));

        await click('#btn-reset');
        await waitFor("getCurrentSeq() === null && !document.querySelector('#btn-submit-configure').disabled", 'reset');
        await reload(await api('history?view=true'));
        assert.deepEqual(await rendered(), {
            seq: null, selected: null, rows: 0, edges: [], pending: null, available: [], script: '', stepDisabled: true
        });
        assert.equal(await evaluate('document.documentElement.scrollHeight'), 768);
        await send('Network.clearBrowserCookies');
        await reload(await api('history?view=true'));
        await evaluate(`
            document.querySelector('#cfg-name').value = 'Browser-created session';
            document.querySelector('.cfg-res-id').value = 'Resource';
            document.querySelector('.cfg-res-total').value = '1';
            document.querySelector('.cfg-proc-id').value = 'Process';
            document.querySelector('#cfg-operations').value = 'Process REQUEST Resource 1';
        `);
        await click('#btn-submit-configure');
        await waitForStep(0);
        assert.equal(await evaluate("document.cookie.includes('deadlock-session')"), false);
        assert.equal(await evaluate("(async () => (await getState()).state.scenario.name)()"), 'Browser-created session');
        assert.equal((await api('history?view=true')).state, null);
        await reload(await api('history?view=true'));
        assert.equal(await evaluate("(async () => (await getState()).state.scenario.name)()"), 'Browser-created session');

        await Promise.race([send('Browser.close'), delay(1000)]);
        console.log('Packaged browser checks passed for history, sessions, graph identity, and startup fallback.');
    } finally {
        socket?.close();
        for (const child of [chrome, simulator]) {
            if (child.pid && child.exitCode === null && child.signalCode === null) {
                const exited = once(child, 'exit');
                if (child.kill()) await Promise.race([exited, delay(3000)]);
            }
        }
        simulator.stdout.destroy();
        if (dirname(resolve(working)) === temporaryRoot) {
            await rm(working, { recursive: true, force: true, maxRetries: 5 });
        }
    }
}

run().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
