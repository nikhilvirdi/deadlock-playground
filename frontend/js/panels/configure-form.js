import { configureScenario } from '../api/endpoints.js';
import { showError, clearError, clearSimulationFeedback } from './feedback.js';

export function renderConfigureForm(container, runCommand, state) {
    if (!container) {
        return;
    }

    container.querySelector('#form-configure')?.remove();
    const active = state;

    const form = document.createElement('form');
    form.id = 'form-configure';

    const fields = document.createElement('div');
    fields.className = 'panel-content';
    fields.tabIndex = 0;
    fields.setAttribute('role', 'region');
    fields.setAttribute('aria-label', 'Scenario fields');
    form.appendChild(fields);

    const fieldError = document.createElement('div');
    fieldError.id = 'cfg-field-error';
    fieldError.className = 'error-line';
    fieldError.setAttribute('role', 'alert');
    let invalidField = null;

    function clearFieldError() {
        invalidField?.removeAttribute('aria-invalid');
        invalidField?.removeAttribute('aria-describedby');
        fieldError.textContent = '';
        invalidField = null;
    }

    function appendRemoveButton(row, entryType, addButton) {
        const removeButton = document.createElement('button');
        removeButton.type = 'button';
        removeButton.className = 'remove-entry';
        removeButton.textContent = 'Remove';
        removeButton.setAttribute('aria-label', `Remove ${entryType}`);
        removeButton.addEventListener('click', () => {
            const adjacentRow = row.nextElementSibling || row.previousElementSibling;
            if (row.contains(invalidField)) clearFieldError();
            row.remove();
            (adjacentRow?.querySelector('input') || addButton).focus();
        });
        row.appendChild(removeButton);
    }

    // Scenario Name
    const nameGroup = document.createElement('div');
    nameGroup.className = 'form-group scenario-name';
    const nameLabel = document.createElement('label');
    nameLabel.textContent = 'Scenario Name';
    const nameInput = document.createElement('input');
    nameInput.type = 'text';
    nameInput.id = 'cfg-name';
    nameInput.placeholder = 'Scenario Name';
    nameInput.value = active?.scenario?.name ?? '';
    nameLabel.appendChild(nameInput);
    nameGroup.appendChild(nameLabel);
    fields.appendChild(nameGroup);

    // Resources
    const resGroup = document.createElement('div');
    resGroup.className = 'form-group';
    const resHeading = document.createElement('h3');
    resHeading.textContent = 'Resources';
    resGroup.appendChild(resHeading);

    const resList = document.createElement('div');
    resList.className = 'resource-list';
    resGroup.appendChild(resList);

    function addResourceRow(defaultId = '', defaultTotal = '', defaultShareable = false) {
        const row = document.createElement('div');
        row.className = 'resource-row';

        const idLabel = document.createElement('label');
        idLabel.textContent = 'Resource ID';
        const idInput = document.createElement('input');
        idInput.type = 'text';
        idInput.className = 'cfg-res-id';
        idInput.placeholder = 'R1';
        if (defaultId) {
            idInput.value = defaultId;
        }
        idLabel.appendChild(idInput);
        row.appendChild(idLabel);

        const totalLabel = document.createElement('label');
        totalLabel.textContent = 'Total instances';
        const totalInput = document.createElement('input');
        totalInput.type = 'number';
        totalInput.min = '1';
        totalInput.className = 'cfg-res-total';
        totalInput.placeholder = '1';
        if (defaultTotal !== '' && defaultTotal !== null && defaultTotal !== undefined) {
            totalInput.value = defaultTotal;
        }
        totalLabel.appendChild(totalInput);
        row.appendChild(totalLabel);

        const shareLabel = document.createElement('label');
        shareLabel.className = 'checkbox-label';
        shareLabel.textContent = 'Shareable';
        const shareInput = document.createElement('input');
        shareInput.type = 'checkbox';
        shareInput.className = 'cfg-res-shareable';
        shareInput.checked = Boolean(defaultShareable);
        shareLabel.prepend(shareInput);
        row.appendChild(shareLabel);

        appendRemoveButton(row, 'resource', addResBtn);
        resList.appendChild(row);
        return idInput;
    }

    const addResBtn = document.createElement('button');
    addResBtn.type = 'button';
    addResBtn.textContent = 'Add resource';
    addResBtn.addEventListener('click', () => {
        if (invalidField === addResBtn) clearFieldError();
        addResourceRow().focus();
    });
    resGroup.appendChild(addResBtn);
    for (const { id, total, shareable } of active?.resources ?? [{}]) {
        addResourceRow(id, total, shareable);
    }
    fields.appendChild(resGroup);

    // Processes
    const procGroup = document.createElement('div');
    procGroup.className = 'form-group';
    const procHeading = document.createElement('h3');
    procHeading.textContent = 'Processes';
    procGroup.appendChild(procHeading);

    const procList = document.createElement('div');
    procList.className = 'process-list-inputs';
    procGroup.appendChild(procList);

    function addProcessRow(defaultId = '', defaultPriority = '') {
        const row = document.createElement('div');
        row.className = 'process-row';

        const idLabel = document.createElement('label');
        idLabel.textContent = 'Process ID';
        const idInput = document.createElement('input');
        idInput.type = 'text';
        idInput.className = 'cfg-proc-id';
        idInput.placeholder = 'P1';
        if (defaultId) {
            idInput.value = defaultId;
        }
        idLabel.appendChild(idInput);
        row.appendChild(idLabel);

        const prioLabel = document.createElement('label');
        prioLabel.textContent = 'Priority';
        const prioInput = document.createElement('input');
        prioInput.type = 'number';
        prioInput.className = 'cfg-proc-priority';
        prioInput.placeholder = '1';
        if (defaultPriority !== '' && defaultPriority !== null && defaultPriority !== undefined) {
            prioInput.value = defaultPriority;
        }
        prioLabel.appendChild(prioInput);
        row.appendChild(prioLabel);

        appendRemoveButton(row, 'process', addProcBtn);
        procList.appendChild(row);
        return idInput;
    }

    const addProcBtn = document.createElement('button');
    addProcBtn.type = 'button';
    addProcBtn.textContent = 'Add process';
    addProcBtn.addEventListener('click', () => {
        if (invalidField === addProcBtn) clearFieldError();
        addProcessRow().focus();
    });
    procGroup.appendChild(addProcBtn);
    for (const { id, priority } of active?.processes ?? [{}]) {
        addProcessRow(id, priority);
    }
    fields.appendChild(procGroup);

    // Operations
    const opsGroup = document.createElement('div');
    opsGroup.className = 'form-group';
    const opsHeading = document.createElement('h3');
    opsHeading.id = 'cfg-operations-heading';
    opsHeading.textContent = 'Operations';
    opsGroup.appendChild(opsHeading);

    const opsTextarea = document.createElement('textarea');
    opsTextarea.id = 'cfg-operations';
    opsTextarea.setAttribute('aria-labelledby', opsHeading.id);
    opsTextarea.rows = 4;
    opsTextarea.placeholder = 'P1 REQUEST R1 1\nP1 RELEASE R1 1';
    if (active?.processes?.every(process => Array.isArray(process.operations))) {
        const formatId = id => /[\s"]/.test(id) ? JSON.stringify(id) : id;
        opsTextarea.value = active.processes.flatMap(process => process.operations.map(operation =>
            `${formatId(process.id)} ${operation.type} ${formatId(operation.resource)} ${operation.count}`)).join('\n');
    } else if (active) {
        opsTextarea.placeholder = "The simulator did not supply this scenario's operation definitions.";
    }
    opsGroup.appendChild(opsTextarea);

    const avoidanceLabel = document.createElement('label');
    const avoidanceInput = document.createElement('input');
    avoidanceInput.type = 'checkbox';
    avoidanceInput.id = 'cfg-bankers-avoidance';
    avoidanceInput.title = "Use scripted resource claims. Turn off to demonstrate deadlocks.";
    avoidanceLabel.append(avoidanceInput, document.createTextNode(" Banker's avoidance"));
    opsGroup.appendChild(avoidanceLabel);
    fields.appendChild(opsGroup);

    // Prevention toggles
    const prevGroup = document.createElement('div');
    prevGroup.className = 'form-group prevention-toggles';
    const prevHeading = document.createElement('h3');
    prevHeading.textContent = 'Prevention';
    prevGroup.appendChild(prevHeading);

    const toggles = [
        { label: 'Mutual exclusion', key: 'mutualExclusion' },
        { label: 'Hold and wait', key: 'holdAndWait' },
        { label: 'Release when blocked', key: 'noPreemption' },
        { label: 'Circular wait', key: 'circularWait' }
    ];

    const toggleInputs = {};
    for (const t of toggles) {
        const label = document.createElement('label');
        const chk = document.createElement('input');
        chk.type = 'checkbox';
        chk.id = 'cfg-prev-' + t.key;
        toggleInputs[t.key] = chk;
        label.appendChild(chk);
        const text = document.createTextNode(' ' + t.label + ' ');
        label.appendChild(text);
        prevGroup.appendChild(label);
    }
    fields.appendChild(prevGroup);

    const submitBtn = document.createElement('button');
    submitBtn.type = 'submit';
    submitBtn.id = 'btn-submit-configure';
    submitBtn.textContent = 'Configure';
    submitBtn.classList.add('btn-primary');
    form.appendChild(submitBtn);

    const errorLine = document.createElement('div');
    errorLine.id = 'cfg-error-line';
    errorLine.className = 'error-line';
    errorLine.setAttribute('role', 'alert');
    fields.appendChild(errorLine);

    function parseForm() {
        const name = nameInput.value.trim();
        if (!name) {
            return { field: nameInput, error: 'Scenario name must not be blank.' };
        }

        const resources = [];
        const resourceIds = new Set();
        const resRows = resList.querySelectorAll('.resource-row');
        if (resRows.length === 0) {
            return { field: addResBtn, error: 'At least one resource is required.' };
        }
        for (let i = 0; i < resRows.length; i++) {
            const row = resRows[i];
            const idInput = row.querySelector('.cfg-res-id');
            const totalInput = row.querySelector('.cfg-res-total');
            const shareInput = row.querySelector('.cfg-res-shareable');

            const id = idInput ? idInput.value.trim() : '';
            if (!id) {
                return { field: idInput, error: 'Resource row ' + (i + 1) + ': ID must not be blank.' };
            }
            if (resourceIds.has(id)) {
                return { field: idInput, error: 'Resource ID must be unique: ' + id };
            }
            resourceIds.add(id);

            const total = Number(totalInput.value);
            if (!/^\d+$/.test(totalInput.value) || total < 1 || total > 256) {
                return { field: totalInput, error: 'Resource ' + id + ': total must be a whole number from 1 to 256.' };
            }

            resources.push({
                id,
                total,
                shareable: shareInput ? shareInput.checked : false
            });
        }

        const processes = [];
        const processIds = new Set();
        const procRows = procList.querySelectorAll('.process-row');
        if (procRows.length === 0) {
            return { field: addProcBtn, error: 'At least one process is required.' };
        }
        for (let i = 0; i < procRows.length; i++) {
            const row = procRows[i];
            const idInput = row.querySelector('.cfg-proc-id');
            const prioInput = row.querySelector('.cfg-proc-priority');

            const id = idInput ? idInput.value.trim() : '';
            if (!id) {
                return { field: idInput, error: 'Process row ' + (i + 1) + ': ID must not be blank.' };
            }
            if (processIds.has(id)) {
                return { field: idInput, error: 'Process ID must be unique: ' + id };
            }
            processIds.add(id);

            const priority = prioInput.value === '' ? 1 : Number(prioInput.value);
            if (prioInput.validity.badInput || (prioInput.value !== '' && !/^-?\d+$/.test(prioInput.value))
                    || priority < -2147483648 || priority > 2147483647) {
                return { field: prioInput, error: 'Process ' + id + ': priority must be a whole number from -2147483648 to 2147483647.' };
            }

            processes.push({
                id,
                priority,
                operations: []
            });
        }

        const rawOps = opsTextarea.value.split('\n');
        let lineIndex = 0;
        for (const rawLine of rawOps) {
            lineIndex++;
            const line = rawLine.trim();
            if (!line) {
                continue;
            }
            const words = /^("(?:[^"\\]|\\.)*"|[^\s"]+)\s+(\S+)\s+("(?:[^"\\]|\\.)*"|[^\s"]+)\s+(\S+)$/.exec(line);
            if (!words) {
                return { field: opsTextarea, error: 'Operation line ' + lineIndex + ': must contain a process, action, resource, and count.' };
            }
            const [, processToken, opTypeRaw, resourceToken, countStr] = words;
            let procId, resId;
            try {
                procId = processToken.startsWith('"') ? JSON.parse(processToken) : processToken;
                resId = resourceToken.startsWith('"') ? JSON.parse(resourceToken) : resourceToken;
            } catch {
                return { field: opsTextarea, error: 'Operation line ' + lineIndex + ': invalid quoted identifier.' };
            }
            if (!processIds.has(procId)) {
                return { field: opsTextarea, error: 'Operation line ' + lineIndex + ': process ' + procId + ' does not exist.' };
            }
            const opType = opTypeRaw.toUpperCase();
            if (opType !== 'REQUEST' && opType !== 'RELEASE') {
                return { field: opsTextarea, error: 'Operation line ' + lineIndex + ': type must be REQUEST or RELEASE.' };
            }
            if (!resourceIds.has(resId)) {
                return { field: opsTextarea, error: 'Operation line ' + lineIndex + ': resource ' + resId + ' does not exist.' };
            }
            const count = Number(countStr);
            if (!/^\d+$/.test(countStr) || count < 1 || count > 2147483647) {
                return { field: opsTextarea, error: 'Operation line ' + lineIndex + ': count must be a whole number from 1 to 2147483647.' };
            }

            const proc = processes.find(p => p.id === procId);
            if (proc) {
                proc.operations.push({
                    type: opType,
                    resource: resId,
                    count: count
                });
            }
        }

        const prevention = {
            mutualExclusion: toggleInputs.mutualExclusion.checked,
            holdAndWait: toggleInputs.holdAndWait.checked,
            noPreemption: toggleInputs.noPreemption.checked,
            circularWait: toggleInputs.circularWait.checked
        };

        return {
            body: {
                name,
                bankersAvoidance: avoidanceInput.checked,
                prevention,
                resources,
                processes
            }
        };
    }

    form.addEventListener('input', (e) => {
        if (e.target === invalidField) clearFieldError();
    });

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError('cfg-error-line');
        clearFieldError();
        const parsed = parseForm();
        if (parsed.error) {
            invalidField = parsed.field;
            const label = invalidField.closest('label');
            if (label) {
                label.parentElement.appendChild(fieldError);
            } else {
                invalidField.after(fieldError);
            }
            fieldError.textContent = parsed.error;
            invalidField.setAttribute('aria-describedby', fieldError.id);
            if (invalidField.type !== 'button') invalidField.setAttribute('aria-invalid', 'true');
            invalidField.focus();
            fieldError.scrollIntoView({ block: 'nearest', inline: 'nearest' });
            return;
        }

        try {
            clearSimulationFeedback();
            await runCommand(() => configureScenario(parsed.body));
        } catch (err) {
            showError('cfg-error-line', err);
            errorLine.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        }
    });

    container.appendChild(form);
    syncPreventionToggles(form, active);
}

function syncPreventionToggles(form, state) {
    const avoidanceInput = form.querySelector('#cfg-bankers-avoidance');
    if (avoidanceInput) {
        avoidanceInput.checked = Boolean(state?.scenario?.bankersAvoidance);
    }
    const prev = state?.prevention;
    const keys = ['mutualExclusion', 'holdAndWait', 'noPreemption', 'circularWait'];
    for (const key of keys) {
        const el = form.querySelector('#cfg-prev-' + key);
        if (el) {
            el.checked = Boolean(prev && prev[key]);
        }
    }
}
