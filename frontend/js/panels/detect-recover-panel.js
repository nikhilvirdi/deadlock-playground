import { detect, recover, reset } from '../api/endpoints.js';
import { clearSimulationFeedback, showSimulationError, showSimulationFeedback } from './feedback.js';

let initialized = false;
let refreshHandler = null;

let btnDetect = null;
let detectResultLine = null;
let detectErrorLine = null;

let policySelect = null;
let strategySelect = null;
let btnRecover = null;
let recoverResultLine = null;
let recoverErrorLine = null;

let btnReset = null;
let resetErrorLine = null;

function createDetectSection(container) {
    const group = document.createElement('div');
    group.className = 'form-group detect-group';

    const btnRow = document.createElement('div');
    btnRow.className = 'action-buttons-row';

    btnDetect = document.createElement('button');
    btnDetect.type = 'button';
    btnDetect.id = 'btn-detect';
    btnDetect.textContent = 'Detect';
    btnRow.appendChild(btnDetect);
    group.appendChild(btnRow);

    detectResultLine = document.createElement('div');
    detectResultLine.id = 'detect-result-line';
    detectResultLine.className = 'simulation-feedback';
    detectResultLine.setAttribute('role', 'status');
    group.appendChild(detectResultLine);

    detectErrorLine = document.createElement('div');
    detectErrorLine.id = 'detect-error-line';
    detectErrorLine.className = 'simulation-feedback';
    detectErrorLine.setAttribute('role', 'status');
    group.appendChild(detectErrorLine);

    btnDetect.addEventListener('click', async () => {
        const revision = clearSimulationFeedback();
        try {
            const data = await detect();
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            let message = 'No deadlock detected.';
            if (data.deadlocked) {
                const processes = data.processes?.length ? ` in ${data.processes.join(', ')}` : '';
                message = `Deadlock detected${processes}.`;
                if (data.cycle?.length) message += ` Cycle: ${data.cycle.join(' → ')}.`;
            }
            showSimulationFeedback(detectResultLine, revision, message, data.deadlocked ? 'error' : 'success');
        } catch (err) {
            showSimulationError(detectErrorLine, revision, err);
        }
    });

    container.appendChild(group);
}

function createRecoverSection(container) {
    const group = document.createElement('div');
    group.className = 'form-group recover-group';

    const heading = document.createElement('h3');
    heading.textContent = 'Deadlock Recovery';
    group.appendChild(heading);

    const controlsRow = document.createElement('div');
    controlsRow.className = 'action-inputs-row';

    const policyLabel = document.createElement('label');
    policyLabel.textContent = 'Policy';
    policySelect = document.createElement('select');
    policySelect.id = 'recover-policy-select';

    const policies = [
        { label: 'Recovery cost', value: 'RECOVERY_COST' },
        { label: 'Priority', value: 'PRIORITY' },
        { label: 'Resources held', value: 'RESOURCES_HELD' }
    ];
    for (const p of policies) {
        const opt = document.createElement('option');
        opt.value = p.value;
        opt.textContent = p.label;
        policySelect.appendChild(opt);
    }
    policySelect.value = 'RECOVERY_COST';
    policyLabel.appendChild(policySelect);
    controlsRow.appendChild(policyLabel);

    const strategyLabel = document.createElement('label');
    strategyLabel.textContent = 'Strategy';
    strategySelect = document.createElement('select');
    strategySelect.id = 'recover-strategy-select';

    const strategies = [
        { label: 'Terminate', value: 'TERMINATE' },
        { label: 'Preempt', value: 'PREEMPT' }
    ];
    for (const s of strategies) {
        const opt = document.createElement('option');
        opt.value = s.value;
        opt.textContent = s.label;
        strategySelect.appendChild(opt);
    }
    strategySelect.value = 'TERMINATE';
    strategyLabel.appendChild(strategySelect);
    controlsRow.appendChild(strategyLabel);

    group.appendChild(controlsRow);

    const btnRow = document.createElement('div');
    btnRow.className = 'action-buttons-row';

    btnRecover = document.createElement('button');
    btnRecover.type = 'button';
    btnRecover.id = 'btn-recover';
    btnRecover.textContent = 'Recover';
    btnRow.appendChild(btnRecover);
    group.appendChild(btnRow);

    recoverResultLine = document.createElement('div');
    recoverResultLine.id = 'recover-result-line';
    recoverResultLine.className = 'simulation-feedback';
    recoverResultLine.setAttribute('role', 'status');
    group.appendChild(recoverResultLine);

    recoverErrorLine = document.createElement('div');
    recoverErrorLine.id = 'recover-error-line';
    recoverErrorLine.className = 'simulation-feedback';
    recoverErrorLine.setAttribute('role', 'status');
    group.appendChild(recoverErrorLine);

    btnRecover.addEventListener('click', async () => {
        const revision = clearSimulationFeedback();
        const policy = policySelect.value;
        const strategy = strategySelect.value;

        try {
            const data = await recover(policy, strategy);
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            const rounds = (data.rounds || []).map(round => round.strategy === 'PREEMPT'
                ? `Preempted resources from ${round.victim}.`
                : `Terminated ${round.victim}.`);
            const outcome = data.resolved ? 'Deadlock resolved.' : 'Deadlock remains.';
            showSimulationFeedback(recoverResultLine, revision, [outcome, ...rounds].join(' '), data.resolved ? 'success' : 'error');
        } catch (err) {
            showSimulationError(recoverErrorLine, revision, err);
        }
    });

    container.appendChild(group);
}

function createResetSection(container) {
    const group = document.createElement('div');
    group.className = 'form-group reset-group';

    const btnRow = document.createElement('div');
    btnRow.className = 'action-buttons-row';

    btnReset = document.createElement('button');
    btnReset.type = 'button';
    btnReset.id = 'btn-reset';
    btnReset.textContent = 'Reset';
    btnRow.appendChild(btnReset);
    group.appendChild(btnRow);

    resetErrorLine = document.createElement('div');
    resetErrorLine.id = 'reset-error-line';
    resetErrorLine.className = 'simulation-feedback';
    resetErrorLine.setAttribute('role', 'status');
    group.appendChild(resetErrorLine);

    btnReset.addEventListener('click', async () => {
        const revision = clearSimulationFeedback();
        try {
            await reset(false);
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
        } catch (err) {
            if (err.code === 'VALIDATION_ERROR') {
                const confirmed = window.confirm('Reset will clear the event history. Continue?');
                if (confirmed) {
                    try {
                        await reset(true);
                        if (typeof refreshHandler === 'function') {
                            await refreshHandler();
                        }
                    } catch (innerErr) {
                        showSimulationError(resetErrorLine, revision, innerErr);
                    }
                }
            } else {
                showSimulationError(resetErrorLine, revision, err);
            }
        }
    });

    container.appendChild(group);
}

export function renderDetectRecoverPanel(state, onRefresh) {
    if (typeof onRefresh === 'function') {
        refreshHandler = onRefresh;
    }

    const panel = document.getElementById('detect-recover-panel');
    if (!panel) {
        return;
    }

    const container = panel.querySelector('.panel-content') || panel;

    if (!initialized) {
        container.textContent = '';
        createDetectSection(container);
        createRecoverSection(container);
        createResetSection(container);
        initialized = true;
    }
}
