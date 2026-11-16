export function clearError(targetId) {
    const el = typeof targetId === 'string' ? document.getElementById(targetId) : targetId;
    if (el) {
        el.textContent = '';
    }
}

export function showError(targetId, error) {
    const el = typeof targetId === 'string' ? document.getElementById(targetId) : targetId;
    if (!el) {
        return;
    }

    let code = 'ERROR';
    let message = '';

    if (error) {
        if (typeof error === 'string') {
            message = error;
        } else {
            code = error.code || 'ERROR';
            message = error.message || String(error);
        }
    }

    el.textContent = `${code}: ${message}`;
}

let simulationFeedbackRevision = 0;

export function clearSimulationFeedback() {
    document.querySelectorAll('.simulation-feedback').forEach(line => {
        line.textContent = '';
        delete line.dataset.status;
    });
    return ++simulationFeedbackRevision;
}

export function showSimulationFeedback(line, revision, message, status = 'info') {
    if (revision === simulationFeedbackRevision) {
        line.dataset.status = status;
        line.textContent = message;
    }
}

export function showSimulationError(line, revision, error) {
    // Detection and recovery report a clear state through the API error envelope.
    const status = error?.code === 'NO_DEADLOCK' ? 'success'
        : error?.code === 'NO_SCENARIO' ? 'info'
        : 'error';
    showSimulationFeedback(line, revision, simulationErrorMessage(error), status);
}

function simulationErrorMessage(error) {
    const message = error?.message;
    switch (message) {
        case 'No waiting process to check':
            return 'No deadlock detected. No processes are waiting.';
        case 'No deadlock to recover from':
            return 'No deadlock detected. Recovery is unnecessary.';
        case 'No scenario loaded':
            return 'Configure a scenario first.';
        case 'Failed to parse JSON response':
            return 'The simulator returned an unreadable response.';
    }
    if (error?.code === 'NETWORK_ERROR') return 'Could not reach the simulator.';
    if (message?.startsWith('Process is not RUNNING: ')) {
        return `Process ${message.slice('Process is not RUNNING: '.length)} must be running.`;
    }
    return message && message !== error?.code ? message : 'The operation could not be completed.';
}
