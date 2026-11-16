export function renderProcessPanel(state) {
    const panel = document.getElementById('process-panel');
    if (!panel) {
        return;
    }

    const container = panel.querySelector('.panel-content') || panel;
    container.textContent = '';

    const actualState = (state && state.state) ? state.state : state;
    const processes = actualState && Array.isArray(actualState.processes) ? actualState.processes : [];
    if (processes.length === 0) {
        const emptyMsg = document.createElement('p');
        emptyMsg.className = 'placeholder-text';
        emptyMsg.textContent = 'No processes';
        container.appendChild(emptyMsg);
        return;
    }

    for (const process of processes) {
        const item = document.createElement('section');
        item.className = 'state-entry';
        const heading = document.createElement('h3');
        heading.textContent = process.id;
        item.appendChild(heading);

        if (process.deadlocked) {
            const badge = document.createElement('span');
            badge.className = 'deadlocked';
            badge.textContent = 'Deadlocked';
            heading.appendChild(badge);
        }

        const held = process.held?.map(h => `${h.resource} × ${h.count}`).join(', ');
        const waiting = process.waitingFor;
        const details = document.createElement('dl');
        const fields = [
            ['Priority', process.priority],
            ['State', process.state],
            ['Held', held || 'None'],
            ['Waiting for', waiting ? `${waiting.resource} × ${waiting.count}` : 'None'],
            ['Pending ops', process.pendingOperations]
        ];
        for (const [label, value] of fields) {
            const term = document.createElement('dt');
            term.textContent = label;
            const detail = document.createElement('dd');
            detail.textContent = value;
            details.append(term, detail);
        }
        item.appendChild(details);
        container.appendChild(item);
    }
}
