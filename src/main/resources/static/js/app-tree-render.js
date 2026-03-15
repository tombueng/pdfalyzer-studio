/**
 * PDFalyzer Studio – Tree node element builder and image preview utilities.
 */
PDFalyzer.TreeRender = (function ($, P) {
    'use strict';

    var COST_TYPE_CLASSES = {
        COSNull: 'cos-cosnull', COSBoolean: 'cos-cosboolean', COSInteger: 'cos-cosinteger',
        COSFloat: 'cos-cosfloat', COSString: 'cos-cosstring', COSName: 'cos-cosname',
        COSArray: 'cos-cosarray', COSDictionary: 'cos-cosdictionary',
        COSStream: 'cos-cosstream', COSObject: 'cos-cosobject', Operator: 'cos-operator'
    };

    // ======================== NODE ELEMENT BUILDER ========================

    function buildNodeEl(node, depth) {
        var $wrapper = $('<div>', {
            'class': 'tree-node',
            'data-node-id': node.id,
            'data-depth': depth
        });
        $wrapper.data('node-model', node);
        var $header  = $('<div>', { 'class': 'tree-node-header' });
        if (node.nodeCategory === 'field' && node.properties && node.properties.FullName) {
            $header.attr('data-field-name', node.properties.FullName);
        }
        $wrapper.append($header);

        // Toggle
        var hasChildren = node.children && node.children.length > 0;
        var shouldAutoExpand = hasChildren && depth === 0;
        var $toggle = $('<span>', { 'class': 'tree-toggle' });
        if (hasChildren) {
            $toggle.html('<i class="fas ' + (shouldAutoExpand ? 'fa-chevron-down' : 'fa-chevron-right') + '"></i>');
        }
        $header.append($toggle);

        // Icon
        if (node.icon) {
            $header.append($('<i>', { 'class': 'fas ' + node.icon + ' tree-icon',
                                       css: { color: node.color || '' } }));
        }

        // Label with search highlight
        var labelText = node.name || '(unnamed)';
        var $label = $('<span>', { 'class': 'tree-label', title: labelText });
        var searchQuery = $('#searchInput').val().trim();
        if (searchQuery && labelText.toLowerCase().indexOf(searchQuery.toLowerCase()) >= 0) {
            var idx = labelText.toLowerCase().indexOf(searchQuery.toLowerCase());
            $label.html(P.Utils.escapeHtml(labelText.substring(0, idx)) +
                '<mark>' + P.Utils.escapeHtml(labelText.substring(idx, idx + searchQuery.length)) + '</mark>' +
                P.Utils.escapeHtml(labelText.substring(idx + searchQuery.length)));
        } else {
            $label.text(labelText);
        }
        $header.append($label);

        // DSS cross-reference badge (numbered, colored)
        if (node.badge) {
            var badges = node.badge.split(',');
            for (var bi = 0; bi < badges.length; bi++) {
                var isNumeric = /^\d+$/.test(badges[bi]);
                var $badge = $('<span>', {
                    'class': 'dss-ref-badge',
                    text: badges[bi],
                    title: isNumeric ? 'DSS cross-reference #' + badges[bi] : badges[bi]
                });
                if (node.badgeColor) {
                    $badge.css({ 'background-color': node.badgeColor, color: '#fff' });
                }
                $header.append($badge);
            }
        }

        // COS type badge
        if (node.cosType) {
            var badgeClass = COST_TYPE_CLASSES[node.cosType] || '';
            $header.append($('<span>', { 'class': 'cos-type-badge ' + badgeClass,
                                          text: node.cosType.replace('COS', '') }));
        }

        // Action buttons
        appendActionButtons($header, node);

        // Click → expand / select / highlight
        $header.on('click', function (e) {
            if ($(e.target).closest('button, a').length) return;
            var additive = !!(e.ctrlKey || e.metaKey || e.shiftKey);
            var suppressPropertiesPanel = false;
            if (hasChildren) {
                var isExpanding = $toggle.find('i').hasClass('fa-chevron-right');
                $toggle.find('i').toggleClass('fa-chevron-right fa-chevron-down');
                $childrenEl.toggle();

                if (isExpanding) {
                    P.Tree.ensureChildrenRendered($childrenEl, node, depth);
                } else {
                    $header.siblings('.node-properties').remove();
                    $childrenEl.find('.node-properties').remove();
                    suppressPropertiesPanel = true;
                }
            }
            P.Tree.selectNode(node, additive, false, suppressPropertiesPanel);
        });

        // Children container (lazy, initially hidden)
        var $childrenEl = $('<div>', {
            'class': 'tree-children',
            css: { display: shouldAutoExpand ? 'block' : 'none' }
        });
        $wrapper.append($childrenEl);

        if (shouldAutoExpand) {
            node.children.forEach(function (c) {
                $childrenEl.append(buildNodeEl(c, depth + 1));
            });
        }

        return $wrapper;
    }

    // ======================== FILETYPE LABEL HELPER ========================

    function microFiletype(label) {
        return '<span class="dl-filetype">' + label + '</span>';
    }

    function guessResourceFiletype(node) {
        if (node.nodeCategory === 'image') {
            var filter = (node.properties && (node.properties.Filter || node.properties.filter)) || '';
            filter = String(filter).replace(/^\//, '').toLowerCase();
            var map = { dctdecode: 'JPG', jpxdecode: 'JPX', jbig2decode: 'JBIG2', flatedecode: 'PNG', ccittfaxdecode: 'TIFF' };
            return map[filter] || 'IMG';
        }
        if (node.cosType === 'COSStream') return 'BIN';
        return '';
    }

    function guessAttachmentFiletype(fileName) {
        if (!fileName) return '';
        var ext = String(fileName).match(/\.([a-z0-9]+)$/i);
        return ext ? ext[1].toUpperCase() : '';
    }

    // ======================== IMAGE TOOLTIP HELPERS ========================

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

    function buildImageMetaBox(meta, variantClass) {
        var boxClass = variantClass ? ('image-meta-box ' + variantClass) : 'image-meta-box';
        var $box = $('<div>', { 'class': boxClass });
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

    // ======================== ACTION BUTTONS ========================

    function appendActionButtons($header, node) {
        // COS edit button (pencil)
        if (node.editable && node.rawValue !== undefined && node.rawValue !== null) {
            $('<button>', { 'class': 'cos-edit-btn', title: 'Edit value',
                             html: '<i class="fas fa-pen"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.CosEditor.show(node, $header);
                })
                .appendTo($header);
        }

        // COS add-entry button (plus) – for containers
        if ((node.cosType === 'COSDictionary' || node.cosType === 'COSArray' ||
             node.cosType === 'COSStream') && node.keyPath) {
            $('<button>', { 'class': 'cos-add-btn', title: 'Add entry',
                             html: '<i class="fas fa-plus"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.CosEditor.addEntry(node, $header);
                })
                .appendTo($header);
        }

        // COS delete button (trash) – for named dictionary/array entries
        if (node.name && (/^[\/]|^\[/.test(node.name)) && node.keyPath) {
            $('<button>', { 'class': 'cos-delete-btn', title: 'Delete entry',
                             html: '<i class="fas fa-trash"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.CosEditor.remove(node, $header);
                })
                .appendTo($header);
        }

        // Image / stream resource buttons
        if (node.nodeCategory !== 'attachment' &&
            (node.nodeCategory === 'image' || node.cosType === 'COSStream') &&
                node.objectNumber >= 0) {
            var resourceUrl = '/api/resource/' + P.state.sessionId + '/' +
                              node.objectNumber + '/' + (node.generationNumber || 0);
            if (node.keyPath) {
                resourceUrl += '?keyPath=' + encodeURIComponent(node.keyPath);
            }
            $('<button>', { 'class': 'resource-open-btn', title: 'Preview resource',
                             html: '<i class="fas fa-eye"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.Resource.preview(resourceUrl + (node.keyPath ? '&inline=true' : '?inline=true'), {
                        name: node.name || '',
                        properties: node.properties || null,
                        objectNumber: node.objectNumber,
                        generationNumber: node.generationNumber || 0
                    });
                })
                .appendTo($header);

            // Add image hover tooltip preview
            if (node.nodeCategory === 'image') {
                $header.on('mouseenter', function() {
                    $('.image-tooltip-preview').each(function () {
                        var staleObjectUrl = $(this).data('objectUrl');
                        if (staleObjectUrl) URL.revokeObjectURL(staleObjectUrl);
                    }).remove();
                    $(document).off('mousemove.imgtooltip');

                    var $tooltip = $('<div>', { 'class': 'image-tooltip-preview' });
                    var $media = $('<div>', { 'class': 'image-tooltip-media' });
                    var $img = $('<img>', { alt: 'Image preview' });
                    var $meta = $('<div>', { 'class': 'image-meta-box image-meta-box-tooltip' })
                        .append($('<div>', { 'class': 'image-meta-title', text: 'Image Info' }))
                        .append(createMetaRow('Status', 'Loading...'));
                    $media.append($img);
                    $tooltip.append($media).append($meta).appendTo('body');

                    var closed = false;
                    var lifeTimer = null;

                    function cleanupTooltip() {
                        if (closed) return;
                        closed = true;
                        if (lifeTimer) {
                            window.clearInterval(lifeTimer);
                            lifeTimer = null;
                        }
                        var objectUrl = $tooltip.data('objectUrl');
                        if (objectUrl) URL.revokeObjectURL(objectUrl);
                        $tooltip.remove();
                        $(document).off('mousemove.imgtooltip');
                    }

                    function sourceIsAliveAndVisible() {
                        return document.body.contains($header[0]) && $header.is(':visible');
                    }

                    var updatePos = function(ev) {
                        if (!sourceIsAliveAndVisible() || !document.body.contains($tooltip[0])) {
                            cleanupTooltip();
                            return;
                        }
                        $tooltip.css({ left: (ev.pageX + 15) + 'px', top: (ev.pageY + 15) + 'px' });
                    };
                    var inlineUrl = resourceUrl + (node.keyPath ? '&inline=true' : '?inline=true');
                    var imageProps = node.properties || null;
                    var fallbackWidth = parsePositiveNumber(firstDefined(imageProps, ['Width', 'width']));
                    var fallbackHeight = parsePositiveNumber(firstDefined(imageProps, ['Height', 'height']));
                    var bitsPerComponent = firstDefined(imageProps, ['BitsPerComponent', 'bitsPerComponent']);
                    var colorSpace = cleanPdfName(firstDefined(imageProps, ['ColorSpace', 'colorSpace']));
                    var filter = cleanPdfName(firstDefined(imageProps, ['Filter', 'filter']));
                    var objectRef = (node.objectNumber >= 0)
                        ? (String(node.objectNumber) + ' ' + String(node.generationNumber || 0) + ' R')
                        : '';

                    fetch(inlineUrl)
                        .then(function (resp) {
                            var ct = resp.headers.get('content-type') || '';
                            return resp.blob().then(function (blob) { return { ct: ct, blob: blob }; });
                        })
                        .then(function (r) {
                            if (!sourceIsAliveAndVisible() || !document.body.contains($tooltip[0])) {
                                cleanupTooltip();
                                return;
                            }

                            var fileType = detectImageType(r.ct, node.name || '', imageProps);
                            var fileSizeText = P.Utils.formatBytes(r.blob.size) + ' (' + r.blob.size + ' B)';
                            var objUrl = URL.createObjectURL(r.blob);
                            $tooltip.data('objectUrl', objUrl);
                            $img.attr('src', objUrl);

                            var renderMeta = function (width, height) {
                                var pixelWidth = parsePositiveNumber(width) || fallbackWidth;
                                var pixelHeight = parsePositiveNumber(height) || fallbackHeight;
                                var pixelSize = (pixelWidth && pixelHeight)
                                    ? (pixelWidth + ' \u00d7 ' + pixelHeight)
                                    : 'n/a';
                                $meta.replaceWith(buildImageMetaBox({
                                    type: fileType,
                                    sizeText: fileSizeText,
                                    pixelSize: pixelSize,
                                    filter: filter,
                                    colorSpace: colorSpace,
                                    bitsPerComponent: bitsPerComponent != null ? String(bitsPerComponent) : '',
                                    objectRef: objectRef
                                }, 'image-meta-box-tooltip'));
                            };

                            $img.on('load', function () {
                                renderMeta(this.naturalWidth, this.naturalHeight);
                            });
                            $img.on('error', function () {
                                renderMeta(null, null);
                            });
                        })
                        .catch(function () {
                            if (!sourceIsAliveAndVisible() || !document.body.contains($tooltip[0])) {
                                cleanupTooltip();
                                return;
                            }
                            $meta.replaceWith(buildImageMetaBox({
                                type: detectImageType('', node.name || '', imageProps),
                                sizeText: 'n/a',
                                pixelSize: 'n/a',
                                filter: filter,
                                colorSpace: colorSpace,
                                bitsPerComponent: bitsPerComponent != null ? String(bitsPerComponent) : '',
                                objectRef: objectRef
                            }, 'image-meta-box-tooltip'));
                        });

                    $(document).on('mousemove.imgtooltip', updatePos);
                    lifeTimer = window.setInterval(function () {
                        if (!sourceIsAliveAndVisible() || !document.body.contains($tooltip[0])) {
                            cleanupTooltip();
                        }
                    }, 120);
                }).on('mouseleave', function() {
                    $('.image-tooltip-preview').each(function () {
                        var objectUrl = $(this).data('objectUrl');
                        if (objectUrl) URL.revokeObjectURL(objectUrl);
                    }).remove();
                    $(document).off('mousemove.imgtooltip');
                });
            }

            var resourceFt = guessResourceFiletype(node);
            $('<button>', { 'class': 'resource-download-btn', title: 'Download resource',
                             html: '<i class="fas fa-download"></i>' + (resourceFt ? microFiletype(resourceFt) : '') })
                .on('click', function (e) {
                    e.stopPropagation();
                    window.open(resourceUrl + (node.keyPath ? '&inline=false' : '?inline=false'), '_blank');
                })
                .appendTo($header);
            if (node.nodeCategory === 'image' && node.keyPath) {
                $('<button>', { 'class': 'resource-delete-btn', title: 'Delete image',
                                 html: '<i class="fas fa-trash"></i>' })
                    .on('click', function (e) {
                        e.stopPropagation();
                        P.Resource.deleteResource(node, $header);
                    })
                    .appendTo($header);
            }
        }

        // Attachment preview and download buttons
        if (node.nodeCategory === 'attachment' && node.properties && node.properties.FileName) {
            var attachUrl = '/api/attachment/' + P.state.sessionId + '/' +
                           encodeURIComponent(node.properties.FileName);
            $('<button>', { 'class': 'resource-open-btn', title: 'Preview attachment',
                             html: '<i class="fas fa-eye"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    window.open(attachUrl + '?inline=true', '_blank');
                })
                .appendTo($header);
            var attachFt = guessAttachmentFiletype(node.properties.FileName);
            $('<button>', { 'class': 'resource-download-btn', title: 'Download attachment',
                             html: '<i class="fas fa-download"></i>' + (attachFt ? microFiletype(attachFt) : '') })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.Resource.downloadAttachment(P.state.sessionId, node.properties.FileName);
                })
                .appendTo($header);
        }

        // Circular-reference jump
        if (node.properties && node.properties.refTarget) {
            var targetNum = parseInt(node.properties.refTarget, 10);
            $('<button>', { 'class': 'ref-jump-btn', title: 'Jump to referenced object',
                             html: '<i class="fas fa-external-link-alt"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.Tree.navigateToObject(targetNum, 0);
                })
                .appendTo($header);
        }

        // Bookmark page-jump
        if (node.nodeCategory === 'bookmark' && node.pageIndex >= 0) {
            $('<button>', { 'class': 'ref-jump-btn', title: 'Jump to page',
                             html: '<i class="fas fa-bookmark"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    P.Viewer.scrollToPage(node.pageIndex);
                })
                .appendTo($header);
        }
    }

    return { buildNodeEl: buildNodeEl };
})(jQuery, PDFalyzer);
