/**
 * PDFalyzer Studio – PDF form field edit mode (draw new fields on the viewer).
 * Core module: loads first. Sub-modules (EditField, EditOptions) access shared
 * state via the getters/setters exposed in the return object.
 */
PDFalyzer.EditMode = (function ($, P) {
    'use strict';

    var drawing  = false;
    var startX   = 0, startY = 0;
    var $drawRect = null;
    var targetPage = -1;
    var pendingCreatePayload = null;
    var lastAddedFieldTemplate = null;
    var pendingFieldOptionOverrides = {};

    var FIELD_CONFIG_DIALOGS = {
        create: { modalId: 'fieldCreateModal' },
        options: { modalId: 'fieldOptionsModal' }
    };

    function clamp(v, lo, hi) { return hi < lo ? lo : Math.max(lo, Math.min(hi, v)); }
    function cloneObject(v) {
        if (v === null || v === undefined) return v;
        try { return JSON.parse(JSON.stringify(v)); } catch (e) { return v; }
    }
    function mergeOptions(defaults, inherited) {
        var merged = cloneObject(defaults || {});
        if (!inherited || typeof inherited !== 'object') return merged;
        Object.keys(inherited).forEach(function (k) { merged[k] = inherited[k]; });
        return merged;
    }
    function hasSession() { return !!P.state.sessionId; }
    function getModalEl(mode) { var d = FIELD_CONFIG_DIALOGS[mode]; return d ? document.getElementById(d.modalId) : null; }
    function showModal(mode) { var el = getModalEl(mode); if (el) bootstrap.Modal.getOrCreateInstance(el).show(); }
    function hideModal(mode) {
        var el = getModalEl(mode); if (!el) return;
        if (document.activeElement && el.contains(document.activeElement)) document.activeElement.blur();
        var m = bootstrap.Modal.getInstance(el); if (m) m.hide();
    }
    function updatePlaceModeCursor() {
        $('#pdfPane').toggleClass('place-mode-active', !!(hasSession() && P.state.editMode && P.state.editFieldType));
    }
    function syncOptionsJavascriptToggleState() {
        var $c = $('#optJavascriptCollapse'), $t = $('#optJavascriptToggle');
        if (!$c.length || !$t.length) return;
        var ex = $c.hasClass('show');
        $t.attr('aria-expanded', ex ? 'true' : 'false').toggleClass('collapsed', !ex);
        $t.find('.field-option-collapse-text').text(ex ? 'Hide' : 'Show');
    }
    function setOptionsJavascriptSectionExpanded(canEdit, scriptValue) {
        var el = document.getElementById('optJavascriptCollapse'); if (!el) return;
        var c = bootstrap.Collapse.getOrCreateInstance(el, { toggle: false });
        if (!canEdit) { c.hide(); syncOptionsJavascriptToggleState(); return; }
        if (String(scriptValue || '').trim()) c.show(); else c.hide();
        syncOptionsJavascriptToggleState();
    }

    // ── Pending lookups ──────────────────────────────────────────────────────
    function findPendingFormAddIndex(name) {
        if (!name || !P.state.pendingFormAdds) return -1;
        for (var i = 0; i < P.state.pendingFormAdds.length; i++)
            if (P.state.pendingFormAdds[i] && P.state.pendingFormAdds[i].fieldName === name) return i;
        return -1;
    }
    function findPendingFormAdd(name) { var i = findPendingFormAddIndex(name); return i >= 0 ? P.state.pendingFormAdds[i] : null; }
    function findPendingRectChange(name) {
        if (!name || !P.state.pendingFieldRects) return null;
        for (var i = 0; i < P.state.pendingFieldRects.length; i++) { var it = P.state.pendingFieldRects[i]; if (it && it.fieldName === name) return it; }
        return null;
    }
    function findFieldNodeByName(fullName) {
        var found = null;
        function walk(n) {
            if (!n) return;
            if (n.nodeCategory === 'field' && n.properties && n.properties.FullName === fullName) { found = n; return; }
            if (n.children) n.children.forEach(walk);
        }
        walk(P.state.treeData); return found;
    }
    function getCurrentFieldRectByName(fullName) {
        if (!fullName) return null;
        var pf = findPendingFormAdd(fullName);
        if (pf) return { x: pf.x, y: pf.y, width: pf.width, height: pf.height, pageIndex: pf.pageIndex };
        var node = findFieldNodeByName(fullName);
        if (!node || !node.boundingBox || node.boundingBox.length < 4) return null;
        var pr = findPendingRectChange(fullName);
        if (pr) return { x: pr.x, y: pr.y, width: pr.width, height: pr.height, pageIndex: node.pageIndex };
        return { x: node.boundingBox[0], y: node.boundingBox[1], width: node.boundingBox[2], height: node.boundingBox[3], pageIndex: node.pageIndex };
    }
    function clampPdfRectForField(name, x, y, w, h) {
        var ri = getCurrentFieldRectByName(name);
        if (!ri) return { x: x, y: y, width: w, height: h };
        var vp = P.state.pageViewports[ri.pageIndex];
        if (!vp || !vp.scale) return { x: x, y: y, width: w, height: h };
        var pw = vp.width / vp.scale, ph = vp.height / vp.scale;
        var sw = clamp(w, 1, pw), sh = clamp(h, 1, ph);
        return { x: clamp(x, 0, Math.max(0, pw - sw)), y: clamp(y, 0, Math.max(0, ph - sh)), width: sw, height: sh };
    }
    function queueRectChange(name, x, y, w, h) {
        var b = clampPdfRectForField(name, x, y, w, h); x = b.x; y = b.y; w = b.width; h = b.height;
        var pf = findPendingFormAdd(name);
        if (pf) { pf.x = x; pf.y = y; pf.width = w; pf.height = h; return; }
        var idx = P.state.pendingFieldRects.findIndex(function (r) { return r.fieldName === name; });
        var pl = { fieldName: name, x: x, y: y, width: w, height: h };
        if (idx >= 0) P.state.pendingFieldRects[idx] = pl; else P.state.pendingFieldRects.push(pl);
    }

    // ── Save / pending ───────────────────────────────────────────────────────
    function updateFailedCosBadge() {
        var n = (P.state.pendingCosChanges || []).filter(function (i) { return !!(i && i.lastError); }).length;
        var $b = $('#formSaveFailedBadge'); if (!$b.length) return;
        if (n > 0) $b.text(String(n)).removeClass('d-none'); else $b.addClass('d-none').text('0');
    }
    function refreshSelectionButtons() {
        $('#formOptionsBtn').prop('disabled', !hasSession() || !(P.state.selectedFieldNames || []).length);
        if (P.EditDesigner && P.EditDesigner.updateDesignerButtons) P.EditDesigner.updateDesignerButtons();
    }
    function updateSaveButton() {
        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        if (!P.state.pendingCosChanges)   P.state.pendingCosChanges = [];
        var hp = P.state.pendingFormAdds.length || P.state.pendingFieldRects.length ||
                 P.state.pendingFieldOptions.length || P.state.pendingCosChanges.length;
        $('#formSaveBtn').prop('disabled', !hp || !hasSession());
        updateFailedCosBadge();
        if (P.Tree && P.Tree.refreshPendingPanel) P.Tree.refreshPendingPanel();
        if (P.Storage && P.Storage.saveDraft) P.Storage.saveDraft(P.state);
    }
    function extractApiError(xhr) {
        if (!xhr) return 'Unknown error';
        return (xhr.responseJSON && xhr.responseJSON.error) || xhr.responseText || xhr.statusText || 'Unknown error';
    }
    function apiFetchQueue(url, body, onTree, onFail) {
        return P.Utils.apiFetch(url, { method: 'POST', contentType: 'application/json', data: JSON.stringify(body) })
            .done(function (d) { if (d && d.tree) P.state.treeData = d.tree; onTree(); })
            .fail(onFail);
    }
    function applyPendingRectUpdates(onDone) {
        var done = typeof onDone === 'function' ? onDone : finalizeSave;
        if (!P.state.pendingFieldRects.length) { done(); return; }
        var queue = P.state.pendingFieldRects.slice();
        function next() {
            if (!queue.length) { done(); return; }
            var it = queue.shift();
            apiFetchQueue('/api/edit/' + P.state.sessionId + '/field/' + encodeURIComponent(it.fieldName) + '/rect',
                { x: it.x, y: it.y, width: it.width, height: it.height }, next,
                function () { P.Utils.toast('Saving field geometry failed for ' + it.fieldName, 'danger'); next(); });
        }
        next();
    }
    function applyPendingOptionUpdates(onDone) {
        var done = typeof onDone === 'function' ? onDone : finalizeSave;
        if (!P.state.pendingFieldOptions || !P.state.pendingFieldOptions.length) { done(); return; }
        var queue = P.state.pendingFieldOptions.slice();
        function next() {
            if (!queue.length) { done(); return; }
            var it = queue.shift();
            if (!it || !it.fieldNames || !it.fieldNames.length) { next(); return; }
            apiFetchQueue('/api/edit/' + P.state.sessionId + '/fields/options',
                { fieldNames: it.fieldNames, options: it.options || {} }, next,
                function () { P.Utils.toast('Saving field options failed', 'danger'); next(); });
        }
        next();
    }
    function applyPendingCosUpdates(onDone) {
        var done = typeof onDone === 'function' ? onDone : function () {};
        if (!P.state.pendingCosChanges || !P.state.pendingCosChanges.length) { done({ total: 0, successes: [], failures: [] }); return; }
        var queue = P.state.pendingCosChanges.slice(), ok = [], fail = [];
        function next() {
            if (!queue.length) { P.state.pendingCosChanges = fail.map(function (f) { return f.item; }); done({ total: ok.length + fail.length, successes: ok, failures: fail }); return; }
            var it = queue.shift();
            if (!it || !it.request) { fail.push({ item: it, error: 'Invalid pending COS entry' }); next(); return; }
            P.Utils.apiFetch('/api/cos/' + P.state.sessionId + '/update', { method: 'POST', contentType: 'application/json', data: JSON.stringify(it.request) })
                .done(function (d) { if (d && d.tree) P.state.treeData = d.tree; if (it && it.lastError) delete it.lastError; ok.push(it); next(); })
                .fail(function (xhr) { var e = extractApiError(xhr); if (it) it.lastError = e; fail.push({ item: it, error: e }); next(); });
        }
        next();
    }
    function finalizeSave(cosResult) {
        P.state.pendingFormAdds = []; P.state.pendingFieldRects = []; P.state.pendingFieldOptions = [];
        pendingFieldOptionOverrides = {};
        if (P.state.treeData) P.Utils.refreshAfterMutation(P.state.treeData);
        refreshSelectionButtons(); updateSaveButton();
        if (cosResult && cosResult.total > 0) {
            if (!cosResult.failures.length) { P.Utils.toast('All pending changes saved', 'success'); return; }
            P.Utils.toast('Save completed: ' + cosResult.successes.length + ' succeeded, ' + cosResult.failures.length + ' failed', 'warning');
            cosResult.failures.forEach(function (f) {
                P.Utils.toast('Failed: ' + ((f.item && f.item.summary) || 'COS change') + ' — ' + (f.error || 'Unknown error'), 'danger');
            });
            return;
        }
        P.Utils.toast('Form changes saved', 'success');
    }
    function savePendingChanges() {
        if (!hasSession()) return;
        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        if (!P.state.pendingCosChanges)   P.state.pendingCosChanges = [];
        if (!P.state.pendingFormAdds.length && !P.state.pendingFieldRects.length &&
            !P.state.pendingFieldOptions.length && !P.state.pendingCosChanges.length) {
            P.Utils.toast('No pending changes', 'info'); return;
        }
        $('#formSaveBtn').prop('disabled', true);
        var saveAdds = $.Deferred().resolve();
        if (P.state.pendingFormAdds.length) {
            saveAdds = P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/add-fields',
                { method: 'POST', contentType: 'application/json', data: JSON.stringify(P.state.pendingFormAdds) });
        }
        saveAdds.done(function (d) {
            if (d && d.tree) P.state.treeData = d.tree;
            applyPendingRectUpdates(function () { applyPendingOptionUpdates(function () { applyPendingCosUpdates(finalizeSave); }); });
        }).fail(function () { P.Utils.toast('Saving new fields failed', 'danger'); updateSaveButton(); });
    }
    function resetPending() {
        P.state.pendingFormAdds = []; P.state.pendingFieldRects = []; P.state.pendingFieldOptions = []; P.state.pendingCosChanges = [];
        pendingFieldOptionOverrides = {}; P.state.selectedFieldNames = []; P.state.selectedImageNodeIds = [];
        lastAddedFieldTemplate = null; updatePlaceModeCursor(); refreshSelectionButtons(); updateSaveButton();
    }

    // ── Draw ─────────────────────────────────────────────────────────────────
    function startDraw(e, pageIndex, wrapper) {
        drawing = true; targetPage = pageIndex;
        var rect = wrapper.getBoundingClientRect();
        startX = clamp(e.clientX - rect.left, 0, rect.width);
        startY = clamp(e.clientY - rect.top, 0, rect.height);
        $drawRect = $('<div>', { 'class': 'draw-rect' }).css({ left: startX + 'px', top: startY + 'px' }).appendTo(wrapper);
        var moveH = function (ev) {
            if (!drawing) return;
            var cx = clamp(ev.clientX - rect.left, 0, rect.width), cy = clamp(ev.clientY - rect.top, 0, rect.height);
            $drawRect.css({ left: Math.min(startX, cx) + 'px', top: Math.min(startY, cy) + 'px', width: Math.abs(cx - startX) + 'px', height: Math.abs(cy - startY) + 'px' });
        };
        var upH = function (ev) {
            drawing = false; $(document).off('mousemove', moveH).off('mouseup', upH);
            var cx = clamp(ev.clientX - rect.left, 0, rect.width), cy = clamp(ev.clientY - rect.top, 0, rect.height);
            var x = Math.min(startX, cx), y = Math.min(startY, cy), w = Math.abs(cx - startX), h = Math.abs(cy - startY);
            if ($drawRect) $drawRect.remove();
            if (w < 10 || h < 10) return;
            var vp = P.state.pageViewports[pageIndex], s = vp.scale;
            var sn = P.EditDesigner ? P.EditDesigner.snapV : function (v) { return v; };
            P.EditField.openCreateFieldDialog(P.state.editFieldType, pageIndex, { x: sn(x / s), y: sn((vp.height - y - h) / s), width: sn(w / s), height: sn(h / s) });
        };
        $(document).on('mousemove', moveH).on('mouseup', upH);
    }

    // ── Field handle rendering ────────────────────────────────────────────────
    function isTruthyFlag(v) {
        if (v === true) return true; if (!v && v !== 0) return false;
        var n = String(v).toLowerCase(); return n === 'true' || n === '1' || n === 'yes';
    }
    function isFieldRequired(fn) {
        if (!fn) return false;
        if (fn.pending) return isTruthyFlag(fn.options ? fn.options.required : null);
        var name = fn.properties && fn.properties.FullName;
        var ov = name && pendingFieldOptionOverrides[name];
        if (ov && Object.prototype.hasOwnProperty.call(ov, 'required')) return isTruthyFlag(ov.required);
        return isTruthyFlag((fn.properties || {}).Required) || isTruthyFlag((fn.properties || {}).required);
    }
    function isFieldReadOnly(fn) {
        if (!fn) return false;
        if (fn.pending) return isTruthyFlag(fn.options ? fn.options.readonly : null);
        var name = fn.properties && fn.properties.FullName;
        var ov = name && pendingFieldOptionOverrides[name];
        if (ov && Object.prototype.hasOwnProperty.call(ov, 'readonly')) return isTruthyFlag(ov.readonly);
        return isTruthyFlag((fn.properties || {}).ReadOnly) || isTruthyFlag((fn.properties || {}).readonly);
    }
    function collectFieldNodesOnPage(pageIndex) {
        var result = [];
        function walk(n) {
            if (!n) return;
            if (n.nodeCategory === 'field' && n.pageIndex === pageIndex && n.boundingBox) {
                var copy = $.extend(true, {}, n);
                var fullName = copy.properties && copy.properties.FullName;
                var pr = fullName ? findPendingRectChange(fullName) : null;
                if (pr) copy.boundingBox = [pr.x, pr.y, pr.width, pr.height];
                result.push(copy);
            }
            if (n.children) n.children.forEach(walk);
        }
        walk(P.state.treeData);
        (P.state.pendingFormAdds || []).forEach(function (pa) {
            if (pa.pageIndex !== pageIndex) return;
            result.push({ nodeCategory: 'field', pageIndex: pageIndex, pending: true,
                boundingBox: [pa.x, pa.y, pa.width, pa.height],
                properties: { FullName: pa.fieldName, Pending: true, FieldType: pa.fieldType },
                options: cloneObject(pa.options || {}) });
        });
        return result;
    }
    function renderFieldHandlesForAllPages() {
        if (!P.state.pdfDoc) return;
        for (var i = 0; i < P.state.pdfDoc.numPages; i++) { var w = $('[data-page="' + i + '"]')[0]; if (w) renderFieldHandles(i, w); }
    }
    function updateActionButtonsSide($h, wrapperEl) {
        if (!$h || !$h.length || !wrapperEl) return;
        var $a = $h.find('.field-handle-actions').first(); if (!$a.length) return;
        $a.removeClass('side-left');
        if ((parseFloat($h.css('left')) || 0) + ($h.outerWidth() || 0) + 6 + ($a.outerWidth() || 0) > (wrapperEl.clientWidth || 0))
            $a.addClass('side-left');
    }
    function renderFieldHandles(pageIndex, wrapperEl, viewportOverride) {
        if (!hasSession() || !wrapperEl) return;
        $(wrapperEl).find('.form-field-handle').remove();
        var vp = viewportOverride || P.state.pageViewports[pageIndex]; if (!vp) return;
        collectFieldNodesOnPage(pageIndex).forEach(function (fn) {
            var bbox = fn.boundingBox, s = vp.scale;
            var fullName = fn.properties && fn.properties.FullName; if (!fullName) return;
            var $h = $('<div>', { 'class': 'form-field-handle' }).attr('data-field-name', fullName)
                .css({ left: bbox[0] * s + 'px', top: (vp.height - (bbox[1] + bbox[3]) * s) + 'px',
                       width: bbox[2] * s + 'px', height: bbox[3] * s + 'px' });
            if (fn.pending) $h.addClass('pending');
            if (isFieldRequired(fn)) $h.addClass('required');
            if (isFieldReadOnly(fn)) { $h.addClass('readonly'); $h.append($('<span>', { 'class': 'form-field-readonly-badge', html: '<i class="fas fa-lock"></i>' })); }
            if ((P.state.selectedFieldNames || []).indexOf(fullName) >= 0) $h.addClass('selected');
            if (fn.pending) { $h.attr('title', (fn.properties.FieldType ? fn.properties.FieldType + ': ' : '') + fullName + ' (pending save)'); $h.append($('<span>', { 'class': 'form-field-pending-label', text: 'Pending' })); }
            var $optBtn = $('<button>', { 'class': 'field-handle-btn field-handle-options', title: 'Options', html: '<i class="fas fa-cog"></i>' }).on('click', function (e) {
                e.stopPropagation(); P.state.selectedFieldNames = [fullName]; P.state.selectedImageNodeIds = [];
                renderFieldHandlesForAllPages(); P.EditOptions.openOptionsPopup();
            });
            var $delBtn = $('<button>', { 'class': 'field-handle-btn field-handle-delete', title: 'Delete', html: '<i class="fas fa-trash-alt"></i>' }).on('click', function (e) { e.stopPropagation(); queueFieldDelete(fullName); });
            if (isFieldDeletePending(fullName)) {
                var $ub = $('<button>', { 'class': 'field-handle-btn field-handle-undo', title: 'Undo remove', html: '<i class="fas fa-undo"></i>' }).on('click', function (e) { e.stopPropagation(); undoFieldDelete(fullName); });
                $h.addClass('removed').append($('<div>', { 'class': 'field-handle-actions' }).append($ub)).appendTo(wrapperEl);
            } else {
                $h.append($('<div>', { 'class': 'field-handle-actions' }).append($optBtn, $delBtn), $('<div>', { 'class': 'form-field-resize' })).appendTo(wrapperEl);
                P.EditField.bindDragResize($h, $h.find('.form-field-resize'), fn, vp);
            }
            updateActionButtonsSide($h, wrapperEl);
        });
        refreshSelectionButtons();
    }

    // ── Selection ────────────────────────────────────────────────────────────
    function syncHandleSelectionClasses() {
        var sel = P.state.selectedFieldNames || [];
        $('.form-field-handle').each(function () { var $e = $(this); $e.toggleClass('selected', sel.indexOf($e.attr('data-field-name')) >= 0); });
    }
    function syncTreeSelectionForField(name) {
        if (!name || !P.Tree || !P.Tree.selectNode) return;
        var n = findFieldNodeByName(name); if (n) P.Tree.selectNode(n, false, true, true);
    }
    function toggleFieldSelection(name) {
        if (!P.state.selectedFieldNames) P.state.selectedFieldNames = [];
        var idx = P.state.selectedFieldNames.indexOf(name);
        if (idx >= 0) P.state.selectedFieldNames.splice(idx, 1); else P.state.selectedFieldNames.push(name);
        syncHandleSelectionClasses(); syncTreeSelectionForField(name); refreshSelectionButtons();
    }
    function findHandleElementByFieldName(name) {
        var match = null;
        $('.form-field-handle').each(function () { if ($(this).attr('data-field-name') === name) { match = $(this); return false; } return true; });
        return match;
    }
    function selectFieldFromViewer(fieldNode, additive) {
        if (!fieldNode || !fieldNode.properties) return;
        var fn = fieldNode.properties.FullName; if (!fn) return;
        if (additive) { toggleFieldSelection(fn); } else {
            P.state.selectedFieldNames = [fn]; P.state.selectedImageNodeIds = [];
            syncHandleSelectionClasses(); syncTreeSelectionForField(fn);
        }
        renderFieldHandlesForAllPages(); P.Tree.selectNode(fieldNode, additive, true); refreshSelectionButtons();
    }
    function refreshFieldSelectionHighlights() { renderFieldHandlesForAllPages(); refreshSelectionButtons(); }

    // ── Field delete ─────────────────────────────────────────────────────────
    function isFieldDeletePending(name) { return !!(P.state.pendingFieldDeletes && P.state.pendingFieldDeletes[name]); }
    function undoFieldDelete(name) {
        if (!P.state.pendingFieldDeletes || !P.state.pendingFieldDeletes[name]) return;
        window.clearTimeout(P.state.pendingFieldDeletes[name].timerId);
        delete P.state.pendingFieldDeletes[name];
        renderFieldHandlesForAllPages(); P.Utils.toast('Removal cancelled for "' + name + '"', 'info');
    }
    function finalizeFieldDelete(name) {
        if (!P.state.sessionId || !P.state.pendingFieldDeletes || !P.state.pendingFieldDeletes[name]) return;
        window.clearTimeout(P.state.pendingFieldDeletes[name].timerId); delete P.state.pendingFieldDeletes[name];
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' + encodeURIComponent(name), { method: 'DELETE' })
            .done(function (d) { P.state.treeData = d.tree; P.Tree.render(P.state.treeData); renderFieldHandlesForAllPages(); P.Utils.toast('Field "' + name + '" deleted', 'success'); })
            .fail(function () { P.Utils.toast('Delete field failed', 'danger'); renderFieldHandlesForAllPages(); });
    }
    function queueFieldDelete(name) {
        if (!P.state.sessionId || !name) return;
        var pi = findPendingFormAddIndex(name);
        if (pi >= 0) {
            P.state.pendingFormAdds.splice(pi, 1);
            P.state.pendingFieldRects = (P.state.pendingFieldRects || []).filter(function (r) { return r.fieldName !== name; });
            if (P.state.selectedFieldNames) P.state.selectedFieldNames = P.state.selectedFieldNames.filter(function (n) { return n !== name; });
            if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) P.Tabs.switchTab(P.state.currentTab);
            renderFieldHandlesForAllPages(); updateSaveButton(); P.Utils.toast('Pending field "' + name + '" removed', 'info'); return;
        }
        if (!P.state.pendingFieldDeletes) P.state.pendingFieldDeletes = {};
        if (P.state.pendingFieldDeletes[name]) return;
        P.state.pendingFieldDeletes[name] = { timerId: window.setTimeout(function () { finalizeFieldDelete(name); }, 5000) };
        if (P.state.selectedFieldNames) P.state.selectedFieldNames = P.state.selectedFieldNames.filter(function (n) { return n !== name; });
        renderFieldHandlesForAllPages(); P.Utils.toast('Field "' + name + '" marked for removal (Undo available)', 'warning');
    }
    function deleteField(name) { queueFieldDelete(name); }
    function setFieldValue(name, value) {
        if (!P.state.sessionId) return;
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' + encodeURIComponent(name) + '/value',
            { method: 'POST', contentType: 'application/json', data: JSON.stringify({ value: value }) })
            .done(function (d) { P.state.treeData = d.tree; P.Tree.render(P.state.treeData); P.Utils.toast('Field value updated', 'success'); })
            .fail(function () { P.Utils.toast('Update field value failed', 'danger'); });
    }

    // ── init ─────────────────────────────────────────────────────────────────
    function init() {
        $('#editToolbar').addClass('active');
        P.state.editMode = true; P.state.selectedFieldNames = []; P.state.selectedImageNodeIds = [];
        if (!P.state.pendingFieldDeletes) P.state.pendingFieldDeletes = {};
        updatePlaceModeCursor();
        $('.edit-field-btn').on('click', function () {
            if (!hasSession()) { P.Utils.toast('Load a PDF session first', 'warning'); return; }
            $('.edit-field-btn').removeClass('active'); $(this).addClass('active');
            P.state.editFieldType = $(this).data('type'); updatePlaceModeCursor();
            P.Utils.toast('Draw a rectangle on the PDF page to place the new ' + P.state.editFieldType + ' field', 'info');
        });
        $('#formSaveBtn').on('click', savePendingChanges);
        P.EditField.init();
        P.EditOptions.init();
    }

    return {
        init: init, startDraw: startDraw, deleteField: deleteField,
        selectFieldFromViewer: selectFieldFromViewer, refreshFieldSelectionHighlights: refreshFieldSelectionHighlights,
        setFieldValue: setFieldValue, renderFieldHandles: renderFieldHandles,
        renderFieldHandlesForAllPages: renderFieldHandlesForAllPages,
        resetPending: resetPending, savePendingChanges: savePendingChanges, updateSaveButton: updateSaveButton,
        showModal: showModal, hideModal: hideModal,
        syncOptionsJavascriptToggleState: syncOptionsJavascriptToggleState,
        setOptionsJavascriptSectionExpanded: setOptionsJavascriptSectionExpanded,
        cloneObject: cloneObject, mergeOptions: mergeOptions, findPendingFormAdd: findPendingFormAdd,
        clamp: clamp,
        // Selection helpers needed by EditField.bindDragResize
        toggleFieldSelection: toggleFieldSelection, syncHandleSelectionClasses: syncHandleSelectionClasses,
        syncTreeSelectionForField: syncTreeSelectionForField, findHandleElementByFieldName: findHandleElementByFieldName,
        getCurrentFieldRectByName: getCurrentFieldRectByName, queueRectChange: queueRectChange,
        refreshSelectionButtons: refreshSelectionButtons,
        getPendingCreatePayload: function () { return pendingCreatePayload; },
        setPendingCreatePayload: function (v) { pendingCreatePayload = v; },
        getLastAddedFieldTemplate: function () { return lastAddedFieldTemplate; },
        setLastAddedFieldTemplate: function (v) { lastAddedFieldTemplate = v; },
        getPendingFieldOptionOverrides: function () { return pendingFieldOptionOverrides; },
        findFieldNodeByName: findFieldNodeByName
    };
})(jQuery, PDFalyzer);
