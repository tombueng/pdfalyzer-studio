/**
 * PDFalyzer Studio – COS attribute inline editor (edit / add-entry / delete).
 */
PDFalyzer.CosEditor = (function ($, P) {
    'use strict';

    function ensurePendingCosChanges() {
        if (!P.state.pendingCosChanges) P.state.pendingCosChanges = [];
    }

    function parseKeyPath(node) {
        try { return JSON.parse(node.keyPath); }
        catch (e) { return null; }
    }

    function resolveTargetScope(node) {
        if (!node || !node.id) return null;
        return String(node.id).indexOf('docinfo') === 0 ? 'docinfo' : null;
    }

    function buildPathText(path) {
        if (!path || !path.length) return '(root)';
        return '/' + path.join('/');
    }

    function queuePendingCosChange(change) {
        ensurePendingCosChanges();
        P.state.pendingCosChanges.push(change);

        if (P.EditMode && P.EditMode.updateSaveButton) {
            P.EditMode.updateSaveButton();
        }
        if (P.Tree && P.Tree.refreshPendingPanel) {
            P.Tree.refreshPendingPanel();
        }
    }

    // ======================== SHOW EDIT WIDGET ========================

    function show(node, $headerEl) {
        $('.cos-inline-editor').remove();
        var $editor = $('<div>', { 'class': 'cos-inline-editor' });
        var $input;

        switch (node.valueType) {
            case 'boolean':
                $input = $('<select>', { 'class': 'cos-edit-input' })
                    .append('<option value="true">true</option><option value="false">false</option>')
                    .val(node.rawValue);
                break;
            case 'integer':
                $input = $('<input>', { 'class': 'cos-edit-input', type: 'number', step: '1', value: node.rawValue });
                break;
            case 'float':
                $input = $('<input>', { 'class': 'cos-edit-input', type: 'number', step: 'any', value: node.rawValue });
                break;
            case 'name':
                $editor.append($('<span>', { 'class': 'cos-name-prefix', text: '/' }));
                $input = $('<input>', { 'class': 'cos-edit-input', type: 'text', value: node.rawValue });
                break;
            default:
                $input = $('<input>', { 'class': 'cos-edit-input', type: 'text', value: node.rawValue || '' });
        }
        $editor.append($input);

        var $saveBtn = $('<button>', { 'class': 'cos-edit-save', title: 'Save',
                                       html: '<i class="fas fa-check"></i>' })
            .on('click', function () { save(node, $input.val(), $editor); });
        var $cancelBtn = $('<button>', { 'class': 'cos-edit-cancel', title: 'Cancel',
                                          html: '<i class="fas fa-times"></i>' })
            .on('click', function () { $editor.remove(); });
        $editor.append($saveBtn, $cancelBtn);

        $headerEl.after($editor);
        $input.trigger('focus').trigger('select');
        $input.on('keydown', function (e) {
            if (e.key === 'Enter')  { e.preventDefault(); $saveBtn.trigger('click'); }
            if (e.key === 'Escape') { e.preventDefault(); $cancelBtn.trigger('click'); }
            e.stopPropagation();
        });
    }

    // ======================== SAVE VALUE ========================

    function save(node, newValue, $editorEl) {
        if (!P.state.sessionId) {
            P.Utils.toast('Cannot edit: session not available', 'warning');
            return;
        }
        var keyPath = parseKeyPath(node);
        if (!keyPath) { P.Utils.toast('Cannot edit: no key path', 'warning'); return; }
        if (!keyPath || keyPath.length === 0) {
            P.Utils.toast('Cannot edit: empty key path', 'warning');
            return;
        }
        queuePendingCosChange({
            operation: 'update',
            summary: 'Update ' + buildPathText(keyPath),
            request: {
                objectNumber: (node.objectNumber === undefined || node.objectNumber === null) ? -1 : node.objectNumber,
                generationNumber: node.generationNumber || 0,
                keyPath: keyPath,
                newValue: newValue,
                valueType: node.cosType,
                operation: 'update',
                targetScope: resolveTargetScope(node)
            }
        });

        P.Utils.toast('COS change queued. Click Save to persist to PDF.', 'info');
        $editorEl.remove();
    }

    // ======================== ADD ENTRY ========================

    function addEntry(node, $headerEl) {
        if (!P.state.sessionId || !node.keyPath) {
            P.Utils.toast('Cannot add entry: key path unavailable', 'warning');
            return;
        }
        $('.cos-inline-editor, .cos-add-editor').remove();
        var path = parseKeyPath(node);
        if (!path) {
            P.Utils.toast('Cannot add entry: key path unavailable', 'warning');
            return;
        }
        var isArray = node.cosType === 'COSArray';
        var $form   = $('<div>', { 'class': 'cos-add-editor' });

        var $keyInput = $('<input>', {
            'class': 'cos-edit-input',
            type: isArray ? 'number' : 'text',
            placeholder: isArray ? 'index (blank=append)' : 'new key'
        });
        if (isArray) $keyInput.attr('min', '0');
        $form.append($keyInput);

        var $typeSelect = $('<select>', { 'class': 'cos-edit-input' });
        ['boolean', 'integer', 'float', 'name', 'string', 'hex-string', 'dictionary', 'array']
            .forEach(function (t) {
                var v = 'COS' + t.charAt(0).toUpperCase() + t.slice(1);
                $typeSelect.append($('<option>', { value: v, text: t }));
            });
        $form.append($typeSelect);

        var $valueInput = $('<input>', { 'class': 'cos-edit-input', type: 'text', placeholder: 'value' });
        $form.append($valueInput);
        $typeSelect.on('change', function () {
            var t = $(this).val();
            $valueInput.toggle(t !== 'COSDictionary' && t !== 'COSArray');
        });

        var $saveBtn = $('<button>', { 'class': 'cos-edit-save', title: 'Add',
                                       html: '<i class="fas fa-check"></i>' })
            .on('click', function () {
                var key = $keyInput.val();
                if (!isArray && (!key || !key.trim())) {
                    P.Utils.toast('Key cannot be empty', 'warning'); return;
                }
                if (!isArray) {
                    var v = P.DictEditor.validateAddEntry(node, key);
                    if (!v.valid) { P.Utils.toast(v.message, 'warning'); return; }
                }
                var type = $typeSelect.val();
                var val  = (type === 'COSDictionary' || type === 'COSArray') ? '' : $valueInput.val();
                var targetPath = path.concat([key]);
                queuePendingCosChange({
                    operation: 'add',
                    summary: 'Add ' + buildPathText(targetPath),
                    request: {
                        objectNumber: (node.objectNumber === undefined || node.objectNumber === null) ? -1 : node.objectNumber,
                        generationNumber: node.generationNumber || 0,
                        keyPath: targetPath,
                        newValue: val,
                        valueType: type,
                        operation: 'add',
                        targetScope: resolveTargetScope(node)
                    }
                });
                P.Utils.toast('COS change queued. Click Save to persist to PDF.', 'info');
                $form.remove();
            });
        var $cancelBtn = $('<button>', { 'class': 'cos-edit-cancel', title: 'Cancel',
                                          html: '<i class="fas fa-times"></i>' })
            .on('click', function () { $form.remove(); });
        $form.append($saveBtn, $cancelBtn);

        ($headerEl && $headerEl.length ? $headerEl : $('.tree-node-header').last()).after($form);
        $keyInput.trigger('focus');
    }

    // ======================== REMOVE ENTRY ========================

    function remove(node, $headerEl) {
        if (!P.state.sessionId) {
            P.Utils.toast('Cannot delete: session not available', 'warning');
            return;
        }
        var keyPath = parseKeyPath(node);
        if (!keyPath) { P.Utils.toast('Cannot delete: no key path', 'warning'); return; }
        if (!keyPath || keyPath.length === 0) {
            P.Utils.toast('Cannot delete: empty key path', 'warning');
            return;
        }

        var warningMsg = '';
        if (node.name && node.name.startsWith('/')) {
            var keyName = node.name.substring(1);
            if (['Type', 'Pages', 'Kids', 'Count', 'Parent', 'MediaBox', 'Fields']
                    .indexOf(keyName) >= 0) {
                warningMsg = ' This may be a required key!';
            }
        }

        $('.cos-inline-editor, .cos-add-editor, .cos-delete-confirm').remove();
        var isDictOrArray = node.cosType === 'COSDictionary' || node.cosType === 'COSArray' ||
                            node.cosType === 'COSStream';
        var confirmText = isDictOrArray
            ? 'Delete entire ' + (node.cosType === 'COSArray' ? 'array' : 'dictionary') +
              ' "' + node.name + '"?' + warningMsg
            : 'Delete ' + node.name + '?' + warningMsg;

        var $confirm = $('<div>', { 'class': 'cos-delete-confirm' }).text(confirmText + ' ');
        $('<button>', { 'class': 'btn btn-sm btn-danger me-1', text: 'Yes' })
            .on('click', function () {
                queuePendingCosChange({
                    operation: 'remove',
                    summary: 'Remove ' + buildPathText(keyPath),
                    request: {
                        objectNumber: (node.objectNumber === undefined || node.objectNumber === null) ? -1 : node.objectNumber,
                        generationNumber: node.generationNumber || 0,
                        keyPath: keyPath,
                        operation: 'remove',
                        targetScope: resolveTargetScope(node)
                    }
                });
                P.Utils.toast('COS change queued. Click Save to persist to PDF.', 'info');
                $confirm.remove();
            })
            .appendTo($confirm);
        $('<button>', { 'class': 'btn btn-sm btn-secondary', text: 'No' })
            .on('click', function () { $confirm.remove(); })
            .appendTo($confirm);

        ($headerEl && $headerEl.length ? $headerEl : $('.tree-node-header').last()).after($confirm);
    }

    return { show: show, save: save, addEntry: addEntry, remove: remove };
})(jQuery, PDFalyzer);
