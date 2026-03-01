/**
 * PDFalyzer – Tree rendering (structural and search results).
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

    function render(data) {
        if (!data) return;
        var $container = $('#treeContent').empty();
        $container.append(buildNodeEl(data, 0));
        appendPendingFieldPanel($container);
        applySelectionClasses();
    }

    function renderSubtree(rootData, category) {
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
    }

    function renderSearchResults(results) {
        var $container = $('#treeContent').empty();
        if (results.length === 0) {
            $container.html('<div class="text-muted text-center mt-3">No results</div>');
            return;
        }
        results.forEach(function (n) { $container.append(buildNodeEl(n, 0)); });
        appendPendingFieldPanel($container);
        applySelectionClasses();
    }

    function appendPendingFieldPanel($container, category) {
        var pending = P.state.pendingFormAdds || [];
        if (!pending.length) return;
        if (category && category !== 'acroform' && category !== 'field') return;

        var html = '<div class="pending-fields-panel">' +
            '<div class="pending-fields-title">Pending new fields (' + pending.length + ')</div>' +
            '<ul class="pending-fields-list">';
        pending.forEach(function (item) {
            html += '<li><span class="pending-field-name">' + P.Utils.escapeHtml(item.fieldName || '(unnamed)') + '</span>' +
                '<span class="pending-field-meta">' +
                P.Utils.escapeHtml((item.fieldType || 'field') + ' · Page ' + ((item.pageIndex || 0) + 1)) +
                '</span></li>';
        });
        html += '</ul><div class="pending-fields-hint">Click Save to persist to PDF</div></div>';

        $container.prepend($(html));
    }

    // ======================== SELECT NODE ========================

    function selectNode(node, additive, preserveFieldSelection, suppressPropertiesPanel) {
        P.state.selectedNodeId = node.id;
        if (!preserveFieldSelection) {
            syncFieldSelection(node, !!additive);
        }
        applySelectionClasses();
        var $el = $('.tree-node[data-node-id="' + node.id + '"]');
        if (!$el.length && node.nodeCategory === 'field' && P.state.treeData) {
            renderSubtree(P.state.treeData, 'field');
            $el = $('.tree-node[data-node-id="' + node.id + '"]');
        }
        applySelectionClasses();
        expandToNode(node);
        if ($el.length) {
            $el[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
        if (node.pageIndex >= 0) {
            P.Viewer.clearHighlights();
            if (node.boundingBox) P.Viewer.highlight(node.pageIndex, node.boundingBox);
            else P.Viewer.scrollToPage(node.pageIndex);
        }
        if (P.EditMode && P.EditMode.refreshFieldSelectionHighlights) {
            P.EditMode.refreshFieldSelectionHighlights();
        }
        if (!suppressPropertiesPanel && node.properties && $el.length) {
            showPropertiesPanel(node, $el.find('> .tree-node-header'));
        }
    }

    function syncFieldSelection(node, additive) {
        if (!P.state.selectedFieldNames) P.state.selectedFieldNames = [];
        var fieldName = node && node.properties ? node.properties.FullName : null;
        if (node && node.nodeCategory === 'field' && fieldName) {
            if (additive) {
                var idx = P.state.selectedFieldNames.indexOf(fieldName);
                if (idx >= 0) P.state.selectedFieldNames.splice(idx, 1);
                else P.state.selectedFieldNames.push(fieldName);
            } else {
                P.state.selectedFieldNames = [fieldName];
            }
            return;
        }
        if (!additive) {
            P.state.selectedFieldNames = [];
        }
    }

    function applySelectionClasses() {
        var selectedFields = P.state.selectedFieldNames || [];
        $('.tree-node-header').removeClass('selected field-selected');
        if (P.state.selectedNodeId !== null && P.state.selectedNodeId !== undefined) {
            $('.tree-node[data-node-id="' + P.state.selectedNodeId + '"] > .tree-node-header').addClass('selected');
        }
        if (!selectedFields.length) return;
        selectedFields.forEach(function (fieldName) {
            $('.tree-node-header[data-field-name="' + fieldName + '"]').addClass('field-selected');
        });
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
        var $wrapper = $('<div>', { 'class': 'tree-node', 'data-node-id': node.id });
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
                    if (!$childrenEl.children().length) {
                        node.children.forEach(function (c) {
                            $childrenEl.append(buildNodeEl(c, depth + 1));
                        });
                    }
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
                    P.Resource.preview(resourceUrl + (node.keyPath ? '&inline=true' : '?inline=true'));
                })
                .appendTo($header);
            
            // Add image hover tooltip preview
            if (node.nodeCategory === 'image') {
                $header.on('mouseenter', function() {
                    if ($('.image-tooltip-preview').length) return;
                    var $tooltip = $('<div>', { 'class': 'image-tooltip-preview' });
                    var $img = $('<img>', { src: resourceUrl + (node.keyPath ? '&inline=true' : '?inline=true') });
                    $tooltip.append($img).appendTo('body');
                    
                    var updatePos = function(ev) {
                        $tooltip.css({ left: (ev.pageX + 15) + 'px', top: (ev.pageY + 15) + 'px' });
                    };
                    $(document).on('mousemove.imgtooltip', updatePos);
                }).on('mouseleave', function() {
                    $('.image-tooltip-preview').remove();
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

    function expandToNode(node) {
        /* Currently the nodes are auto-expanded up to depth 2.
           For deeper nodes the user can click to expand. */
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
             applySelectionClasses: applySelectionClasses };
})(jQuery, PDFalyzer);
