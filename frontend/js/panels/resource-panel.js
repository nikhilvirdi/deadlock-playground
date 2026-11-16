export function renderResourcePanel(state) {
    const panel = document.getElementById('resource-panel');
    if (!panel) {
        return;
    }

    const container = panel.querySelector('.panel-content') || panel;
    container.textContent = '';

    const actualState = (state && state.state) ? state.state : state;
    const resources = actualState && Array.isArray(actualState.resources) ? actualState.resources : [];
    if (resources.length === 0) {
        const emptyMsg = document.createElement('p');
        emptyMsg.className = 'placeholder-text';
        emptyMsg.textContent = 'No resources';
        container.appendChild(emptyMsg);
        return;
    }

    for (const resource of resources) {
        const item = document.createElement('section');
        item.className = 'state-entry';
        const heading = document.createElement('h3');
        heading.textContent = resource.id;

        const details = document.createElement('dl');
        const fields = [
            ['Total instances', resource.total],
            ['Available', resource.available],
            ['Shareable', resource.shareable ? 'Yes' : 'No']
        ];
        for (const [label, value] of fields) {
            const term = document.createElement('dt');
            term.textContent = label;
            const detail = document.createElement('dd');
            detail.textContent = value;
            details.append(term, detail);
        }
        item.append(heading, details);
        container.appendChild(item);
    }
}
