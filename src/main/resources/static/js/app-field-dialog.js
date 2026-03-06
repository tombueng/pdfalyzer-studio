/**
 * PDFalyzer Studio – Generic schema-driven field dialog engine.
 * Builds a tabbed Bootstrap modal from the backend field property schema.
 * Depends on app-edit-field.js being loaded first (P.EditField must exist).
 */
PDFalyzer.FieldDialog = (function ($, P) {
    'use strict';

    var _schemaCache = null;
    var _currentDescriptors = [];
    var _currentMode = null;      // 'create' | 'edit'
    var _currentFieldType = null;
    var _currentCallbacks = null; // { onApply, onHide, isMultiEdit, suggestedId, contextInfo }
    var _bsModal = null;

    var GROUP_LABELS = { general: 'General', appearance: 'Appearance', options: 'Options', script: 'Script' };
    var GROUP_ICONS  = { general: 'fa-info-circle', appearance: 'fa-palette', options: 'fa-sliders-h', script: 'fa-code' };
    var GROUP_ORDER  = ['general', 'appearance', 'options', 'script'];

    // ── Schema loading ────────────────────────────────────────────────────────

    function loadSchema(callback) {
        if (_schemaCache) { callback(_schemaCache); return; }
        $.getJSON('/api/fields/schema', function (data) {
            _schemaCache = data;
            callback(data);
        }).fail(function () {
            P.Utils.toast('Failed to load field schema', 'danger');
            callback([]);
        });
    }

    // ── Control builders ──────────────────────────────────────────────────────

    function buildBooleanControl(desc, value, isMultiEdit) {
        var n = 'fd_' + desc.key;
        var cur = (value === true || value === 'true') ? 'true'
                : (value === false || value === 'false') ? 'false'
                : (isMultiEdit ? 'keep' : 'false');
        var $wrap = $('<div class="mb-2 field-config-block" data-fd-key="' + esc(desc.key) + '">');
        $wrap.append('<label class="form-label form-label-sm">' + esc(desc.label) + '</label>');
        var $radios = $('<div class="field-option-radios">');
        if (isMultiEdit) {
            $radios.append(radioHtml(n, 'keep', 'Keep current', 'tri-keep', cur === 'keep'));
        }
        $radios.append(radioHtml(n, 'true',  'True',  '', cur === 'true'));
        $radios.append(radioHtml(n, 'false', 'False', '', cur === 'false'));
        $wrap.append($radios);
        return $wrap;
    }

    function radioHtml(name, val, label, wrapClass, checked) {
        var id = name + '_' + val;
        return '<div class="form-check form-check-sm' + (wrapClass ? ' ' + wrapClass : '') + '">'
            + '<input class="form-check-input" type="radio" name="' + name + '" id="' + id + '" value="' + val + '"' + (checked ? ' checked' : '') + '>'
            + '<label class="form-check-label" for="' + id + '">' + label + '</label></div>';
    }

    function buildTextControl(desc, value) {
        return wrapControl(desc.key, desc.label,
            '<input class="form-control form-control-sm" id="fd_' + esc(desc.key) + '" type="text"'
            + (desc.readOnly ? ' readonly' : '')
            + ' value="' + esc(String(value || '')) + '">');
    }

    function buildNumberControl(desc, value) {
        return wrapControl(desc.key, desc.label,
            '<input class="form-control form-control-sm" id="fd_' + esc(desc.key) + '" type="number" step="any"'
            + (desc.readOnly ? ' readonly' : '')
            + ' value="' + esc(value !== null && value !== undefined ? String(value) : '') + '">');
    }

    function buildColorControl(desc, value) {
        var isEmpty = (value === null || value === undefined || value === '');
        var hex = (!isEmpty && /^#[0-9a-fA-F]{6}$/.test(String(value))) ? String(value) : '#808080';
        var textVal = isEmpty ? '' : hex;
        var id = 'fd_' + desc.key;
        var $wrap = $(wrapControl(desc.key, desc.label,
            '<div class="d-flex align-items-center gap-2">'
            + '<input class="form-control form-control-color" id="' + id + '_picker" type="color" value="' + hex + '" style="width:40px;height:30px;padding:2px;">'
            + '<input class="form-control form-control-sm" id="' + id + '" type="text" value="' + esc(textVal) + '" placeholder="(not set)" style="width:110px;">'
            + '</div>'));
        $wrap.find('#' + id + '_picker').on('input', function () { $wrap.find('#' + id).val($(this).val()); });
        $wrap.find('#' + id).on('input', function () {
            var v = $(this).val().trim();
            if (/^#[0-9a-fA-F]{6}$/.test(v)) $wrap.find('#' + id + '_picker').val(v);
        });
        return $wrap;
    }

    function buildSelectControl(desc, value) {
        var id = 'fd_' + desc.key;
        var isEmpty = value === null || value === undefined;
        // Prepend a "(not set)" sentinel option in edit mode when the field has no value
        var notSetOpt = (isEmpty && _currentMode !== 'create')
            ? '<option value="">(not set)</option>' : '';
        var opts = notSetOpt + (desc.validValues || []).map(function (v) {
            return '<option value="' + esc(v) + '">' + esc(v) + '</option>';
        }).join('');
        var $wrap = $(wrapControl(desc.key, desc.label,
            '<select class="form-select form-select-sm" id="' + id + '"' + (desc.readOnly ? ' disabled' : '') + '>'
            + opts + '</select>'));
        var sel = !isEmpty ? String(value) : (notSetOpt ? '' : String(desc.defaultValue || ''));
        $wrap.find('#' + id).val(sel);
        return $wrap;
    }

    function buildChoiceTableControl(desc, value) {
        var tbodyId = 'fdChoiceRows';
        var defaultId = 'fdChoiceDefault';
        var syncFn = function () { P.EditField.syncChoiceDefaultSelect('#' + defaultId, tbodyId); };
        var $wrap = $('<div class="mb-2" data-fd-key="' + esc(desc.key) + '">');
        $wrap.append('<label class="form-label form-label-sm"><i class="fas fa-list me-1"></i>' + esc(desc.label) + '</label>');
        $wrap.append(choiceTableHtml(tbodyId));
        $wrap.append('<button type="button" class="btn btn-outline-secondary btn-sm w-100 mt-1" id="fdAddChoiceRow"><i class="fas fa-plus me-1"></i>Add item</button>');
        $wrap.append('<div class="mt-2"><label class="form-label form-label-sm">Default selection</label>'
            + '<select class="form-select form-select-sm" id="' + defaultId + '"><option value="">(none)</option></select></div>');
        var rows = P.EditField.parseChoicesToRows(value);
        P.EditField.renderChoiceOptionTable(rows, tbodyId, syncFn);
        syncFn();
        $wrap.find('#fdAddChoiceRow').on('click', function () {
            var idx = $('#' + tbodyId + ' tr').length;
            $('#' + tbodyId).append(P.EditField.buildChoiceOptionRow('', '', idx, tbodyId, syncFn));
            P.EditField.renumberChoiceRows(tbodyId);
            syncFn();
        });
        return $wrap;
    }

    function buildRadioTableControl(desc, value) {
        var tbodyId = 'fdRadioRows';
        var defaultId = 'fdRadioDefault';
        var syncFn = function () { P.EditField.syncRadioDefaultSelect('#' + defaultId, tbodyId); };
        var $wrap = $('<div class="mb-2" data-fd-key="' + esc(desc.key) + '">');
        $wrap.append('<label class="form-label form-label-sm"><i class="fas fa-dot-circle me-1"></i>' + esc(desc.label)
            + ' <span class="text-muted" style="font-size:11px;">— one button per row, stacked vertically</span></label>');
        $wrap.append(choiceTableHtml(tbodyId));
        $wrap.append('<button type="button" class="btn btn-outline-secondary btn-sm w-100 mt-1" id="fdAddRadioRow"><i class="fas fa-plus me-1"></i>Add option</button>');
        $wrap.append('<div class="mt-2"><label class="form-label form-label-sm">Default selection</label>'
            + '<select class="form-select form-select-sm" id="' + defaultId + '"><option value="">(none)</option></select></div>');
        var rows = Array.isArray(value) ? value : [];
        P.EditField.renderRadioOptionTable(rows, tbodyId, syncFn);
        syncFn();
        $wrap.find('#fdAddRadioRow').on('click', function () {
            var idx = $('#' + tbodyId + ' tr').length;
            $('#' + tbodyId).append(P.EditField.buildRadioOptionRow('', '', idx, tbodyId, syncFn));
            P.EditField.renumberRadioRows(tbodyId);
            syncFn();
        });
        return $wrap;
    }

    function buildScriptControl(desc, value) {
        var jsDef = { presetSelect: '#fdJsPreset', paramsContainer: '#fdJsPresetParams', applyButton: '#fdJsPresetApply', scriptTarget: '#fdJavascript' };
        var $wrap = $('<div class="mb-2" data-fd-key="' + esc(desc.key) + '">');
        $wrap.append('<label class="form-label form-label-sm"><i class="fab fa-js me-1"></i>' + esc(desc.label) + '</label>');
        $wrap.append(
            '<div class="row g-2 mb-2">'
            + '<div class="col-7"><select class="form-select form-select-sm" id="fdJsPreset">'
            + '<option value="custom">Custom script</option><option value="length">Length (min/max)</option>'
            + '<option value="number">Number (min/max)</option><option value="date">Date (format)</option>'
            + '</select></div>'
            + '<div class="col-5 d-grid"><button type="button" class="btn btn-outline-accent btn-sm" id="fdJsPresetApply"><i class="fas fa-magic me-1"></i>Insert preset</button></div>'
            + '</div>'
            + '<div class="field-js-preset-params mb-2" id="fdJsPresetParams"></div>'
            + '<textarea class="form-control form-control-sm" id="fdJavascript" rows="5" placeholder="if (event.value) { ... }"></textarea>');
        $wrap.find('#fdJavascript').val(value || '');
        $wrap.find('#fdJsPreset').val('custom').on('change', function () { P.EditField.renderJsPresetParams('fd', jsDef); });
        $wrap.find('#fdJsPresetApply').on('click', function () {
            var key = $('#fdJsPreset').val() || 'custom'; if (key === 'custom') return;
            var script = P.EditField.buildPresetScript(key, P.EditField.collectJsPresetParams('fd', jsDef));
            if (!script) { P.Utils.toast('Fill preset values first', 'warning'); return; }
            $('#fdJavascript').val(script); P.Utils.toast('Preset inserted', 'info');
        });
        P.EditField.renderJsPresetParams('fd', jsDef);
        return $wrap;
    }

    function buildControl(desc, value, isMultiEdit) {
        switch (desc.controlType) {
            case 'boolean':    return buildBooleanControl(desc, value, isMultiEdit);
            case 'text':       return buildTextControl(desc, value);
            case 'number':     return buildNumberControl(desc, value);
            case 'color':      return buildColorControl(desc, value);
            case 'select':     return buildSelectControl(desc, value);
            case 'choiceTable':return buildChoiceTableControl(desc, value);
            case 'radioTable': return buildRadioTableControl(desc, value);
            case 'script':     return buildScriptControl(desc, value);
            default:           return $('<div>');
        }
    }

    // ── Tab builder ───────────────────────────────────────────────────────────

    function buildTabs(descriptors, values, isMultiEdit) {
        var groups = {};
        GROUP_ORDER.forEach(function (g) { groups[g] = []; });
        descriptors.forEach(function (d) { (groups[d.group || 'general'] = groups[d.group || 'general'] || []).push(d); });

        var $nav = $('<ul class="nav nav-tabs nav-tabs-sm mt-2" id="fdTabNav" role="tablist">');
        var $content = $('<div class="tab-content" id="fdTabContent">');
        var first = true;

        GROUP_ORDER.forEach(function (gKey) {
            var descs = groups[gKey];
            if (!descs || !descs.length) return;
            var tabId = 'fdTab_' + gKey, paneId = 'fdPane_' + gKey;
            $nav.append('<li class="nav-item" role="presentation">'
                + '<button class="nav-link' + (first ? ' active' : '') + '" id="' + tabId
                + '" data-bs-toggle="tab" data-bs-target="#' + paneId + '" type="button" role="tab">'
                + '<i class="fas ' + (GROUP_ICONS[gKey] || 'fa-circle') + ' me-1"></i>'
                + (GROUP_LABELS[gKey] || gKey) + '</button></li>');

            var $pane = $('<div class="tab-pane fade' + (first ? ' show active' : '') + '" id="' + paneId + '" role="tabpanel">');
            var $grid = $('<div class="row g-2 pt-2">');
            descs.forEach(function (desc) {
                // In edit mode: if the field has no value for this attribute, show empty rather than
                // the schema default — so the user can see what the PDF actually has set.
                var hasValue = values && values[desc.key] !== undefined && values[desc.key] !== null;
                var value = hasValue ? values[desc.key]
                    : (_currentMode === 'create' ? desc.defaultValue : null);
                var $ctrl = buildControl(desc, value, isMultiEdit);
                var colClass = colClassFor(desc);
                $grid.append($('<div class="' + colClass + '">').append($ctrl));
            });
            $pane.append($grid);
            $content.append($pane);
            first = false;
        });

        return { $nav: $nav, $content: $content };
    }

    function colClassFor(desc) {
        if (desc.controlType === 'choiceTable' || desc.controlType === 'radioTable'
                || desc.controlType === 'script' || desc.key === 'defaultValue') return 'col-12';
        return 'col-6';
    }

    // ── Value collection ──────────────────────────────────────────────────────

    function collectControlValue(desc) {
        switch (desc.controlType) {
            case 'boolean': {
                var v = $('input[name="fd_' + desc.key + '"]:checked').val();
                if (v === 'true') return true;
                if (v === 'false') return false;
                return null; // 'keep' → null
            }
            case 'text':
            case 'number':
            case 'select':
            case 'color':
                return $('#fd_' + desc.key).val();
            case 'choiceTable':
                return P.EditField.collectChoiceOptions('fdChoiceRows');
            case 'radioTable':
                return P.EditField.collectRadioOptionsFrom('fdRadioRows');
            case 'script':
                return $('#fdJavascript').val() || '';
            default:
                return null;
        }
    }

    function collect() {
        var result = {};
        _currentDescriptors.forEach(function (desc) {
            if (desc.readOnly) return;
            var val = collectControlValue(desc);
            if (val !== null && val !== undefined) result[desc.key] = val;
        });
        // Choice default selection
        var $cd = $('#fdChoiceDefault');
        if ($cd.length) result.defaultValue = $cd.val() || '';
        // Radio default selection
        var $rd = $('#fdRadioDefault');
        if ($rd.length) result.radioDefault = $rd.val() || '';
        return result;
    }

    // ── Open ──────────────────────────────────────────────────────────────────

    function open(mode, fieldType, currentValues, callbacks) {
        _currentMode = mode;
        _currentFieldType = fieldType;
        _currentCallbacks = callbacks || {};

        var title = mode === 'create' ? 'Add Field' : 'Field Options';
        var icon  = mode === 'create' ? 'fa-square-plus' : 'fa-sliders-h';
        $('#fieldEditModalTitle').html('<i class="fas ' + icon + ' me-2"></i>' + title);
        $('#fieldEditModalApplyBtn').text(mode === 'create' ? 'Queue Field' : 'Apply');

        var ctx = callbacks && callbacks.contextInfo;
        $('#fdContextInfo').text(ctx || '').toggleClass('d-none', !ctx);

        var isCreate = mode === 'create';
        $('#fdIdentityRow').toggle(isCreate);
        if (isCreate) {
            $('#fdFieldType').val(fieldType);
            $('#fdFieldPage').val(callbacks && callbacks.pageDisplay ? callbacks.pageDisplay : '');
            $('#fdFieldId').val(callbacks && callbacks.suggestedId ? callbacks.suggestedId : '');
        }

        loadSchema(function (schema) {
            var isMultiEdit = !!(callbacks && callbacks.isMultiEdit);
            var descriptors = schema.filter(function (d) {
                if (d.readOnly) return false; // shown in identity row, not tabs
                if (d.supportedTypes && d.supportedTypes.length) {
                    return d.supportedTypes.indexOf(fieldType) >= 0;
                }
                return true;
            });
            _currentDescriptors = descriptors;

            var tabs = buildTabs(descriptors, currentValues, isMultiEdit);
            $('#fdTabNav').remove();
            $('#fdTabContent').remove();
            $('#fdTabsContainer').append(tabs.$nav).append(tabs.$content);

            if (!_bsModal) _bsModal = new bootstrap.Modal(document.getElementById('fieldEditModal'));
            _bsModal.show();
        });
    }

    function hide() {
        if (_bsModal) _bsModal.hide();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    function wrapControl(key, label, innerHtml) {
        return '<div class="mb-2" data-fd-key="' + esc(key) + '">'
            + '<label class="form-label form-label-sm" for="fd_' + esc(key) + '">' + esc(label) + '</label>'
            + innerHtml + '</div>';
    }

    function choiceTableHtml(tbodyId) {
        return '<table class="table table-sm table-borderless radio-option-table mb-1">'
            + '<thead><tr>'
            + '<th style="width:32px;font-size:11px;">#</th>'
            + '<th style="font-size:11px;">Value <span class="text-muted" style="font-size:10px;">(export key)</span></th>'
            + '<th style="font-size:11px;">Label <span class="text-muted" style="font-size:10px;">(display, optional)</span></th>'
            + '<th style="width:36px;"></th>'
            + '</tr></thead>'
            + '<tbody id="' + tbodyId + '"></tbody></table>';
    }

    function esc(s) {
        return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // ── init ─────────────────────────────────────────────────────────────────

    function init() {
        $('#fieldEditModalApplyBtn').on('click', function () {
            if (_currentCallbacks && _currentCallbacks.onApply) {
                _currentCallbacks.onApply(collect());
            }
        });
        $('#fieldEditModal').on('hidden.bs.modal', function () {
            if (_currentCallbacks && _currentCallbacks.onHide) _currentCallbacks.onHide();
            _currentCallbacks = null;
        });
    }

    return { init: init, open: open, collect: collect, hide: hide };

})(jQuery, PDFalyzer);
