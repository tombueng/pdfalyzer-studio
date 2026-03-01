/**
 * PDFalyzer – COS attribute inline editor (edit / add-entry / delete).
 */
PDFalyzer.CosEditor = (function ($, P) {
    'use strict';

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
        if (!P.state.sessionId || node.objectNumber === undefined || node.objectNumber < 0) {
            P.Utils.toast('Cannot edit: object reference not available', 'warning');
            return;
        }
        var keyPath;
        try { keyPath = JSON.parse(node.keyPath); }
        catch (e) { P.Utils.toast('Cannot edit: no key path', 'warning'); return; }
        if (!keyPath || keyPath.length === 0) {
            P.Utils.toast('Cannot edit: empty key path', 'warning');
            return;
        }
        P.Utils.apiFetch('/api/cos/' + P.state.sessionId + '/update', {
            method: 'POST', contentType: 'application/json',
            data: JSON.stringify({
                objectNumber: node.objectNumber,
                generationNumber: node.generationNumber || 0,
                keyPath: keyPath,
                newValue: newValue,
                valueType: node.cosType,
                operation: 'update'
            })
        })
        .done(function (data) {
            P.state.treeData = data.tree;
            P.Tree.render(P.state.treeData);
            P.Viewer.loadPdf(P.state.sessionId);
            P.Utils.toast('Value updated successfully', 'success');
        })
        .fail(function () { P.Utils.toast('Edit failed', 'danger'); })
        .always(function () { $editorEl.remove(); });
    }

    // ======================== ADD ENTRY ========================

    function addEntry(node, $headerEl) {
        if (!P.state.sessionId || !node.keyPath) {
            P.Utils.toast('Cannot add entry: key path unavailable', 'warning');
            return;
        }
        $('.cos-inline-editor, .cos-add-editor').remove();
        var path    = JSON.parse(node.keyPath);
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
                P.Utils.apiFetch('/api/cos/' + P.state.sessionId + '/update', {
                    method: 'POST', contentType: 'application/json',
                    data: JSON.stringify({
                        objectNumber: node.objectNumber,
                        generationNumber: node.generationNumber || 0,
                        keyPath: path.concat([key]),
                        newValue: val,
                        valueType: type,
                        operation: 'add'
                    })
                })
                .done(function (data) {
                    P.state.treeData = data.tree;
                    P.Tree.render(P.state.treeData);
                    P.Viewer.loadPdf(P.state.sessionId);
                    P.Utils.toast('Entry added', 'success');
                })
                .fail(function () { P.Utils.toast('Add failed', 'danger'); });
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
        if (!P.state.sessionId || node.objectNumber === undefined || node.objectNumber < 0) {
            P.Utils.toast('Cannot delete: object reference not available', 'warning');
            return;
        }
        var keyPath;
        try { keyPath = JSON.parse(node.keyPath); }
        catch (e) { P.Utils.toast('Cannot delete: no key path', 'warning'); return; }
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
                P.Utils.apiFetch('/api/cos/' + P.state.sessionId + '/update', {
                    method: 'POST', contentType: 'application/json',
                    data: JSON.stringify({
                        objectNumber: node.objectNumber,
                        generationNumber: node.generationNumber || 0,
                        keyPath: keyPath,
                        operation: 'remove'
                    })
                })
                .done(function (data) {
                    P.state.treeData = data.tree;
                    P.Tree.render(P.state.treeData);
                    P.Viewer.loadPdf(P.state.sessionId);
                    P.Utils.toast('Entry removed', 'success');
                })
                .fail(function () { P.Utils.toast('Delete failed', 'danger'); });
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
