/**
 * PDFalyzer Studio – Tree rendering (structural and search results).
 */
PDFalyzer.Tree = (function ($, P) {
    'use strict';

    var COST_TYPE_CLASSES = {
        COSNull: 'cos-cosnull', COSBoolean: 'cos-cosboolean', COSInteger: 'cos-cosinteger',
        COSFloat: 'cos-cosfloat', COSString: 'cos-cosstring', COSName: 'cos-cosname',
        COSArray: 'cos-cosarray', COSDictionary: 'cos-cosdictionary',
        COSStream: 'cos-cosstream', COSObject: 'cos-cosobject', Operator: 'cos-operator'
    };

    // ======================== RENDER ========================

    function captureViewState() {
        var $container = $('#treeContent');
        if (!$container.length) return null;
        var $nodes = $container.find('.tree-node');
        if (!$nodes.length) return null;
        var expandedNodeIds = [];
        $nodes.each(function () {
            var $node = $(this);
            var $children = $node.children('.tree-children');
            if (!$children.length) return;
            if ($children.css('display') !== 'none') {
                expandedNodeIds.push(String($node.data('node-id')));
            }
        });
        return {
            expandedNodeIds: expandedNodeIds,
            scrollTop: $container.scrollTop()
        };
    }

    function restoreViewState(viewState) {
        if (!viewState) return;
        var expandedSet = Object.create(null);
        (viewState.expandedNodeIds || []).forEach(function (id) {
            expandedSet[String(id)] = true;
        });

        function applyState($node) {
            var $children = $node.children('.tree-children');
            if (!$children.length) return;

            var nodeId = String($node.data('node-id'));
            var isExpanded = !!expandedSet[nodeId];

            if (isExpanded) {
                var nodeModel = $node.data('node-model');
                var nodeDepth = parseInt($node.attr('data-depth'), 10) || 0;
                ensureChildrenRendered($children, nodeModel, nodeDepth);
            }

            $children.css('display', isExpanded ? 'block' : 'none');
            var $icon = $node.find('> .tree-node-header > .tree-toggle > i');
            if ($icon.length) {
                $icon.toggleClass('fa-chevron-down', isExpanded)
                    .toggleClass('fa-chevron-right', !isExpanded);
            }

            if (isExpanded) {
                $children.children('.tree-node').each(function () {
                    applyState($(this));
                });
            }
        }

        $('#treeContent > .tree-node').each(function () {
            applyState($(this));
        });

        if (typeof viewState.scrollTop === 'number') {
            $('#treeContent').scrollTop(viewState.scrollTop);
        }
    }

    function render(data, options) {
        if (!data) return;
        var opts = options || {};
        var viewState = Object.prototype.hasOwnProperty.call(opts, 'viewState')
            ? opts.viewState
            : captureViewState();
        var $container = $('#treeContent').empty();
        $container.append(buildNodeEl(data, 0));
        appendPendingFieldPanel($container);
        applySelectionClasses();
        restoreViewState(viewState);
    }

    function renderSubtree(rootData, category, options) {
        var opts = options || {};
        var viewState = Object.prototype.hasOwnProperty.call(opts, 'viewState')
            ? opts.viewState
            : captureViewState();
        var $container = $('#treeContent').empty();
        var nodes = findAllByCategory(rootData, category);
        if (nodes.length === 0) {
            $container.html('<div class="text-muted text-center mt-3">No items found</div>');
            appendPendingFieldPanel($container, category);
            return;
        }
        nodes.forEach(function (n) { $container.append(buildNodeEl(n, 0)); });
        appendPendingFieldPanel($container, category);
        applySelectionClasses();
        restoreViewState(viewState);
    }

    function renderSearchResults(results, options) {
        var opts = options || {};
        var viewState = Object.prototype.hasOwnProperty.call(opts, 'viewState')
            ? opts.viewState
            : captureViewState();
        var $container = $('#treeContent').empty();
        if (results.length === 0) {
            $container.html('<div class="text-muted text-center mt-3">No results</div>');
            return;
        }
        results.forEach(function (n) { $container.append(buildNodeEl(n, 0)); });
        appendPendingFieldPanel($container);
        applySelectionClasses();
        restoreViewState(viewState);
    }

    function appendPendingFieldPanel($container, category) {
        var pendingFields = P.state.pendingFormAdds || [];
        var pendingCos = P.state.pendingCosChanges || [];
        if (!pendingFields.length && !pendingCos.length) return;
        if (category && category !== 'acroform' && category !== 'field' &&
            category !== 'info' && category !== 'cos' && category !== 'raw-cos') return;

        var total = pendingFields.length + pendingCos.length;

        var html = '<div class="pending-fields-panel">' +
            '<div class="pending-fields-title">Pending changes (' + total + ')</div>' +
            '<ul class="pending-fields-list">';

        pendingFields.forEach(function (item) {
            html += '<li><span class="pending-field-name">' + P.Utils.escapeHtml(item.fieldName || '(unnamed)') + '</span>' +
                '<span class="pending-field-meta">' +
                P.Utils.escapeHtml((item.fieldType || 'field') + ' · Page ' + ((item.pageIndex || 0) + 1)) +
                '</span></li>';
        });

        pendingCos.forEach(function (item) {
            var op = item && item.operation ? String(item.operation).toUpperCase() : 'COS';
            var summary = item && item.summary ? item.summary : 'COS change';
            html += '<li><span class="pending-field-name">' + P.Utils.escapeHtml(summary) + '</span>' +
                '<span class="pending-field-meta">' + P.Utils.escapeHtml(op) + '</span></li>';
        });

        html += '</ul><div class="pending-fields-hint">Click Save to persist to PDF</div></div>';

        $container.prepend($(html));
    }

    function refreshPendingPanel() {
        var $container = $('#treeContent');
        if (!$container.length) return;
        $container.find('.pending-fields-panel').remove();

        var category = null;
        if (P.state.currentTab === 'forms') category = 'acroform';
        else if (P.state.currentTab === 'rawcos') category = 'raw-cos';

        appendPendingFieldPanel($container, category);
    }

    // ======================== SELECT NODE ========================

    function selectNode(node, additive, preserveFieldSelection, suppressPropertiesPanel) {
        P.state.selectedNodeId = node.id;
        if (!preserveFieldSelection) {
            syncFieldSelection(node, !!additive);
        }
        applySelectionClasses();
        var $el = $('.tree-node[data-node-id="' + node.id + '"]');
        if (!$el.length && node.nodeCategory === 'field' && P.state.treeData && P.state.currentTab === 'forms') {
            renderSubtree(P.state.treeData, 'field');
            $el = $('.tree-node[data-node-id="' + node.id + '"]');
        }
        if (!$el.length && P.state.treeData) {
            render(P.state.treeData);
            $el = $('.tree-node[data-node-id="' + node.id + '"]');
        }
        applySelectionClasses();
        expandToNode(node);
        if ($el.length) {
            $el[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
        refreshViewerSelectionHighlights(node);
        if (P.EditMode && P.EditMode.refreshFieldSelectionHighlights) {
            P.EditMode.refreshFieldSelectionHighlights();
        }
        if (!suppressPropertiesPanel && node.properties && $el.length) {
            showPropertiesPanel(node, $el.find('> .tree-node-header'));
        }
    }

    function ensureChildrenRendered($childrenEl, node, depth) {
        if (!$childrenEl || !$childrenEl.length || !$childrenEl.children || $childrenEl.children().length) return;
        if (!node || !node.children || !node.children.length) return;
        var nextDepth = (depth || 0) + 1;
        node.children.forEach(function (c) {
            $childrenEl.append(buildNodeEl(c, nextDepth));
        });
    }

    function syncFieldSelection(node, additive) {
        if (!P.state.selectedFieldNames) P.state.selectedFieldNames = [];
        if (!P.state.selectedImageNodeIds) P.state.selectedImageNodeIds = [];

        var fieldName = node && node.properties ? node.properties.FullName : null;
        if (node && node.nodeCategory === 'field' && fieldName) {
            if (additive) {
                var idx = P.state.selectedFieldNames.indexOf(fieldName);
                if (idx >= 0) P.state.selectedFieldNames.splice(idx, 1);
                else P.state.selectedFieldNames.push(fieldName);
            } else {
                P.state.selectedFieldNames = [fieldName];
                P.state.selectedImageNodeIds = [];
            }
            return;
        }

        if (node && node.nodeCategory === 'image' && node.id !== undefined && node.id !== null) {
            var imageNodeId = node.id;
            if (additive) {
                var imageIdx = P.state.selectedImageNodeIds.indexOf(imageNodeId);
                if (imageIdx >= 0) P.state.selectedImageNodeIds.splice(imageIdx, 1);
                else P.state.selectedImageNodeIds.push(imageNodeId);
            } else {
                P.state.selectedImageNodeIds = [imageNodeId];
                P.state.selectedFieldNames = [];
            }
            return;
        }
        if (!additive) {
            P.state.selectedFieldNames = [];
            P.state.selectedImageNodeIds = [];
        }
    }

    function applySelectionClasses() {
        var selectedFields = P.state.selectedFieldNames || [];
        var selectedImages = P.state.selectedImageNodeIds || [];
        $('.tree-node-header').removeClass('selected field-selected image-selected');
        if (P.state.selectedNodeId !== null && P.state.selectedNodeId !== undefined) {
            $('.tree-node[data-node-id="' + P.state.selectedNodeId + '"] > .tree-node-header').addClass('selected');
        }
        if (selectedImages.length) {
            selectedImages.forEach(function (imageNodeId) {
                $('.tree-node[data-node-id="' + imageNodeId + '"] > .tree-node-header').addClass('image-selected');
            });
        }
        if (!selectedFields.length) return;
        selectedFields.forEach(function (fieldName) {
            $('.tree-node-header[data-field-name="' + fieldName + '"]').addClass('field-selected');
        });
    }

    function refreshViewerSelectionHighlights(node) {
        if (!P.Viewer) return;

        var selectedImageIds = P.state.selectedImageNodeIds || [];
        if (selectedImageIds.length && P.state.treeData) {
            var imageIdSet = Object.create(null);
            selectedImageIds.forEach(function (id) {
                imageIdSet[String(id)] = true;
            });

            var selectedImageNodes = [];
            (function walk(n) {
                if (!n) return;
                if (n.nodeCategory === 'image' && n.id !== undefined && n.id !== null && imageIdSet[String(n.id)]) {
                    selectedImageNodes.push(n);
                }
                if (n.children) n.children.forEach(walk);
            })(P.state.treeData);

            P.Viewer.clearHighlights();
            selectedImageNodes.forEach(function (imgNode) {
                if (imgNode.pageIndex >= 0 && imgNode.boundingBox) {
                    P.Viewer.highlight(imgNode.pageIndex, imgNode.boundingBox);
                }
            });

            if (node && node.pageIndex >= 0) {
                P.Viewer.scrollToPage(node.pageIndex);
            } else if (selectedImageNodes.length && selectedImageNodes[0].pageIndex >= 0) {
                P.Viewer.scrollToPage(selectedImageNodes[0].pageIndex);
            }
            return;
        }

        if (node && node.pageIndex >= 0) {
            P.Viewer.clearHighlights();
            if (node.boundingBox) P.Viewer.highlight(node.pageIndex, node.boundingBox);
            else P.Viewer.scrollToPage(node.pageIndex);
        }
    }

    function showPropertiesPanel(node, $headerEl) {
        $headerEl.siblings('.node-properties').remove();
        var displayProps = collectDisplayProperties(node);
        if (Object.keys(displayProps).length === 0) return;
        var $props = $('<div>', { 'class': 'node-properties' });
        Object.entries(displayProps).forEach(function (kv) {
            var $row = $('<div>', { 'class': 'prop-row' });
            $row.append($('<span>', { 'class': 'prop-key', text: kv[0] + ':' }));
            var $val = $('<span>', { 'class': 'prop-val' });
            // If value looks like a ref target, make it clickable
            if (kv[0] === 'refTarget') {
                var objNum = parseInt(kv[1], 10);
                $val.append(
                    $('<a>', { href: '#', text: 'obj ' + kv[1] })
                        .on('click', function (e) {
                            e.preventDefault();
                            navigateToObject(objNum, 0);
                        })
                );
            } else {
                $val.text(kv[1]);
            }
            $row.append($val);
            $props.append($row);
        });
        $headerEl.after($props);
    }

    function collectDisplayProperties(node) {
        var merged = {};
        if (node.properties) {
            Object.entries(node.properties).forEach(function (kv) {
                merged[kv[0]] = kv[1];
            });
        }
        if (node.nodeCategory && merged.Category === undefined) {
            merged.Category = node.nodeCategory;
        }
        if (node.type && merged.Type === undefined) {
            merged.Type = node.type;
        }
        if (node.pageIndex >= 0 && merged.Page === undefined) {
            merged.Page = String(node.pageIndex + 1);
        }
        if (node.objectNumber >= 0 && merged.Object === undefined) {
            var gen = node.generationNumber >= 0 ? node.generationNumber : 0;
            merged.Object = node.objectNumber + ' ' + gen + ' R';
        }
        if (node.boundingBox && node.boundingBox.length === 4 && merged.BoundingBox === undefined) {
            merged.BoundingBox = node.boundingBox.map(function (n) { return Number(n).toFixed(1); }).join(', ');
        }
        if (node.children && node.children.length && merged.Children === undefined) {
            merged.Children = String(node.children.length);
        }
        return merged;
    }

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
                    // Expanding - render children if not already done
                    ensureChildrenRendered($childrenEl, node, depth);
                } else {
                    // Collapsing - remove properties panel
                    $header.siblings('.node-properties').remove();
                    $childrenEl.find('.node-properties').remove();
                    suppressPropertiesPanel = true;
                }
            }
            selectNode(node, additive, false, suppressPropertiesPanel);
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

    function appendActionButtons($header, node) {
        // COS edit button (pencil)
        if (node.editable && node.rawValue !== undefined && node.rawValue !== null) {
            $('<button>', { 'class': 'cos-edit-btn', title: 'Edit value',
                             html: '<i class="fas fa-pencil-alt"></i>' })
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
                             html: '<i class="fas fa-trash-alt"></i>' })
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
            var $previewBtn = $('<button>', { 'class': 'resource-open-btn', title: 'Preview resource',
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
                                    ? (pixelWidth + ' × ' + pixelHeight)
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
            
            $('<button>', { 'class': 'resource-download-btn', title: 'Download resource',
                             html: '<i class="fas fa-download"></i>' })
                .on('click', function (e) {
                    e.stopPropagation();
                    window.open(resourceUrl + (node.keyPath ? '&inline=false' : '?inline=false'), '_blank');
                })
                .appendTo($header);
            if (node.nodeCategory === 'image' && node.keyPath) {
                $('<button>', { 'class': 'resource-delete-btn', title: 'Delete image',
                                 html: '<i class="fas fa-trash-alt"></i>' })
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
            $('<button>', { 'class': 'resource-download-btn', title: 'Download attachment',
                             html: '<i class="fas fa-download"></i>' })
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
                    navigateToObject(targetNum, 0);
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

    // ======================== NAVIGATION HELPERS ========================

    function findPathById(node, targetId, path) {
        if (!node) return false;
        path.push(node);
        if (String(node.id) === String(targetId)) return true;
        if (node.children) {
            for (var i = 0; i < node.children.length; i++) {
                if (findPathById(node.children[i], targetId, path)) return true;
            }
        }
        path.pop();
        return false;
    }

    function expandToNode(node) {
        if (!node || !P.state.treeData) return;

        var path = [];
        if (!findPathById(P.state.treeData, node.id, path) || path.length < 2) return;

        for (var depth = 0; depth < path.length - 1; depth++) {
            var ancestor = path[depth];
            var $ancestor = $('.tree-node[data-node-id="' + ancestor.id + '"]');
            if (!$ancestor.length) {
                continue;
            }

            var $childrenEl = $ancestor.children('.tree-children');
            if (!$childrenEl.length) continue;

            ensureChildrenRendered($childrenEl, ancestor, depth);
            $childrenEl.css('display', 'block');

            var $icon = $ancestor.find('> .tree-node-header > .tree-toggle > i');
            if ($icon.length) {
                $icon.removeClass('fa-chevron-right').addClass('fa-chevron-down');
            }
        }
    }

    function navigateToObject(objNum, genNum) {
        if (!P.state.treeData) return;
        var found = null;
        function dfs(n) {
            if (found) return;
            if (n.objectNumber === objNum && n.generationNumber === genNum) { found = n; return; }
            if (n.children) n.children.forEach(dfs);
        }
        dfs(P.state.treeData);
        if (found) {
            render(P.state.treeData);
            selectNode(found);
        } else {
            P.Utils.toast('Referenced object not found', 'warning');
        }
    }

    function findAllByCategory(node, category, results) {
        if (!results) results = [];
        if (node.nodeCategory === category) results.push(node);
        if (node.children) node.children.forEach(function (c) {
            findAllByCategory(c, category, results);
        });
        return results;
    }

    return { render: render, renderSubtree: renderSubtree,
             renderSearchResults: renderSearchResults, selectNode: selectNode,
             navigateToObject: navigateToObject, findAllByCategory: findAllByCategory,
             applySelectionClasses: applySelectionClasses,
             captureViewState: captureViewState, restoreViewState: restoreViewState,
             refreshPendingPanel: refreshPendingPanel };
})(jQuery, PDFalyzer);
