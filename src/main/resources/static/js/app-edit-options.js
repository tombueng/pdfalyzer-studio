/**
 * PDFalyzer Studio – Field Options dialog logic.
 * Depends on app-edit-mode.js, app-edit-field.js, and app-field-dialog.js being loaded first.
 */
PDFalyzer.EditOptions = (function ($, P) {
    'use strict';

    var _dialogState = null; // { selectedEntries, single, singleFt, fieldNames }

    var BOOLEAN_KEYS = ['required', 'readonly', 'multiline', 'editable', 'multiSelect', 'checked'];
    var APPEARANCE_KEYS = ['fontName', 'fontSize', 'textColor', 'backgroundColor', 'borderColor',
        'borderWidth', 'borderStyle', 'alignment', 'rotation', 'maxLength'];

    // What the dialog displays when a field has no explicit value for a given appearance key.
    // If the collected value equals this AND the field had no original value, we skip it
    // to avoid inadvertently writing defaults onto fields that had no appearance settings.
    var APPEARANCE_DISPLAY_DEFAULTS = {
        fontName: 'Helv', textColor: '#000000', backgroundColor: '#000000',
        borderColor: '#000000', borderStyle: 'solid', alignment: 'left', rotation: '0'
    };

    // ── Tri-state value parser ────────────────────────────────────────────────

    function parseTriStateValue(value) {
        if (value === true || value === 'true') return true;
        if (value === false || value === 'false') return false;
        return null; // 'keep'
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
        if (optionName === 'fontName') return 'FontName';
        if (optionName === 'fontSize') return 'FontSize';
        if (optionName === 'textColor') return 'TextColor';
        if (optionName === 'backgroundColor') return 'BackgroundColor';
        if (optionName === 'borderColor') return 'BorderColor';
        if (optionName === 'borderWidth') return 'BorderWidth';
        if (optionName === 'borderStyle') return 'BorderStyle';
        if (optionName === 'alignment') return 'Alignment';
        if (optionName === 'rotation') return 'Rotation';
        if (optionName === 'maxLength') return 'MaxLength';
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
        // Choices: prefer ChoicePairs (preserves display labels) over Options (values-only csv)
        if (optionName === 'choices' && entry.node && entry.node.properties) {
            var pairs = entry.node.properties.ChoicePairs;
            if (pairs) {
                try {
                    return JSON.parse(pairs).map(function (p) { return { value: p.v || '', label: p.l || '' }; });
                } catch (e) { /* fall through */ }
            }
            var optStr = entry.node.properties.Options || '';
            if (optStr) {
                return optStr.split(',').map(function (v) { return { value: v.trim(), label: '' }; }).filter(function (r) { return r.value; });
            }
            return null;
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

    // ── Load radio options for a single entry ─────────────────────────────────

    function loadOptRadioOptions(single) {
        if (!single) return [];
        if (single.kind === 'pending' && single.pendingField) {
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
            return optStr.split(/,\s*/).map(function (v) { return { value: v.trim(), label: '' }; }).filter(function (r) { return r.value; });
        }
        return [];
    }

    // ── Build current values for the dialog ───────────────────────────────────

    function buildCurrentValues(selectedEntries, single) {
        var vals = {};
        BOOLEAN_KEYS.forEach(function (key) {
            vals[key] = resolveTriState(selectedEntries, key);
        });
        if (single) {
            ['defaultValue', 'choices', 'javascript'].concat(APPEARANCE_KEYS).forEach(function (key) {
                var v = readOptionValue(single, key);
                if (v !== null && v !== undefined) vals[key] = v;
            });
            var singleFt = P.EditField.getEntryFieldType(single);
            if (singleFt === 'radio') {
                vals.radioOptions = loadOptRadioOptions(single);
            }
        }
        return vals;
    }

    // ── Option overrides (for persisted fields pre-save UI) ───────────────────

    function applyOptionOverridesToPersistedSelection(fieldNames, options) {
        if (!fieldNames || !fieldNames.length || !options) return;
        var overrides = P.EditMode.getPendingFieldOptionOverrides();
        fieldNames.forEach(function (fieldName) {
            if (!fieldName) return;
            if (!overrides[fieldName]) overrides[fieldName] = {};
            var target = overrides[fieldName];
            BOOLEAN_KEYS.forEach(function (key) {
                if (options[key] !== null && options[key] !== undefined) target[key] = options[key];
            });
            APPEARANCE_KEYS.forEach(function (key) {
                if (options[key] !== undefined) target[key] = options[key];
            });
            if (options.defaultValue !== undefined) target.defaultValue = options.defaultValue;
            if (options.choices !== undefined) target.choices = P.EditMode.cloneObject(options.choices);
            if (options.javascript !== undefined) target.javascript = options.javascript;
        });
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
        var singleFt = single ? P.EditField.getEntryFieldType(single) : null;

        var fieldTypes = {};
        selectedEntries.forEach(function (e) { fieldTypes[P.EditField.getEntryFieldType(e)] = true; });
        var intersectedType = Object.keys(fieldTypes).length === 1 ? Object.keys(fieldTypes)[0] : null;

        var currentValues = buildCurrentValues(selectedEntries, single);
        _dialogState = { selectedEntries: selectedEntries, single: single, singleFt: singleFt,
            fieldNames: selected.slice(), originalValues: currentValues };

        var contextInfo = selected.length === 1
            ? 'Editing: ' + selected[0]
            : 'Editing ' + selected.length + ' fields';

        P.FieldDialog.open('edit', intersectedType, currentValues, {
            isMultiEdit: !single,
            contextInfo: contextInfo,
            onApply: applyOptions,
            onHide: function () { _dialogState = null; }
        });
    }

    // ── Apply options from dialog ─────────────────────────────────────────────

    function applyOptions(opts) {
        if (!P.state.sessionId || !_dialogState) return;
        var selectedEntries = _dialogState.selectedEntries;
        var single = _dialogState.single;
        var singleFt = _dialogState.singleFt;
        var fieldNames = _dialogState.fieldNames;
        var isChoiceField = singleFt === 'combo' || singleFt === 'list';
        var isRadioField = singleFt === 'radio';

        var options = {};

        BOOLEAN_KEYS.forEach(function (key) {
            if (opts[key] !== undefined) {
                var v = parseTriStateValue(opts[key]);
                if (v !== null) options[key] = v;
            }
        });

        var origVals = _dialogState.originalValues || {};
        APPEARANCE_KEYS.forEach(function (key) {
            if (opts[key] === undefined) return;
            var val = opts[key];
            var orig = origVals[key];
            if (val === '') {
                // Explicit clear: signal removal only when the attribute was previously set in the PDF
                if (orig !== null && orig !== undefined && orig !== '') {
                    options[key] = null; // null = remove this attribute from the PDF
                }
                return;
            }
            // Skip if field had no value AND the new value is just the unchanged dialog display default
            if (orig === null || orig === undefined) {
                var displayDef = APPEARANCE_DISPLAY_DEFAULTS[key];
                if (displayDef !== undefined && String(val) === String(displayDef)) return;
            }
            options[key] = val;
        });

        if (opts.javascript !== undefined) options.javascript = opts.javascript;

        if (opts.defaultValue !== undefined && opts.defaultValue !== '') {
            options.defaultValue = opts.defaultValue;
        }

        // Choices (combo / list)
        if (isChoiceField && opts.choices !== undefined) {
            var choiceRows = Array.isArray(opts.choices) ? opts.choices : [];
            if (choiceRows.length) {
                options.choices = P.EditField.choiceRowsToBackendFormat(choiceRows);
                options.defaultValue = opts.defaultValue || '';
            }
        }

        // Radio: handle separately
        if (isRadioField && single && opts.radioOptions !== undefined) {
            var radioRows = Array.isArray(opts.radioOptions) ? opts.radioOptions : [];
            if (!radioRows.length) { P.Utils.toast('At least one radio option is required', 'warning'); return; }
            var radioDefault = opts.radioDefault || '';
            if (single.kind === 'pending') {
                applyRadioOptionsToPending(single.pendingField.fieldName, radioRows, radioDefault, options);
                finishLocalOptionsApply('Radio options updated');
                return;
            } else if (single.kind === 'persisted') {
                applyRadioOptionsToPersisted(single.name, radioRows, radioDefault, options);
                return; // async, handles its own close
            }
        }

        // Standard path
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
                BOOLEAN_KEYS.forEach(function (key) {
                    if (options[key] !== null && options[key] !== undefined) pf.options[key] = options[key];
                });
                APPEARANCE_KEYS.forEach(function (key) {
                    if (options[key] !== undefined) pf.options[key] = options[key];
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

    // ── Radio option helpers ──────────────────────────────────────────────────

    function applyRadioOptionsToPending(groupName, radioRows, radioDefault, sharedOptions) {
        var existingEntries = (P.state.pendingFormAdds || []).filter(function (pa) {
            return pa && pa.fieldName === groupName && pa._radioHandleKey;
        });
        var firstEntry = existingEntries[0];
        var newValues = Object.create(null);
        radioRows.forEach(function (r) { if (r.value) newValues[r.value] = true; });
        P.state.pendingFormAdds = (P.state.pendingFormAdds || []).filter(function (pa) {
            if (!pa || pa.fieldName !== groupName || !pa._radioHandleKey) return true;
            var ev = pa.options && pa.options.exportValue || '';
            return !!newValues[ev];
        });

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
        P.FieldDialog.hide();
        P.Utils.toast(toastMessage, 'success');
    }

    // ── init ─────────────────────────────────────────────────────────────────

    function init() {
        $('#formOptionsBtn').on('click', openOptionsPopup);
    }

    return {
        init: init,
        openOptionsPopup: openOptionsPopup
    };
})(jQuery, PDFalyzer);
