let cy = null;
let resizeObserver = null;
let latestState = null;
let loading;
let dependenciesReady = false;
let dependenciesFailed = false;

const libraries = [
    'cytoscape-3.30.2.min.js',
    'dagre-0.8.5.min.js',
    'cytoscape-dagre-2.5.0.js'
];

async function loadLibraries() {
    for (const file of libraries) {
        await new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = new URL('../../vendor/' + file, import.meta.url).href;
            script.onload = resolve;
            script.onerror = reject;
            document.head.append(script);
        });
    }
    if (typeof globalThis.cytoscape !== 'function' || typeof globalThis.dagre?.layout !== 'function'
        || typeof globalThis.cytoscape('layout', 'dagre') !== 'function') {
        throw new Error('Graph libraries failed to initialize');
    }
}

function showUnavailable() {
    resizeObserver?.disconnect();
    resizeObserver = null;
    const previous = cy;
    cy = null;
    window.cy = null;
    document.getElementById('graph-canvas').hidden = true;
    const status = document.getElementById('graph-empty');
    status.textContent = 'Graph visualization is unavailable.';
    status.hidden = false;
    try {
        previous?.destroy();
    } catch {
        // The graph is already hidden even if the renderer cannot tear down cleanly.
    }
}

export function renderGraph(state) {
    latestState = state;
    const container = document.getElementById('graph-canvas');
    if (!container || dependenciesFailed) return;
    if (!dependenciesReady) {
        loading ??= loadLibraries().then(() => {
            dependenciesReady = true;
            renderGraph(latestState);
        }).catch(() => {
            dependenciesFailed = true;
            showUnavailable();
        });
        return;
    }
    try {
        container.hidden = false;
        const status = document.getElementById('graph-empty');
        status.textContent = 'Configure a scenario to display the Resource Allocation Graph.';
        status.hidden = Boolean(state?.scenario);
        if (state && !state.graph) throw new Error('Graph data is missing');
        drawGraph(container, state?.graph);
    } catch {
        showUnavailable();
    }
}

function drawGraph(container, graph) {
    const { nodes, edges } = graph ?? { nodes: [], edges: [] };
    const nodeIds = new Set(nodes.map(node => node.data.id));
    const allIds = new Set([...nodeIds, ...edges.map(edge => edge.data.id)]);
    if (nodes.some(({ data }) => typeof data.id !== 'string' || typeof data.label !== 'string'
        || !['process', 'resource'].includes(data.type))
        || allIds.size !== nodes.length + edges.length
        || edges.some(({ data }) => typeof data.id !== 'string' || !nodeIds.has(data.source) || !nodeIds.has(data.target)
            || !Number.isInteger(data.count) || data.count <= 0 || !['request', 'allocation'].includes(data.kind))) {
        throw new Error('Malformed graph elements');
    }

    if (!cy) {
        cy = globalThis.cytoscape({
            container,
            elements: [],
            style: [
                {
                    selector: 'node',
                    style: {
                        'font-family': 'Georgia, serif',
                        'color': '#000000',
                        'text-valign': 'center',
                        'text-halign': 'center',
                        'text-wrap': 'wrap',
                        'text-overflow-wrap': 'anywhere',
                        'text-max-width': '160px',
                        'padding': '8px',
                        'width': 'label',
                        'height': 'label'
                    }
                },
                {
                    selector: 'node[type = "process"]',
                    style: {
                        'shape': 'ellipse',
                        'background-color': '#ffffff',
                        'border-width': function(ele) {
                            return ele.data('deadlocked') === true ? 3 : 1;
                        },
                        'border-color': '#000000',
                        'label': function(ele) {
                            const label = ele.data('label');
                            if (ele.data('deadlocked') === true) {
                                return `${label}\nDEADLOCKED`;
                            }
                            return label;
                        }
                    }
                },
                {
                    selector: 'node[type = "resource"]',
                    style: {
                        'shape': 'rectangle',
                        'background-color': '#ffffff',
                        'border-width': 1,
                        'border-color': '#000000',
                        'label': function(ele) {
                            const label = ele.data('label');
                            const instances = ele.data('instances');
                            if (!instances || instances <= 0) {
                                return label;
                            }
                            const dots = Array(instances).fill('\u25CF').join(' ');
                            return `${label} ${dots}`;
                        }
                    }
                },
                {
                    selector: 'edge',
                    style: {
                        'curve-style': 'bezier',
                        'width': 1.5,
                        'line-color': '#000000',
                        'target-arrow-shape': 'triangle',
                        'target-arrow-color': '#000000',
                        'font-family': 'Georgia, serif',
                        'font-size': '10px',
                        'color': '#000000',
                        'text-background-color': '#ffffff',
                        'text-background-opacity': 1,
                        'text-background-padding': '2px',
                        'label': function(ele) {
                            const count = ele.data('count');
                            return count > 1 ? String(count) : '';
                        }
                    }
                },
                {
                    selector: 'edge[kind = "allocation"]',
                    style: {
                        'line-style': 'solid'
                    }
                },
                {
                    selector: 'edge[kind = "request"]',
                    style: {
                        'line-style': 'dashed'
                    }
                },
                {
                    selector: 'edge[?inCycle]',
                    style: {
                        'line-color': '#666666',
                        'target-arrow-color': '#666666'
                    }
                },
                {
                    selector: 'edge[?deadlocked]',
                    style: {
                        'line-color': '#444444',
                        'width': 3,
                        'target-arrow-color': '#444444'
                    }
                }
            ]
        });
        if (typeof window !== 'undefined') {
            window.cy = cy;
        }

        if (!resizeObserver && typeof ResizeObserver !== 'undefined') {
            resizeObserver = new ResizeObserver(() => {
                if (!cy) return;
                try {
                    cy.resize();
                    if (cy.elements().length > 0) cy.fit(undefined, 30);
                } catch {
                    showUnavailable();
                }
            });
            resizeObserver.observe(container);
        }
    }

    cy.elements().remove();

    if (nodes.length === 0) return;

    const elements = cy.add([...nodes, ...edges]);
    cy.resize();
    const isolated = elements.nodes().filter(node => node.degree() === 0);
    const connected = elements.not(isolated);
    if (connected.length > 0) {
        connected.layout({ name: 'dagre', rankDir: 'LR', rankSep: 36, nodeSep: 20, fit: false }).run();
    }
    if (isolated.length > 0) {
        const cellWidth = isolated.reduce((width, node) => Math.max(width, node.outerWidth()), 0) + 20;
        const cellHeight = isolated.reduce((height, node) => Math.max(height, node.outerHeight()), 0) + 20;
        const aspect = Math.max(cy.width(), 1) / Math.max(cy.height(), 1);
        const columns = Math.round(Math.sqrt(isolated.length * aspect * cellHeight / cellWidth));
        const cols = Math.max(1, Math.min(isolated.length, columns));
        const rows = Math.ceil(isolated.length / cols);
        const width = cols * cellWidth;
        const bounds = connected.length > 0 ? connected.boundingBox() : null;
        isolated.layout({
            name: 'grid',
            cols,
            rows,
            boundingBox: {
                x1: bounds ? (bounds.x1 + bounds.x2 - width) / 2 : 0,
                y1: bounds ? bounds.y2 + 12 : 0,
                w: width,
                h: rows * cellHeight
            },
            avoidOverlap: false,
            fit: false
        }).run();
    }
    cy.fit(undefined, 30);
}
