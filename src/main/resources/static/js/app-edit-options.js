/**
 * PDFalyzer Studio – Field Options dialog logic.
 * Depends on app-edit-mode.js and app-edit-field.js being loaded first.
 */
PDFalyzer.EditOptions = (function ($, P) {
    'use strict';

    var TRI_STATE_GROUPS = {
        required:    { inputName: 'optRequired',    blockSelector: '#optRequiredBlock' },
        readonly:    { inputName: 'optReadonly',    blockSelector: '#optReadonlyBlock' },
        multiline:   { inputName: 'optMultiline',  blockSelector: '#optMultilineBlock' },
        editable:    { inputName: 'optEditable',   blockSelector: '#optEditableBlock' },
        multiSelect: { inputName: 'optMultiSelect',blockSelector: '#optMultiSelectBlock' },
        checked:     { inputName: 'optChecked',    blockSelector: '#optCheckedBlock' }
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
        if (!$target.length) $target = $inputs.filter('[value="' + (allowKeep ? 'keep' : 'false') + '"]');
        $target.prop('checked', true);
    }

    function getTriStateControlValue(optionName) {
        var group = TRI_STATE_GROUPS[optionName];
        if (!group) return 'keep';
        return $('input[name="' + group.inputName + '"]:checked').val() || 'keep';
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
        if (optionName === 'multiSelect') return 'MultiSelect';
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
            var lookupKey = pendingField._radioHandleKey || pendingField.fieldName;
            var idx = remaining.indexOf(lookupKey);
            if (idx < 0) return;
            found.push({ kind: 'pending', name: lookupKey, pendingField: pendingField });
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
            ['required', 'readonly', 'multiline', 'editable', 'multiSelect', 'checked'].forEach(function (key) {
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
        selectedEntries.forEach(function (entry) { fieldTypes[P.EditField.getEntryFieldType(entry)] = true; });
        var typeCount = Object.keys(fieldTypes).length;
        if (single) {
            $('#fieldOptionsScopeHint').text('Showing options relevant for this ' + P.EditField.getEntryFieldType(single) + ' field.');
            return;
        }
        if (typeCount > 1) { $('#fieldOptionsScopeHint').text('Mixed field types selected. Only shared options are shown.'); return; }
        if (!Object.keys(visibleKeys).length) { $('#fieldOptionsScopeHint').text('No common editable options for current selection.'); return; }
        $('#fieldOptionsScopeHint').text('Tri-state values apply to all selected fields.');
    }

    // ── JS preset helpers (options mode) ────────────────────────────────────

    function renderJsPresetParamsOptions() { P.EditField.renderJsPresetParams('options', JS_PRESET_OPTIONS); }

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

    // ── Choice options in edit dialog ────────────────────────────────────────

    function loadOptChoiceOptions(entry) {
        var raw = readOptionValue(entry, 'choices');
        return P.EditField.parseChoicesToRows(raw);
    }

    function syncOptChoiceDefault() {
        P.EditField.syncChoiceDefaultSelect('#optChoiceDefault', 'optChoiceOptionRows');
    }

    function syncOptRadioDefault() {
        P.EditField.syncRadioDefaultSelect('#optRadioDefault', 'optRadioOptionRows');
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
        var singleFt = single ? P.EditField.getEntryFieldType(single) : null;
        var isChoiceField = singleFt === 'combo' || singleFt === 'list';
        var isRadioField = singleFt === 'radio';

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

        // Default value: show for text/signature, hide for combo/list/radio (they use their own selects)
        var canEditSingleValue = !!single && !!visibleKeys.defaultValue && !isChoiceField && !isRadioField;
        var canEditChoices = !!single && isChoiceField;
        var canEditRadioOptions = !!single && isRadioField;
        var canEditJavascript = !!single && !!visibleKeys.javascript;

        P.EditField.setFieldConfigBlockVisible('#optDefaultValueBlock', canEditSingleValue);
        P.EditField.setFieldConfigBlockVisible('#optChoiceOptionsBlock', canEditChoices);
        P.EditField.setFieldConfigBlockVisible('#optChoiceDefaultBlock', canEditChoices);
        P.EditField.setFieldConfigBlockVisible('#optRadioOptionsBlock', canEditRadioOptions);
        P.EditField.setFieldConfigBlockVisible('#optRadioDefaultBlock', canEditRadioOptions);
        P.EditField.setFieldConfigBlockVisible('#optJavascriptBlock', canEditJavascript);

        if (canEditSingleValue) {
            $('#optDefaultValue').val(readOptionValue(single, 'defaultValue') || readOptionValue(single, 'value') || '');
            $('#optDefaultValue').prop('disabled', false);
        }

        if (canEditChoices) {
            var choiceRows = loadOptChoiceOptions(single);
            P.EditField.renderChoiceOptionTable(choiceRows, 'optChoiceOptionRows', syncOptChoiceDefault);
            syncOptChoiceDefault();
            // Set current default selection
            var curDefault = readOptionValue(single, 'defaultValue') || readOptionValue(single, 'value') || '';
            if (curDefault) $('#optChoiceDefault').val(curDefault);
        }

        if (canEditRadioOptions) {
            var radioRows = loadOptRadioOptions(single);
            P.EditField.renderRadioOptionTable(radioRows, 'optRadioOptionRows', syncOptRadioDefault);
            syncOptRadioDefault();
            // Current default: for pending use radioDefault, for persisted use Value
            var curRadioDefault = single.kind === 'pending'
                ? (single.pendingField.options && single.pendingField.options.radioDefault || '')
                : (readOptionValue(single, 'value') || '');
            if (curRadioDefault) $('#optRadioDefault').val(curRadioDefault);
        }

        var javascriptValue = canEditJavascript ? (readOptionValue(single, 'javascript') || '') : '';
        $('#optJavascript').val(javascriptValue).prop('disabled', !canEditJavascript);
        initializeJsPresetUi();
        P.EditMode.setOptionsJavascriptSectionExpanded(canEditJavascript, javascriptValue);
        updateFieldOptionsScopeHint(single, selectedEntries, visibleKeys);
        P.EditMode.showModal('options');
    }

    function loadOptRadioOptions(single) {
        if (!single) return [];
        if (single.kind === 'pending' && single.pendingField) {
            // Collect all radio group entries with same fieldName
            var groupName = single.pendingField.fieldName;
            var groupEntries = (P.state.pendingFormAdds || []).filter(function (pa) {
                return pa && pa.fieldName === groupName && pa._radioHandleKey;
            });
            return groupEntries.map(function (pa) {
                return { value: pa.options && pa.options.exportValue || '', label: pa.options && pa.options.exportLabel || '' };
            });
        }
        if (single.kind === 'persisted' && single.node) {
            var optStr = single.node.properties && single.node.properties.Options || '';
            // Radio Options property is stored as "Val1, Val2, Val3"
            return optStr.split(/,\s*/).map(function (v) { return { value: v.trim(), label: '' }; }).filter(function (r) { return r.value; });
        }
        return [];
    }

    // ── Apply options from modal ──────────────────────────────────────────────

    function applyOptionsFromModal() {
        if (!P.state.sessionId) return;
        var fieldNames = (P.state.selectedFieldNames || []).slice();
        if (!fieldNames.length) return;

        var selectedEntries = getSelectedFieldEntries();
        var single = selectedEntries.length === 1 ? selectedEntries[0] : null;
        var singleFt = single ? P.EditField.getEntryFieldType(single) : null;
        var isChoiceField = singleFt === 'combo' || singleFt === 'list';
        var isRadioField = singleFt === 'radio';

        var options = {};
        if (P.EditField.isFieldConfigBlockVisible('#optRequiredBlock')) options.required = parseTriStateValue(getTriStateControlValue('required'));
        if (P.EditField.isFieldConfigBlockVisible('#optReadonlyBlock')) options.readonly = parseTriStateValue(getTriStateControlValue('readonly'));
        if (P.EditField.isFieldConfigBlockVisible('#optMultilineBlock')) options.multiline = parseTriStateValue(getTriStateControlValue('multiline'));
        if (P.EditField.isFieldConfigBlockVisible('#optEditableBlock')) options.editable = parseTriStateValue(getTriStateControlValue('editable'));
        if (P.EditField.isFieldConfigBlockVisible('#optMultiSelectBlock')) options.multiSelect = parseTriStateValue(getTriStateControlValue('multiSelect'));
        if (P.EditField.isFieldConfigBlockVisible('#optCheckedBlock')) options.checked = parseTriStateValue(getTriStateControlValue('checked'));

        if (P.EditField.isFieldConfigBlockVisible('#optDefaultValueBlock') && !$('#optDefaultValue').prop('disabled')) {
            var defaultValue = $('#optDefaultValue').val();
            if (defaultValue) options.defaultValue = defaultValue;
        }

        // Combo / list choice items
        if (P.EditField.isFieldConfigBlockVisible('#optChoiceOptionsBlock') && isChoiceField) {
            var choiceRows = P.EditField.collectChoiceOptions('optChoiceOptionRows');
            if (choiceRows.length) {
                options.choices = P.EditField.choiceRowsToBackendFormat(choiceRows);
                options.defaultValue = $('#optChoiceDefault').val() || '';
            }
        }

        if (P.EditField.isFieldConfigBlockVisible('#optJavascriptBlock') && !$('#optJavascript').prop('disabled')) {
            options.javascript = $('#optJavascript').val() || '';
        }

        // ── Radio options: handle separately ────────────────────────────────
        if (P.EditField.isFieldConfigBlockVisible('#optRadioOptionsBlock') && isRadioField && single) {
            var radioRows = P.EditField.collectRadioOptionsFrom('optRadioOptionRows');
            if (!radioRows.length) { P.Utils.toast('At least one radio option is required', 'warning'); return; }
            var radioDefault = $('#optRadioDefault').val() || '';

            if (single.kind === 'pending') {
                applyRadioOptionsToPending(single.pendingField.fieldName, radioRows, radioDefault, options);
                finishLocalOptionsApply('Radio options updated');
                return;
            } else if (single.kind === 'persisted') {
                applyRadioOptionsToPersisted(single.name, radioRows, radioDefault, options);
                return; // async, handles its own close
            }
        }

        // ── Standard path ────────────────────────────────────────────────────
        if (!Object.keys(options).length && !isChoiceField) {
            P.Utils.toast('No applicable options for selected fields', 'info');
            return;
        }

        var pendingNames = [], persistedNames = [];
        fieldNames.forEach(function (name) {
            if (P.EditMode.findPendingFormAdd(name)) pendingNames.push(name);
            else persistedNames.push(name);
        });

        pendingNames.forEach(function (name) {
            var pendingField = P.EditMode.findPendingFormAdd(name);
            if (!pendingField) return;
            var targets = [pendingField];
            if (pendingField._radioHandleKey && pendingField.fieldName) {
                var groupName = pendingField.fieldName;
                (P.state.pendingFormAdds || []).forEach(function (pa) {
                    if (pa && pa !== pendingField && pa.fieldName === groupName && pa._radioHandleKey) targets.push(pa);
                });
            }
            targets.forEach(function (pf) {
                if (!pf.options) pf.options = {};
                ['required', 'readonly', 'multiline', 'editable', 'multiSelect', 'checked'].forEach(function (key) {
                    if (options[key] !== null && options[key] !== undefined) pf.options[key] = options[key];
                });
                if (options.defaultValue !== undefined) {
                    if (pf.fieldType === 'radio') pf.options.radioDefault = options.defaultValue;
                    else pf.options.defaultValue = options.defaultValue;
                }
                if (options.choices !== undefined) pf.options.choices = P.EditMode.cloneObject(options.choices);
                if (options.javascript !== undefined) pf.options.javascript = options.javascript;
            });
        });

        if (!persistedNames.length) {
            finishLocalOptionsApply('Pending field options updated');
            return;
        }

        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        P.state.pendingFieldOptions.push({ fieldNames: persistedNames.slice(), options: P.EditMode.cloneObject(options) });
        applyOptionOverridesToPersistedSelection(persistedNames, options);
        finishLocalOptionsApply(pendingNames.length ? 'Field options queued (saved + pending fields)' : 'Field options queued. Click Save to persist.');
    }

    function applyRadioOptionsToPending(groupName, radioRows, radioDefault, sharedOptions) {
        var existingEntries = (P.state.pendingFormAdds || []).filter(function (pa) {
            return pa && pa.fieldName === groupName && pa._radioHandleKey;
        });
        var firstEntry = existingEntries[0];
        var existingMap = Object.create(null);
        existingEntries.forEach(function (pa) { existingMap[pa.options && pa.options.exportValue || ''] = pa; });

        // Remove entries for deleted options
        var newValues = Object.create(null);
        radioRows.forEach(function (r) { if (r.value) newValues[r.value] = true; });
        P.state.pendingFormAdds = (P.state.pendingFormAdds || []).filter(function (pa) {
            if (!pa || pa.fieldName !== groupName || !pa._radioHandleKey) return true;
            var ev = pa.options && pa.options.exportValue || '';
            return !!newValues[ev];
        });

        // Add or update entries
        radioRows.forEach(function (row, i) {
            if (!row.value) return;
            var key = groupName + '\x00' + row.value;
            var existing = P.EditMode.findPendingFormAdd(key);
            if (existing) {
                existing.options.exportLabel = row.label || '';
                existing.options.radioDefault = radioDefault;
                if (sharedOptions.required !== null && sharedOptions.required !== undefined) existing.options.required = sharedOptions.required;
                if (sharedOptions.readonly !== null && sharedOptions.readonly !== undefined) existing.options.readonly = sharedOptions.readonly;
                if (sharedOptions.javascript !== undefined) existing.options.javascript = sharedOptions.javascript;
            } else {
                var base = firstEntry || { pageIndex: 0, x: 50, y: 50, width: 20, height: 14 };
                P.state.pendingFormAdds.push({
                    fieldType: 'radio', fieldName: groupName,
                    _radioHandleKey: key,
                    pageIndex: base.pageIndex, x: base.x, y: base.y - i * (base.height + 4),
                    width: base.width, height: base.height,
                    options: {
                        exportValue: row.value, exportLabel: row.label || '',
                        required: sharedOptions.required || false,
                        readonly: sharedOptions.readonly || false,
                        radioDefault: radioDefault,
                        javascript: sharedOptions.javascript || ''
                    }
                });
            }
        });
    }

    function applyRadioOptionsToPersisted(fieldName, radioRows, radioDefault, sharedOptions) {
        var fieldOptions = {};
        if (sharedOptions.required !== null && sharedOptions.required !== undefined) fieldOptions.required = sharedOptions.required;
        if (sharedOptions.readonly !== null && sharedOptions.readonly !== undefined) fieldOptions.readonly = sharedOptions.readonly;
        if (sharedOptions.javascript !== undefined) fieldOptions.javascript = sharedOptions.javascript;
        if (radioDefault) fieldOptions.radioDefault = radioDefault;

        $.ajax({
            url: '/api/edit/' + encodeURIComponent(P.state.sessionId) + '/radio/' + encodeURIComponent(fieldName) + '/restructure',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ options: radioRows, fieldOptions: fieldOptions }),
            success: function (response) {
                if (response.tree) {
                    P.state.treeData = response.tree;
                    if (P.Tree && P.Tree.render) P.Tree.render(response.tree);
                }
                finishLocalOptionsApply('Radio options updated');
            },
            error: function (xhr) {
                var msg = (xhr.responseJSON && xhr.responseJSON.message) || xhr.responseText || 'Server error';
                P.Utils.toast('Failed to update radio options: ' + msg, 'danger');
            }
        });
    }

    function finishLocalOptionsApply(toastMessage) {
        if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) P.Tabs.switchTab(P.state.currentTab);
        P.EditMode.renderFieldHandlesForAllPages();
        P.EditMode.updateSaveButton();
        P.EditMode.hideModal('options');
        P.Utils.toast(toastMessage, 'success');
    }

    // ── init ─────────────────────────────────────────────────────────────────

    function init() {
        bindJsPresetControls();
        $('#applyFieldOptionsBtn').on('click', applyOptionsFromModal);
        $('#formOptionsBtn').on('click', openOptionsPopup);
        $('#optJavascriptCollapse').on('shown.bs.collapse hidden.bs.collapse', P.EditMode.syncOptionsJavascriptToggleState);

        // Choice item table buttons (edit/options dialog)
        $('#addOptChoiceOptionBtn').on('click', function () {
            var idx = $('#optChoiceOptionRows tr').length;
            $('#optChoiceOptionRows').append(P.EditField.buildChoiceOptionRow('', '', idx, 'optChoiceOptionRows', syncOptChoiceDefault));
            P.EditField.renumberChoiceRows('optChoiceOptionRows');
            syncOptChoiceDefault();
        });

        // Radio option table buttons (edit/options dialog)
        $('#addOptRadioOptionBtn').on('click', function () {
            var idx = $('#optRadioOptionRows tr').length;
            $('#optRadioOptionRows').append(P.EditField.buildRadioOptionRow('', '', idx, 'optRadioOptionRows', syncOptRadioDefault));
            P.EditField.renumberRadioRows('optRadioOptionRows');
            syncOptRadioDefault();
        });
    }

    return {
        init: init,
        openOptionsPopup: openOptionsPopup
    };
})(jQuery, PDFalyzer);
