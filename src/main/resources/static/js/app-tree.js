/**
 * PDFalyzer Studio – Tree rendering (structural and search results).
 */
PDFalyzer.Tree = (function ($, P) {
    'use strict';

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
        $container.append(P.TreeRender.buildNodeEl(data, 0));
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
            let iconHtml = '';
            if (category === 'bookmarks') {
                iconHtml = '<i class="fas fa-bookmark fa-2x mb-2 text-muted"></i><br>';
                $container.html('<div class="text-muted text-center mt-3">' + iconHtml + 'No bookmarks found</div>');
            } else if (category === 'attachments') {
                iconHtml = '<i class="fas fa-paperclip fa-2x mb-2 text-muted"></i><br>';
                $container.html('<div class="text-muted text-center mt-3">' + iconHtml + 'No attachments found</div>');
            } else if (category === 'forms' || category === 'acroform' || category === 'field') {
                iconHtml = '<i class="fas fa-file fa-2x mb-2 text-muted"></i><br>';
                $container.html('<div class="text-muted text-center mt-3">' + iconHtml + 'No forms found</div>');
            } else {
                $container.html('<div class="text-muted text-center mt-3">No items found</div>');
            }
            appendPendingFieldPanel($container);
            return;
        }
        nodes.forEach(function (n) { $container.append(P.TreeRender.buildNodeEl(n, 0)); });
        appendPendingFieldPanel($container);
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
        results.forEach(function (n) { $container.append(P.TreeRender.buildNodeEl(n, 0)); });
        appendPendingFieldPanel($container);
        applySelectionClasses();
        restoreViewState(viewState);
    }

    function appendPendingFieldPanel($container) {
        var stacks = P.state.fieldUndoStacks || {};
        var total = Object.keys(stacks).reduce(function (sum, k) { return sum + stacks[k].length; }, 0);
        total += (P.state.pendingCosChanges || []).length;
        if (!total) return;

        var $hint = $('<div>', { 'class': 'pending-changes-hint-banner' })
            .html('<i class="fas fa-clock-rotate-left me-1"></i>' + total +
                  ' pending change' + (total > 1 ? 's' : '') +
                  ' <span class="pending-hint-link">View details</span>');
        $hint.on('click', function () {
            $('.tab-btn').removeClass('active');
            $('#changesTabBtn').addClass('active').show();
            P.Tabs.switchTab('changes');
        });
        $container.prepend($hint);
    }

    function refreshPendingPanel() {
        var $container = $('#treeContent');
        if (!$container.length) return;
        $container.find('.pending-changes-hint-banner').remove();
        if (P.state.currentTab !== 'changes') appendPendingFieldPanel($container);
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
            $childrenEl.append(P.TreeRender.buildNodeEl(c, nextDepth));
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

    function selectNodeById(nodeId) {
        if (nodeId === null || nodeId === undefined || !P.state.treeData) return;
        var found = null;
        var target = String(nodeId);
        (function walk(n) {
            if (found) return;
            if (String(n.id) === target) { found = n; return; }
            if (n.children) n.children.forEach(walk);
        })(P.state.treeData);
        if (found) selectNode(found, false, true, true);
    }

    return { render: render, renderSubtree: renderSubtree,
             renderSearchResults: renderSearchResults, selectNode: selectNode,
             navigateToObject: navigateToObject, findAllByCategory: findAllByCategory,
             selectNodeById: selectNodeById,
             applySelectionClasses: applySelectionClasses,
             captureViewState: captureViewState, restoreViewState: restoreViewState,
             refreshPendingPanel: refreshPendingPanel,
             ensureChildrenRendered: ensureChildrenRendered };
})(jQuery, PDFalyzer);
