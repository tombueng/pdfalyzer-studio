/**
 * PDFalyzer Studio – Field Options dialog logic.
 * Depends on app-edit-mode.js and app-edit-field.js being loaded first.
 */
PDFalyzer.EditOptions = (function ($, P) {
    'use strict';

    var TRI_STATE_GROUPS = {
        required: { inputName: 'optRequired', blockSelector: '#optRequiredBlock' },
        readonly: { inputName: 'optReadonly', blockSelector: '#optReadonlyBlock' },
        multiline: { inputName: 'optMultiline', blockSelector: '#optMultilineBlock' },
        editable: { inputName: 'optEditable', blockSelector: '#optEditableBlock' },
        checked: { inputName: 'optChecked', blockSelector: '#optCheckedBlock' }
    };

    var JS_PRESET_OPTIONS = {
        presetSelect: '#optJsPreset',
        paramsContainer: '#optJsPresetParams',
        applyButton: '#optJsPresetApplyBtn',
        scriptTarget: '#optJavascript'
    };

    // ── Tri-state controls ───────────────────────────────────────────────────

    function setTriStateControlValue(optionName, value, allowKeep) {
        var group = TRI_STATE_GROUPS[optionName];
        if (!group) return;
        var $inputs = $('input[name="' + group.inputName + '"]');
        if (!$inputs.length) return;
        $inputs.closest('.tri-keep').toggle(!!allowKeep);
        var normalized = value === 'true' || value === 'false' || value === 'keep' ? value : 'keep';
        if (!allowKeep && normalized === 'keep') normalized = 'false';
        $inputs.prop('checked', false);
        var $target = $inputs.filter('[value="' + normalized + '"]');
        if (!$target.length) {
            $target = $inputs.filter('[value="' + (allowKeep ? 'keep' : 'false') + '"]');
        }
        $target.prop('checked', true);
    }

    function getTriStateControlValue(optionName) {
        var group = TRI_STATE_GROUPS[optionName];
        if (!group) return 'keep';
        var value = $('input[name="' + group.inputName + '"]:checked').val();
        return value || 'keep';
    }

    function parseTriStateValue(value) {
        if (value === 'true') return true;
        if (value === 'false') return false;
        return null;
    }

    // ── Option value readers ─────────────────────────────────────────────────

    function mapPropertyName(optionName) {
        if (optionName === 'required') return 'Required';
        if (optionName === 'readonly') return 'ReadOnly';
        if (optionName === 'multiline') return 'Multiline';
        if (optionName === 'editable') return 'Editable';
        if (optionName === 'checked') return 'Checked';
        if (optionName === 'defaultValue' || optionName === 'value') return 'Value';
        if (optionName === 'choices') return 'Options';
        if (optionName === 'javascript') return 'JavaScript';
        return optionName;
    }

    function readOptionValue(entry, optionName) {
        if (!entry) return null;
        if (entry.kind === 'pending' && entry.pendingField) {
            var pendingOptions = entry.pendingField.options || {};
            if (pendingOptions[optionName] !== undefined) return pendingOptions[optionName];
            return null;
        }
        if (entry.kind === 'persisted' && entry.name) {
            var overrides = P.EditMode.getPendingFieldOptionOverrides();
            if (overrides[entry.name]) {
                var ovr = overrides[entry.name];
                if (ovr[optionName] !== undefined) return ovr[optionName];
            }
        }
        var propertyName = mapPropertyName(optionName);
        if (entry.node && entry.node.properties && entry.node.properties[propertyName] !== undefined) {
            return entry.node.properties[propertyName];
        }
        return null;
    }

    function readBooleanOption(entry, optionName) {
        var value = readOptionValue(entry, optionName);
        if (value === null || value === undefined) return false;
        if (typeof value === 'boolean') return value;
        return String(value).toLowerCase() === 'true';
    }

    function resolveTriState(entries, optionName) {
        if (!entries.length) return 'keep';
        var first = readBooleanOption(entries[0], optionName);
        for (var i = 1; i < entries.length; i++) {
            if (readBooleanOption(entries[i], optionName) !== first) return 'keep';
        }
        return first ? 'true' : 'false';
    }

    // ── Selected field entries ────────────────────────────────────────────────

    function getSelectedFieldEntries() {
        var names = P.state.selectedFieldNames || [];
        var found = [];
        var remaining = names.slice();

        (P.state.pendingFormAdds || []).forEach(function (pendingField) {
            if (!pendingField || !pendingField.fieldName) return;
            var idx = remaining.indexOf(pendingField.fieldName);
            if (idx < 0) return;
            found.push({ kind: 'pending', name: pendingField.fieldName, pendingField: pendingField });
            remaining.splice(idx, 1);
        });

        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName) {
                if (remaining.indexOf(node.properties.FullName) >= 0) {
                    found.push({ kind: 'persisted', name: node.properties.FullName, node: node });
                }
            }
            if (node.children) node.children.forEach(walk);
        }
        walk(P.state.treeData);
        return found;
    }

    // ── Option overrides ─────────────────────────────────────────────────────

    function applyOptionOverridesToPersistedSelection(fieldNames, options) {
        if (!fieldNames || !fieldNames.length || !options) return;
        var overrides = P.EditMode.getPendingFieldOptionOverrides();
        fieldNames.forEach(function (fieldName) {
            if (!fieldName) return;
            if (!overrides[fieldName]) overrides[fieldName] = {};
            var target = overrides[fieldName];
            ['required', 'readonly', 'multiline', 'editable', 'checked'].forEach(function (key) {
                if (options[key] !== null && options[key] !== undefined) target[key] = options[key];
            });
            if (options.defaultValue !== undefined) target.defaultValue = options.defaultValue;
            if (options.choices !== undefined) target.choices = P.EditMode.cloneObject(options.choices);
            if (options.javascript !== undefined) target.javascript = options.javascript;
        });
    }

    // ── Scope hint ───────────────────────────────────────────────────────────

    function updateFieldOptionsScopeHint(single, selectedEntries, visibleKeys) {
        var fieldTypes = {};
        selectedEntries.forEach(function (entry) {
            fieldTypes[P.EditField.getEntryFieldType(entry)] = true;
        });
        var typeCount = Object.keys(fieldTypes).length;
        if (single) {
            $('#fieldOptionsScopeHint').text('Showing options relevant for this ' + P.EditField.getEntryFieldType(single) + ' field.');
            return;
        }
        if (typeCount > 1) {
            $('#fieldOptionsScopeHint').text('Mixed field types selected. Only shared options are shown.');
            return;
        }
        if (!Object.keys(visibleKeys).length) {
            $('#fieldOptionsScopeHint').text('No common editable options for current selection.');
            return;
        }
        $('#fieldOptionsScopeHint').text('Tri-state values apply to all selected fields.');
    }

    // ── JS preset helpers (options mode) ────────────────────────────────────

    function renderJsPresetParamsOptions() {
        P.EditField.renderJsPresetParams('options', JS_PRESET_OPTIONS);
    }

    function applyJsPresetToOptionsDialog() {
        var presetKey = $(JS_PRESET_OPTIONS.presetSelect).val() || 'custom';
        if (presetKey === 'custom') return;
        var params = P.EditField.collectJsPresetParams('options', JS_PRESET_OPTIONS);
        var script = P.EditField.buildPresetScript(presetKey, params);
        if (!script) { P.Utils.toast('Please fill preset values first', 'warning'); return; }
        $(JS_PRESET_OPTIONS.scriptTarget).val(script);
        P.Utils.toast('Validation preset inserted', 'info');
    }

    function bindJsPresetControls() {
        $(JS_PRESET_OPTIONS.presetSelect).on('change', renderJsPresetParamsOptions);
        $(JS_PRESET_OPTIONS.applyButton).on('click', applyJsPresetToOptionsDialog);
    }

    function initializeJsPresetUi() {
        $(JS_PRESET_OPTIONS.presetSelect).val('custom');
        renderJsPresetParamsOptions();
    }

    // ── Open options popup ───────────────────────────────────────────────────

    function openOptionsPopup() {
        if (!P.state.sessionId) return;
        var selected = P.state.selectedFieldNames || [];
        if (!selected.length) {
            P.Utils.toast('Select one or more fields first (Ctrl/Shift-click handles)', 'warning');
            return;
        }

        var selectedEntries = getSelectedFieldEntries();
        var single = selectedEntries.length === 1 ? selectedEntries[0] : null;
        var showKeepOption = !single;
        var visibleKeys = P.EditField.computeVisibleOptionKeys(selectedEntries);

        $('#fieldOptionsSelectionInfo').text(
            selected.length === 1
                ? ('Editing: ' + selected[0])
                : ('Editing ' + selected.length + ' fields (tri-state controls)')
        );

        Object.keys(TRI_STATE_GROUPS).forEach(function (optionName) {
            var group = TRI_STATE_GROUPS[optionName];
            P.EditField.setFieldConfigBlockVisible(group.blockSelector, !!visibleKeys[optionName]);
            if (visibleKeys[optionName]) {
                setTriStateControlValue(optionName, resolveTriState(selectedEntries, optionName), showKeepOption);
            }
        });

        var canEditSingleValue = !!single && !!visibleKeys.defaultValue;
        var canEditChoices = !!single && !!visibleKeys.choices;
        var canEditJavascript = !!single && !!visibleKeys.javascript;

        P.EditField.setFieldConfigBlockVisible('#optDefaultValueBlock', canEditSingleValue);
        P.EditField.setFieldConfigBlockVisible('#optChoicesBlock', canEditChoices);
        P.EditField.setFieldConfigBlockVisible('#optJavascriptBlock', canEditJavascript);

        $('#optDefaultValue').val(canEditSingleValue ? (readOptionValue(single, 'defaultValue') || readOptionValue(single, 'value') || '') : '');
        var choicesVal = canEditChoices ? readOptionValue(single, 'choices') : null;
        $('#optChoices').val(Array.isArray(choicesVal) ? choicesVal.join(',') : (choicesVal || ''));
        var javascriptValue = canEditJavascript ? (readOptionValue(single, 'javascript') || '') : '';
        $('#optJavascript').val(javascriptValue);

        initializeJsPresetUi();

        $('#optDefaultValue').prop('disabled', !canEditSingleValue);
        $('#optChoices').prop('disabled', !canEditChoices);
        $('#optJavascript').prop('disabled', !canEditJavascript);

        P.EditMode.setOptionsJavascriptSectionExpanded(canEditJavascript, javascriptValue);

        updateFieldOptionsScopeHint(single, selectedEntries, visibleKeys);

        P.EditMode.showModal('options');
    }

    // ── Apply options from modal ──────────────────────────────────────────────

    function applyOptionsFromModal() {
        if (!P.state.sessionId) return;
        var fieldNames = (P.state.selectedFieldNames || []).slice();
        if (!fieldNames.length) return;

        var options = {};
        if (P.EditField.isFieldConfigBlockVisible('#optRequiredBlock')) {
            options.required = parseTriStateValue(getTriStateControlValue('required'));
        }
        if (P.EditField.isFieldConfigBlockVisible('#optReadonlyBlock')) {
            options.readonly = parseTriStateValue(getTriStateControlValue('readonly'));
        }
        if (P.EditField.isFieldConfigBlockVisible('#optMultilineBlock')) {
            options.multiline = parseTriStateValue(getTriStateControlValue('multiline'));
        }
        if (P.EditField.isFieldConfigBlockVisible('#optEditableBlock')) {
            options.editable = parseTriStateValue(getTriStateControlValue('editable'));
        }
        if (P.EditField.isFieldConfigBlockVisible('#optCheckedBlock')) {
            options.checked = parseTriStateValue(getTriStateControlValue('checked'));
        }
        if (P.EditField.isFieldConfigBlockVisible('#optDefaultValueBlock') && !$('#optDefaultValue').prop('disabled')) {
            var defaultValue = $('#optDefaultValue').val();
            if (defaultValue) options.defaultValue = defaultValue;
        }
        if (P.EditField.isFieldConfigBlockVisible('#optChoicesBlock') && !$('#optChoices').prop('disabled')) {
            var choicesRaw = $('#optChoices').val();
            if (choicesRaw) {
                options.choices = choicesRaw.split(',')
                    .map(function (v) { return v.trim(); })
                    .filter(function (v) { return v.length > 0; });
            }
        }
        if (P.EditField.isFieldConfigBlockVisible('#optJavascriptBlock') && !$('#optJavascript').prop('disabled')) {
            options.javascript = $('#optJavascript').val() || '';
        }

        if (!Object.keys(options).length) {
            P.Utils.toast('No applicable options for selected fields', 'info');
            return;
        }

        var pendingNames = [];
        var persistedNames = [];
        fieldNames.forEach(function (name) {
            if (P.EditMode.findPendingFormAdd(name)) pendingNames.push(name);
            else persistedNames.push(name);
        });

        pendingNames.forEach(function (name) {
            var pendingField = P.EditMode.findPendingFormAdd(name);
            if (!pendingField) return;
            if (!pendingField.options) pendingField.options = {};
            ['required', 'readonly', 'multiline', 'editable', 'checked'].forEach(function (key) {
                if (options[key] !== null && options[key] !== undefined) pendingField.options[key] = options[key];
            });
            if (options.defaultValue !== undefined) pendingField.options.defaultValue = options.defaultValue;
            if (options.choices !== undefined) pendingField.options.choices = P.EditMode.cloneObject(options.choices);
            if (options.javascript !== undefined) pendingField.options.javascript = options.javascript;
        });

        var finishLocalOptionsApply = function (toastMessage) {
            if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) {
                P.Tabs.switchTab(P.state.currentTab);
            }
            P.EditMode.renderFieldHandlesForAllPages();
            P.EditMode.updateSaveButton();
            P.EditMode.hideModal('options');
            P.Utils.toast(toastMessage, 'success');
        };

        if (!persistedNames.length) {
            finishLocalOptionsApply('Pending field options updated');
            return;
        }

        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        P.state.pendingFieldOptions.push({ fieldNames: persistedNames.slice(), options: P.EditMode.cloneObject(options) });
        applyOptionOverridesToPersistedSelection(persistedNames, options);
        finishLocalOptionsApply(pendingNames.length
            ? 'Field options queued (saved + pending fields)'
            : 'Field options queued. Click Save to persist.');
    }

    // ── init ─────────────────────────────────────────────────────────────────

    function init() {
        bindJsPresetControls();
        $('#applyFieldOptionsBtn').on('click', applyOptionsFromModal);
        $('#formOptionsBtn').on('click', openOptionsPopup);
        $('#optJavascriptCollapse').on('shown.bs.collapse hidden.bs.collapse',
            P.EditMode.syncOptionsJavascriptToggleState);
    }

    return {
        init: init,
        openOptionsPopup: openOptionsPopup
    };
})(jQuery, PDFalyzer);
