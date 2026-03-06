/**
 * PDFalyzer Studio – Create Field dialog + drag/resize for field handles.
 * Depends on app-edit-mode.js being loaded first (P.EditMode must exist).
 */
PDFalyzer.EditField = (function ($, P) {
    'use strict';

    var CREATE_BOOL_GROUPS = {
        required:    { inputName: 'createRequired',    blockSelector: '#createRequiredBlock' },
        readonly:    { inputName: 'createReadonly',    blockSelector: '#createReadonlyBlock' },
        multiline:   { inputName: 'createMultiline',  blockSelector: '#createMultilineBlock' },
        editable:    { inputName: 'createEditable',   blockSelector: '#createEditableBlock' },
        multiSelect: { inputName: 'createMultiSelect',blockSelector: '#createMultiSelectBlock' },
        checked:     { inputName: 'createChecked',    blockSelector: '#createCheckedBlock' }
    };
    var JS_PRESET_CREATE = { presetSelect: '#createJsPreset', paramsContainer: '#createJsPresetParams',
                              applyButton: '#createJsPresetApplyBtn', scriptTarget: '#createJavascript' };

    // ── Shared option-key helpers ────────────────────────────────────────────
    function getEntryFieldType(entry) {
        if (!entry) return 'unknown';
        if (entry.kind === 'pending' && entry.pendingField && entry.pendingField.fieldType)
            return String(entry.pendingField.fieldType).toLowerCase();
        var props = entry.node && entry.node.properties ? entry.node.properties : {};
        if (props.FieldSubType) return String(props.FieldSubType).toLowerCase();
        if (!props.FieldType) return 'unknown';
        var n = String(props.FieldType).toLowerCase();
        return n === 'tx' ? 'text' : n === 'ch' ? 'combo' : n === 'btn' ? 'checkbox' : n === 'sig' ? 'signature' : n;
    }
    function getSupportedOptionsForFieldType(ft) {
        var s = { required: true, readonly: true };
        if (ft === 'text')      { s.multiline = true; s.defaultValue = true; s.javascript = true; }
        if (ft === 'combo')     { s.editable = true; s.defaultValue = true; s.choices = true; s.javascript = true; }
        if (ft === 'list')      { s.multiSelect = true; s.defaultValue = true; s.choices = true; s.javascript = true; }
        if (ft === 'checkbox')  { s.checked = true; s.javascript = true; }
        if (ft === 'radio')     { s.radioOptions = true; s.defaultValue = true; s.javascript = true; }
        if (ft === 'signature') {}
        return s;
    }
    function computeVisibleOptionKeys(entries) {
        if (!entries || !entries.length) return {};
        var visible = null;
        entries.forEach(function (e) {
            var sup = getSupportedOptionsForFieldType(getEntryFieldType(e));
            if (!visible) { visible = sup; return; }
            Object.keys(visible).forEach(function (k) { if (!sup[k]) delete visible[k]; });
        });
        return visible || {};
    }

    // ── DOM helpers ──────────────────────────────────────────────────────────
    function setFieldConfigBlockVisible(sel, vis) { $(sel).toggleClass('field-option-hidden', !vis); }
    function isFieldConfigBlockVisible(sel) { return !$(sel).hasClass('field-option-hidden'); }
    function setCreateBooleanControlValue(optName, bval) {
        var g = CREATE_BOOL_GROUPS[optName]; if (!g) return;
        var $inp = $('input[name="' + g.inputName + '"]'); if (!$inp.length) return;
        $inp.prop('checked', false); $inp.filter('[value="' + (bval ? 'true' : 'false') + '"]').prop('checked', true);
    }
    function getCreateBooleanControlValue(optName) {
        var g = CREATE_BOOL_GROUPS[optName]; if (!g) return false;
        return $('input[name="' + g.inputName + '"]:checked').val() === 'true';
    }

    // ── Field ID suggestion ──────────────────────────────────────────────────
    function getFieldIdPrefix(ft) {
        var n = (ft || '').toLowerCase();
        return n === 'text' ? 'text' : n === 'combo' ? 'combo' : n === 'list' ? 'list' :
               n === 'checkbox' ? 'check' : n === 'radio' ? 'radio' : n === 'signature' ? 'sign' : 'field';
    }
    function collectUsedFieldIds() {
        var used = Object.create(null);
        function mark(name) { if (name) used[String(name)] = true; }
        function walk(n) { if (!n) return; if (n.nodeCategory === 'field' && n.properties && n.properties.FullName) mark(n.properties.FullName); if (n.children) n.children.forEach(walk); }
        walk(P.state.treeData);
        (P.state.pendingFormAdds || []).forEach(function (pa) { if (pa && pa.fieldName) mark(pa.fieldName); });
        return used;
    }
    function suggestNextFieldId(ft) {
        var prefix = getFieldIdPrefix(ft), used = collectUsedFieldIds(), idx = 1;
        while (used[prefix + idx]) idx++;
        return prefix + idx;
    }

    // ── Default / resolved options ───────────────────────────────────────────
    function getDefaultOptions(ft) {
        var defChoices = [{ value: 'A', label: '' }, { value: 'B', label: '' }, { value: 'C', label: '' }];
        if (ft === 'text')      return { required: false, readonly: false, multiline: false, defaultValue: '' };
        if (ft === 'checkbox')  return { required: false, readonly: false, checked: false };
        if (ft === 'combo')     return { required: false, readonly: false, editable: false, choices: defChoices, defaultValue: '' };
        if (ft === 'list')      return { required: false, readonly: false, multiSelect: false, choices: defChoices, defaultValue: '' };
        if (ft === 'radio')     return { required: false, readonly: false,
            radioOptions: [{ value: 'Option1', label: '' }, { value: 'Option2', label: '' }],
            radioDefault: '' };
        if (ft === 'signature') return { required: true, readonly: false };
        return {};
    }

    // ── Generic choice row helpers (combo/list) ──────────────────────────────
    function parseChoicesToRows(choices) {
        if (!choices) return [];
        if (Array.isArray(choices)) {
            return choices.map(function (c) {
                if (c && typeof c === 'object') return { value: c.value || '', label: c.label || '' };
                return { value: String(c).trim(), label: '' };
            }).filter(function (r) { return r.value; });
        }
        return String(choices).split(',').map(function (v) { return { value: v.trim(), label: '' }; }).filter(function (r) { return r.value; });
    }

    function buildChoiceOptionRow(val, lbl, idx, tbodyId, syncFn) {
        var $tr = $('<tr>').attr('data-row-idx', idx);
        $tr.append($('<td>').addClass('text-muted align-middle choice-row-num').text(idx + 1));
        $tr.append($('<td>').append($('<input>').addClass('form-control form-control-sm choice-opt-value').val(val).attr('placeholder', 'value')));
        $tr.append($('<td>').append($('<input>').addClass('form-control form-control-sm choice-opt-label').val(lbl).attr('placeholder', 'same as value')));
        $tr.append($('<td>').append(
            $('<button>').addClass('btn btn-sm btn-outline-danger choice-opt-remove').attr('title', 'Remove')
                .html('<i class="fas fa-times"></i>')
                .on('click', function () { $tr.remove(); renumberChoiceRows(tbodyId); if (syncFn) syncFn(); })
        ));
        $tr.find('.choice-opt-value').on('input', function () { if (syncFn) syncFn(); });
        return $tr;
    }
    function renumberChoiceRows(tbodyId) {
        $('#' + tbodyId + ' tr').each(function (i) {
            $(this).attr('data-row-idx', i).find('.choice-row-num').text(i + 1);
        });
    }
    function renderChoiceOptionTable(rows, tbodyId, syncFn) {
        var $tbody = $('#' + tbodyId);
        $tbody.empty();
        var items = rows && rows.length ? rows : [{ value: 'A', label: '' }, { value: 'B', label: '' }];
        items.forEach(function (row, i) { $tbody.append(buildChoiceOptionRow(row.value || '', row.label || '', i, tbodyId, syncFn)); });
        renumberChoiceRows(tbodyId);
    }
    function collectChoiceOptions(tbodyId) {
        var rows = [], seen = Object.create(null);
        $('#' + tbodyId + ' tr').each(function () {
            var v = ($(this).find('.choice-opt-value').val() || '').trim();
            var l = ($(this).find('.choice-opt-label').val() || '').trim();
            if (!v || seen[v]) return;
            seen[v] = true;
            rows.push({ value: v, label: l });
        });
        return rows;
    }
    function syncChoiceDefaultSelect(selectId, tbodyId) {
        var prev = $(selectId).val();
        var $sel = $(selectId).empty().append($('<option>').val('').text('(none)'));
        collectChoiceOptions(tbodyId).forEach(function (row) {
            var display = row.label && row.label.trim() ? row.label.trim() + ' (' + row.value + ')' : row.value;
            $sel.append($('<option>').val(row.value).text(display));
        });
        if (prev) $sel.val(prev);
        if (!$sel.val()) $sel.val('');
    }
    function choiceRowsToBackendFormat(rows) {
        var hasDistinct = rows.some(function (r) { return r.label && r.label.trim() && r.label.trim() !== r.value; });
        if (hasDistinct) return rows.map(function (r) { return { value: r.value, label: r.label || r.value }; });
        return rows.map(function (r) { return r.value; });
    }

    // ── Radio option table helpers ────────────────────────────────────────────
    function renderRadioOptionTable(rows, tbodyId, syncFn) {
        tbodyId = tbodyId || 'radioOptionRows';
        var $tbody = $('#' + tbodyId);
        $tbody.empty();
        var items = Array.isArray(rows) ? rows : [];
        if (!items.length) items = [{ value: 'Option1', label: '' }, { value: 'Option2', label: '' }];
        items.forEach(function (row, i) { $tbody.append(buildRadioOptionRow(row.value || '', row.label || '', i, tbodyId, syncFn)); });
        renumberRadioRows(tbodyId);
    }
    function buildRadioOptionRow(val, lbl, idx, tbodyId, syncFn) {
        tbodyId = tbodyId || 'radioOptionRows';
        var $tr = $('<tr>').attr('data-row-idx', idx);
        $tr.append($('<td>').addClass('text-muted align-middle radio-row-num').text(idx + 1));
        $tr.append($('<td>').append($('<input>').addClass('form-control form-control-sm radio-opt-value').val(val).attr('placeholder', 'value')));
        $tr.append($('<td>').append($('<input>').addClass('form-control form-control-sm radio-opt-label').val(lbl).attr('placeholder', 'same as value')));
        $tr.append($('<td>').append(
            $('<button>').addClass('btn btn-sm btn-outline-danger radio-opt-remove').attr('title', 'Remove')
                .html('<i class="fas fa-times"></i>')
                .on('click', function () { $tr.remove(); renumberRadioRows(tbodyId); if (syncFn) syncFn(); })
        ));
        $tr.find('.radio-opt-value').on('input', function () { if (syncFn) syncFn(); });
        return $tr;
    }
    function renumberRadioRows(tbodyId) {
        tbodyId = tbodyId || 'radioOptionRows';
        $('#' + tbodyId + ' tr').each(function (i) {
            $(this).attr('data-row-idx', i).find('.radio-row-num').text(i + 1);
        });
    }
    function syncRadioDefaultSelect(selectId, tbodyId) {
        selectId = selectId || '#createRadioDefault';
        tbodyId = tbodyId || 'radioOptionRows';
        var prev = $(selectId).val();
        var $sel = $(selectId).empty().append($('<option>').val('').text('(none)'));
        collectRadioOptionsFrom(tbodyId).forEach(function (row) {
            var display = row.label && row.label.trim() ? row.label.trim() + ' (' + row.value + ')' : row.value;
            $sel.append($('<option>').val(row.value).text(display));
        });
        if (prev) $sel.val(prev);
        if (!$sel.val()) $sel.val('');
    }
    function collectRadioOptionsFrom(tbodyId) {
        tbodyId = tbodyId || 'radioOptionRows';
        var rows = [], seen = Object.create(null);
        $('#' + tbodyId + ' tr').each(function () {
            var v = ($(this).find('.radio-opt-value').val() || '').trim();
            var l = ($(this).find('.radio-opt-label').val() || '').trim();
            if (!v || seen[v]) return;
            seen[v] = true;
            rows.push({ value: v, label: l });
        });
        return rows;
    }
    // legacy alias used in init binding
    function collectRadioOptions() { return collectRadioOptionsFrom('radioOptionRows'); }

    function resolveCreateOptions(ft) {
        var def = getDefaultOptions(ft);
        var tpl = P.EditMode.getLastAddedFieldTemplate();
        if (tpl && tpl.options) return P.EditMode.mergeOptions(def, tpl.options);
        var adds = P.state.pendingFormAdds;
        if (adds && adds.length) { var prev = adds[adds.length - 1]; if (prev && prev.options) return P.EditMode.mergeOptions(def, prev.options); }
        return def;
    }

    // ── JS preset helpers ────────────────────────────────────────────────────
    function getJsPresetDefinitions() {
        return {
            custom: { title: 'Custom script', fields: [] },
            length: { title: 'Length (min/max)', fields: [{ key: 'min', label: 'Min length', type: 'number', placeholder: '0' }, { key: 'max', label: 'Max length', type: 'number', placeholder: '100' }] },
            number: { title: 'Number (min/max)', fields: [{ key: 'min', label: 'Min number', type: 'number', placeholder: '0' }, { key: 'max', label: 'Max number', type: 'number', placeholder: '100' }] },
            date: { title: 'Date (format)', fields: [{ key: 'format', label: 'Date format', type: 'select', options: [{ value: 'yyyy-mm-dd', label: 'YYYY-MM-DD' }, { value: 'dd.mm.yyyy', label: 'DD.MM.YYYY' }, { value: 'mm/dd/yyyy', label: 'MM/DD/YYYY' }] }] }
        };
    }
    function renderJsPresetParams(mode, def) {
        if (!def) return;
        var $sel = $(def.presetSelect), $con = $(def.paramsContainer); if (!$sel.length || !$con.length) return;
        var pd = getJsPresetDefinitions()[$sel.val() || 'custom'] || getJsPresetDefinitions().custom;
        $con.empty();
        if (!pd.fields || !pd.fields.length) { $con.append('<div class="text-muted" style="font-size:12px;">Use the text area for custom validation JavaScript.</div>'); return; }
        var html = '';
        pd.fields.forEach(function (f) {
            var id = mode + 'JsPreset_' + f.key;
            html += '<div class="mb-2"><label class="form-label form-label-sm" for="' + id + '">' + f.label + '</label>';
            if (f.type === 'select') {
                html += '<select class="form-select form-select-sm" id="' + id + '">';
                (f.options || []).forEach(function (o) { html += '<option value="' + o.value + '">' + o.label + '</option>'; });
                html += '</select>';
            } else { html += '<input class="form-control form-control-sm" id="' + id + '" type="' + (f.type || 'text') + '" placeholder="' + (f.placeholder || '') + '">'; }
            html += '</div>';
        });
        $con.html(html);
    }
    function collectJsPresetParams(mode, def) {
        if (!def) return {};
        var pd = getJsPresetDefinitions()[$(def.presetSelect).val() || 'custom'] || getJsPresetDefinitions().custom;
        var params = {};
        (pd.fields || []).forEach(function (f) { params[f.key] = $('#' + mode + 'JsPreset_' + f.key).val(); });
        return params;
    }
    function buildPresetScript(key, params) {
        function numOrNull(v) { return (v !== '' && v !== undefined) ? Number(v) : null; }
        if (key === 'length') {
            var mn = numOrNull(params.min), mx = numOrNull(params.max), ch = [];
            if (!isNaN(mn) && mn !== null) ch.push('len < ' + mn); if (!isNaN(mx) && mx !== null) ch.push('len > ' + mx);
            if (!ch.length) return '';
            return 'if (event.value !== null && event.value !== undefined && event.value !== "") {\n    var len = String(event.value).length;\n    if (' + ch.join(' || ') + ') {\n        app.alert("Invalid length.");\n        event.rc = false;\n    }\n}';
        }
        if (key === 'number') {
            var mn2 = numOrNull(params.min), mx2 = numOrNull(params.max), ch2 = [];
            if (!isNaN(mn2) && mn2 !== null) ch2.push('num < ' + mn2); if (!isNaN(mx2) && mx2 !== null) ch2.push('num > ' + mx2);
            return 'if (event.value !== null && event.value !== undefined && event.value !== "") {\n    var num = Number(event.value);\n    if (isNaN(num)' + (ch2.length ? ' || ' + ch2.join(' || ') : '') + ') {\n        app.alert("Please enter a valid number' + (ch2.length ? ' in range' : '') + '.");\n        event.rc = false;\n    }\n}';
        }
        if (key === 'date') {
            var fmt = params.format || 'yyyy-mm-dd';
            var rx = '/^\\d{4}-\\d{2}-\\d{2}$/', lbl = 'YYYY-MM-DD';
            if (fmt === 'dd.mm.yyyy') { rx = '/^\\d{2}\\.\\d{2}\\.\\d{4}$/'; lbl = 'DD.MM.YYYY'; }
            else if (fmt === 'mm/dd/yyyy') { rx = '/^\\d{2}\\/\\d{2}\\/\\d{4}$/'; lbl = 'MM/DD/YYYY'; }
            return 'if (event.value !== null && event.value !== undefined && event.value !== "") {\n    var pattern = ' + rx + ';\n    if (!pattern.test(String(event.value))) {\n        app.alert("Please enter date as ' + lbl + '.");\n        event.rc = false;\n    }\n}';
        }
        return '';
    }
    function applyJsPresetToDialog(mode, def) {
        if (!def) return;
        var key = $(def.presetSelect).val() || 'custom'; if (key === 'custom') return;
        var script = buildPresetScript(key, collectJsPresetParams(mode, def));
        if (!script) { P.Utils.toast('Please fill preset values first', 'warning'); return; }
        $(def.scriptTarget).val(script); P.Utils.toast('Validation preset inserted', 'info');
    }
    function bindJsPresetControls() {
        $(JS_PRESET_CREATE.presetSelect).on('change', function () { renderJsPresetParams('create', JS_PRESET_CREATE); });
        $(JS_PRESET_CREATE.applyButton).on('click', function () { applyJsPresetToDialog('create', JS_PRESET_CREATE); });
    }
    function initializeJsPresetUi(mode, def) {
        if (!def) return; $(def.presetSelect).val('custom'); renderJsPresetParams(mode, def);
    }

    // ── Open / apply create-field dialog ────────────────────────────────────
    function openCreateFieldDialog(fieldType, pageIndex, rect) {
        if (!fieldType || !rect) return;
        var payload = { fieldType: fieldType, pageIndex: pageIndex, x: rect.x, y: rect.y, width: rect.width, height: rect.height, options: resolveCreateOptions(fieldType) };
        P.EditMode.setPendingCreatePayload(payload);

        P.FieldDialog.open('create', fieldType, payload.options, {
            suggestedId: suggestNextFieldId(fieldType),
            pageDisplay: String(pageIndex + 1),
            onApply: function (opts) { applyCreateFromDialog(opts); },
            onHide: function () { P.EditMode.setPendingCreatePayload(null); }
        });
    }

    function applyCreateFromDialog(opts) {
        var pcp = P.EditMode.getPendingCreatePayload(); if (!pcp) return;
        var fieldId = ($('#fdFieldId').val() || '').trim();
        if (!fieldId) { P.Utils.toast('Field ID is required', 'warning'); return; }

        var mergedOpts = P.EditMode.cloneObject(pcp.options || {});
        Object.keys(opts).forEach(function (k) { if (opts[k] !== null && opts[k] !== undefined) mergedOpts[k] = opts[k]; });

        // Radio groups: fan out one pending entry per option, stacked vertically
        if (pcp.fieldType === 'radio') {
            var radioRows = Array.isArray(opts.radioOptions) ? opts.radioOptions : [];
            if (!radioRows.length) { P.Utils.toast('Add at least one radio option', 'warning'); return; }
            var radioDefault = opts.radioDefault || '';
            var gap = 4;
            radioRows.forEach(function (row, i) {
                var handleKey = fieldId + '\x00' + row.value;
                P.state.pendingFormAdds.push({
                    fieldType: 'radio', fieldName: fieldId,
                    _radioHandleKey: handleKey,
                    pageIndex: pcp.pageIndex,
                    x: pcp.x, y: pcp.y - i * (pcp.height + gap),
                    width: pcp.width, height: pcp.height,
                    options: {
                        exportValue: row.value, exportLabel: row.label || '',
                        required: mergedOpts.required, readonly: mergedOpts.readonly,
                        radioDefault: radioDefault, javascript: mergedOpts.javascript || '',
                        fontName: mergedOpts.fontName, fontSize: mergedOpts.fontSize,
                        textColor: mergedOpts.textColor, backgroundColor: mergedOpts.backgroundColor,
                        borderColor: mergedOpts.borderColor, borderWidth: mergedOpts.borderWidth,
                        borderStyle: mergedOpts.borderStyle
                    }
                });
                P.EditMode.pushFieldUndo(handleKey, { type: 'add' });
            });
            P.EditMode.setLastAddedFieldTemplate({ fieldType: 'radio', options: P.EditMode.cloneObject(mergedOpts) });
            P.EditMode.setPendingCreatePayload(null);
            P.FieldDialog.hide();
            P.EditMode.renderFieldHandlesForAllPages();
            if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) P.Tabs.switchTab(P.state.currentTab);
            P.EditMode.updateSaveButton();
            P.Utils.toast(radioRows.length + ' radio option(s) queued as group "' + fieldId + '". Click Save to persist.', 'info');
            return;
        }

        // Combo/list: validate choices and convert to backend format
        if (pcp.fieldType === 'combo' || pcp.fieldType === 'list') {
            var choiceRows = Array.isArray(mergedOpts.choices) ? mergedOpts.choices : [];
            if (!choiceRows.length) { P.Utils.toast('Add at least one item', 'warning'); return; }
            mergedOpts.choices = choiceRowsToBackendFormat(choiceRows);
        }

        var qf = { fieldType: pcp.fieldType, fieldName: fieldId, pageIndex: pcp.pageIndex, x: pcp.x, y: pcp.y, width: pcp.width, height: pcp.height, options: mergedOpts };
        P.state.pendingFormAdds.push(qf);
        P.EditMode.pushFieldUndo(fieldId, { type: 'add' });
        P.EditMode.setLastAddedFieldTemplate({ fieldType: qf.fieldType, options: P.EditMode.cloneObject(qf.options || {}) });
        P.EditMode.setPendingCreatePayload(null);
        P.FieldDialog.hide();
        P.EditMode.renderFieldHandlesForAllPages();
        if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) P.Tabs.switchTab(P.state.currentTab);
        P.EditMode.updateSaveButton();
        P.Utils.toast('Field queued. Click Save to persist changes.', 'info');
    }

    // ── Drag / resize for field handles ──────────────────────────────────────
    function bindDragResize($handle, $resize, fieldNode, viewport) {
        var dragging = false, resizing = false, start = {}, moveH = null, upH = null;
        var fullName = fieldNode && fieldNode.properties ? fieldNode.properties.FullName : null;
        var clamp = P.EditMode.clamp;
        function detach() { if (moveH) $(document).off('mousemove', moveH); if (upH) $(document).off('mouseup', upH); moveH = null; upH = null; }
        function openOpts() {
            P.state.selectedFieldNames = [fullName]; P.state.selectedImageNodeIds = [];
            P.EditMode.syncHandleSelectionClasses(); P.EditMode.syncTreeSelectionForField(fullName);
            P.EditMode.refreshSelectionButtons(); P.EditOptions.openOptionsPopup();
        }
        function clampSize(ev) {
            var wEl = $handle.parent()[0], ln = parseFloat($handle.css('left')) || 0, tn = parseFloat($handle.css('top')) || 0;
            return { w: clamp(start.width + (ev.clientX - start.x), 20, Math.max(20, (wEl ? wEl.clientWidth : 0) - ln)),
                     h: clamp(start.height + (ev.clientY - start.y), 14, Math.max(14, (wEl ? wEl.clientHeight : 0) - tn)) };
        }
        function commitRect() {
            var newW = $handle.width(), newH = $handle.height();
            if (Math.abs(newW - start.width) < 1 && Math.abs(newH - start.height) < 1) { detach(); return; }
            var l = parseFloat($handle.css('left')), t = parseFloat($handle.css('top'));
            var s = viewport.scale, fn = fieldNode && fieldNode.properties ? fieldNode.properties.FullName : null;
            var sn = P.EditDesigner ? P.EditDesigner.snapV : function (v) { return v; };
            if (fn) P.EditMode.queueRectChange(fn, sn(l / s), sn((viewport.height - t - $handle.height()) / s), sn(newW / s), sn(newH / s));
            P.EditMode.renderFieldHandlesForAllPages(); P.EditMode.updateSaveButton(); detach();
        }
        $handle.on('dblclick', function (e) {
            if ($(e.target).closest('.form-field-resize, .field-handle-btn').length) return;
            e.preventDefault(); e.stopPropagation(); if (!fullName) return; openOpts();
        });
        $handle.on('mousedown', function (e) {
            if ($(e.target).closest('.form-field-resize, .field-handle-btn').length) return;
            if (e.detail >= 2 && fullName) { e.preventDefault(); e.stopPropagation(); openOpts(); return; }
            e.preventDefault(); e.stopPropagation(); detach();
            var name = fieldNode.properties && fieldNode.properties.FullName;
            if (name) {
                if (e.ctrlKey || e.metaKey || e.shiftKey) { P.EditMode.toggleFieldSelection(name); }
                else { var sel = P.state.selectedFieldNames || []; if (!(sel.length > 1 && sel.indexOf(name) >= 0)) { P.state.selectedFieldNames = [name]; P.state.selectedImageNodeIds = []; P.EditMode.syncTreeSelectionForField(name); } }
                P.EditMode.refreshSelectionButtons(); P.EditMode.syncHandleSelectionClasses();
            }
            var moveNames = (P.state.selectedFieldNames || []).slice(); if (name && moveNames.indexOf(name) < 0) moveNames = [name];
            var targets = [];
            moveNames.forEach(function (fn) {
                var $th = P.EditMode.findHandleElementByFieldName(fn); if (!$th || !$th.length || $th.hasClass('removed')) return;
                var ri = P.EditMode.getCurrentFieldRectByName(fn); if (!ri) return;
                var vp2 = P.state.pageViewports[ri.pageIndex]; if (!vp2) return;
                targets.push({ fieldName: fn, $el: $th, left: parseFloat($th.css('left')), top: parseFloat($th.css('top')), rect: ri, viewport: vp2 });
            });
            if (!targets.length && name) { var fr = P.EditMode.getCurrentFieldRectByName(name); if (fr) targets.push({ fieldName: name, $el: $handle, left: parseFloat($handle.css('left')), top: parseFloat($handle.css('top')), rect: fr, viewport: viewport }); }
            dragging = true; start = { x: e.clientX, y: e.clientY, left: parseFloat($handle.css('left')), top: parseFloat($handle.css('top')), dragTargets: targets };
            moveH = function (ev) {
                var dx = ev.clientX - start.x, dy = ev.clientY - start.y;
                if (dragging) {
                    if (start.dragTargets && start.dragTargets.length) {
                        start.dragTargets.forEach(function (t) { var tw = t.$el.parent()[0]; t.$el.css({ left: clamp(t.left + dx, 0, Math.max(0, (tw ? tw.clientWidth : 0) - (t.$el.outerWidth() || 0))) + 'px', top: clamp(t.top + dy, 0, Math.max(0, (tw ? tw.clientHeight : 0) - (t.$el.outerHeight() || 0))) + 'px' }); });
                    } else { var wEl2 = $handle.parent()[0]; $handle.css({ left: clamp(start.left + dx, 0, Math.max(0, (wEl2 ? wEl2.clientWidth : 0) - ($handle.outerWidth() || 0))) + 'px', top: clamp(start.top + dy, 0, Math.max(0, (wEl2 ? wEl2.clientHeight : 0) - ($handle.outerHeight() || 0))) + 'px' }); }
                } else if (resizing) { var sz = clampSize(ev); $handle.css({ width: sz.w + 'px', height: sz.h + 'px' }); }
            };
            upH = function (ev) {
                if (!dragging && !resizing) { detach(); return; }
                var wasDrag = dragging; dragging = false; resizing = false;
                if (wasDrag) {
                    var dx2 = ev.clientX - start.x, dy2 = ev.clientY - start.y;
                    if (Math.abs(dx2) > 1 || Math.abs(dy2) > 1) {
                        var sn = P.EditDesigner ? P.EditDesigner.snapV : function (v) { return v; };
                        (start.dragTargets || []).forEach(function (t) { if (!t.viewport || !t.rect) return; var tl = parseFloat(t.$el.css('left')) || 0, tt = parseFloat(t.$el.css('top')) || 0; P.EditMode.queueRectChange(t.fieldName, sn(t.rect.x + (tl - t.left) / t.viewport.scale), sn(t.rect.y - (tt - t.top) / t.viewport.scale), t.rect.width, t.rect.height); });
                        P.EditMode.renderFieldHandlesForAllPages(); P.EditMode.updateSaveButton();
                    }
                    detach();
                } else { commitRect(); }
            };
            $(document).on('mousemove', moveH).on('mouseup', upH);
        });
        $resize.on('mousedown', function (e) {
            e.preventDefault(); e.stopPropagation(); detach(); resizing = true;
            start = { x: e.clientX, y: e.clientY, width: $handle.width(), height: $handle.height() };
            moveH = function (ev) { if (resizing) { var sz = clampSize(ev); $handle.css({ width: sz.w + 'px', height: sz.h + 'px' }); } };
            upH = function () { if (!resizing) { detach(); return; } resizing = false; commitRect(); };
            $(document).on('mousemove', moveH).on('mouseup', upH);
        });
    }

    // ── init ─────────────────────────────────────────────────────────────────
    function init() {
        P.FieldDialog.init();
    }

    return {
        init: init, openCreateFieldDialog: openCreateFieldDialog, bindDragResize: bindDragResize,
        computeVisibleOptionKeys: computeVisibleOptionKeys, getEntryFieldType: getEntryFieldType,
        getSupportedOptionsForFieldType: getSupportedOptionsForFieldType,
        setFieldConfigBlockVisible: setFieldConfigBlockVisible, isFieldConfigBlockVisible: isFieldConfigBlockVisible,
        getJsPresetDefinitions: getJsPresetDefinitions, buildPresetScript: buildPresetScript,
        renderJsPresetParams: renderJsPresetParams, collectJsPresetParams: collectJsPresetParams,
        suggestNextFieldId: suggestNextFieldId,
        collectRadioOptions: collectRadioOptions,
        collectRadioOptionsFrom: collectRadioOptionsFrom,
        renderRadioOptionTable: renderRadioOptionTable, buildRadioOptionRow: buildRadioOptionRow,
        renumberRadioRows: renumberRadioRows, syncRadioDefaultSelect: syncRadioDefaultSelect,
        renderChoiceOptionTable: renderChoiceOptionTable, buildChoiceOptionRow: buildChoiceOptionRow,
        renumberChoiceRows: renumberChoiceRows, syncChoiceDefaultSelect: syncChoiceDefaultSelect,
        collectChoiceOptions: collectChoiceOptions, parseChoicesToRows: parseChoicesToRows,
        choiceRowsToBackendFormat: choiceRowsToBackendFormat,
        applyCreateFromDialog: applyCreateFromDialog
    };
})(jQuery, PDFalyzer);
