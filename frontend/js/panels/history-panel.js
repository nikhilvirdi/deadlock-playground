const eventLabels = new Map([
    ['REQUEST', 'Request'],
    ['GRANT', 'Grant'],
    ['BLOCK', 'Request blocked'],
    ['RELEASE', 'Release'],
    ['STATE_CHANGE', 'State change'],
    ['AVOIDANCE_CHECK', 'Safety check'],
    ['DETECT', 'Detection'],
    ['VICTIM_SELECTED', 'Victim selected'],
    ['TERMINATE', 'Termination'],
    ['PREEMPT', 'Preemption'],
    ['ROLLBACK', 'Rollback'],
    ['RESOLVED', 'Recovery result']
]);

// Events carry a type and description, with several types shared by different outcomes.
function eventStatus(type, description) {
    switch (type) {
        case 'GRANT':
        case 'RELEASE':
        case 'TERMINATE':
        case 'PREEMPT':
        case 'ROLLBACK':
            return 'success';
        case 'BLOCK':
            return /^(Circular wait prevention|Hold and wait prevention): /.test(description) ? 'error' : 'warning';
        case 'AVOIDANCE_CHECK':
            if (description.startsWith('Safe sequence: ')) return 'success';
            if (/^(Unsafe state: |Insufficient available instances of resource )/.test(description)) return 'warning';
            break;
        case 'DETECT':
            if (description === 'No deadlock detected') return 'success';
            if (description.startsWith('Deadlock detected: ')) return 'error';
            break;
        case 'RESOLVED':
            if (description === 'Deadlock resolved') return 'success';
            if (description === 'Recovery failed') return 'error';
            break;
        case 'STATE_CHANGE':
            if (description.endsWith(' completed')) return 'success';
            break;
    }
    return 'info';
}

export function renderHistoryPanel(events, currentSeq, onJump) {
    const panel = document.getElementById('history-panel');
    if (!panel) {
        return;
    }

    const table = panel.querySelector('table');
    const body = table.tBodies[0];
    const focusedStep = body.contains(document.activeElement)
        ? document.activeElement.closest('tr').dataset.seq
        : null;
    let eventList = [];
    if (Array.isArray(events)) {
        eventList = events;
    } else if (events && Array.isArray(events.events)) {
        eventList = events.events;
    }

    table.hidden = eventList.length === 0;
    panel.querySelector('#history-empty').hidden = !table.hidden;

    const rows = document.createDocumentFragment();
    let currentRow;
    let position = '';
    let focusTarget;
    for (const [index, ev] of eventList.entries()) {
        const row = document.createElement('tr');
        rows.appendChild(row);
        row.dataset.seq = String(ev.seq);
        const stepCell = row.insertCell();
        const description = ev.description || '';
        const typeCell = row.insertCell();
        typeCell.className = 'history-event-type';
        typeCell.dataset.status = eventStatus(ev.type, description);
        typeCell.textContent = eventLabels.get(ev.type) ?? ev.type?.toLowerCase().replaceAll('_', ' ') ?? '';
        row.insertCell().textContent = description;

        const isCurrent = ev.seq === currentSeq;
        if (isCurrent) {
            row.classList.add('current-event');
            currentRow = row;
            position = `${index + 1} of ${eventList.length}`;
        }

        if (typeof onJump === 'function') {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = String(ev.seq);
            button.setAttribute('aria-label', `Go to step ${ev.seq}`);
            if (isCurrent) button.setAttribute('aria-current', 'step');
            if (row.dataset.seq === focusedStep) focusTarget = button;
            stepCell.appendChild(button);
            row.classList.add('history-selectable');
            row.addEventListener('click', () => onJump(ev.seq));
        } else {
            stepCell.textContent = String(ev.seq);
        }
    }

    body.replaceChildren(rows);
    const indicator = document.getElementById('history-position');
    if (indicator.textContent !== position) indicator.textContent = position;
    focusTarget?.focus({ preventScroll: true });

    if (currentRow && panel.scrollHeight > panel.clientHeight) {
        const header = table.tHead.rows[0].cells[0].getBoundingClientRect();
        const bounds = currentRow.getBoundingClientRect();
        const bottom = panel.getBoundingClientRect().top + panel.clientHeight;
        if (bounds.top < header.bottom || bounds.height > bottom - header.bottom) {
            panel.scrollTop += bounds.top - header.bottom;
        } else if (bounds.bottom > bottom) {
            panel.scrollTop += bounds.bottom - bottom;
        }
    }
}
