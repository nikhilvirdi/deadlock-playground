import { request } from './client.js';

let cachedScenarioOperations = null;

function scenarioConfigurationKey(state) {
    return JSON.stringify([
        state.scenario.name,
        Boolean(state.scenario.bankersAvoidance),
        state.resources.map(({ id, total, shareable }) => [id, total, shareable]),
        state.processes.map(({ id, priority }) => [id, priority])
    ]);
}

export function savedScenarioOperations(state) {
    if (!state?.scenario || !cachedScenarioOperations) return '';

    if (cachedScenarioOperations.configuration !== scenarioConfigurationKey(state) || typeof cachedScenarioOperations.text !== 'string') return '';

    const matchesOperationCounts = state.processes.every(({ pendingOperations }, index) => {
        const count = cachedScenarioOperations.counts?.[index];
        return Number.isInteger(count) && (state.step === 0 ? pendingOperations === count : pendingOperations <= count);
    });
    return matchesOperationCounts ? cachedScenarioOperations.text : '';
}

export function clearSavedScenarioOperations() {
    cachedScenarioOperations = null;
}

export async function getState() {
    return request('GET', '/api/state');
}

export async function configureScenario(scenarioBody, operationsText) {
    const result = await request('POST', '/api/scenario', scenarioBody);
    // The state API exposes pending counts without the operation definitions.
    try {
        const scenario = typeof scenarioBody === 'string' ? JSON.parse(scenarioBody) : scenarioBody;
        const operations = scenario.processes.map(({ id, operations }) =>
            (Array.isArray(operations) ? operations : []).map(op =>
                `${id} ${op.type.toUpperCase()} ${op.resource} ${op.count ?? 1}`));
        cachedScenarioOperations = {
            configuration: scenarioConfigurationKey(result.state ?? result),
            counts: operations.map(lines => lines.length),
            text: operationsText ?? operations.flat().join('\n')
        };
    } catch {
        clearSavedScenarioOperations();
    }
    return result;
}

export async function reset(confirmed = false) {
    return request('POST', '/api/reset', { confirmed });
}

export async function getHistory() {
    return request('GET', '/api/history');
}

export async function requestResource(process, resource, count) {
    return request('POST', '/api/request', { process, resource, count });
}

export async function releaseResource(process, resource, count) {
    return request('POST', '/api/release', { process, resource, count });
}

export async function step() {
    return request('POST', '/api/step', {});
}

export async function detect() {
    return request('POST', '/api/detect', {});
}

export async function recover(policy = 'RECOVERY_COST', strategy = 'TERMINATE') {
    return request('POST', '/api/recover', {
        policy: policy !== undefined ? policy : 'RECOVERY_COST',
        strategy: strategy !== undefined ? strategy : 'TERMINATE'
    });
}

export async function jumpToEvent(seq) {
    return request('POST', '/api/history/jump', { seq });
}
