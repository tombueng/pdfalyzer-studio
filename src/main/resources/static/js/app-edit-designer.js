/**
 * PDFalyzer Studio – Field designer: snap-to-grid, copy/paste, align/distribute.
 * Depends on app-edit-mode.js and app-edit-field.js (loaded first).
 */
PDFalyzer.EditDesigner = (function ($, P) {
    'use strict';

    var clipboard = [];
    var gridEnabled = false;
    var gridSize = 10; // PDF user units

    // ── Snap ─────────────────────────────────────────────────────────────────
    function snapV(v) { return gridEnabled ? Math.round(v / gridSize) * gridSize : v; }

    function toggleGrid() {
        gridEnabled = !gridEnabled;
        $('#snapGridBtn').toggleClass('active', gridEnabled)
            .attr('title', (gridEnabled ? 'Snap to grid ON' : 'Snap to grid OFF') + ' (G)');
        P.Utils.toast('Snap to grid ' + (gridEnabled ? 'ON (every ' + gridSize + ' pt)' : 'OFF'), 'info');
    }

    // ── Selection rects ───────────────────────────────────────────────────────
    function getSelectedRects() {
        var result = [];
        (P.state.selectedFieldNames || []).forEach(function (name) {
            var rect = P.EditMode.getCurrentFieldRectByName(name);
            if (rect) result.push({ name: name, rect: rect });
        });
        return result;
    }

    // ── Copy / Paste ──────────────────────────────────────────────────────────
    function copySelected() {
        var sel = P.state.selectedFieldNames || [];
        if (!sel.length) return;
        clipboard = [];
        sel.forEach(function (name) {
            var rect = P.EditMode.getCurrentFieldRectByName(name);
            var pf = P.EditMode.findPendingFormAdd(name);
            if (!rect) return;
            var fieldType = 'text';
            if (pf && pf.fieldType) {
                fieldType = pf.fieldType;
            } else if (P.EditMode.findFieldNodeByName) {
                var node = P.EditMode.findFieldNodeByName(name);
                if (node && node.properties) {
                    var ft = node.properties.FieldSubType || node.properties.FieldType || 'text';
                    fieldType = ft.toLowerCase() === 'tx' ? 'text' : ft.toLowerCase() === 'ch' ? 'combo' :
                                ft.toLowerCase() === 'btn' ? 'checkbox' : ft.toLowerCase();
                }
            }
            clipboard.push({ fieldType: fieldType, width: rect.width, height: rect.height,
                options: pf ? P.EditMode.cloneObject(pf.options || {}) : {},
                pageIndex: rect.pageIndex, fromX: rect.x, fromY: rect.y });
        });
        if (clipboard.length) P.Utils.toast('Copied ' + clipboard.length + ' field(s)', 'info');
        updateDesignerButtons();
    }

    function pasteFields() {
        if (!clipboard.length || !P.state.sessionId) return;
        var offset = gridEnabled ? gridSize : 5;
        clipboard.forEach(function (c) {
            var fieldId = P.EditField.suggestNextFieldId(c.fieldType);
            P.state.pendingFormAdds.push({ fieldType: c.fieldType, fieldName: fieldId,
                pageIndex: c.pageIndex || 0,
                x: snapV(c.fromX + offset), y: snapV(c.fromY - offset),
                width: c.width, height: c.height,
                options: P.EditMode.cloneObject(c.options || {}) });
            P.EditMode.pushFieldUndo(fieldId, { type: 'add' });
        });
        P.EditMode.renderFieldHandlesForAllPages();
        P.EditMode.updateSaveButton();
        P.Utils.toast('Pasted ' + clipboard.length + ' field(s)', 'info');
    }

    // ── Align / distribute ────────────────────────────────────────────────────
    function alignFields(mode) {
        var items = getSelectedRects();
        if (items.length < 2) { P.Utils.toast('Select 2+ fields to align', 'warning'); return; }
        var minX = Infinity, maxRight = -Infinity, minY = Infinity, maxTop = -Infinity;
        items.forEach(function (it) {
            var r = it.rect;
            minX = Math.min(minX, r.x); maxRight = Math.max(maxRight, r.x + r.width);
            minY = Math.min(minY, r.y); maxTop = Math.max(maxTop, r.y + r.height);
        });
        var midX = (minX + maxRight) / 2, midY = (minY + maxTop) / 2;
        items.forEach(function (it) {
            var r = it.rect, nx = r.x, ny = r.y;
            if      (mode === 'left')    nx = minX;
            else if (mode === 'right')   nx = maxRight - r.width;
            else if (mode === 'top')     ny = maxTop - r.height;
            else if (mode === 'bottom')  ny = minY;
            else if (mode === 'centerH') nx = midX - r.width / 2;
            else if (mode === 'centerV') ny = midY - r.height / 2;
            P.EditMode.queueRectChange(it.name, snapV(nx), snapV(ny), r.width, r.height);
        });
        P.EditMode.renderFieldHandlesForAllPages();
        P.EditMode.updateSaveButton();
    }

    function sizeFields(mode) {
        var items = getSelectedRects();
        if (items.length < 2) { P.Utils.toast('Select 2+ fields to match size', 'warning'); return; }
        var refW = items[0].rect.width, refH = items[0].rect.height;
        items.forEach(function (it) {
            var r = it.rect;
            var nw = (mode === 'matchWidth' || mode === 'matchSize') ? refW : r.width;
            var nh = (mode === 'matchHeight' || mode === 'matchSize') ? refH : r.height;
            P.EditMode.queueRectChange(it.name, r.x, r.y, nw, nh);
        });
        P.EditMode.renderFieldHandlesForAllPages();
        P.EditMode.updateSaveButton();
    }

    function distributeFields(axis) {
        var items = getSelectedRects();
        if (items.length < 3) { P.Utils.toast('Select 3+ fields to distribute', 'warning'); return; }
        if (axis === 'h') {
            items.sort(function (a, b) { return a.rect.x - b.rect.x; });
            var totalW = 0; items.forEach(function (it) { totalW += it.rect.width; });
            var spanW = (items[items.length - 1].rect.x + items[items.length - 1].rect.width) - items[0].rect.x;
            var gapH = (spanW - totalW) / (items.length - 1);
            var cx = items[0].rect.x;
            items.forEach(function (it) {
                P.EditMode.queueRectChange(it.name, snapV(cx), it.rect.y, it.rect.width, it.rect.height);
                cx += it.rect.width + gapH;
            });
        } else {
            items.sort(function (a, b) { return a.rect.y - b.rect.y; });
            var totalH = 0; items.forEach(function (it) { totalH += it.rect.height; });
            var spanH = (items[items.length - 1].rect.y + items[items.length - 1].rect.height) - items[0].rect.y;
            var gapV = (spanH - totalH) / (items.length - 1);
            var cy = items[0].rect.y;
            items.forEach(function (it) {
                P.EditMode.queueRectChange(it.name, it.rect.x, snapV(cy), it.rect.width, it.rect.height);
                cy += it.rect.height + gapV;
            });
        }
        P.EditMode.renderFieldHandlesForAllPages();
        P.EditMode.updateSaveButton();
    }

    // ── Button state ──────────────────────────────────────────────────────────
    function updateDesignerButtons() {
        var nSel = (P.state.selectedFieldNames || []).length;
        var hasSel = !!(P.state.sessionId && nSel);
        $('#copyFieldsBtn').prop('disabled', !hasSel);
        $('#pasteFieldsBtn').prop('disabled', !clipboard.length || !P.state.sessionId);
        $('#alignDropdownBtn').prop('disabled', !hasSel || nSel < 2);
    }

    // ── init ──────────────────────────────────────────────────────────────────
    function init() {
        $('#snapGridBtn').on('click', toggleGrid);
        $('#copyFieldsBtn').on('click', copySelected);
        $('#pasteFieldsBtn').on('click', pasteFields);
        $('#alignLeftBtn').on('click',    function () { alignFields('left'); });
        $('#alignRightBtn').on('click',   function () { alignFields('right'); });
        $('#alignTopBtn').on('click',     function () { alignFields('top'); });
        $('#alignBottomBtn').on('click',  function () { alignFields('bottom'); });
        $('#centerHBtn').on('click',      function () { alignFields('centerH'); });
        $('#centerVBtn').on('click',      function () { alignFields('centerV'); });
        $('#distributeHBtn').on('click',  function () { distributeFields('h'); });
        $('#distributeVBtn').on('click',  function () { distributeFields('v'); });
        $('#matchWidthBtn').on('click',   function () { sizeFields('matchWidth'); });
        $('#matchHeightBtn').on('click',  function () { sizeFields('matchHeight'); });
        $('#matchSizeBtn').on('click',    function () { sizeFields('matchSize'); });
        updateDesignerButtons();
    }

    return {
        init: init, toggleGrid: toggleGrid,
        copySelected: copySelected, pasteFields: pasteFields,
        alignFields: alignFields, distributeFields: distributeFields, sizeFields: sizeFields,
        updateDesignerButtons: updateDesignerButtons,
        snapV: snapV, isGridEnabled: function () { return gridEnabled; }
    };
})(jQuery, PDFalyzer);
