/**
 * PDFalyzer Studio – resource preview, download and attachment handling.
 */
PDFalyzer.Resource = (function ($, P) {
    'use strict';

    function firstDefined(obj, keys) {
        if (!obj) return null;
        for (var i = 0; i < keys.length; i += 1) {
            var key = keys[i];
            if (obj[key] !== undefined && obj[key] !== null && obj[key] !== '') return obj[key];
        }
        return null;
    }

    function parsePositiveNumber(value) {
        var parsed = Number(value);
        return isFinite(parsed) && parsed > 0 ? Math.round(parsed) : null;
    }

    function cleanPdfName(value) {
        if (value === undefined || value === null) return '';
        return String(value).replace(/^\//, '');
    }

    function detectImageType(contentType, fileName, imageProps) {
        var ct = (contentType || '').toLowerCase();
        if (ct.indexOf('image/') === 0) {
            return ct.split('/')[1].split(';')[0].trim() || 'image';
        }

        var name = String(fileName || '');
        var extMatch = name.match(/\.([a-z0-9]+)$/i);
        if (extMatch) return extMatch[1].toLowerCase();

        var filter = cleanPdfName(firstDefined(imageProps, ['Filter', 'filter'])).toLowerCase();
        var filterMap = {
            dctdecode: 'jpg',
            jpxdecode: 'jpx',
            jbig2decode: 'jbig2',
            flatedecode: 'png',
            ccittfaxdecode: 'tiff'
        };
        return filterMap[filter] || 'image';
    }

    function createMetaRow(label, value) {
        return $('<div>', { 'class': 'image-meta-row' })
            .append($('<span>', { 'class': 'image-meta-label', text: label }))
            .append($('<span>', { 'class': 'image-meta-value', text: value || 'n/a' }));
    }

    function buildImageMetaBox(meta) {
        var $box = $('<div>', { 'class': 'image-meta-box image-meta-box-modal' });
        $box.append($('<div>', { 'class': 'image-meta-title', text: 'Image Info' }));
        $box
            .append(createMetaRow('Type', meta.type))
            .append(createMetaRow('File size', meta.sizeText))
            .append(createMetaRow('Pixel size', meta.pixelSize));

        if (meta.filter) $box.append(createMetaRow('Filter', meta.filter));
        if (meta.colorSpace) $box.append(createMetaRow('Color space', meta.colorSpace));
        if (meta.bitsPerComponent) $box.append(createMetaRow('Bits/component', meta.bitsPerComponent));
        if (meta.objectRef) $box.append(createMetaRow('Object', meta.objectRef));
        return $box;
    }

    function preview(url, context) {
        var previewContext = context || {};
        var imageProps = previewContext.properties || null;
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
                $body.removeClass('resource-preview-image');
                if (ct.startsWith('image/')) {
                    var objUrl = URL.createObjectURL(r.blob);
                    $body.addClass('resource-preview-image');
                    var $imageWrap = $('<div>', { 'class': 'resource-preview-image-wrap' });
                    var $img = $('<img>', {
                        src: objUrl,
                        alt: 'Resource image preview',
                        css: { 'max-width': '100%', height: 'auto' }
                    });
                    $imageWrap.append($img);
                    $body.append($imageWrap);

                    var fileType = detectImageType(ct, previewContext.name, imageProps);
                    var fileSizeText = P.Utils.formatBytes(r.blob.size) + ' (' + r.blob.size + ' B)';
                    var fallbackWidth = parsePositiveNumber(firstDefined(imageProps, ['Width', 'width']));
                    var fallbackHeight = parsePositiveNumber(firstDefined(imageProps, ['Height', 'height']));
                    var bitsPerComponent = firstDefined(imageProps, ['BitsPerComponent', 'bitsPerComponent']);
                    var colorSpace = cleanPdfName(firstDefined(imageProps, ['ColorSpace', 'colorSpace']));
                    var filter = cleanPdfName(firstDefined(imageProps, ['Filter', 'filter']));
                    var objectRef = (previewContext.objectNumber >= 0)
                        ? (String(previewContext.objectNumber) + ' ' + String(previewContext.generationNumber || 0) + ' R')
                        : '';

                    var renderMeta = function (width, height) {
                        var pixelWidth = parsePositiveNumber(width) || fallbackWidth;
                        var pixelHeight = parsePositiveNumber(height) || fallbackHeight;
                        var pixelSize = (pixelWidth && pixelHeight)
                            ? (pixelWidth + ' × ' + pixelHeight)
                            : 'n/a';

                        var $meta = buildImageMetaBox({
                            type: fileType,
                            sizeText: fileSizeText,
                            pixelSize: pixelSize,
                            filter: filter,
                            colorSpace: colorSpace,
                            bitsPerComponent: bitsPerComponent != null ? String(bitsPerComponent) : '',
                            objectRef: objectRef
                        });
                        $body.append($meta);
                    };

                    $img.on('load', function () {
                        renderMeta(this.naturalWidth, this.naturalHeight);
                    });
                    $img.on('error', function () {
                        renderMeta(null, null);
                    });

                    $('#resourcePreviewLabelText').text('Image Preview');
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
                    $('#resourcePreviewLabelText').text('Text Preview');
                } else {
                    window.open(url, '_blank');
                    return;
                }
                var modalEl = document.getElementById('resourcePreviewModal');
                if (P.Utils && P.Utils.prepareModal) {
                    P.Utils.prepareModal(modalEl);
                }
                modalEl.__pdfalyzerReturnFocusEl = document.activeElement;
                bootstrap.Modal.getOrCreateInstance(modalEl).show();
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
