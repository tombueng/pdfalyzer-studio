/**
 * PDFalyzer Studio – shared application state.
 * MUST be loaded before all other modules.
 */
var PDFalyzer = (function () {
    'use strict';

    var STORAGE_CURRENT_SESSION_KEY = 'pdfalyzer.currentSessionId';
    var STORAGE_DRAFT_PREFIX = 'pdfalyzer.pending.';

    function getStorage() {
        try {
            return window.localStorage;
        } catch (e) {
            return null;
        }
    }

    function getDraftKey(sessionId) {
        return STORAGE_DRAFT_PREFIX + String(sessionId || '');
    }

    function asArray(value) {
        return Array.isArray(value) ? value : [];
    }

    function asObject(value) {
        return (value && typeof value === 'object' && !Array.isArray(value)) ? value : {};
    }

    function normalizeTab(tab) {
        var allowed = ['structure', 'forms', 'fonts', 'validation', 'rawcos', 'bookmarks', 'attachments', 'signatures', 'changes'];
        return allowed.indexOf(tab) >= 0 ? tab : 'structure';
    }

    function setCurrentSessionId(sessionId) {
        var storage = getStorage();
        if (!storage) return;
        try {
            if (sessionId) {
                storage.setItem(STORAGE_CURRENT_SESSION_KEY, String(sessionId));
            } else {
                storage.removeItem(STORAGE_CURRENT_SESSION_KEY);
            }
        } catch (e) {
            // ignore storage errors
        }
    }

    function getCurrentSessionId() {
        var storage = getStorage();
        if (!storage) return null;
        try {
            var value = storage.getItem(STORAGE_CURRENT_SESSION_KEY);
            return value ? String(value) : null;
        } catch (e) {
            return null;
        }
    }

    function clearCurrentSessionId() {
        setCurrentSessionId(null);
    }

    function saveDraft(state) {
        if (!state || !state.sessionId) return;
        var storage = getStorage();
        if (!storage) return;

        var payload = {
            version: 2,
            sessionId: String(state.sessionId),
            updatedAt: Date.now(),
            currentTab: normalizeTab(state.currentTab),
            panMode: !!(state.panMode),
            layerMode: (typeof state.layerMode === 'number') ? state.layerMode : 2,
            autoZoomMode: state.autoZoomMode || 'off',
            currentScale: (typeof state.currentScale === 'number' && state.currentScale > 0) ? state.currentScale : 1.5,
            editFieldType: state.editFieldType || null,
            tabTreeViewStates: (state.tabTreeViewStates && typeof state.tabTreeViewStates === 'object') ? state.tabTreeViewStates : {},
            selectedNodeId: (state.selectedNodeId !== null && state.selectedNodeId !== undefined) ? state.selectedNodeId : null,
            selectedFieldNames: Array.isArray(state.selectedFieldNames) ? state.selectedFieldNames : [],
            viewerScrollState: (state.viewerScrollState && typeof state.viewerScrollState === 'object') ? state.viewerScrollState : null,
            pendingFormAdds: asArray(state.pendingFormAdds),
            pendingFieldRects: asArray(state.pendingFieldRects),
            pendingFieldOptions: asArray(state.pendingFieldOptions),
            pendingCosChanges: asArray(state.pendingCosChanges),
            pendingFieldValues: asObject(state.pendingFieldValues),
            pendingFieldDeletes: asObject(state.pendingFieldDeletes),
            fieldUndoStacks: asObject(state.fieldUndoStacks),
            treePaneWidth: (typeof state.treePaneWidth === 'number' && state.treePaneWidth > 0) ? state.treePaneWidth : null,
            signatureTabState: state.signatureTabState || null,
            pendingSignatures: asArray(state.pendingSignatures),
            nextOrderIndex: (typeof state.nextOrderIndex === 'number') ? state.nextOrderIndex : 0
        };

        try {
            storage.setItem(getDraftKey(state.sessionId), JSON.stringify(payload));
            setCurrentSessionId(state.sessionId);
        } catch (e) {
            // ignore storage errors
        }
    }

    function loadDraft(sessionId) {
        if (!sessionId) return null;
        var storage = getStorage();
        if (!storage) return null;
        try {
            var raw = storage.getItem(getDraftKey(sessionId));
            if (!raw) return null;
            var parsed = JSON.parse(raw);
            if (!parsed || String(parsed.sessionId || '') !== String(sessionId)) return null;
            return {
                currentTab: normalizeTab(parsed.currentTab),
                panMode: !!parsed.panMode,
                layerMode: (typeof parsed.layerMode === 'number' && parsed.layerMode >= 0 && parsed.layerMode <= 2) ? parsed.layerMode : 2,
                autoZoomMode: (parsed.autoZoomMode === 'width' || parsed.autoZoomMode === 'height') ? parsed.autoZoomMode : 'off',
                currentScale: (typeof parsed.currentScale === 'number' && parsed.currentScale > 0) ? parsed.currentScale : 1.5,
                editFieldType: parsed.editFieldType || null,
                tabTreeViewStates: (parsed.tabTreeViewStates && typeof parsed.tabTreeViewStates === 'object') ? parsed.tabTreeViewStates : {},
                selectedNodeId: (parsed.selectedNodeId !== null && parsed.selectedNodeId !== undefined) ? parsed.selectedNodeId : null,
                selectedFieldNames: Array.isArray(parsed.selectedFieldNames) ? parsed.selectedFieldNames : [],
                viewerScrollState: (parsed.viewerScrollState && typeof parsed.viewerScrollState === 'object') ? parsed.viewerScrollState : null,
                pendingFormAdds: asArray(parsed.pendingFormAdds),
                pendingFieldRects: asArray(parsed.pendingFieldRects),
                pendingFieldOptions: asArray(parsed.pendingFieldOptions),
                pendingCosChanges: asArray(parsed.pendingCosChanges),
                pendingFieldValues: asObject(parsed.pendingFieldValues),
                pendingFieldDeletes: asObject(parsed.pendingFieldDeletes),
                fieldUndoStacks: asObject(parsed.fieldUndoStacks),
                treePaneWidth: (typeof parsed.treePaneWidth === 'number' && parsed.treePaneWidth > 0) ? parsed.treePaneWidth : null,
                signatureTabState: parsed.signatureTabState || null,
                pendingSignatures: asArray(parsed.pendingSignatures),
                nextOrderIndex: (typeof parsed.nextOrderIndex === 'number') ? parsed.nextOrderIndex : 0
            };
        } catch (e) {
            return null;
        }
    }

    function clearDraft(sessionId) {
        if (!sessionId) return;
        var storage = getStorage();
        if (!storage) return;
        try {
            storage.removeItem(getDraftKey(sessionId));
        } catch (e) {
            // ignore storage errors
        }
    }

    function applyDraftToState(state, draft) {
        if (!state) return;
        var safeDraft = draft || {};
        state.currentTab = normalizeTab(safeDraft.currentTab || state.currentTab || 'structure');
        state.panMode = !!(safeDraft.panMode);
        state.layerMode = (typeof safeDraft.layerMode === 'number' && safeDraft.layerMode >= 0 && safeDraft.layerMode <= 2) ? safeDraft.layerMode : 2;
        state.autoZoomMode = (safeDraft.autoZoomMode === 'width' || safeDraft.autoZoomMode === 'height') ? safeDraft.autoZoomMode : 'off';
        if (typeof safeDraft.currentScale === 'number' && safeDraft.currentScale > 0) {
            state.currentScale = safeDraft.currentScale;
        }
        state.editFieldType = safeDraft.editFieldType || null;
        state.tabTreeViewStates = (safeDraft.tabTreeViewStates && typeof safeDraft.tabTreeViewStates === 'object') ? safeDraft.tabTreeViewStates : {};
        state.selectedNodeId = (safeDraft.selectedNodeId !== null && safeDraft.selectedNodeId !== undefined) ? safeDraft.selectedNodeId : null;
        state.selectedFieldNames = Array.isArray(safeDraft.selectedFieldNames) ? safeDraft.selectedFieldNames : [];
        state.viewerScrollState = (safeDraft.viewerScrollState && typeof safeDraft.viewerScrollState === 'object') ? safeDraft.viewerScrollState : null;
        state.pendingFormAdds = asArray(safeDraft.pendingFormAdds);
        state.pendingFieldRects = asArray(safeDraft.pendingFieldRects);
        state.pendingFieldOptions = asArray(safeDraft.pendingFieldOptions);
        state.pendingCosChanges = asArray(safeDraft.pendingCosChanges);
        state.pendingFieldValues = asObject(safeDraft.pendingFieldValues);
        state.pendingFieldDeletes = asObject(safeDraft.pendingFieldDeletes);
        state.fieldUndoStacks = asObject(safeDraft.fieldUndoStacks);
        if (typeof safeDraft.treePaneWidth === 'number' && safeDraft.treePaneWidth > 0) {
            state.treePaneWidth = safeDraft.treePaneWidth;
            $('#treePane').css('width', safeDraft.treePaneWidth + 'px');
        }
        state.signatureTabState = safeDraft.signatureTabState || null;
        state.pendingSignatures = asArray(safeDraft.pendingSignatures);
        state.nextOrderIndex = (typeof safeDraft.nextOrderIndex === 'number') ? safeDraft.nextOrderIndex : 0;
    }
    var api = {
        state: {
            sessionId: null,
            pdfDoc: null,
            treeData: null,
            rawCosTreeData: null,
            currentTab: 'structure',
            tabTreeViewStates: {},
            encryptionInfo: null,
            selectedNodeId: null,
            editMode: true,
            editFieldType: null,
            pendingFormAdds: [],
            pendingFieldRects: [],
            pendingFieldOptions: [],
            pendingCosChanges: [],
            pendingFieldValues: {},
            pendingFieldDeletes: {},
            fieldUndoStacks: {},
            selectedFieldNames: [],
            selectedImageNodeIds: [],
            pageViewports: [],
            pageCanvases: [],
            currentScale: 1.5,
            autoZoomMode: 'off',   // 'off' | 'width' | 'height'
            panMode: false,
            layerMode: 2,          // 2 = "Fill-out mode" default
            viewerScrollState: null,
            basePageSize: { width: 0, height: 0 },
            signatureData: null,
            signatureTabState: null,
            pendingSignatures: [],
            nextOrderIndex: 0
        },
        Storage: {
            setCurrentSessionId: setCurrentSessionId,
            getCurrentSessionId: getCurrentSessionId,
            clearCurrentSessionId: clearCurrentSessionId,
            saveDraft: saveDraft,
            loadDraft: loadDraft,
            clearDraft: clearDraft,
            applyDraftToState: applyDraftToState
        }
    };

    window.addEventListener('beforeunload', function () {
        if (api && api.Storage && api.state && api.state.sessionId) {
            if (api.ViewerRender && api.ViewerRender.capturePaneViewState) {
                api.state.viewerScrollState = api.ViewerRender.capturePaneViewState();
            }
            api.Storage.saveDraft(api.state);
        }
    });

    return api;
}());
