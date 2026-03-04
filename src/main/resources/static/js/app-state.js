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

    function normalizeTab(tab) {
        var allowed = ['structure', 'forms', 'fonts', 'validation', 'rawcos', 'bookmarks', 'attachments'];
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
            version: 1,
            sessionId: String(state.sessionId),
            updatedAt: Date.now(),
            currentTab: normalizeTab(state.currentTab),
            pendingFormAdds: asArray(state.pendingFormAdds),
            pendingFieldRects: asArray(state.pendingFieldRects),
            pendingFieldOptions: asArray(state.pendingFieldOptions),
            pendingCosChanges: asArray(state.pendingCosChanges)
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
                pendingFormAdds: asArray(parsed.pendingFormAdds),
                pendingFieldRects: asArray(parsed.pendingFieldRects),
                pendingFieldOptions: asArray(parsed.pendingFieldOptions),
                pendingCosChanges: asArray(parsed.pendingCosChanges)
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
        state.pendingFormAdds = asArray(safeDraft.pendingFormAdds);
        state.pendingFieldRects = asArray(safeDraft.pendingFieldRects);
        state.pendingFieldOptions = asArray(safeDraft.pendingFieldOptions);
        state.pendingCosChanges = asArray(safeDraft.pendingCosChanges);
        state.pendingFieldDeletes = {};
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
            pendingFieldDeletes: {},
            selectedFieldNames: [],
            selectedImageNodeIds: [],
            pageViewports: [],
            pageCanvases: [],
            currentScale: 1.5,
            autoZoomMode: 'off',   // 'off' | 'width' | 'height'
            basePageSize: { width: 0, height: 0 }
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
            api.Storage.saveDraft(api.state);
        }
    });

    return api;
}());
