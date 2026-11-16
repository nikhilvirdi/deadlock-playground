import {
    requestResource,
    releaseResource,
    step
} from '../api/endpoints.js';
import { clearSimulationFeedback, showSimulationError, showSimulationFeedback } from './feedback.js';

const requestOutcomes = new Map([
    ['GRANTED', { label: 'granted', status: 'success' }],
    ['BLOCKED', { label: 'blocked', status: 'warning' }],
    ['DENIED_UNSAFE', { label: 'denied because it would leave the system in an unsafe state', status: 'warning' }],
    ['REJECTED_PREVENTION', { label: 'rejected by a prevention rule', status: 'error' }]
]);

let initialized = false;
let refreshHandler = null;

let procSelect = null;
let resSelect = null;
let countInput = null;
let btnRequest = null;
let btnRelease = null;
let btnStep = null;
let resultLine = null;
let opErrorLine = null;

function readCount(revision) {
    const raw = countInput.value;
    if (raw === '' && !countInput.validity.badInput) return 1;
    const count = Number(raw);
    if (/^\d+$/.test(raw) && count >= 1 && count <= 2147483647) return count;
    showSimulationFeedback(opErrorLine, revision, 'Enter a whole number from 1 to 2147483647.', 'error');
    return null;
}

function createActionSection(container) {
    const group = document.createElement('div');
    group.className = 'form-group simulation-actions';

    const controlsRow = document.createElement('div');
    controlsRow.className = 'action-inputs-row';

    const procLabel = document.createElement('label');
    procLabel.textContent = 'Process';
    procSelect = document.createElement('select');
    procSelect.id = 'action-proc-select';
    procLabel.appendChild(procSelect);
    controlsRow.appendChild(procLabel);

    const resLabel = document.createElement('label');
    resLabel.textContent = 'Resource';
    resSelect = document.createElement('select');
    resSelect.id = 'action-res-select';
    resLabel.appendChild(resSelect);
    controlsRow.appendChild(resLabel);

    const countLabel = document.createElement('label');
    countLabel.textContent = 'Count';
    countInput = document.createElement('input');
    countInput.type = 'number';
    countInput.id = 'action-count-input';
    countInput.min = '1';
    countInput.placeholder = '1';
    countLabel.appendChild(countInput);
    controlsRow.appendChild(countLabel);

    group.appendChild(controlsRow);

    const btnRow = document.createElement('div');
    btnRow.className = 'action-buttons-row';

    btnRequest = document.createElement('button');
    btnRequest.type = 'button';
    btnRequest.id = 'btn-request';
    btnRequest.textContent = 'Request';
    btnRow.appendChild(btnRequest);

    btnRelease = document.createElement('button');
    btnRelease.type = 'button';
    btnRelease.id = 'btn-release';
    btnRelease.textContent = 'Release';
    btnRow.appendChild(btnRelease);

    btnStep = document.createElement('button');
    btnStep.type = 'button';
    btnStep.id = 'btn-step';
    btnStep.textContent = 'Step';
    btnRow.appendChild(btnStep);

    group.appendChild(btnRow);

    resultLine = document.createElement('div');
    resultLine.id = 'action-result-line';
    resultLine.className = 'simulation-feedback';
    resultLine.setAttribute('role', 'status');
    group.appendChild(resultLine);

    opErrorLine = document.createElement('div');
    opErrorLine.id = 'action-op-error';
    opErrorLine.className = 'simulation-feedback';
    opErrorLine.setAttribute('role', 'status');
    group.appendChild(opErrorLine);

    btnRequest.addEventListener('click', async () => {
        const revision = clearSimulationFeedback();
        const process = procSelect.value;
        const resource = resSelect.value;
        if (!process || !resource) {
            showSimulationFeedback(opErrorLine, revision, 'Please select a process and a resource.', 'error');
            return;
        }
        const count = readCount(revision);
        if (count === null) return;

        try {
            const data = await requestResource(process, resource, count);
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            const outcome = requestOutcomes.get(data.outcome);
            const message = data.reason && (data.outcome === 'GRANTED' || data.outcome === 'BLOCKED')
                ? data.reason
                : [`Request ${outcome?.label ?? 'processed'}`, data.reason].filter(Boolean).join('. ');
            showSimulationFeedback(resultLine, revision, message, outcome?.status);
        } catch (err) {
            showSimulationError(opErrorLine, revision, err);
        }
    });

    btnRelease.addEventListener('click', async () => {
        const revision = clearSimulationFeedback();
        const process = procSelect.value;
        const resource = resSelect.value;
        if (!process || !resource) {
            showSimulationFeedback(opErrorLine, revision, 'Please select a process and a resource.', 'error');
            return;
        }
        const count = readCount(revision);
        if (count === null) return;

        try {
            const data = await releaseResource(process, resource, count);
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            let message = `Released ${count} of ${resource} from ${process}.`;
            if (data.unblocked?.length) message += ` Unblocked ${data.unblocked.join(', ')}.`;
            showSimulationFeedback(resultLine, revision, message, 'success');
        } catch (err) {
            showSimulationError(opErrorLine, revision, err);
        }
    });

    btnStep.addEventListener('click', async () => {
        const revision = clearSimulationFeedback();
        try {
            const data = await step();
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            let description = data.executed?.description || '';
            let status = 'success';
            const outcomeCode = description.match(/: (BLOCKED|DENIED_UNSAFE|REJECTED_PREVENTION)$/)?.[1];
            if (outcomeCode) {
                const outcome = requestOutcomes.get(outcomeCode);
                description = outcomeCode === 'REJECTED_PREVENTION'
                    ? description.slice(0, -outcomeCode.length - 2)
                    : description.slice(0, -outcomeCode.length) + outcome.label;
                status = outcome.status;
            } else if (description === 'No runnable process; deadlock or waiting') {
                description = 'No process can run. Processes are waiting or deadlocked';
                status = 'warning';
            }
            if (data.finished) {
                btnStep.disabled = true;
                if (description !== 'Scenario finished') {
                    description = description ? `${description}. Scenario finished` : 'Scenario finished';
                }
            }
            showSimulationFeedback(resultLine, revision, `${description || 'Step executed'}.`, status);
        } catch (err) {
            showSimulationError(opErrorLine, revision, err);
        }
    });

    container.appendChild(group);
}

function updateSelectOptions(selectEl, items, idKey) {
    if (!selectEl) {
        return;
    }
    const currentVal = selectEl.value;
    selectEl.textContent = '';

    for (const item of items) {
        const id = item[idKey];
        const opt = document.createElement('option');
        opt.value = id;
        opt.textContent = id;
        selectEl.appendChild(opt);
    }

    if (currentVal && items.some(i => i[idKey] === currentVal)) {
        selectEl.value = currentVal;
    }
}

export function renderActionPanel(state, onRefresh) {
    if (typeof onRefresh === 'function') {
        refreshHandler = onRefresh;
    }

    const panel = document.getElementById('action-panel');
    if (!panel) {
        return;
    }

    const container = panel.querySelector('.panel-content') || panel;

    if (!initialized) {
        container.textContent = '';
        createActionSection(container);
        initialized = true;
    }

    const actualState = (state && state.state) ? state.state : state;
    const processes = (actualState && Array.isArray(actualState.processes)) ? actualState.processes : [];
    const resources = (actualState && Array.isArray(actualState.resources)) ? actualState.resources : [];

    updateSelectOptions(procSelect, processes, 'id');
    updateSelectOptions(resSelect, resources, 'id');

    if (actualState && Array.isArray(actualState.processes)) {
        const totalPending = actualState.processes.reduce((sum, p) => sum + (p.pendingOperations || 0), 0);
        if (btnStep) btnStep.disabled = totalPending <= 0;
    } else {
        if (btnStep) btnStep.disabled = true;
        if (countInput) countInput.value = '';
    }
}
