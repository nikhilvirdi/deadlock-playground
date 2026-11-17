import {
    getState,
    getSimulationView,
    configureScenario,
    reset,
    getHistory,
    requestResource,
    releaseResource,
    step,
    detect,
    recover,
    jumpToEvent
} from './api/endpoints.js';
import { renderProcessPanel } from './panels/process-panel.js';
import { renderResourcePanel } from './panels/resource-panel.js';
import { renderConfigureForm } from './panels/configure-form.js';
import { renderActionPanel } from './panels/action-panel.js';
import { renderDetectRecoverPanel } from './panels/detect-recover-panel.js';
import { renderHistoryPanel } from './panels/history-panel.js';
import { clearSimulationFeedback, showSimulationError } from './panels/feedback.js';
import { renderGraph } from './graph/graph-view.js';

let view = null;
let configurationKey = null;
let requestNumber = 0;
let appliedRequest = 0;
let ignoreThrough = 0;
let synchronized = false;
let commandPending = false;

if (typeof window !== 'undefined') {
    Object.assign(window, {
        getState, configureScenario, reset, getHistory, requestResource, releaseResource, step,
        detect, recover, jumpToEvent, refresh, jump,
        getCurrentSeq: () => view?.state?.step ?? null
    });
}

function canSendCommands() {
    return synchronized && !commandPending;
}

function updateControls() {
    const enabled = canSendCommands();
    const state = view?.state ?? null;
    const events = view?.events ?? [];
    renderActionPanel(state, runCommand, enabled);
    renderDetectRecoverPanel(state, runCommand, enabled);
    document.getElementById('btn-submit-configure').disabled = !enabled;
    for (const [id, offset] of [['btn-step-back', -1], ['btn-step-forward', 1]]) {
        const button = document.getElementById(id);
        button.disabled = !enabled || !state || !events.some(event => event.seq === state.step + offset);
    }
    for (const button of document.querySelectorAll('#history-panel tbody button')) {
        button.setAttribute('aria-disabled', String(!enabled));
    }
}

function renderView(nextView) {
    const { state, events } = nextView;
    renderGraph(state);
    renderProcessPanel(state);
    renderResourcePanel(state);
    renderHistoryPanel(events, state?.step ?? null, jump);

    const key = state ? nextView.serverId + ':' + state.scenario.configurationRevision : null;
    if (configurationKey !== key) {
        renderConfigureForm(document.getElementById('configure-panel'), runCommand, state);
        configurationKey = key;
    }
}

function notice(message, status = 'warning') {
    clearSimulationFeedback();
    const line = document.getElementById('action-op-error');
    line.dataset.status = status;
    line.dataset.syncNotice = 'true';
    line.textContent = message;
}

function clearNotice() {
    const line = document.getElementById('action-op-error');
    if (line.dataset.syncNotice) {
        delete line.dataset.syncNotice;
        delete line.dataset.status;
        line.textContent = '';
    }
}

async function readView(committed) {
    const request = ++requestNumber;
    try {
        const nextView = await getSimulationView();
        if (request <= ignoreThrough) return;
        if (typeof nextView?.serverId !== 'string' || !Number.isSafeInteger(nextView.revision)
            || !Array.isArray(nextView.events) || !Object.hasOwn(nextView, 'state')
            || (nextView.state && !nextView.events.some(event => event.seq === nextView.state.step))) {
            throw new Error('Invalid simulation view');
        }
        if (committed && committed.serverId === nextView.serverId && nextView.revision < committed.revision) {
            throw new Error('View predates the completed command');
        }
        if (view && (nextView.serverId === view.serverId
            ? nextView.revision < view.revision : request < appliedRequest)) return;
        if (!view || view.serverId !== nextView.serverId || view.revision !== nextView.revision) {
            renderView(nextView);
            view = nextView;
        }
        appliedRequest = Math.max(appliedRequest, request);
        synchronized = true;
        clearNotice();
        updateControls();
    } catch (error) {
        if (request <= ignoreThrough || request !== requestNumber) return;
        ignoreThrough = requestNumber;
        synchronized = false;
        updateControls();
        throw error;
    }
}

export async function refresh() {
    if (commandPending) return null;
    try {
        await readView();
        return synchronized ? view : null;
    } catch {
        notice('Could not refresh the simulation display. Reload the page to try again.');
        return null;
    }
}

async function runCommand(send) {
    if (!canSendCommands()) return;
    commandPending = true;
    ignoreThrough = requestNumber;
    updateControls();
    try {
        let committed;
        try {
            committed = await send();
        } catch (error) {
            if (error.code === 'STALE_VIEW' || (error.code === 'NO_SCENARIO' && view?.state)) {
                try {
                    await readView();
                } catch {
                    notice('The simulation changed and the display could not be refreshed. Reload the page to try again.');
                    return;
                }
            }
            if (error.code === 'NETWORK_ERROR' || (error.status >= 500 && error.code !== 'SESSION_LIMIT') || error.message === 'Failed to parse JSON response') {
                synchronized = false;
                notice('Could not confirm whether the command was applied. Reload the page before trying again.');
                return;
            }
            throw error;
        }

        if (view.serverId === committed.serverId && view.revision === committed.revision) return committed;
        try {
            await readView(committed);
        } catch {
            notice('The simulator processed the command, but the display could not be refreshed. Reload the page to resynchronize.');
            return;
        }
        if (view.serverId !== committed.serverId || view.revision !== committed.revision) {
            notice('The command completed. The display shows newer simulator state.', 'info');
            return;
        }
        return committed;
    } finally {
        commandPending = false;
        updateControls();
    }
}

export async function jump(seq) {
    if (!canSendCommands() || !view?.state) return;
    const feedback = clearSimulationFeedback();
    try {
        const from = view.serverId + ':' + view.revision;
        const result = await runCommand(() => jumpToEvent(seq, from));
        return result?.state;
    } catch (error) {
        showSimulationError(document.getElementById('action-op-error'), feedback, error);
    }
}

export async function init() {
    renderConfigureForm(document.getElementById('configure-panel'), runCommand, null);
    renderHistoryPanel([], null, jump);
    document.getElementById('btn-step-back').addEventListener('click', () => jump(view.state.step - 1));
    document.getElementById('btn-step-forward').addEventListener('click', () => jump(view.state.step + 1));
    updateControls();
    return refresh();
}

if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
}
