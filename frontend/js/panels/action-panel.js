import {
    requestResource,
    releaseResource,
    step
} from '../api/endpoints.js';
import { showError, clearError } from './feedback.js';

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

function createActionSection(container) {
    const group = document.createElement('div');
    group.className = 'form-group simulation-actions';

    const heading = document.createElement('h3');
    heading.textContent = 'Operations';
    group.appendChild(heading);

    const controlsRow = document.createElement('div');
    controlsRow.className = 'action-inputs-row';

    const procLabel = document.createElement('label');
    procLabel.textContent = 'Process: ';
    procSelect = document.createElement('select');
    procSelect.id = 'action-proc-select';
    procLabel.appendChild(procSelect);
    controlsRow.appendChild(procLabel);

    const resLabel = document.createElement('label');
    resLabel.textContent = ' Resource: ';
    resSelect = document.createElement('select');
    resSelect.id = 'action-res-select';
    resLabel.appendChild(resSelect);
    controlsRow.appendChild(resLabel);

    const countLabel = document.createElement('label');
    countLabel.textContent = ' Count: ';
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
    resultLine.className = 'action-result-line';
    group.appendChild(resultLine);

    opErrorLine = document.createElement('div');
    opErrorLine.id = 'action-op-error';
    opErrorLine.className = 'error-line';
    group.appendChild(opErrorLine);

    btnRequest.addEventListener('click', async () => {
        clearError('action-op-error');
        resultLine.textContent = '';
        const process = procSelect.value;
        const resource = resSelect.value;
        const count = parseInt(countInput.value, 10) || 1;

        if (!process || !resource) {
            showError('action-op-error', { code: 'VALIDATION_ERROR', message: 'Please select a process and a resource.' });
            return;
        }

        try {
            const data = await requestResource(process, resource, count);
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            if (data.reason) {
                resultLine.textContent = `${data.outcome}: ${data.reason}`;
            } else {
                resultLine.textContent = `${data.outcome}`;
            }
        } catch (err) {
            showError('action-op-error', err);
        }
    });

    btnRelease.addEventListener('click', async () => {
        clearError('action-op-error');
        resultLine.textContent = '';
        const process = procSelect.value;
        const resource = resSelect.value;
        const count = parseInt(countInput.value, 10) || 1;

        if (!process || !resource) {
            showError('action-op-error', { code: 'VALIDATION_ERROR', message: 'Please select a process and a resource.' });
            return;
        }

        try {
            const data = await releaseResource(process, resource, count);
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            if (data.unblocked && data.unblocked.length > 0) {
                resultLine.textContent = `Unblocked: ${data.unblocked.join(', ')}`;
            } else {
                resultLine.textContent = 'Unblocked: none';
            }
        } catch (err) {
            showError('action-op-error', err);
        }
    });

    btnStep.addEventListener('click', async () => {
        clearError('action-op-error');
        resultLine.textContent = '';
        try {
            const data = await step();
            if (typeof refreshHandler === 'function') {
                await refreshHandler();
            }
            const desc = data.executed ? data.executed.description : '';
            if (data.finished) {
                btnStep.disabled = true;
                resultLine.textContent = desc ? `${desc} (Scenario finished)` : 'Scenario finished';
            } else {
                resultLine.textContent = desc || 'Step executed';
            }
        } catch (err) {
            showError('action-op-error', err);
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
        if (totalPending > 0) {
            if (btnStep) btnStep.disabled = false;
        }
    }
}
