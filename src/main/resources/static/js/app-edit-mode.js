/**
 * PDFalyzer Studio – PDF form field edit mode (draw new fields on the viewer).
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

    var CREATE_BOOL_GROUPS = {
        required: { inputName: 'createRequired', blockSelector: '#createRequiredBlock' },
        readonly: { inputName: 'createReadonly', blockSelector: '#createReadonlyBlock' },
        multiline: { inputName: 'createMultiline', blockSelector: '#createMultilineBlock' },
        editable: { inputName: 'createEditable', blockSelector: '#createEditableBlock' },
        checked: { inputName: 'createChecked', blockSelector: '#createCheckedBlock' }
    };

    var JS_PRESET_DIALOGS = {
        create: {
            presetSelect: '#createJsPreset',
            paramsContainer: '#createJsPresetParams',
            applyButton: '#createJsPresetApplyBtn',
            scriptTarget: '#createJavascript'
        },
        options: {
            presetSelect: '#optJsPreset',
            paramsContainer: '#optJsPresetParams',
            applyButton: '#optJsPresetApplyBtn',
            scriptTarget: '#optJavascript'
        }
    };

    function hasSession() {
        return !!P.state.sessionId;
    }

    function getFieldConfigDialogEl(mode) {
        var def = FIELD_CONFIG_DIALOGS[mode];
        if (!def) return null;
        return document.getElementById(def.modalId);
    }

    function showFieldConfigModal(mode) {
        var modalEl = getFieldConfigDialogEl(mode);
        if (!modalEl) return;
        var modal = bootstrap.Modal.getOrCreateInstance(modalEl);
        modal.show();
    }

    function hideFieldConfigModal(mode) {
        var modalEl = getFieldConfigDialogEl(mode);
        if (!modalEl) return;
        if (document.activeElement && modalEl.contains(document.activeElement)) {
            document.activeElement.blur();
        }
        var modal = bootstrap.Modal.getInstance(modalEl);
        if (modal) modal.hide();
    }

    function updatePlaceModeCursor() {
        var active = !!(hasSession() && P.state.editMode && P.state.editFieldType);
        $('#pdfPane').toggleClass('place-mode-active', active);
    }

    function init() {
        $('#editToolbar').addClass('active');
        P.state.editMode = true;
        P.state.selectedFieldNames = [];
        P.state.selectedImageNodeIds = [];
        if (!P.state.pendingFieldDeletes) P.state.pendingFieldDeletes = {};
        updatePlaceModeCursor();

        $('.edit-field-btn').on('click', function () {
            if (!hasSession()) {
                P.Utils.toast('Load a PDF session first', 'warning');
                return;
            }
            $('.edit-field-btn').removeClass('active');
            $(this).addClass('active');
            P.state.editFieldType = $(this).data('type');
            updatePlaceModeCursor();
            P.Utils.toast('Draw a rectangle on the PDF page to place the new ' + P.state.editFieldType + ' field', 'info');
        });

        $('#formSaveBtn').on('click', savePendingChanges);
        $('#formOptionsBtn').on('click', openOptionsPopup);
        $('#applyFieldOptionsBtn').on('click', applyOptionsFromModal);
        $('#applyCreateFieldBtn').on('click', applyCreateFieldFromModal);
        bindJsPresetControls('create');
        bindJsPresetControls('options');
        $('#optJavascriptCollapse').on('shown.bs.collapse hidden.bs.collapse', syncOptionsJavascriptToggleState);
        $('#fieldCreateModal').on('hidden.bs.modal', function () {
            pendingCreatePayload = null;
        });
    }

    function syncOptionsJavascriptToggleState() {
        var $collapse = $('#optJavascriptCollapse');
        var $toggle = $('#optJavascriptToggle');
        if (!$collapse.length || !$toggle.length) return;
        var expanded = $collapse.hasClass('show');
        $toggle.attr('aria-expanded', expanded ? 'true' : 'false');
        $toggle.toggleClass('collapsed', !expanded);
        $toggle.find('.field-option-collapse-text').text(expanded ? 'Hide' : 'Show');
    }

    function setOptionsJavascriptSectionExpanded(canEditJavascript, scriptValue) {
        var collapseEl = document.getElementById('optJavascriptCollapse');
        if (!collapseEl) return;
        var collapse = bootstrap.Collapse.getOrCreateInstance(collapseEl, { toggle: false });
        if (!canEditJavascript) {
            collapse.hide();
            syncOptionsJavascriptToggleState();
            return;
        }
        var hasScript = !!String(scriptValue || '').trim();
        if (hasScript) {
            collapse.show();
        } else {
            collapse.hide();
        }
        syncOptionsJavascriptToggleState();
    }

    function startDraw(e, pageIndex, wrapper) {
        drawing    = true;
        targetPage = pageIndex;
        var rect   = wrapper.getBoundingClientRect();
        startX     = clamp(e.clientX - rect.left, 0, rect.width);
        startY     = clamp(e.clientY - rect.top, 0, rect.height);

        $drawRect = $('<div>', { 'class': 'draw-rect' })
            .css({ left: startX + 'px', top: startY + 'px' })
            .appendTo(wrapper);

        var moveHandler = function (ev) {
            if (!drawing) return;
            var cx = clamp(ev.clientX - rect.left, 0, rect.width);
            var cy = clamp(ev.clientY - rect.top, 0, rect.height);
            $drawRect.css({
                left:   Math.min(startX, cx) + 'px',
                top:    Math.min(startY, cy) + 'px',
                width:  Math.abs(cx - startX) + 'px',
                height: Math.abs(cy - startY) + 'px'
            });
        };

        var upHandler = function (ev) {
            drawing = false;
            $(document).off('mousemove', moveHandler).off('mouseup', upHandler);
            var cx = clamp(ev.clientX - rect.left, 0, rect.width);
            var cy = clamp(ev.clientY - rect.top, 0, rect.height);
            var x = Math.min(startX, cx), y = Math.min(startY, cy);
            var w = Math.abs(cx - startX), h = Math.abs(cy - startY);
            if ($drawRect) $drawRect.remove();
            if (w < 10 || h < 10) return;

            var vp    = P.state.pageViewports[pageIndex];
            var scale = vp.scale;
            var pdfX  = x / scale;
            var pdfY  = (vp.height - y - h) / scale;
            var pdfW  = w / scale;
            var pdfH  = h / scale;
            openCreateFieldDialog(P.state.editFieldType, pageIndex, {
                x: pdfX,
                y: pdfY,
                width: pdfW,
                height: pdfH
            });
        };

        $(document).on('mousemove', moveHandler).on('mouseup', upHandler);
    }

    function savePendingChanges() {
        if (!hasSession()) return;
        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        if (!P.state.pendingCosChanges) P.state.pendingCosChanges = [];

        if (!P.state.pendingFormAdds.length && !P.state.pendingFieldRects.length &&
            !P.state.pendingFieldOptions.length && !P.state.pendingCosChanges.length) {
            P.Utils.toast('No pending changes', 'info');
            return;
        }

        $('#formSaveBtn').prop('disabled', true);

        var saveAdds = $.Deferred().resolve();
        if (P.state.pendingFormAdds.length) {
            saveAdds = P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/add-fields', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(P.state.pendingFormAdds)
            });
        }

        saveAdds
            .done(function (data) {
                if (data && data.tree) {
                    P.state.treeData = data.tree;
                }
                applyPendingRectUpdates(function () {
                    applyPendingOptionUpdates(function () {
                        applyPendingCosUpdates(function (cosResult) {
                            finalizeSave(cosResult);
                        });
                    });
                });
            })
            .fail(function () {
                P.Utils.toast('Saving new fields failed', 'danger');
                updateSaveButton();
            });
    }

    function applyPendingRectUpdates(onDone) {
        var done = typeof onDone === 'function' ? onDone : finalizeSave;
        if (!P.state.pendingFieldRects.length) {
            done();
            return;
        }

        var queue = P.state.pendingFieldRects.slice();
        function next() {
            if (!queue.length) {
                done();
                return;
            }
            var item = queue.shift();
            P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                encodeURIComponent(item.fieldName) + '/rect', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    x: item.x,
                    y: item.y,
                    width: item.width,
                    height: item.height
                })
            }).done(function (data) {
                if (data && data.tree) P.state.treeData = data.tree;
                next();
            }).fail(function () {
                P.Utils.toast('Saving field geometry failed for ' + item.fieldName, 'danger');
                next();
            });
        }
        next();
    }

    function applyPendingOptionUpdates(onDone) {
        var done = typeof onDone === 'function' ? onDone : finalizeSave;
        if (!P.state.pendingFieldOptions || !P.state.pendingFieldOptions.length) {
            done();
            return;
        }

        var queue = P.state.pendingFieldOptions.slice();
        function next() {
            if (!queue.length) {
                done();
                return;
            }

            var item = queue.shift();
            if (!item || !item.fieldNames || !item.fieldNames.length) {
                next();
                return;
            }

            P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/fields/options', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ fieldNames: item.fieldNames, options: item.options || {} })
            }).done(function (data) {
                if (data && data.tree) P.state.treeData = data.tree;
                next();
            }).fail(function () {
                P.Utils.toast('Saving field options failed', 'danger');
                next();
            });
        }
        next();
    }

    function extractApiError(xhr) {
        if (!xhr) return 'Unknown error';
        if (xhr.responseJSON && xhr.responseJSON.error) return xhr.responseJSON.error;
        if (xhr.responseText) return xhr.responseText;
        if (xhr.statusText) return xhr.statusText;
        return 'Unknown error';
    }

    function applyPendingCosUpdates(onDone) {
        var done = typeof onDone === 'function' ? onDone : function () {};
        if (!P.state.pendingCosChanges || !P.state.pendingCosChanges.length) {
            done({ total: 0, successes: [], failures: [] });
            return;
        }

        var queue = P.state.pendingCosChanges.slice();
        var successes = [];
        var failures = [];

        function next() {
            if (!queue.length) {
                P.state.pendingCosChanges = failures.map(function (f) {
                    return f.item;
                });
                done({
                    total: successes.length + failures.length,
                    successes: successes,
                    failures: failures
                });
                return;
            }

            var item = queue.shift();
            if (!item || !item.request) {
                failures.push({ item: item, error: 'Invalid pending COS entry' });
                next();
                return;
            }

            P.Utils.apiFetch('/api/cos/' + P.state.sessionId + '/update', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(item.request)
            }).done(function (data) {
                if (data && data.tree) P.state.treeData = data.tree;
                if (item && item.lastError) delete item.lastError;
                successes.push(item);
                next();
            }).fail(function (xhr) {
                var errorText = extractApiError(xhr);
                if (item) item.lastError = errorText;
                failures.push({ item: item, error: errorText });
                next();
            });
        }

        next();
    }

    function finalizeSave(cosResult) {
        P.state.pendingFormAdds = [];
        P.state.pendingFieldRects = [];
        P.state.pendingFieldOptions = [];
        pendingFieldOptionOverrides = {};
        if (P.state.treeData) P.Utils.refreshAfterMutation(P.state.treeData);
        refreshSelectionButtons();
        updateSaveButton();

        if (cosResult && cosResult.total > 0) {
            if (cosResult.failures.length === 0) {
                P.Utils.toast('All pending changes saved', 'success');
            } else {
                P.Utils.toast(
                    'Save completed: ' + cosResult.successes.length + ' succeeded, ' +
                    cosResult.failures.length + ' failed',
                    'warning'
                );
                cosResult.failures.forEach(function (failure) {
                    var summary = (failure.item && failure.item.summary) ? failure.item.summary : 'COS change';
                    P.Utils.toast('Failed: ' + summary + ' — ' + (failure.error || 'Unknown error'), 'danger');
                });
            }
            return;
        }

        P.Utils.toast('Form changes saved', 'success');
    }

    function resetPending() {
        P.state.pendingFormAdds = [];
        P.state.pendingFieldRects = [];
        P.state.pendingFieldOptions = [];
        P.state.pendingCosChanges = [];
        pendingFieldOptionOverrides = {};
        P.state.selectedFieldNames = [];
        P.state.selectedImageNodeIds = [];
        lastAddedFieldTemplate = null;
        updatePlaceModeCursor();
        refreshSelectionButtons();
        updateSaveButton();
    }

    function updateSaveButton() {
        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        if (!P.state.pendingCosChanges) P.state.pendingCosChanges = [];
        var hasPending = P.state.pendingFormAdds.length > 0 ||
            P.state.pendingFieldRects.length > 0 ||
            P.state.pendingFieldOptions.length > 0 ||
            P.state.pendingCosChanges.length > 0;
        $('#formSaveBtn').prop('disabled', !hasPending || !hasSession());
        updateFailedCosBadge();
        if (P.Tree && P.Tree.refreshPendingPanel) {
            P.Tree.refreshPendingPanel();
        }
        if (P.Storage && P.Storage.saveDraft) {
            P.Storage.saveDraft(P.state);
        }
    }

    function updateFailedCosBadge() {
        var pendingCos = P.state.pendingCosChanges || [];
        var failedCount = pendingCos.filter(function (item) {
            return !!(item && item.lastError);
        }).length;

        var $badge = $('#formSaveFailedBadge');
        if (!$badge.length) return;

        if (failedCount > 0) {
            $badge.text(String(failedCount)).removeClass('d-none');
        } else {
            $badge.addClass('d-none').text('0');
        }
    }

    function refreshSelectionButtons() {
        var selectedCount = (P.state.selectedFieldNames || []).length;
        $('#formOptionsBtn').prop('disabled', !hasSession() || selectedCount === 0);
    }

    function getDefaultOptions(fieldType) {
        if (fieldType === 'text') return { required: false, readonly: false, multiline: false, defaultValue: '' };
        if (fieldType === 'checkbox') return { required: false, readonly: false, checked: false };
        if (fieldType === 'combo') return { required: false, readonly: false, editable: false, choices: 'A,B,C', defaultValue: '' };
        if (fieldType === 'radio') return { required: false, readonly: false };
        if (fieldType === 'signature') return { required: true, readonly: false };
        return {};
    }

    function cloneObject(value) {
        if (value === null || value === undefined) return value;
        try {
            return JSON.parse(JSON.stringify(value));
        } catch (err) {
            return value;
        }
    }

    function mergeOptions(defaults, inherited) {
        var merged = cloneObject(defaults || {});
        if (!inherited || typeof inherited !== 'object') return merged;
        Object.keys(inherited).forEach(function (key) {
            merged[key] = inherited[key];
        });
        return merged;
    }

    function resolveCreateOptions(fieldType) {
        var defaults = getDefaultOptions(fieldType);
        if (lastAddedFieldTemplate && lastAddedFieldTemplate.options) {
            return mergeOptions(defaults, lastAddedFieldTemplate.options);
        }
        if (P.state.pendingFormAdds && P.state.pendingFormAdds.length > 0) {
            var previous = P.state.pendingFormAdds[P.state.pendingFormAdds.length - 1];
            if (previous && previous.options) {
                return mergeOptions(defaults, previous.options);
            }
        }
        return defaults;
    }

    function findPendingFormAddIndex(fieldName) {
        if (!fieldName || !P.state.pendingFormAdds) return -1;
        for (var i = 0; i < P.state.pendingFormAdds.length; i++) {
            if (P.state.pendingFormAdds[i] && P.state.pendingFormAdds[i].fieldName === fieldName) return i;
        }
        return -1;
    }

    function findPendingFormAdd(fieldName) {
        var idx = findPendingFormAddIndex(fieldName);
        return idx >= 0 ? P.state.pendingFormAdds[idx] : null;
    }

    function findPendingRectChange(fieldName) {
        if (!fieldName || !P.state.pendingFieldRects) return null;
        for (var i = 0; i < P.state.pendingFieldRects.length; i++) {
            var item = P.state.pendingFieldRects[i];
            if (item && item.fieldName === fieldName) return item;
        }
        return null;
    }

    function getFieldIdPrefix(fieldType) {
        var normalized = (fieldType || '').toLowerCase();
        if (normalized === 'text') return 'text';
        if (normalized === 'combo') return 'combo';
        if (normalized === 'checkbox') return 'check';
        if (normalized === 'radio') return 'radio';
        if (normalized === 'signature') return 'sign';
        return 'field';
    }

    function collectUsedFieldIds() {
        var used = Object.create(null);

        function markUsed(name) {
            if (!name) return;
            used[String(name)] = true;
        }

        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName) {
                markUsed(node.properties.FullName);
            }
            if (node.children && node.children.length) {
                node.children.forEach(walk);
            }
        }

        walk(P.state.treeData);

        (P.state.pendingFormAdds || []).forEach(function (pendingAdd) {
            if (pendingAdd && pendingAdd.fieldName) markUsed(pendingAdd.fieldName);
        });

        return used;
    }

    function suggestNextFieldId(fieldType) {
        var prefix = getFieldIdPrefix(fieldType);
        var used = collectUsedFieldIds();
        var index = 1;
        while (used[prefix + index]) {
            index += 1;
        }
        return prefix + index;
    }

    function openCreateFieldDialog(fieldType, pageIndex, rect) {
        if (!fieldType || !rect) return;
        pendingCreatePayload = {
            fieldType: fieldType,
            pageIndex: pageIndex,
            x: rect.x,
            y: rect.y,
            width: rect.width,
            height: rect.height,
            options: resolveCreateOptions(fieldType)
        };

        $('#createFieldType').val(fieldType);
        $('#createFieldPage').val(String(pageIndex + 1));
        $('#createFieldId').val(suggestNextFieldId(fieldType));

        var createEntry = { kind: 'pending', pendingField: { fieldType: fieldType } };
        var visibleKeys = computeVisibleOptionKeys([createEntry]);

        Object.keys(CREATE_BOOL_GROUPS).forEach(function (optionName) {
            var def = CREATE_BOOL_GROUPS[optionName];
            setFieldConfigBlockVisible(def.blockSelector, !!visibleKeys[optionName]);
            if (visibleKeys[optionName]) {
                setCreateBooleanControlValue(optionName, !!pendingCreatePayload.options[optionName]);
            }
        });

        setFieldConfigBlockVisible('#createDefaultValueBlock', !!visibleKeys.defaultValue);
        setFieldConfigBlockVisible('#createChoicesBlock', !!visibleKeys.choices);
        setFieldConfigBlockVisible('#createJavascriptBlock', !!visibleKeys.javascript);

        $('#createDefaultValue').val(pendingCreatePayload.options.defaultValue || '');
        var createChoices = pendingCreatePayload.options.choices;
        $('#createChoices').val(Array.isArray(createChoices) ? createChoices.join(',') : (createChoices || ''));
        $('#createJavascript').val(pendingCreatePayload.options.javascript || '');

        $('#fieldCreateScopeHint').text('Showing options relevant for this ' + String(fieldType).toLowerCase() + ' field.');
        initializeJsPresetUi('create');

        showFieldConfigModal('create');
    }

    function applyCreateFieldFromModal() {
        if (!pendingCreatePayload) return;

        var fieldId = ($('#createFieldId').val() || '').trim();
        if (!fieldId) {
            P.Utils.toast('Field ID is required', 'warning');
            return;
        }

        var options = cloneObject(pendingCreatePayload.options || {});

        Object.keys(CREATE_BOOL_GROUPS).forEach(function (key) {
            var def = CREATE_BOOL_GROUPS[key];
            if (!isFieldConfigBlockVisible(def.blockSelector)) return;
            options[key] = getCreateBooleanControlValue(key);
        });

        if (isFieldConfigBlockVisible('#createDefaultValueBlock')) {
            options.defaultValue = $('#createDefaultValue').val() || '';
        }

        if (isFieldConfigBlockVisible('#createChoicesBlock')) {
            var rawChoices = $('#createChoices').val() || '';
            options.choices = rawChoices
                .split(',')
                .map(function (v) { return v.trim(); })
                .filter(function (v) { return v.length > 0; });
        }

        if (isFieldConfigBlockVisible('#createJavascriptBlock')) {
            options.javascript = $('#createJavascript').val() || '';
        }

        var queuedField = {
            fieldType: pendingCreatePayload.fieldType,
            fieldName: fieldId,
            pageIndex: pendingCreatePayload.pageIndex,
            x: pendingCreatePayload.x,
            y: pendingCreatePayload.y,
            width: pendingCreatePayload.width,
            height: pendingCreatePayload.height,
            options: options
        };
        P.state.pendingFormAdds.push(queuedField);
        lastAddedFieldTemplate = {
            fieldType: queuedField.fieldType,
            options: cloneObject(queuedField.options || {})
        };
        pendingCreatePayload = null;

        hideFieldConfigModal('create');

        renderFieldHandlesForAllPages();
        if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) {
            P.Tabs.switchTab(P.state.currentTab);
        }
        updateSaveButton();
        P.Utils.toast('Field queued. Click Save to persist changes.', 'info');
    }

    function computeNextLineRect(pageIndex, fieldType) {
        var baseX = 50;
        var baseY = 700;
        var lineHeight = 34;
        var width = fieldType === 'signature' ? 220 : 180;
        var height = fieldType === 'signature' ? 28 : 22;

        var existingOnPage = collectFieldNodesOnPage(pageIndex).length;
        var pendingOnPage = P.state.pendingFormAdds.filter(function (f) { return f.pageIndex === pageIndex; }).length;
        var line = existingOnPage + pendingOnPage;
        return {
            x: baseX,
            y: Math.max(40, baseY - (line * lineHeight)),
            width: width,
            height: height
        };
    }

    function collectFieldNodesOnPage(pageIndex) {
        var result = [];
        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.pageIndex === pageIndex && node.boundingBox) {
                var copy = $.extend(true, {}, node);
                var fullName = copy.properties && copy.properties.FullName;
                var pendingRect = fullName ? findPendingRectChange(fullName) : null;
                if (pendingRect) {
                    copy.boundingBox = [pendingRect.x, pendingRect.y, pendingRect.width, pendingRect.height];
                }
                result.push(copy);
            }
            if (node.children) node.children.forEach(walk);
        }
        walk(P.state.treeData);

        (P.state.pendingFormAdds || []).forEach(function (pendingAdd) {
            if (pendingAdd.pageIndex !== pageIndex) return;
            result.push({
                nodeCategory: 'field',
                pageIndex: pageIndex,
                pending: true,
                boundingBox: [pendingAdd.x, pendingAdd.y, pendingAdd.width, pendingAdd.height],
                properties: {
                    FullName: pendingAdd.fieldName,
                    Pending: true,
                    FieldType: pendingAdd.fieldType
                },
                options: cloneObject(pendingAdd.options || {})
            });
        });

        return result;
    }

    function isTruthyFlag(value) {
        if (value === true) return true;
        if (value === false || value === null || value === undefined) return false;
        var normalized = String(value).toLowerCase();
        return normalized === 'true' || normalized === '1' || normalized === 'yes';
    }

    function isFieldRequired(fieldNode) {
        if (!fieldNode) return false;
        if (fieldNode.pending) {
            var pendingRequired = fieldNode.options ? fieldNode.options.required : null;
            return isTruthyFlag(pendingRequired);
        }

        var fullName = fieldNode.properties && fieldNode.properties.FullName;
        if (fullName && pendingFieldOptionOverrides[fullName] &&
            Object.prototype.hasOwnProperty.call(pendingFieldOptionOverrides[fullName], 'required')) {
            return isTruthyFlag(pendingFieldOptionOverrides[fullName].required);
        }

        var props = fieldNode.properties || {};
        return isTruthyFlag(props.Required) || isTruthyFlag(props.required);
    }

    function isFieldReadOnly(fieldNode) {
        if (!fieldNode) return false;
        if (fieldNode.pending) {
            var pendingReadOnly = fieldNode.options ? fieldNode.options.readonly : null;
            return isTruthyFlag(pendingReadOnly);
        }

        var fullName = fieldNode.properties && fieldNode.properties.FullName;
        if (fullName && pendingFieldOptionOverrides[fullName] &&
            Object.prototype.hasOwnProperty.call(pendingFieldOptionOverrides[fullName], 'readonly')) {
            return isTruthyFlag(pendingFieldOptionOverrides[fullName].readonly);
        }

        var props = fieldNode.properties || {};
        return isTruthyFlag(props.ReadOnly) || isTruthyFlag(props.readonly);
    }

    function renderFieldHandles(pageIndex, wrapperEl, viewportOverride) {
        if (!hasSession() || !wrapperEl) return;
        $(wrapperEl).find('.form-field-handle').remove();
        var viewport = viewportOverride || P.state.pageViewports[pageIndex];
        if (!viewport) return;

        var fields = collectFieldNodesOnPage(pageIndex);
        fields.forEach(function (fieldNode) {
            var bbox = fieldNode.boundingBox;
            var scale = viewport.scale;
            var left = bbox[0] * scale;
            var top = viewport.height - (bbox[1] + bbox[3]) * scale;
            var width = bbox[2] * scale;
            var height = bbox[3] * scale;
            var isPending = !!fieldNode.pending;

            var fullName = fieldNode.properties && fieldNode.properties.FullName;
            if (!fullName) return;

            var $handle = $('<div>', { 'class': 'form-field-handle' })
                .attr('data-field-name', fullName)
                .css({ left: left + 'px', top: top + 'px', width: width + 'px', height: height + 'px' });
            if (isPending) {
                $handle.addClass('pending');
            }
            if (isFieldRequired(fieldNode)) {
                $handle.addClass('required');
            }
            if (isFieldReadOnly(fieldNode)) {
                $handle.addClass('readonly');
                $handle.append($('<span>', {
                    'class': 'form-field-readonly-badge',
                    html: '<i class="fas fa-lock"></i>'
                }));
            }
            if ((P.state.selectedFieldNames || []).indexOf(fullName) >= 0) {
                $handle.addClass('selected');
            }

            if (isPending) {
                var pendingLabel = fieldNode.properties && fieldNode.properties.FieldType
                    ? (fieldNode.properties.FieldType + ': ' + fullName)
                    : fullName;
                $handle.attr('title', pendingLabel + ' (pending save)');
                $handle.append($('<span>', { 'class': 'form-field-pending-label', text: 'Pending' }));
            }
            
            // Add options button to handle
            var $optBtn = $('<button>', { 
                'class': 'field-handle-btn field-handle-options',
                'title': 'Options',
                html: '<i class="fas fa-cog"></i>'
            }).on('click', function(e) {
                e.stopPropagation();
                P.state.selectedFieldNames = [fullName];
                P.state.selectedImageNodeIds = [];
                renderFieldHandlesForAllPages();
                openOptionsPopup();
            });
            
            // Add delete button to handle
            var $delBtn = $('<button>', { 
                'class': 'field-handle-btn field-handle-delete',
                'title': 'Delete',
                html: '<i class="fas fa-trash-alt"></i>'
            }).on('click', function(e) {
                e.stopPropagation();
                queueFieldDelete(fullName);
            });
            
            if (isFieldDeletePending(fullName)) {
                var $undoBtn = $('<button>', {
                    'class': 'field-handle-btn field-handle-undo',
                    'title': 'Undo remove',
                    html: '<i class="fas fa-undo"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    undoFieldDelete(fullName);
                });
                var $undoActions = $('<div>', { 'class': 'field-handle-actions' }).append($undoBtn);
                $handle.addClass('removed').append($undoActions).appendTo(wrapperEl);
                updateActionButtonsSide($handle, wrapperEl);
            } else {
                var $resize = $('<div>', { 'class': 'form-field-resize' });
                var $actions = $('<div>', { 'class': 'field-handle-actions' }).append($optBtn, $delBtn);
                $handle.append($actions, $resize).appendTo(wrapperEl);
                updateActionButtonsSide($handle, wrapperEl);
                bindDragResize($handle, $resize, fieldNode, viewport);
            }
        });
        refreshSelectionButtons();
    }

    function updateActionButtonsSide($handle, wrapperEl) {
        if (!$handle || !$handle.length || !wrapperEl) return;
        var $actions = $handle.find('.field-handle-actions').first();
        if (!$actions.length) return;

        $actions.removeClass('side-left');

        var handleLeft = parseFloat($handle.css('left')) || 0;
        var handleWidth = $handle.outerWidth() || 0;
        var actionsWidth = $actions.outerWidth() || 0;
        var gap = 6;
        var wrapperWidth = wrapperEl.clientWidth || 0;

        var rightEdgeWithActions = handleLeft + handleWidth + gap + actionsWidth;
        if (rightEdgeWithActions > wrapperWidth) {
            $actions.addClass('side-left');
        }
    }

    function bindDragResize($handle, $resize, fieldNode, viewport) {
        var dragging = false;
        var resizing = false;
        var start = {};
        var moveHandler = null;
        var upHandler = null;
        var fullName = fieldNode && fieldNode.properties ? fieldNode.properties.FullName : null;

        $handle.on('dblclick', function (e) {
            if ($(e.target).closest('.form-field-resize, .field-handle-btn').length) return;
            e.preventDefault();
            e.stopPropagation();
            if (!fullName) return;
            P.state.selectedFieldNames = [fullName];
            P.state.selectedImageNodeIds = [];
            syncHandleSelectionClasses();
            syncTreeSelectionForField(fullName);
            refreshSelectionButtons();
            openOptionsPopup();
        });

        function detachDragHandlers() {
            if (moveHandler) $(document).off('mousemove', moveHandler);
            if (upHandler) $(document).off('mouseup', upHandler);
            moveHandler = null;
            upHandler = null;
        }

        $handle.on('mousedown', function (e) {
            if ($(e.target).closest('.form-field-resize, .field-handle-btn').length) return;
            if (e.detail >= 2 && fullName) {
                e.preventDefault();
                e.stopPropagation();
                P.state.selectedFieldNames = [fullName];
                P.state.selectedImageNodeIds = [];
                syncHandleSelectionClasses();
                syncTreeSelectionForField(fullName);
                refreshSelectionButtons();
                openOptionsPopup();
                return;
            }
            e.preventDefault();
            e.stopPropagation();
            detachDragHandlers();

            var name = fieldNode.properties && fieldNode.properties.FullName;
            if (name) {
                if (e.ctrlKey || e.metaKey || e.shiftKey) {
                    toggleFieldSelection(name);
                } else {
                    var selectedNames = P.state.selectedFieldNames || [];
                    if (!(selectedNames.length > 1 && selectedNames.indexOf(name) >= 0)) {
                        P.state.selectedFieldNames = [name];
                        P.state.selectedImageNodeIds = [];
                        syncTreeSelectionForField(name);
                    }
                }
                refreshSelectionButtons();
                syncHandleSelectionClasses();
            }

            var moveNames = (P.state.selectedFieldNames || []).slice();
            if (name && moveNames.indexOf(name) < 0) moveNames = [name];

            var dragTargets = [];
            moveNames.forEach(function (fieldName) {
                var $targetHandle = findHandleElementByFieldName(fieldName);
                if (!$targetHandle || !$targetHandle.length || $targetHandle.hasClass('removed')) return;
                var rectInfo = getCurrentFieldRectByName(fieldName);
                if (!rectInfo) return;
                var vp = P.state.pageViewports[rectInfo.pageIndex];
                if (!vp) return;
                dragTargets.push({
                    fieldName: fieldName,
                    $el: $targetHandle,
                    left: parseFloat($targetHandle.css('left')),
                    top: parseFloat($targetHandle.css('top')),
                    rect: rectInfo,
                    viewport: vp
                });
            });

            if (!dragTargets.length && name) {
                var fallbackRect = getCurrentFieldRectByName(name);
                if (fallbackRect) {
                    dragTargets.push({
                        fieldName: name,
                        $el: $handle,
                        left: parseFloat($handle.css('left')),
                        top: parseFloat($handle.css('top')),
                        rect: fallbackRect,
                        viewport: viewport
                    });
                }
            }

            dragging = true;
            start = {
                x: e.clientX,
                y: e.clientY,
                left: parseFloat($handle.css('left')),
                top: parseFloat($handle.css('top')),
                dragTargets: dragTargets
            };

            moveHandler = function (ev) {
                if (dragging) {
                    var dx = ev.clientX - start.x;
                    var dy = ev.clientY - start.y;
                    if (start.dragTargets && start.dragTargets.length) {
                        start.dragTargets.forEach(function (target) {
                            var targetWrapper = target.$el.parent()[0];
                            var targetWidth = target.$el.outerWidth() || 0;
                            var targetHeight = target.$el.outerHeight() || 0;
                            var maxLeft = Math.max(0, (targetWrapper ? targetWrapper.clientWidth : 0) - targetWidth);
                            var maxTop = Math.max(0, (targetWrapper ? targetWrapper.clientHeight : 0) - targetHeight);
                            var nextLeft = clamp(target.left + dx, 0, maxLeft);
                            var nextTop = clamp(target.top + dy, 0, maxTop);
                            target.$el.css({ left: nextLeft + 'px', top: nextTop + 'px' });
                        });
                    } else {
                        var wrapperEl = $handle.parent()[0];
                        var handleWidth = $handle.outerWidth() || 0;
                        var handleHeight = $handle.outerHeight() || 0;
                        var maxLeftSingle = Math.max(0, (wrapperEl ? wrapperEl.clientWidth : 0) - handleWidth);
                        var maxTopSingle = Math.max(0, (wrapperEl ? wrapperEl.clientHeight : 0) - handleHeight);
                        var clampedLeft = clamp(start.left + dx, 0, maxLeftSingle);
                        var clampedTop = clamp(start.top + dy, 0, maxTopSingle);
                        $handle.css({ left: clampedLeft + 'px', top: clampedTop + 'px' });
                    }
                } else if (resizing) {
                    var wrapperEl = $handle.parent()[0];
                    var leftNow = parseFloat($handle.css('left')) || 0;
                    var topNow = parseFloat($handle.css('top')) || 0;
                    var maxW = Math.max(20, (wrapperEl ? wrapperEl.clientWidth : 0) - leftNow);
                    var maxH = Math.max(14, (wrapperEl ? wrapperEl.clientHeight : 0) - topNow);
                    var rw = clamp(start.width + (ev.clientX - start.x), 20, maxW);
                    var rh = clamp(start.height + (ev.clientY - start.y), 14, maxH);
                    $handle.css({ width: rw + 'px', height: rh + 'px' });
                }
            };

            upHandler = function (ev) {
                if (!dragging && !resizing) {
                    detachDragHandlers();
                    return;
                }
                var wasDragging = dragging;
                dragging = false;
                resizing = false;

                if (wasDragging) {
                    var dx = ev.clientX - start.x;
                    var dy = ev.clientY - start.y;
                    var targets = start.dragTargets || [];

                    targets.forEach(function (target) {
                        if (!target.viewport || !target.rect) return;
                        var targetLeft = parseFloat(target.$el.css('left')) || 0;
                        var targetTop = parseFloat(target.$el.css('top')) || 0;
                        var pdfDx = (targetLeft - target.left) / target.viewport.scale;
                        var pdfDy = -(targetTop - target.top) / target.viewport.scale;
                        queueRectChange(
                            target.fieldName,
                            target.rect.x + pdfDx,
                            target.rect.y + pdfDy,
                            target.rect.width,
                            target.rect.height
                        );
                    });

                    renderFieldHandlesForAllPages();
                    updateSaveButton();
                    detachDragHandlers();
                    return;
                }

                var left = parseFloat($handle.css('left'));
                var top = parseFloat($handle.css('top'));
                var width = $handle.width();
                var height = $handle.height();
                var scale = viewport.scale;

                var pdfX = left / scale;
                var pdfY = (viewport.height - top - height) / scale;
                var pdfW = width / scale;
                var pdfH = height / scale;

                var fullName = fieldNode && fieldNode.properties ? fieldNode.properties.FullName : null;
                if (fullName) {
                    queueRectChange(fullName, pdfX, pdfY, pdfW, pdfH);
                }
                renderFieldHandlesForAllPages();
                updateSaveButton();
                detachDragHandlers();
            };

            $(document).on('mousemove', moveHandler).on('mouseup', upHandler);
        });

        $resize.on('mousedown', function (e) {
            e.preventDefault();
            e.stopPropagation();
            detachDragHandlers();
            resizing = true;
            start = {
                x: e.clientX,
                y: e.clientY,
                width: $handle.width(),
                height: $handle.height()
            };
            moveHandler = function (ev) {
                if (!resizing) return;
                var wrapperEl = $handle.parent()[0];
                var leftNow = parseFloat($handle.css('left')) || 0;
                var topNow = parseFloat($handle.css('top')) || 0;
                var maxW = Math.max(20, (wrapperEl ? wrapperEl.clientWidth : 0) - leftNow);
                var maxH = Math.max(14, (wrapperEl ? wrapperEl.clientHeight : 0) - topNow);
                var rw = clamp(start.width + (ev.clientX - start.x), 20, maxW);
                var rh = clamp(start.height + (ev.clientY - start.y), 14, maxH);
                $handle.css({ width: rw + 'px', height: rh + 'px' });
            };

            upHandler = function () {
                if (!resizing) {
                    detachDragHandlers();
                    return;
                }
                resizing = false;

                var left = parseFloat($handle.css('left'));
                var top = parseFloat($handle.css('top'));
                var width = $handle.width();
                var height = $handle.height();
                var scale = viewport.scale;

                var pdfX = left / scale;
                var pdfY = (viewport.height - top - height) / scale;
                var pdfW = width / scale;
                var pdfH = height / scale;

                var fullName = fieldNode && fieldNode.properties ? fieldNode.properties.FullName : null;
                if (fullName) {
                    queueRectChange(fullName, pdfX, pdfY, pdfW, pdfH);
                }
                renderFieldHandlesForAllPages();
                updateSaveButton();
                detachDragHandlers();
            };

            $(document).on('mousemove', moveHandler).on('mouseup', upHandler);
        });
    }

    function toggleFieldSelection(fieldName) {
        if (!P.state.selectedFieldNames) P.state.selectedFieldNames = [];
        var idx = P.state.selectedFieldNames.indexOf(fieldName);
        if (idx >= 0) P.state.selectedFieldNames.splice(idx, 1);
        else P.state.selectedFieldNames.push(fieldName);
        syncHandleSelectionClasses();
        syncTreeSelectionForField(fieldName);
        refreshSelectionButtons();
    }

    function selectFieldFromViewer(fieldNode, additive) {
        if (!fieldNode || !fieldNode.properties) return;
        var fullName = fieldNode.properties.FullName;
        if (!fullName) return;

        if (additive) {
            toggleFieldSelection(fullName);
        } else {
            P.state.selectedFieldNames = [fullName];
            P.state.selectedImageNodeIds = [];
            syncHandleSelectionClasses();
            syncTreeSelectionForField(fullName);
        }

        renderFieldHandlesForAllPages();
        P.Tree.selectNode(fieldNode, additive, true);
        refreshSelectionButtons();
    }

    function refreshFieldSelectionHighlights() {
        renderFieldHandlesForAllPages();
        refreshSelectionButtons();
    }

    function findFieldNodeByName(fullName) {
        var found = null;
        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName === fullName) {
                found = node;
                return;
            }
            if (node.children) node.children.forEach(walk);
        }
        walk(P.state.treeData);
        return found;
    }

    function getCurrentFieldRectByName(fullName) {
        if (!fullName) return null;
        var pendingField = findPendingFormAdd(fullName);
        if (pendingField) {
            return {
                x: pendingField.x,
                y: pendingField.y,
                width: pendingField.width,
                height: pendingField.height,
                pageIndex: pendingField.pageIndex
            };
        }

        var node = findFieldNodeByName(fullName);
        if (!node || !node.boundingBox || node.boundingBox.length < 4) return null;
        var pendingRect = findPendingRectChange(fullName);
        if (pendingRect) {
            return {
                x: pendingRect.x,
                y: pendingRect.y,
                width: pendingRect.width,
                height: pendingRect.height,
                pageIndex: node.pageIndex
            };
        }

        return {
            x: node.boundingBox[0],
            y: node.boundingBox[1],
            width: node.boundingBox[2],
            height: node.boundingBox[3],
            pageIndex: node.pageIndex
        };
    }

    function findHandleElementByFieldName(fieldName) {
        var match = null;
        $('.form-field-handle').each(function () {
            if ($(this).attr('data-field-name') === fieldName) {
                match = $(this);
                return false;
            }
            return true;
        });
        return match;
    }

    function syncTreeSelectionForField(fieldName) {
        if (!fieldName || !P.Tree || !P.Tree.selectNode) return;
        var node = findFieldNodeByName(fieldName);
        if (!node) return;
        P.Tree.selectNode(node, false, true, true);
    }

    function syncHandleSelectionClasses() {
        var selected = P.state.selectedFieldNames || [];
        $('.form-field-handle').each(function () {
            var $el = $(this);
            var fieldName = $el.attr('data-field-name');
            $el.toggleClass('selected', selected.indexOf(fieldName) >= 0);
        });
    }

    function renderFieldHandlesForAllPages() {
        if (!P.state.pdfDoc) return;
        for (var i = 0; i < P.state.pdfDoc.numPages; i++) {
            var wrapper = $('[data-page="' + i + '"]')[0];
            if (wrapper) renderFieldHandles(i, wrapper);
        }
    }

    var TRI_STATE_GROUPS = {
        required: { inputName: 'optRequired', blockSelector: '#optRequiredBlock' },
        readonly: { inputName: 'optReadonly', blockSelector: '#optReadonlyBlock' },
        multiline: { inputName: 'optMultiline', blockSelector: '#optMultilineBlock' },
        editable: { inputName: 'optEditable', blockSelector: '#optEditableBlock' },
        checked: { inputName: 'optChecked', blockSelector: '#optCheckedBlock' }
    };

    function getEntryFieldType(entry) {
        if (!entry) return 'unknown';
        if (entry.kind === 'pending' && entry.pendingField && entry.pendingField.fieldType) {
            return String(entry.pendingField.fieldType).toLowerCase();
        }
        var props = entry.node && entry.node.properties ? entry.node.properties : {};
        var subType = props.FieldSubType;
        if (subType) return String(subType).toLowerCase();
        var fieldType = props.FieldType;
        if (!fieldType) return 'unknown';
        var normalized = String(fieldType).toLowerCase();
        if (normalized === 'tx') return 'text';
        if (normalized === 'ch') return 'combo';
        if (normalized === 'btn') return 'checkbox';
        if (normalized === 'sig') return 'signature';
        return normalized;
    }

    function getSupportedOptionsForFieldType(fieldType) {
        var supported = { required: true, readonly: true };
        if (fieldType === 'text') {
            supported.multiline = true;
            supported.defaultValue = true;
            supported.javascript = true;
        }
        if (fieldType === 'combo') {
            supported.editable = true;
            supported.defaultValue = true;
            supported.choices = true;
            supported.javascript = true;
        }
        if (fieldType === 'list') {
            supported.defaultValue = true;
            supported.choices = true;
            supported.javascript = true;
        }
        if (fieldType === 'checkbox') {
            supported.checked = true;
            supported.javascript = true;
        }
        if (fieldType === 'radio') {
            supported.javascript = true;
        }
        return supported;
    }

    function computeVisibleOptionKeys(entries) {
        if (!entries || !entries.length) return {};
        var visible = null;
        entries.forEach(function (entry) {
            var supported = getSupportedOptionsForFieldType(getEntryFieldType(entry));
            if (!visible) {
                visible = supported;
                return;
            }
            Object.keys(visible).forEach(function (key) {
                if (!supported[key]) delete visible[key];
            });
        });
        return visible || {};
    }

    function setFieldConfigBlockVisible(selector, isVisible) {
        $(selector).toggleClass('field-option-hidden', !isVisible);
    }

    function isFieldConfigBlockVisible(selector) {
        return !$(selector).hasClass('field-option-hidden');
    }

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

    function setCreateBooleanControlValue(optionName, boolValue) {
        var group = CREATE_BOOL_GROUPS[optionName];
        if (!group) return;
        var $inputs = $('input[name="' + group.inputName + '"]');
        if (!$inputs.length) return;
        $inputs.prop('checked', false);
        $inputs.filter('[value="' + (boolValue ? 'true' : 'false') + '"]').prop('checked', true);
    }

    function getCreateBooleanControlValue(optionName) {
        var group = CREATE_BOOL_GROUPS[optionName];
        if (!group) return false;
        var value = $('input[name="' + group.inputName + '"]:checked').val();
        return value === 'true';
    }

    function getTriStateControlValue(optionName) {
        var group = TRI_STATE_GROUPS[optionName];
        if (!group) return 'keep';
        var value = $('input[name="' + group.inputName + '"]:checked').val();
        return value || 'keep';
    }

    function updateFieldOptionsScopeHint(single, selectedEntries, visibleKeys) {
        var fieldTypes = {};
        selectedEntries.forEach(function (entry) {
            fieldTypes[getEntryFieldType(entry)] = true;
        });
        var typeCount = Object.keys(fieldTypes).length;
        if (single) {
            $('#fieldOptionsScopeHint').text('Showing options relevant for this ' + getEntryFieldType(single) + ' field.');
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

    function openOptionsPopup() {
        if (!hasSession()) return;
        var selected = P.state.selectedFieldNames || [];
        if (!selected.length) {
            P.Utils.toast('Select one or more fields first (Ctrl/Shift-click handles)', 'warning');
            return;
        }

        var selectedEntries = getSelectedFieldEntries();
        var single = selectedEntries.length === 1 ? selectedEntries[0] : null;
        var showKeepOption = !single;
        var visibleKeys = computeVisibleOptionKeys(selectedEntries);
        $('#fieldOptionsSelectionInfo').text(
            selected.length === 1
                ? ('Editing: ' + selected[0])
                : ('Editing ' + selected.length + ' fields (tri-state controls)')
        );

        Object.keys(TRI_STATE_GROUPS).forEach(function (optionName) {
            var group = TRI_STATE_GROUPS[optionName];
            setFieldConfigBlockVisible(group.blockSelector, !!visibleKeys[optionName]);
            if (visibleKeys[optionName]) {
                setTriStateControlValue(optionName, resolveTriState(selectedEntries, optionName), showKeepOption);
            }
        });

        var canEditSingleValue = !!single && !!visibleKeys.defaultValue;
        var canEditChoices = !!single && !!visibleKeys.choices;
        var canEditJavascript = !!single && !!visibleKeys.javascript;

        setFieldConfigBlockVisible('#optDefaultValueBlock', canEditSingleValue);
        setFieldConfigBlockVisible('#optChoicesBlock', canEditChoices);
        setFieldConfigBlockVisible('#optJavascriptBlock', canEditJavascript);

        $('#optDefaultValue').val(canEditSingleValue ? (readOptionValue(single, 'defaultValue') || readOptionValue(single, 'value') || '') : '');
        var choicesVal = canEditChoices ? readOptionValue(single, 'choices') : null;
        $('#optChoices').val(Array.isArray(choicesVal) ? choicesVal.join(',') : (choicesVal || ''));
        var javascriptValue = canEditJavascript ? (readOptionValue(single, 'javascript') || '') : '';
        $('#optJavascript').val(javascriptValue);

        initializeJsPresetUi('options');

        $('#optDefaultValue').prop('disabled', !canEditSingleValue);
        $('#optChoices').prop('disabled', !canEditChoices);
        $('#optJavascript').prop('disabled', !canEditJavascript);

        setOptionsJavascriptSectionExpanded(canEditJavascript, javascriptValue);

        updateFieldOptionsScopeHint(single, selectedEntries, visibleKeys);

        showFieldConfigModal('options');
    }

    function getJsPresetDefinitions() {
        return {
            custom: { title: 'Custom script', fields: [] },
            length: {
                title: 'Length (min/max)',
                fields: [
                    { key: 'min', label: 'Min length', type: 'number', placeholder: '0' },
                    { key: 'max', label: 'Max length', type: 'number', placeholder: '100' }
                ]
            },
            number: {
                title: 'Number (min/max)',
                fields: [
                    { key: 'min', label: 'Min number', type: 'number', placeholder: '0' },
                    { key: 'max', label: 'Max number', type: 'number', placeholder: '100' }
                ]
            },
            date: {
                title: 'Date (format)',
                fields: [
                    {
                        key: 'format',
                        label: 'Date format',
                        type: 'select',
                        options: [
                            { value: 'yyyy-mm-dd', label: 'YYYY-MM-DD' },
                            { value: 'dd.mm.yyyy', label: 'DD.MM.YYYY' },
                            { value: 'mm/dd/yyyy', label: 'MM/DD/YYYY' }
                        ]
                    }
                ]
            }
        };
    }

    function renderJsPresetParams(mode) {
        var def = JS_PRESET_DIALOGS[mode];
        if (!def) return;
        var $select = $(def.presetSelect);
        var $container = $(def.paramsContainer);
        if (!$select.length || !$container.length) return;

        var presetKey = $select.val() || 'custom';
        var presetDef = getJsPresetDefinitions()[presetKey] || getJsPresetDefinitions().custom;
        $container.empty();

        if (!presetDef.fields || !presetDef.fields.length) {
            $container.append('<div class="text-muted" style="font-size:12px;">Use the text area for custom validation JavaScript.</div>');
            return;
        }

        var html = '';
        presetDef.fields.forEach(function (field) {
            var inputId = mode + 'JsPreset_' + field.key;
            html += '<div class="mb-2">';
            html += '<label class="form-label form-label-sm" for="' + inputId + '">' + field.label + '</label>';
            if (field.type === 'select') {
                html += '<select class="form-select form-select-sm" id="' + inputId + '">';
                (field.options || []).forEach(function (opt) {
                    html += '<option value="' + opt.value + '">' + opt.label + '</option>';
                });
                html += '</select>';
            } else {
                html += '<input class="form-control form-control-sm" id="' + inputId + '" type="' + (field.type || 'text') + '" placeholder="' + (field.placeholder || '') + '">';
            }
            html += '</div>';
        });
        $container.html(html);
    }

    function collectJsPresetParams(mode) {
        var def = JS_PRESET_DIALOGS[mode];
        if (!def) return {};
        var presetKey = $(def.presetSelect).val() || 'custom';
        var presetDef = getJsPresetDefinitions()[presetKey] || getJsPresetDefinitions().custom;
        var params = {};
        (presetDef.fields || []).forEach(function (field) {
            var inputId = '#' + mode + 'JsPreset_' + field.key;
            params[field.key] = $(inputId).val();
        });
        return params;
    }

    function buildPresetScript(presetKey, params) {
        if (presetKey === 'length') {
            var minLen = params.min !== '' && params.min !== undefined ? Number(params.min) : null;
            var maxLen = params.max !== '' && params.max !== undefined ? Number(params.max) : null;
            var checks = [];
            if (!isNaN(minLen) && minLen !== null) checks.push('len < ' + minLen);
            if (!isNaN(maxLen) && maxLen !== null) checks.push('len > ' + maxLen);
            if (!checks.length) return '';
            return [
                'if (event.value !== null && event.value !== undefined && event.value !== "") {',
                '    var len = String(event.value).length;',
                '    if (' + checks.join(' || ') + ') {',
                '        app.alert("Invalid length.");',
                '        event.rc = false;',
                '    }',
                '}'
            ].join('\n');
        }

        if (presetKey === 'number') {
            var minNum = params.min !== '' && params.min !== undefined ? Number(params.min) : null;
            var maxNum = params.max !== '' && params.max !== undefined ? Number(params.max) : null;
            var numChecks = [];
            if (!isNaN(minNum) && minNum !== null) numChecks.push('num < ' + minNum);
            if (!isNaN(maxNum) && maxNum !== null) numChecks.push('num > ' + maxNum);
            return [
                'if (event.value !== null && event.value !== undefined && event.value !== "") {',
                '    var num = Number(event.value);',
                '    if (isNaN(num)' + (numChecks.length ? ' || ' + numChecks.join(' || ') : '') + ') {',
                '        app.alert("Please enter a valid number' + (numChecks.length ? ' in range' : '') + '.");',
                '        event.rc = false;',
                '    }',
                '}'
            ].join('\n');
        }

        if (presetKey === 'date') {
            var format = params.format || 'yyyy-mm-dd';
            var regex = '/^\\d{4}-\\d{2}-\\d{2}$/';
            var label = 'YYYY-MM-DD';
            if (format === 'dd.mm.yyyy') {
                regex = '/^\\d{2}\\.\\d{2}\\.\\d{4}$/';
                label = 'DD.MM.YYYY';
            } else if (format === 'mm/dd/yyyy') {
                regex = '/^\\d{2}\\/\\d{2}\\/\\d{4}$/';
                label = 'MM/DD/YYYY';
            }
            return [
                'if (event.value !== null && event.value !== undefined && event.value !== "") {',
                '    var pattern = ' + regex + ';',
                '    if (!pattern.test(String(event.value))) {',
                '        app.alert("Please enter date as ' + label + '.");',
                '        event.rc = false;',
                '    }',
                '}'
            ].join('\n');
        }

        return '';
    }

    function applyJsPresetToDialog(mode) {
        var def = JS_PRESET_DIALOGS[mode];
        if (!def) return;
        var presetKey = $(def.presetSelect).val() || 'custom';
        if (presetKey === 'custom') return;
        var script = buildPresetScript(presetKey, collectJsPresetParams(mode));
        if (!script) {
            P.Utils.toast('Please fill preset values first', 'warning');
            return;
        }
        $(def.scriptTarget).val(script);
        P.Utils.toast('Validation preset inserted', 'info');
    }

    function bindJsPresetControls(mode) {
        var def = JS_PRESET_DIALOGS[mode];
        if (!def) return;
        $(def.presetSelect).on('change', function () {
            renderJsPresetParams(mode);
        });
        $(def.applyButton).on('click', function () {
            applyJsPresetToDialog(mode);
        });
    }

    function initializeJsPresetUi(mode) {
        var def = JS_PRESET_DIALOGS[mode];
        if (!def) return;
        $(def.presetSelect).val('custom');
        renderJsPresetParams(mode);
    }

    function resolveTriState(entries, optionName) {
        if (!entries.length) return 'keep';
        var first = readBooleanOption(entries[0], optionName);
        for (var i = 1; i < entries.length; i++) {
            if (readBooleanOption(entries[i], optionName) !== first) return 'keep';
        }
        return first ? 'true' : 'false';
    }

    function readBooleanOption(entry, optionName) {
        var value = readOptionValue(entry, optionName);
        if (value === null || value === undefined) return false;
        if (typeof value === 'boolean') return value;
        return String(value).toLowerCase() === 'true';
    }

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
        if (entry.kind === 'persisted' && entry.name && pendingFieldOptionOverrides[entry.name]) {
            var overrideOptions = pendingFieldOptionOverrides[entry.name];
            if (overrideOptions[optionName] !== undefined) return overrideOptions[optionName];
        }
        var propertyName = mapPropertyName(optionName);
        if (entry.node && entry.node.properties && entry.node.properties[propertyName] !== undefined) {
            return entry.node.properties[propertyName];
        }
        return null;
    }

    function applyOptionOverridesToPersistedSelection(fieldNames, options) {
        if (!fieldNames || !fieldNames.length || !options) return;
        fieldNames.forEach(function (fieldName) {
            if (!fieldName) return;
            if (!pendingFieldOptionOverrides[fieldName]) pendingFieldOptionOverrides[fieldName] = {};
            var target = pendingFieldOptionOverrides[fieldName];

            ['required', 'readonly', 'multiline', 'editable', 'checked'].forEach(function (key) {
                if (options[key] !== null && options[key] !== undefined) {
                    target[key] = options[key];
                }
            });

            if (options.defaultValue !== undefined) target.defaultValue = options.defaultValue;
            if (options.choices !== undefined) target.choices = cloneObject(options.choices);
            if (options.javascript !== undefined) target.javascript = options.javascript;
        });
    }

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

    function parseTriStateValue(value) {
        if (value === 'true') return true;
        if (value === 'false') return false;
        return null;
    }

    function applyOptionsFromModal() {
        if (!hasSession()) return;
        var fieldNames = (P.state.selectedFieldNames || []).slice();
        if (!fieldNames.length) return;

        var options = {};
        if (isFieldConfigBlockVisible('#optRequiredBlock')) {
            options.required = parseTriStateValue(getTriStateControlValue('required'));
        }
        if (isFieldConfigBlockVisible('#optReadonlyBlock')) {
            options.readonly = parseTriStateValue(getTriStateControlValue('readonly'));
        }
        if (isFieldConfigBlockVisible('#optMultilineBlock')) {
            options.multiline = parseTriStateValue(getTriStateControlValue('multiline'));
        }
        if (isFieldConfigBlockVisible('#optEditableBlock')) {
            options.editable = parseTriStateValue(getTriStateControlValue('editable'));
        }
        if (isFieldConfigBlockVisible('#optCheckedBlock')) {
            options.checked = parseTriStateValue(getTriStateControlValue('checked'));
        }

        if (isFieldConfigBlockVisible('#optDefaultValueBlock') && !$('#optDefaultValue').prop('disabled')) {
            var defaultValue = $('#optDefaultValue').val();
            if (defaultValue) options.defaultValue = defaultValue;
        }
        if (isFieldConfigBlockVisible('#optChoicesBlock') && !$('#optChoices').prop('disabled')) {
            var choicesRaw = $('#optChoices').val();
            if (choicesRaw) {
                options.choices = choicesRaw.split(',').map(function (v) { return v.trim(); })
                    .filter(function (v) { return v.length > 0; });
            }
        }
        if (isFieldConfigBlockVisible('#optJavascriptBlock') && !$('#optJavascript').prop('disabled')) {
            options.javascript = $('#optJavascript').val() || '';
        }

        if (!Object.keys(options).length) {
            P.Utils.toast('No applicable options for selected fields', 'info');
            return;
        }

        var pendingNames = [];
        var persistedNames = [];
        fieldNames.forEach(function (name) {
            if (findPendingFormAdd(name)) pendingNames.push(name);
            else persistedNames.push(name);
        });

        pendingNames.forEach(function (name) {
            var pendingField = findPendingFormAdd(name);
            if (!pendingField) return;
            if (!pendingField.options) pendingField.options = {};
            ['required', 'readonly', 'multiline', 'editable', 'checked'].forEach(function (key) {
                if (options[key] !== null && options[key] !== undefined) pendingField.options[key] = options[key];
            });
            if (options.defaultValue !== undefined) pendingField.options.defaultValue = options.defaultValue;
            if (options.choices !== undefined) pendingField.options.choices = cloneObject(options.choices);
            if (options.javascript !== undefined) pendingField.options.javascript = options.javascript;
        });

        var finishLocalOptionsApply = function (toastMessage) {
            if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) {
                P.Tabs.switchTab(P.state.currentTab);
            }
            renderFieldHandlesForAllPages();
            updateSaveButton();
            hideFieldConfigModal('options');
            P.Utils.toast(toastMessage, 'success');
        };

        if (!persistedNames.length) {
            finishLocalOptionsApply('Pending field options updated');
            return;
        }

        if (!P.state.pendingFieldOptions) P.state.pendingFieldOptions = [];
        P.state.pendingFieldOptions.push({ fieldNames: persistedNames.slice(), options: cloneObject(options) });
        applyOptionOverridesToPersistedSelection(persistedNames, options);
        finishLocalOptionsApply(pendingNames.length
            ? 'Field options queued (saved + pending fields)'
            : 'Field options queued. Click Save to persist.');
    }

    function queueRectChange(fieldName, x, y, width, height) {
        var bounded = clampPdfRectForField(fieldName, x, y, width, height);
        x = bounded.x;
        y = bounded.y;
        width = bounded.width;
        height = bounded.height;

        var pendingField = findPendingFormAdd(fieldName);
        if (pendingField) {
            pendingField.x = x;
            pendingField.y = y;
            pendingField.width = width;
            pendingField.height = height;
            return;
        }
        var idx = P.state.pendingFieldRects.findIndex(function (r) {
            return r.fieldName === fieldName;
        });
        var payload = { fieldName: fieldName, x: x, y: y, width: width, height: height };
        if (idx >= 0) {
            P.state.pendingFieldRects[idx] = payload;
        } else {
            P.state.pendingFieldRects.push(payload);
        }
    }

    function clamp(value, min, max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    function clampPdfRectForField(fieldName, x, y, width, height) {
        var rectInfo = getCurrentFieldRectByName(fieldName);
        if (!rectInfo) {
            return { x: x, y: y, width: width, height: height };
        }
        var vp = P.state.pageViewports[rectInfo.pageIndex];
        if (!vp || !vp.scale) {
            return { x: x, y: y, width: width, height: height };
        }

        var pageWidthPdf = vp.width / vp.scale;
        var pageHeightPdf = vp.height / vp.scale;

        var safeW = clamp(width, 1, pageWidthPdf);
        var safeH = clamp(height, 1, pageHeightPdf);
        var safeX = clamp(x, 0, Math.max(0, pageWidthPdf - safeW));
        var safeY = clamp(y, 0, Math.max(0, pageHeightPdf - safeH));

        return { x: safeX, y: safeY, width: safeW, height: safeH };
    }

    /**
     * Delete a form field by fully-qualified name.
     */
    function deleteField(fieldName) {
        queueFieldDelete(fieldName);
    }

    function isFieldDeletePending(fieldName) {
        return !!(P.state.pendingFieldDeletes && P.state.pendingFieldDeletes[fieldName]);
    }

    function queueFieldDelete(fieldName) {
        if (!P.state.sessionId || !fieldName) return;

        var pendingIdx = findPendingFormAddIndex(fieldName);
        if (pendingIdx >= 0) {
            P.state.pendingFormAdds.splice(pendingIdx, 1);
            P.state.pendingFieldRects = (P.state.pendingFieldRects || []).filter(function (r) {
                return r.fieldName !== fieldName;
            });
            if (P.state.selectedFieldNames) {
                P.state.selectedFieldNames = P.state.selectedFieldNames.filter(function (n) {
                    return n !== fieldName;
                });
            }
            if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) {
                P.Tabs.switchTab(P.state.currentTab);
            }
            renderFieldHandlesForAllPages();
            updateSaveButton();
            P.Utils.toast('Pending field "' + fieldName + '" removed', 'info');
            return;
        }

        if (!P.state.pendingFieldDeletes) P.state.pendingFieldDeletes = {};
        if (P.state.pendingFieldDeletes[fieldName]) return;

        var timerId = window.setTimeout(function () {
            finalizeFieldDelete(fieldName);
        }, 5000);
        P.state.pendingFieldDeletes[fieldName] = { timerId: timerId };

        if (P.state.selectedFieldNames) {
            P.state.selectedFieldNames = P.state.selectedFieldNames.filter(function (n) {
                return n !== fieldName;
            });
        }
        renderFieldHandlesForAllPages();
        P.Utils.toast('Field "' + fieldName + '" marked for removal (Undo available)', 'warning');
    }

    function undoFieldDelete(fieldName) {
        if (!P.state.pendingFieldDeletes || !P.state.pendingFieldDeletes[fieldName]) return;
        window.clearTimeout(P.state.pendingFieldDeletes[fieldName].timerId);
        delete P.state.pendingFieldDeletes[fieldName];
        renderFieldHandlesForAllPages();
        P.Utils.toast('Removal cancelled for "' + fieldName + '"', 'info');
    }

    function finalizeFieldDelete(fieldName) {
        if (!P.state.sessionId) return;
        if (!P.state.pendingFieldDeletes || !P.state.pendingFieldDeletes[fieldName]) return;

        window.clearTimeout(P.state.pendingFieldDeletes[fieldName].timerId);
        delete P.state.pendingFieldDeletes[fieldName];

        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                          encodeURIComponent(fieldName), { method: 'DELETE' })
            .done(function (data) {
                P.state.treeData = data.tree;
                P.Tree.render(P.state.treeData);
                renderFieldHandlesForAllPages();
                P.Utils.toast('Field "' + fieldName + '" deleted', 'success');
            })
            .fail(function () {
                P.Utils.toast('Delete field failed', 'danger');
                renderFieldHandlesForAllPages();
            });
    }

    /**
     * Update a form field value by fully-qualified name.
     */
    function setFieldValue(fieldName, value) {
        if (!P.state.sessionId) return;
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                          encodeURIComponent(fieldName) + '/value', {
            method: 'POST', contentType: 'application/json',
            data: JSON.stringify({ value: value })
        })
        .done(function (data) {
            P.state.treeData = data.tree;
            P.Tree.render(P.state.treeData);
            P.Utils.toast('Field value updated', 'success');
        })
        .fail(function () { P.Utils.toast('Update field value failed', 'danger'); });
    }

    return {
        init: init,
        startDraw: startDraw,
        deleteField: deleteField,
        selectFieldFromViewer: selectFieldFromViewer,
        refreshFieldSelectionHighlights: refreshFieldSelectionHighlights,
        setFieldValue: setFieldValue,
        renderFieldHandles: renderFieldHandles,
        resetPending: resetPending,
        savePendingChanges: savePendingChanges,
        updateSaveButton: updateSaveButton
    };
})(jQuery, PDFalyzer);
