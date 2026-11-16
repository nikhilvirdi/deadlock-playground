let cy = null;
let currentProcesses = [];
let resizeObserver = null;

if (typeof cytoscape !== 'undefined' && typeof cytoscapeDagre !== 'undefined') {
    cytoscape.use(cytoscapeDagre);
}

function getEdgeCount(ele) {
    const rawCount = ele.data('count');
    if (typeof rawCount === 'number') {
        return rawCount;
    }
    const source = ele.data('source');
    const target = ele.data('target');
    const kind = ele.data('kind');

    if (kind === 'request') {
        const proc = currentProcesses.find(p => p.id === source);
        if (proc && proc.waitingFor && proc.waitingFor.resource === target) {
            return proc.waitingFor.count;
        }
    } else if (kind === 'allocation') {
        const proc = currentProcesses.find(p => p.id === target);
        if (proc && Array.isArray(proc.held)) {
            const entry = proc.held.find(h => h.resource === source);
            if (entry) {
                return entry.count;
            }
        }
    }
    return 1;
}

function isProcessDeadlocked(ele) {
    const proc = currentProcesses.find(p => p.id === ele.data('id'));
    return proc ? Boolean(proc.deadlocked) : false;
}

export function getCy() {
    return cy;
}

export function renderGraph(state) {
    const container = document.getElementById('graph-canvas');
    if (!container) {
        return;
    }

    const actualState = (state && state.state) ? state.state : state;
    document.getElementById('graph-empty').hidden = Boolean(actualState && actualState.scenario);

    if (!cy) {
        if (typeof cytoscape !== 'undefined' && typeof cytoscapeDagre !== 'undefined') {
            cytoscape.use(cytoscapeDagre);
        }
        cy = cytoscape({
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
                            return isProcessDeadlocked(ele) ? 3 : 1;
                        },
                        'border-color': '#000000',
                        'label': function(ele) {
                            const id = ele.data('id');
                            if (isProcessDeadlocked(ele)) {
                                return `${id}\nDEADLOCKED`;
                            }
                            return id;
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
                            const id = ele.data('id');
                            const instances = ele.data('instances');
                            if (!instances || instances <= 0) {
                                return id;
                            }
                            const dots = Array(instances).fill('\u25CF').join(' ');
                            return `${id} ${dots}`;
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
                            const count = getEdgeCount(ele);
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
                if (cy) {
                    cy.resize();
                    if (cy.elements().length > 0) {
                        cy.fit(undefined, 30);
                    }
                }
            });
            resizeObserver.observe(container);
        }
    }

    cy.elements().remove();

    currentProcesses = actualState && Array.isArray(actualState.processes) ? actualState.processes : [];

    const graph = actualState && actualState.graph;
    const nodes = graph && Array.isArray(graph.nodes) ? graph.nodes : [];
    const edges = graph && Array.isArray(graph.edges) ? graph.edges : [];

    if (!graph || nodes.length === 0) {
        return;
    }

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
