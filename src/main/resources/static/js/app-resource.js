/**
 * PDFalyzer Studio – resource preview, download and attachment handling.
 */
PDFalyzer.Resource = (function ($, P) {
    'use strict';

    function preview(url) {
        P.Utils.showLoading();
        fetch(url)
            .then(function (resp) {
                P.Utils.hideLoading();
                var ct = resp.headers.get('content-type') || '';
                return resp.blob().then(function (blob) { return { ct: ct, blob: blob }; });
            })
            .then(function (r) {
                var ct = r.ct.toLowerCase();
                var $body = $('#resourcePreviewBody').empty();
                if (ct.startsWith('image/')) {
                    var objUrl = URL.createObjectURL(r.blob);
                    $body.append($('<img>', { src: objUrl, css: { 'max-width': '100%', height: 'auto' } }));
                    $('#resourcePreviewLabel').text('Image Preview');
                } else if (ct.indexOf('xml') !== -1 || ct.indexOf('json') !== -1 || ct.startsWith('text/')) {
                    var reader = new FileReader();
                    reader.onload = function (evt) {
                        $body.append(
                            $('<pre>').text(evt.target.result)
                                .css({ 'max-height': '400px', overflow: 'auto',
                                       background: '#1e1e1e', padding: '10px',
                                       'border-radius': '4px', color: '#d4d4d4' })
                        );
                    };
                    reader.readAsText(r.blob);
                    $('#resourcePreviewLabel').text('Text Preview');
                } else {
                    window.open(url, '_blank');
                    return;
                }
                new bootstrap.Modal(document.getElementById('resourcePreviewModal')).show();
            })
            .catch(function () {
                P.Utils.hideLoading();
                P.Utils.toast('Could not preview resource', 'danger');
            });
    }

    function deleteResource(node, $headerEl) {
        if (!P.state.sessionId || node.objectNumber === undefined || node.objectNumber < 0) {
            P.Utils.toast('Cannot delete: object reference not available', 'warning');
            return;
        }
        if (!node.keyPath) {
            P.Utils.toast('Cannot delete: no key path available', 'warning');
            return;
        }
        var keyPath;
        try { keyPath = JSON.parse(node.keyPath); }
        catch (e) { P.Utils.toast('Cannot delete: invalid key path', 'warning'); return; }

        $('.cos-inline-editor, .cos-add-editor, .cos-delete-confirm').remove();
        var resourceType = node.nodeCategory === 'image' ? 'image' : 'resource';
        var $confirm = $('<div>', { 'class': 'cos-delete-confirm' })
            .text('Delete ' + resourceType + ' "' + node.name + '"? ');

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
                    P.Utils.refreshAfterMutation(data.tree);
                    P.Utils.toast(resourceType.charAt(0).toUpperCase() +
                                  resourceType.slice(1) + ' deleted', 'success');
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

    function downloadAttachment(sessionId, fileName) {
        window.open('/api/attachment/' + sessionId + '/' + encodeURIComponent(fileName), '_blank');
    }

    return { preview: preview, deleteResource: deleteResource,
             downloadAttachment: downloadAttachment };
})(jQuery, PDFalyzer);
