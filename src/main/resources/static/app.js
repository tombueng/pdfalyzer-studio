/**
 * PDFalyzer UI - Client-side application (jQuery)
 */
var PDFalyzer = (function ($) {
    'use strict';

    var sessionId = null;
    var pdfDoc = null;
    var treeData = null;
    var currentTab = 'structure';
    var selectedNodeId = null;
    var editMode = false;
    var editFieldType = null;
    var pageViewports = [];
    var pageCanvases = [];
    var currentScale = 1.5;
    var autoZoomMode = 'off'; // 'off', 'width', 'height'
    var basePageSize = { width: 0, height: 0 };

    // ===================== UTILITIES =====================

    function showLoading() { $('#statusLoading').show(); }
    function hideLoading() { $('#statusLoading').hide(); }

    function toast(msg, type) {
        var iconMap = {
            success: 'fa-check-circle',
            danger: 'fa-exclamation-circle',
            warning: 'fa-exclamation-triangle',
            info: 'fa-info-circle'
        };
        var iconClass = iconMap[type] || 'fa-info-circle';
        var $icon = $('<i>', { 'class': 'fas ' + iconClass + ' toast-icon-' + type });
        var $text = $('<span>', { text: msg, 'class': 'toast-text-' + type });
        var $el = $('<div>', { 'class': 'toast-msg' + (type ? ' text-' + type : '') });
        $el.append($icon).append(' ').append($text);
        $('#toastContainer').append($el);
        // persist until user clicks
        $el.on('click', function () { $el.remove(); });
    }

    function apiFetch(url, opts) {
        showLoading();
        var skeletonTimer = setTimeout(function () {
            $('#treeContent').addClass('skeleton');
        }, 1000);

        return $.ajax($.extend({
            url: url,
            dataType: (opts && opts.processData === false) ? undefined : 'json'
        }, opts || {}))
            .always(function () {
                clearTimeout(skeletonTimer);
                $('#treeContent').removeClass('skeleton');
                hideLoading();
            });
    }

    // ===================== UPLOAD =====================

    var Upload = {
        init: function () {
            $('#fileInput').on('change', function () {
                if (this.files.length > 0) {
                    Upload.upload(this.files[0]);
                }
            });

            // Drag and drop
            $(document).on('dragover', function (e) {
                e.preventDefault();
                $('#dropOverlay').addClass('active');
            });

            $('#pdfPane').on('dragleave', function (e) {
                if (!$.contains(this, e.relatedTarget)) {
                    $('#dropOverlay').removeClass('active');
                }
            }).on('drop', function (e) {
                e.preventDefault();
                $('#dropOverlay').removeClass('active');
                var files = e.originalEvent.dataTransfer.files;
                if (files.length > 0 && (files[0].type === 'application/pdf' || files[0].name.toLowerCase().endsWith('.pdf'))) {
                    Upload.upload(files[0]);
                } else {
                    toast('Please drop a PDF file', 'warning');
                }
            });
        },

        upload: function (file) {
            if (file.size > 100 * 1024 * 1024) {
                toast('File exceeds 100 MB limit', 'danger');
                return;
            }

            var formData = new FormData();
            formData.append('file', file);

            var $btn = $('#uploadBtn');
            $btn.html('<span class="spinner-border spinner-border-sm"></span> Uploading...');

            apiFetch('/api/upload', {
                method: 'POST',
                data: formData,
                processData: false,
                contentType: false,
                dataType: 'json'
            })
                .done(function (data) {
                    sessionId = data.sessionId;
                    treeData = data.tree;

                    $('#statusFilename').html('<i class="fas fa-file-pdf"></i> ' + data.filename);
                    $('#statusPages').html('<i class="fas fa-copy"></i> ' + data.pageCount + ' pages');
                    $('#statusSession').html('<i class="fas fa-clock"></i> Session active');
                    $('#searchInput').prop('disabled', false);
                    $('#editModeBtn').prop('disabled', false);
                    $('#downloadBtn').prop('disabled', false);
                    $('#exportTreeBtn').prop('disabled', false);
                    $('#zoomModeBtn').prop('disabled', false);

                    Tree.render(treeData);
                    Viewer.loadPdf(sessionId);
                    toast('PDF loaded: ' + data.filename + ' (' + data.pageCount + ' pages)', 'success');
                })
                .fail(function (xhr) {
                    var msg = 'Upload failed';
                    try { msg += ': ' + JSON.parse(xhr.responseText).error; } catch (e) {}
                    toast(msg, 'danger');
                })
                .always(function () {
                    $btn.html('<i class="fas fa-upload me-1"></i> Upload PDF');
                });
        }
    };

    // ===================== RESOURCE PREVIEW =====================

    var Resource = {
        preview: function(url) {
            // fetch as blob to inspect headers
            showLoading();
            fetch(url)
                .then(function(resp) {
                    hideLoading();
                    var ct = resp.headers.get('content-type') || '';
                    return resp.blob().then(function(blob) { return {ct: ct, blob: blob}; });
                })
                .then(function(r) {
                    var ct = r.ct.toLowerCase();
                    var $body = $('#resourcePreviewBody');
                    $body.empty();
                    if (ct.startsWith('image/')) {
                        var urlObj = URL.createObjectURL(r.blob);
                        var $img = $('<img>', { src: urlObj, css: { 'max-width': '100%', 'height': 'auto' } });
                        $body.append($img);
                        $('#resourcePreviewLabel').text('Image Preview');
                    } else if (ct.indexOf('xml') !== -1 || ct.indexOf('json') !== -1 || ct.startsWith('text/')) {
                        var reader = new FileReader();
                        reader.onload = function(evt) {
                            var text = evt.target.result;
                            var $pre = $('<pre>').text(text).css({'max-height': '400px', 'overflow': 'auto', 'background': '#1e1e1e', 'padding': '10px', 'border-radius': '4px', 'color': '#d4d4d4'});
                            $body.append($pre);
                        };
                        reader.readAsText(r.blob);
                        $('#resourcePreviewLabel').text('Text Preview');
                    } else {
                        // fallback to new tab for unknown types
                        window.open(url, '_blank');
                        return;
                    }
                    var modal = new bootstrap.Modal(document.getElementById('resourcePreviewModal'));
                    modal.show();
                })
                .catch(function(err) {
                    hideLoading();
                    toast('Could not preview resource', 'danger');
                });
        }
    };

    // ===================== TREE =====================

    var Tree = {
        render: function (node) {
            var $container = $('#treeContent').empty();
            if (!node) {
                $container.html('<div class="text-muted text-center mt-5">No data</div>');
                return;
            }
            $container.append(Tree.buildNodeEl(node, true));
        },

        buildNodeEl: function (node, expanded) {
            var $div = $('<div>', { 'class': 'tree-node', 'data-node-id': node.id || '' });
            var hasChildren = node.children && node.children.length > 0;
            var isExpanded = expanded !== false;

            // Header
            var $header = $('<div>', { 'class': 'tree-node-header' });
            if (node.id === selectedNodeId) $header.addClass('selected');

            // Toggle
            var $toggle = $('<span>', { 'class': 'tree-toggle' + (isExpanded ? ' expanded' : '') });
            if (hasChildren) $toggle.html('<i class="fas fa-caret-right"></i>');
            $header.append($toggle);

            // Icon
            $header.append(
                $('<span>', { 'class': 'tree-icon' })
                    .css('color', node.color || '#aaa')
                    .html('<i class="fas ' + (node.icon || 'fa-circle') + '"></i>')
            );

            // COS type badge
            if (node.cosType) {
                $header.append(
                    $('<span>', {
                        'class': 'cos-type-badge cos-' + node.cosType.toLowerCase(),
                        text: node.cosType.replace('COS', '')
                    })
                );
            }

            // Label
            $header.append($('<span>', { 'class': 'tree-label', text: node.name || '(unnamed)' }));

            // Inline edit button for editable COS primitives
            if (node.editable && node.rawValue !== undefined && node.rawValue !== null) {
                var $editBtn = $('<span>', {
                    'class': 'cos-edit-btn',
                    title: 'Edit this value',
                    html: '<i class="fas fa-pencil-alt"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    CosEditor.show(node, $header);
                });
                $header.append($editBtn);
            }
            // Circular reference jump icon
            if (node.properties && node.properties.refTarget) {
                var $refBtn = $('<span>', {
                    'class': 'ref-jump-btn',
                    title: 'Go to referenced object',
                    html: '<i class="fas fa-link"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    Tree.navigateToObject(parseInt(node.properties.refTarget), node.generationNumber);
                });
                $header.append($refBtn);
            }

            // Delete button for dictionary/array entries
            if (node.name && (/^[\/]|^\[/.test(node.name))) {
                var isDict = node.cosType === 'COSDictionary' || node.cosType === 'COSStream';
                var $delBtn = $('<span>', {
                    'class': 'cos-delete-btn',
                    title: isDict ? 'Delete entire dictionary' : 'Delete this entry',
                    html: '<i class="fas fa-trash-alt"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    CosEditor.remove(node, $header);
                });
                $header.append($delBtn);
            }

            // Info button for dictionaries/arrays showing type and allowed operations
            if (node.cosType === 'COSDictionary' || node.cosType === 'COSArray') {
                var dictType = (node.properties && node.properties.Type) ? node.properties.Type : 'Generic Dictionary';
                var $infoBtn = $('<span>', {
                    'class': 'cos-info-btn',
                    title: 'Dictionary type: ' + dictType,
                    html: '<i class="fas fa-info-circle"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    DictEditor.showInfo(node);
                });
                $header.append($infoBtn);
            }

            // Add entry button for dictionaries/arrays
            if (node.cosType === 'COSDictionary' || node.cosType === 'COSArray') {
                var $addBtn = $('<span>', {
                    'class': 'cos-add-btn',
                    title: 'Add entry',
                    html: '<i class="fas fa-plus"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    CosEditor.addEntry(node, $header);
                });
                $header.append($addBtn);
            }

            // Resource download/open buttons (available for stream objects)
            if (node.objectNumber >= 0 && node.cosType === 'COSStream') {
                var urlBase = '/api/resource/' + sessionId + '/' + node.objectNumber + '/' + node.generationNumber;
                var $dl = $('<span>', {
                    'class': 'resource-download-btn',
                    title: 'Download resource',
                    html: '<i class="fas fa-download"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    window.open(urlBase + '?inline=false', '_blank');
                });
                var $open = $('<span>', {
                    'class': 'resource-open-btn',
                    title: 'Preview',
                    html: '<i class="fas fa-eye"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    Resource.preview(urlBase + '?inline=true');
                });
                $header.append($dl).append($open);
            }

            $div.append($header);

            // Properties panel (include additional info)
            var hasProps = (node.properties && Object.keys(node.properties).length > 0);
            if (hasProps || node.keyPath || node.objectNumber >= 0) {
                var $propsDiv = $('<div>', { 'class': 'node-properties' })
                    .css('display', isExpanded ? 'block' : 'none');
                // key path if available
                if (node.keyPath) {
                    $propsDiv.append(
                        $('<div>', { 'class': 'prop-row' })
                            .append($('<span>', { 'class': 'prop-key', text: 'Key Path' }))
                            .append($('<span>', { 'class': 'prop-val', text: node.keyPath }))
                    );
                }
                // object info
                if (node.objectNumber >= 0) {
                    var objInfo = node.objectNumber + '/' + node.generationNumber;
                    $propsDiv.append(
                        $('<div>', { 'class': 'prop-row' })
                            .append($('<span>', { 'class': 'prop-key', text: 'Object' }))
                            .append($('<span>', { 'class': 'prop-val', text: objInfo }))
                    );
                }
                if (hasProps) {
                    $.each(node.properties, function (key, val) {
                        $propsDiv.append(
                            $('<div>', { 'class': 'prop-row' })
                                .append($('<span>', { 'class': 'prop-key', text: key }))
                                .append($('<span>', { 'class': 'prop-val', text: val }))
                        );
                    });
                }
                $div.append($propsDiv);
            }

            // Children (render lazily)
            if (hasChildren) {
                var $childrenDiv = $('<div>', { 'class': 'tree-children' })
                    .css('display', isExpanded ? 'block' : 'none')
                    .data('children-rendered', isExpanded);
                if (isExpanded) {
                    for (var i = 0; i < node.children.length; i++) {
                        $childrenDiv.append(Tree.buildNodeEl(node.children[i], false));
                    }
                }
                $div.append($childrenDiv);

                // Toggle click
                $toggle.on('click', (function ($cd, $pd, $tg, node) {
                    return function (e) {
                        e.stopPropagation();
                        var showing = $cd.css('display') !== 'none';
                        if (showing) {
                            // collapsing: remove children to free DOM
                            $cd.empty();
                            $cd.data('children-rendered', false);
                        } else {
                            // expanding: build if not already done
                            if (!$cd.data('children-rendered')) {
                                for (var i = 0; i < node.children.length; i++) {
                                    $cd.append(Tree.buildNodeEl(node.children[i], false));
                                }
                                $cd.data('children-rendered', true);
                            }
                        }
                        $cd.css('display', showing ? 'none' : 'block');
                        if ($pd.length) $pd.css('display', showing ? 'none' : 'block');
                        $tg.toggleClass('expanded');
                    };
                })($childrenDiv, $div.children('.node-properties'), $toggle, node));
            }

            // Click to select
            $header.on('click', function () {
                Tree.selectNode(node);
            });

            return $div;
        },

        selectNode: function (node) {
            $('.tree-node-header.selected').removeClass('selected');
            selectedNodeId = node.id;

            $('.tree-node').each(function () {
                if ($(this).data('node-id') === node.id) {
                    // expand all parents so the node is visible
                    $(this).parents('.tree-children').show();
                    $(this).parents('.tree-node').children('.tree-toggle').addClass('expanded');
                    $(this).children('.tree-node-header').addClass('selected');
                    this.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                    return false;
                }
            });

            Viewer.clearHighlights();
            if (node.pageIndex >= 0 && node.boundingBox) {
                Viewer.highlight(node.pageIndex, node.boundingBox);
            } else if (node.pageIndex >= 0) {
                Viewer.scrollToPage(node.pageIndex);
            }
        },

        renderSearchResults: function (results) {
            var $container = $('#treeContent').empty();
            if (results.length === 0) {
                $container.html('<div class="text-muted text-center mt-3">No results found</div>');
                return;
            }
            $container.append('<div class="text-muted mb-2" style="font-size:11px;padding:4px 8px;">' +
                results.length + ' results</div>');
            for (var i = 0; i < results.length; i++) {
                $container.append(Tree.buildNodeEl(results[i], true));
            }
        },

        renderSubtree: function (node, filterType) {
            if (!node) return;
            var found = Tree.findByCategory(node, filterType);
            var $container = $('#treeContent').empty();
            if (found) {
                $container.append(Tree.buildNodeEl(found, true));
            } else {
                $container.html('<div class="text-muted text-center mt-3">No ' + filterType + ' found in this PDF</div>');
            }
        },

        findByCategory: function (node, category) {
            if (node.nodeCategory === category) return node;
            if (node.children) {
                for (var i = 0; i < node.children.length; i++) {
                    var result = Tree.findByCategory(node.children[i], category);
                    if (result) return result;
                }
            }
            return null;
        },

        findAllByCategory: function (node, category, results) {
            if (!results) results = [];
            if (node.nodeCategory === category) results.push(node);
            if (node.children) {
                for (var i = 0; i < node.children.length; i++) {
                    Tree.findAllByCategory(node.children[i], category, results);
                }
            }
            return results;
        },
        navigateToObject: function (objNum, genNum) {
            if (!treeData) return;
            var found = null;
            // depth-first search
            function dfs(n) {
                if (found) return;
                if (n.objectNumber === objNum && n.generationNumber === genNum) {
                    found = n;
                    return;
                }
                if (n.children) {
                    n.children.forEach(dfs);
                }
            }
            dfs(treeData);
            if (found) {
                Tree.render(treeData);
                Tree.selectNode(found);
            } else {
                toast('Referenced object not found', 'warning');
            }
        },

        escapeHtml: function (text) {
            return $('<div>').text(text).html();
        }
    };

    // ===================== VIEWER =====================

    var Viewer = {
        loadPdf: function (sid) {
            var url = '/api/pdf/' + sid;
            var $viewer = $('#pdfViewer').empty().show();
            $('#uploadSplash').hide();
            pageViewports = [];
            pageCanvases = [];

            pdfjsLib.getDocument(url).promise.then(function (pdf) {
                pdfDoc = pdf;
                // capture base size from first page at scale=1
                pdf.getPage(1).then(function(p){
                    var vp = p.getViewport({ scale: 1 });
                    basePageSize.width = vp.width;
                    basePageSize.height = vp.height;
                });
                Viewer.renderAllPages();
            }).catch(function (err) {
                toast('Failed to render PDF: ' + err.message, 'danger');
            });
        },
        renderAllPages: function() {
            if (!pdfDoc) return;
            var renderChain = Promise.resolve();
            $('#pdfViewer').empty();
            for (var i = 1; i <= pdfDoc.numPages; i++) {
                renderChain = renderChain.then((function (pageNum) {
                    return function () {
                        return Viewer.renderPage(pageNum);
                    };
                })(i));
            }
            return renderChain;
        },
        setScale: function(scale) {
            if (scale <= 0) return;
            currentScale = scale;
            autoZoomMode = 'off';
            Viewer.renderAllPages();
            // update zoom button state if available
            if (typeof Zoom !== 'undefined' && Zoom.updateButton) Zoom.updateButton();
        },
        fitWidth: function() {
            if (!basePageSize.width) return;
            var avail = $('#pdfPane').width() - 40; // padding
            var scale = avail / basePageSize.width;
            currentScale = scale;
            autoZoomMode = 'width';
            Viewer.renderAllPages();
            if (typeof Zoom !== 'undefined' && Zoom.updateButton) Zoom.updateButton();
        },
        fitHeight: function() {
            if (!basePageSize.height) return;
            var avail = $('#pdfPane').height() - 40;
            var scale = avail / basePageSize.height;
            currentScale = scale;
            autoZoomMode = 'height';
            Viewer.renderAllPages();
            if (typeof Zoom !== 'undefined' && Zoom.updateButton) Zoom.updateButton();
        },
        applyAutoZoom: function() {
            if (autoZoomMode === 'width') Viewer.fitWidth();
            else if (autoZoomMode === 'height') Viewer.fitHeight();
        },

        renderPage: function (pageNum) {
            return pdfDoc.getPage(pageNum).then(function (page) {
                var scale = currentScale;
                var viewport = page.getViewport({ scale: scale });
                pageViewports[pageNum - 1] = viewport;

                var $wrapper = $('<div>', {
                    'class': 'pdf-page-wrapper',
                    'data-page': pageNum - 1
                }).css('animation-delay', ((pageNum - 1) * 0.08) + 's');

                var canvas = document.createElement('canvas');
                canvas.width = viewport.width;
                canvas.height = viewport.height;
                $wrapper.append(canvas);
                pageCanvases[pageNum - 1] = canvas;

                $wrapper.append($('<div>', { 'class': 'pdf-page-label', text: 'Page ' + pageNum }));
                $('#pdfViewer').append($wrapper);

                // Click handler for PDF-to-tree selection
                $(canvas).on('click', function (e) {
                    Viewer.handleClick(e, pageNum - 1);
                }).on('mousedown', function (e) {
                    if (editMode && editFieldType) {
                        EditMode.startDraw(e, pageNum - 1, $wrapper[0]);
                    }
                });

                return page.render({ canvasContext: canvas.getContext('2d'), viewport: viewport }).promise;
            });
        },

        highlight: function (pageIndex, bbox) {
            Viewer.scrollToPage(pageIndex);

            var $wrapper = $('[data-page="' + pageIndex + '"]');
            if (!$wrapper.length || !pageViewports[pageIndex]) return;

            var vp = pageViewports[pageIndex];
            var scale = vp.scale;
            var x = bbox[0] * scale;
            var y = vp.height - (bbox[1] + bbox[3]) * scale;
            var w = bbox[2] * scale;
            var h = bbox[3] * scale;

            $('<div>', { 'class': 'pdf-highlight' }).css({
                left: x + 'px',
                top: y + 'px',
                width: w + 'px',
                height: h + 'px'
            }).appendTo($wrapper);
        },

        clearHighlights: function () {
            $('.pdf-highlight').remove();
        },

        scrollToPage: function (pageIndex) {
            var wrapper = $('[data-page="' + pageIndex + '"]')[0];
            if (wrapper) {
                wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        },

        handleClick: function (e, pageIndex) {
            if (editMode) return;
            if (!treeData || !pageViewports[pageIndex]) return;

            var canvas = pageCanvases[pageIndex];
            if (!canvas) return;
            var rect = canvas.getBoundingClientRect();
            var vp = pageViewports[pageIndex];
            var scale = vp.scale;

            var pdfX = (e.clientX - rect.left) / scale;
            var pdfY = (vp.height - (e.clientY - rect.top)) / scale;

            var match = Viewer.findNodeAtPoint(treeData, pageIndex, pdfX, pdfY);
            if (match) {
                Tree.selectNode(match);
            }
        },

        findNodeAtPoint: function (node, pageIndex, x, y) {
            var best = null;
            if (node.pageIndex === pageIndex && node.boundingBox) {
                var bb = node.boundingBox;
                if (x >= bb[0] && x <= bb[0] + bb[2] && y >= bb[1] && y <= bb[1] + bb[3]) {
                    best = node;
                }
            }
            if (node.children) {
                for (var i = 0; i < node.children.length; i++) {
                    var childMatch = Viewer.findNodeAtPoint(node.children[i], pageIndex, x, y);
                    if (childMatch) best = childMatch;
                }
            }
            return best;
        }
    };

    // ===================== SEARCH =====================

    var Search = {
        timer: null,
        init: function () {
            $('#searchInput').on('input', function () {
                clearTimeout(Search.timer);
                var query = $(this).val().trim();
                if (query.length === 0) {
                    Tree.render(treeData);
                    return;
                }
                Search.timer = setTimeout(function () {
                    Search.search(query);
                }, 300);
            });
        },

        search: function (query) {
            if (!sessionId) return;
            apiFetch('/api/tree/' + sessionId + '/search', { data: { q: query } })
                .done(function (results) {
                    Tree.renderSearchResults(results);
                })
                .fail(function () {
                    toast('Search error', 'danger');
                });
        }
    };

    // ===================== TABS =====================

    var Tabs = {
        init: function () {
            $('.tab-btn').on('click', function () {
                $('.tab-btn').removeClass('active');
                $(this).addClass('active');
                currentTab = $(this).data('tab');
                Tabs.switchTab(currentTab);
            });
        },

        switchTab: function (tab) {
            if (!treeData || !sessionId) return;

            switch (tab) {
                case 'structure':
                    Tree.render(treeData);
                    break;
                case 'forms':
                    Tree.renderSubtree(treeData, 'acroform');
                    break;
                case 'fonts':
                    Tabs.loadFonts();
                    break;
                case 'validation':
                    Tabs.loadValidation();
                    break;
                case 'rawcos':
                    Tabs.loadRawCos();
                    break;
                case 'bookmarks':
                    Tree.renderSubtree(treeData, 'bookmarks');
                    break;
            }
        },

        loadFonts: function () {
            apiFetch('/api/fonts/' + sessionId)
                .done(function (fonts) {
                    Tabs.renderFontTable(fonts);
                })
                .fail(function () { toast('Font analysis error', 'danger'); });
        },

        renderFontTable: function (fonts) {
            var $container = $('#treeContent');
            if (fonts.length === 0) {
                $container.html('<div class="text-muted text-center mt-3">No fonts found</div>');
                return;
            }

            var html = '<table class="font-table"><thead><tr>' +
                '<th>Font</th><th>Type</th><th>Embedded</th><th>Subset</th><th>Page</th><th>Issues</th>' +
                '</tr></thead><tbody>';

            for (var i = 0; i < fonts.length; i++) {
                var f = fonts[i];
                var embIcon = f.embedded
                    ? '<i class="fas fa-check-circle text-success"></i>'
                    : '<i class="fas fa-times-circle text-danger"></i>';
                var subsetIcon = f.subset
                    ? '<i class="fas fa-check text-muted"></i>'
                    : '<i class="fas fa-minus text-muted"></i>';
                var issueHtml = '';
                if (f.issues && f.issues.length > 0) {
                    issueHtml = '<i class="fas fa-exclamation-triangle text-warning me-1"></i>' +
                        '<span title="' + Tree.escapeHtml(f.issues.join('; ')) + '">' + f.issues.length + '</span>';
                } else {
                    issueHtml = '<i class="fas fa-check text-success"></i>';
                }
                html += '<tr><td>' + Tree.escapeHtml(f.fontName || '(unknown)') + '</td>' +
                    '<td>' + Tree.escapeHtml(f.fontType || '') + '</td>' +
                    '<td>' + embIcon + '</td>' +
                    '<td>' + subsetIcon + '</td>' +
                    '<td>' + (f.pageIndex + 1) + '</td>' +
                    '<td>' + issueHtml + '</td></tr>';
            }
            html += '</tbody></table>';
            $container.html(html);
        },

        loadValidation: function () {
            var $container = $('#treeContent');
            $container.html(
                '<div class="text-center mt-3">' +
                '<button class="btn btn-accent btn-sm me-2" id="runValidateBtn">' +
                '<i class="fas fa-play me-1"></i>Run Validation</button>' +
                '<button class="btn btn-outline-accent btn-sm" id="exportValidateBtn" disabled>' +
                '<i class="fas fa-download me-1"></i>Export Report</button></div>'
            );

            $('#runValidateBtn').on('click', function () {
                $container.html('<div class="text-muted text-center mt-3">' +
                    '<span class="spinner-border spinner-border-sm"></span> Validating...</div>');

                apiFetch('/api/validate/' + sessionId)
                    .done(function (issues) {
                        Tabs.renderValidation(issues);
                    })
                    .fail(function () { toast('Validation error', 'danger'); });
            });

            $('#exportValidateBtn').on('click', function () {
                window.open('/api/validate/' + sessionId + '/export', '_blank');
            });
        },

        loadRawCos: function () {
            apiFetch('/api/tree/' + sessionId + '/raw-cos')
                .done(function (rawTree) {
                    Tree.render(rawTree);
                })
                .fail(function () {
                    toast('Failed to load raw COS', 'danger');
                });
        },

        renderValidation: function (issues) {
            var $container = $('#treeContent');
            var exportBtn = '<div class="text-end mb-2" style="padding:4px 8px;">' +
                '<button class="btn btn-outline-accent btn-sm" onclick="window.open(\'/api/validate/' + sessionId + '/export\', \'_blank\')">' +
                '<i class="fas fa-download me-1"></i>Export Report</button></div>';

            if (issues.length === 0) {
                $container.html(exportBtn +
                    '<div class="text-center mt-3">' +
                    '<i class="fas fa-check-circle text-success fa-2x"></i>' +
                    '<p class="mt-2 text-success">No issues found!</p></div>');
                return;
            }

            var order = { 'ERROR': 0, 'WARNING': 1, 'INFO': 2 };
            issues.sort(function (a, b) { return (order[a.severity] || 3) - (order[b.severity] || 3); });

            var html = exportBtn + '<div style="padding:8px;">';
            var counts = { ERROR: 0, WARNING: 0, INFO: 0 };
            for (var i = 0; i < issues.length; i++) counts[issues[i].severity] = (counts[issues[i].severity] || 0) + 1;

            html += '<div class="mb-2" style="font-size:12px;">' +
                (counts.ERROR ? '<span class="text-danger me-3"><i class="fas fa-times-circle"></i> ' + counts.ERROR + ' errors</span>' : '') +
                (counts.WARNING ? '<span class="text-warning me-3"><i class="fas fa-exclamation-triangle"></i> ' + counts.WARNING + ' warnings</span>' : '') +
                (counts.INFO ? '<span class="text-info"><i class="fas fa-info-circle"></i> ' + counts.INFO + ' info</span>' : '') +
                '</div>';

            for (var j = 0; j < issues.length; j++) {
                var issue = issues[j];
                var cls = issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info';
                var severityIcon = issue.severity === 'ERROR' ? 'fa-times-circle text-danger'
                    : issue.severity === 'WARNING' ? 'fa-exclamation-triangle text-warning'
                        : 'fa-info-circle text-info';

                html += '<div class="validation-issue ' + cls + '">' +
                    '<div class="issue-header"><i class="fas ' + severityIcon + '"></i>' +
                    '<span class="issue-rule">' + Tree.escapeHtml(issue.ruleId) + '</span></div>' +
                    '<div>' + Tree.escapeHtml(issue.message) + '</div>' +
                    '<div class="issue-spec">' + Tree.escapeHtml(issue.specReference || '') +
                    ' &mdash; ' + Tree.escapeHtml(issue.location || '') + '</div></div>';
            }
            html += '</div>';
            $container.html(html);
        }
    };

    // ===================== EDIT MODE =====================

    var EditMode = {
        drawing: false,
        startX: 0, startY: 0,
        $drawRect: null,
        targetPage: -1,

        init: function () {
            $('#editModeBtn').on('click', function () {
                editMode = !editMode;
                $(this).toggleClass('active', editMode);
                $('#editToolbar').toggleClass('active', editMode);
                if (!editMode) {
                    editFieldType = null;
                    $('.edit-field-btn').removeClass('active');
                }
            });

            $('.edit-field-btn').on('click', function () {
                $('.edit-field-btn').removeClass('active');
                $(this).addClass('active');
                editFieldType = $(this).data('type');
            });
        },

        startDraw: function (e, pageIndex, wrapper) {
            EditMode.drawing = true;
            EditMode.targetPage = pageIndex;
            var rect = wrapper.getBoundingClientRect();
            EditMode.startX = e.clientX - rect.left;
            EditMode.startY = e.clientY - rect.top;

            EditMode.$drawRect = $('<div>', { 'class': 'draw-rect' }).css({
                left: EditMode.startX + 'px',
                top: EditMode.startY + 'px'
            }).appendTo(wrapper);

            var moveHandler = function (ev) {
                if (!EditMode.drawing) return;
                var curX = ev.clientX - rect.left;
                var curY = ev.clientY - rect.top;
                EditMode.$drawRect.css({
                    left: Math.min(EditMode.startX, curX) + 'px',
                    top: Math.min(EditMode.startY, curY) + 'px',
                    width: Math.abs(curX - EditMode.startX) + 'px',
                    height: Math.abs(curY - EditMode.startY) + 'px'
                });
            };

            var upHandler = function (ev) {
                EditMode.drawing = false;
                $(document).off('mousemove', moveHandler).off('mouseup', upHandler);

                var curX = ev.clientX - rect.left;
                var curY = ev.clientY - rect.top;
                var x = Math.min(EditMode.startX, curX);
                var y = Math.min(EditMode.startY, curY);
                var w = Math.abs(curX - EditMode.startX);
                var h = Math.abs(curY - EditMode.startY);

                if (EditMode.$drawRect) EditMode.$drawRect.remove();

                if (w < 10 || h < 10) return;

                var vp = pageViewports[pageIndex];
                var scale = vp.scale;
                var pdfX = x / scale;
                var pdfY = (vp.height - y - h) / scale;
                var pdfW = w / scale;
                var pdfH = h / scale;

                var fieldName = prompt('Field name:', editFieldType + '_' + Date.now());
                if (!fieldName) return;

                EditMode.addField(pageIndex, pdfX, pdfY, pdfW, pdfH, fieldName);
            };

            $(document).on('mousemove', moveHandler).on('mouseup', upHandler);
        },

        addField: function (pageIndex, x, y, w, h, fieldName) {
            var body = {
                fieldType: editFieldType,
                fieldName: fieldName,
                pageIndex: pageIndex,
                x: x, y: y, width: w, height: h
            };

            apiFetch('/api/edit/' + sessionId + '/add-field', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(body)
            })
                .done(function (data) {
                    treeData = data.tree;
                    Tree.render(treeData);
                    Viewer.loadPdf(sessionId);
                    toast('Field "' + fieldName + '" added', 'success');
                })
                .fail(function () { toast('Add field failed', 'danger'); });
        }
    };

    // ===================== COS EDITOR =====================

    var CosEditor = {
        show: function (node, $headerEl) {
            // Remove any existing editor
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
                case 'string':
                case 'hex-string':
                default:
                    $input = $('<input>', { 'class': 'cos-edit-input', type: 'text', value: node.rawValue || '' });
                    break;
            }
            $editor.append($input);

            var $saveBtn = $('<button>', {
                'class': 'cos-edit-save',
                title: 'Save',
                html: '<i class="fas fa-check"></i>'
            }).on('click', function () {
                CosEditor.save(node, $input.val(), $editor);
            });
            $editor.append($saveBtn);

            var $cancelBtn = $('<button>', {
                'class': 'cos-edit-cancel',
                title: 'Cancel',
                html: '<i class="fas fa-times"></i>'
            }).on('click', function () {
                $editor.remove();
            });
            $editor.append($cancelBtn);

            // Insert editor after the header
            $headerEl.after($editor);
            $input.trigger('focus').trigger('select');

            // Enter saves, Escape cancels
            $input.on('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); $saveBtn.trigger('click'); }
                if (e.key === 'Escape') { e.preventDefault(); $cancelBtn.trigger('click'); }
                e.stopPropagation();
            });
        },

        addEntry: function(node, $headerEl) {
            // inline form instead of browser prompts
            if (!sessionId || !node.keyPath) {
                toast('Cannot add entry: key path unavailable', 'warning');
                return;
            }
            // remove any existing add or edit widgets
            $('.cos-inline-editor, .cos-add-editor').remove();

            var path = JSON.parse(node.keyPath);
            var isArray = node.cosType === 'COSArray';

            var $form = $('<div>', { 'class': 'cos-add-editor' });
            var $keyInput = $('<input>', {
                'class': 'cos-edit-input',
                type: isArray ? 'number' : 'text',
                placeholder: isArray ? 'index (blank=append)' : 'new key'
            });
            if (isArray) $keyInput.attr('min', '0');
            $form.append($keyInput);

            var $typeSelect = $('<select>', { 'class': 'cos-edit-input' });
            var types = ['boolean', 'integer', 'float', 'name', 'string', 'hex-string'];
            types.forEach(function(t) {
                var v = 'COS' + t.charAt(0).toUpperCase() + t.slice(1);
                $typeSelect.append($('<option>', { value: v, text: t }));
            });
            $form.append($typeSelect);

            var $valueInput = $('<input>', { 'class': 'cos-edit-input', type: 'text', placeholder: 'value' });
            $form.append($valueInput);

            var $saveBtn = $('<button>', {
                'class': 'cos-edit-save',
                title: 'Add',
                html: '<i class="fas fa-check"></i>'
            }).on('click', function () {
                var key = $keyInput.val();
                if (!isArray && (!key || key.trim().length === 0)) {
                    toast('Key cannot be empty', 'warning');
                    return;
                }
                
                // Validate using DictEditor
                if (!isArray) {
                    var validation = DictEditor.validateAddEntry(node, key);
                    if (!validation.valid) {
                        toast(validation.message, 'warning');
                        return;
                    }
                }
                
                var type = $typeSelect.val();
                var val = $valueInput.val();
                var k = key; // keep as string; empty string will trigger append on backend
                var body = {
                    objectNumber: node.objectNumber,
                    generationNumber: node.generationNumber || 0,
                    keyPath: path.concat([k]),
                    newValue: val,
                    valueType: type,
                    operation: 'add'
                };
                apiFetch('/api/cos/' + sessionId + '/update', {
                    method: 'POST',
                    contentType: 'application/json',
                    data: JSON.stringify(body)
                })
                    .done(function (data) {
                        treeData = data.tree;
                        Tree.render(treeData);
                        Viewer.loadPdf(sessionId);
                        toast('Entry added', 'success');
                    })
                    .fail(function () {
                        toast('Add failed', 'danger');
                    });
                $form.remove();
            });
            var $cancelBtn = $('<button>', {
                'class': 'cos-edit-cancel',
                title: 'Cancel',
                html: '<i class="fas fa-times"></i>'
            }).on('click', function () {
                $form.remove();
            });
            $form.append($saveBtn, $cancelBtn);

            // insert form after header element
            if ($headerEl && $headerEl.length) {
                $headerEl.after($form);
            } else {
                $('.tree-node-header').last().after($form);
            }
            $keyInput.trigger('focus');
        },
        remove: function(node, $headerEl) {
            if (!sessionId || node.objectNumber === undefined || node.objectNumber < 0) {
                toast('Cannot delete: object reference not available', 'warning');
                return;
            }
            var keyPath;
            try { keyPath = JSON.parse(node.keyPath); }
            catch (e) { toast('Cannot delete: no key path', 'warning'); return; }
            if (!keyPath || keyPath.length === 0) {
                toast('Cannot delete: empty key path', 'warning');
                return;
            }
            // inline confirmation box
            $('.cos-inline-editor, .cos-add-editor, .cos-delete-confirm').remove();
            var $confirm = $('<div>', { 'class': 'cos-delete-confirm' });
            $confirm.text('Delete ' + node.name + '? ');
            var $yes = $('<button>', { 'class': 'btn btn-sm btn-danger me-1', text: 'Yes' })
                .on('click', function () {
                    var body = {
                        objectNumber: node.objectNumber,
                        generationNumber: node.generationNumber || 0,
                        keyPath: keyPath,
                        operation: 'remove'
                    };
                    apiFetch('/api/cos/' + sessionId + '/update', {
                        method: 'POST',
                        contentType: 'application/json',
                        data: JSON.stringify(body)
                    })
                        .done(function (data) {
                            treeData = data.tree;
                            Tree.render(treeData);
                            Viewer.loadPdf(sessionId);
                            toast('Entry removed', 'success');
                        })
                        .fail(function () {
                            toast('Delete failed', 'danger');
                        });
                    $confirm.remove();
                });
            var $no = $('<button>', { 'class': 'btn btn-sm btn-secondary', text: 'No' })
                .on('click', function () { $confirm.remove(); });
            $confirm.append($yes, $no);
            if ($headerEl && $headerEl.length) {
                $headerEl.after($confirm);
            } else {
                $('.tree-node-header').last().after($confirm);
            }
        },

        save: function (node, newValue, $editorEl) {
            if (!sessionId || node.objectNumber === undefined || node.objectNumber < 0) {
                toast('Cannot edit: object reference not available', 'warning');
                return;
            }

            var keyPath;
            try { keyPath = JSON.parse(node.keyPath); }
            catch (e) { toast('Cannot edit: no key path', 'warning'); return; }

            if (!keyPath || keyPath.length === 0) {
                toast('Cannot edit: empty key path', 'warning');
                return;
            }

            var body = {
                objectNumber: node.objectNumber,
                generationNumber: node.generationNumber || 0,
                keyPath: keyPath,
                newValue: newValue,
                valueType: node.cosType,
                operation: 'update'
            };

            apiFetch('/api/cos/' + sessionId + '/update', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(body)
            })
                .done(function (data) {
                    treeData = data.tree;
                    Tree.render(treeData);
                    Viewer.loadPdf(sessionId);
                    toast('Value updated successfully', 'success');
                })
                .fail(function () {
                    toast('Edit failed', 'danger');
                })
                .always(function () { $editorEl.remove(); });
        }
    };

    // ===================== DICT EDITOR (Type-aware dictionary management) =====================

    var DictEditor = {
        // PDFs dictionary type definitions and allowed/common keys
        dictTypeInfo: {
            'Catalog': {
                description: 'PDF Document Catalog (root)',
                required: ['Type', 'Pages'],
                common: ['Pages', 'Outlines', 'StructTreeRoot', 'Lang', 'Threads', 'AcroForm', 'Resources']
            },
            'Pages': {
                description: 'Pages tree node',
                required: ['Type', 'Count', 'Kids'],
                common: ['Kids', 'Count', 'Parent', 'MediaBox', 'Resources']
            },
            'Page': {
                description: 'Individual page',
                required: ['Type', 'Parent', 'MediaBox'],
                common: ['MediaBox', 'Contents', 'Resources', 'Parent', 'Rotate', 'CropBox', 'BleedBox', 'TrimBox', 'ArtBox']
            },
            'AcroForm': {
                description: 'Interactive form (AcroForm)',
                required: ['Fields'],
                common: ['Fields', 'SigFlags', 'NeedAppearances', 'DA', 'Resources', 'XFA']
            },
            'Font': {
                description: 'Font descriptor',
                required: ['Type', 'Subtype', 'BaseFont'],
                common: ['BaseFont', 'Subtype', 'Encoding', 'FirstChar', 'LastChar', 'Widths', 'FontDescriptor', 'ToUnicode']
            },
            'XObject': {
                description: 'External object (image, form, etc.)',
                required: ['Type', 'Subtype'],
                common: ['Subtype', 'Width', 'Height', 'ColorSpace', 'BitsPerComponent', 'Filter']
            },
            'Outlines': {
                description: 'Document outline (bookmarks)',
                required: ['Type'],
                common: ['First', 'Last', 'Count']
            },
            'GenericDictionary': {
                description: 'Generic PDF Dictionary',
                common: []
            }
        },

        // Detect dictionary type from node properties
        detectDictType: function(node) {
            if (!node.properties) return 'GenericDictionary';
            var type = node.properties.Type;
            var subtype = node.properties.Subtype;
            
            // Map to known types
            if (type === 'Catalog') return 'Catalog';
            if (type === 'Pages') return 'Pages';
            if (type === 'Page') return 'Page';
            if (type === 'Font') return 'Font';
            if (type === 'XObject') return 'XObject';
            if (type === 'Outlines') return 'Outlines';
            if (node.name === '/AcroForm' || type === 'AcroForm') return 'AcroForm';
            
            return 'GenericDictionary';
        },

        // Get info about a dictionary type
        getDictTypeInfo: function(dictType) {
            return DictEditor.dictTypeInfo[dictType] || DictEditor.dictTypeInfo['GenericDictionary'];
        },

        // Show info popup about dictionary
        showInfo: function(node) {
            $('.cos-dict-info').remove();
            
            var dictType = DictEditor.detectDictType(node);
            var info = DictEditor.getDictTypeInfo(dictType);
            
            var $infoDiv = $('<div>', { 'class': 'cos-dict-info' });
            var $title = $('<div>', { 'class': 'dict-info-title' });
            $title.append($('<strong>', { text: 'Type: ' + dictType }));
            $title.append($('<br>'));
            $title.append($('<em>', { text: info.description }));
            $infoDiv.append($title);
            
            if (info.required && info.required.length > 0) {
                var $reqDiv = $('<div>', { 'class': 'dict-info-section' });
                $reqDiv.append($('<strong>', { text: 'Required Keys:' }));
                var $reqList = $('<ul>');
                info.required.forEach(function(key) {
                    $reqList.append($('<li>', { text: key }));
                });
                $reqDiv.append($reqList);
                $infoDiv.append($reqDiv);
            }
            
            if (info.common && info.common.length > 0) {
                var $commonDiv = $('<div>', { 'class': 'dict-info-section' });
                $commonDiv.append($('<strong>', { text: 'Common Keys:' }));
                var $commonList = $('<ul>');
                info.common.forEach(function(key) {
                    $commonList.append($('<li>', { text: key }));
                });
                $commonDiv.append($commonList);
                $infoDiv.append($commonDiv);
            }
            
            var $closeBtn = $('<button>', {
                'class': 'btn btn-sm btn-secondary',
                text: 'Close'
            }).on('click', function() {
                $infoDiv.remove();
            });
            $infoDiv.append($closeBtn);
            
            // Find and append after the tree node
            $('.tree-node[data-node-id="' + node.id + '"]').append($infoDiv);
        },

        // Validate adding a new entry to a dictionary
        validateAddEntry: function(parentNode, newKey) {
            var dictType = DictEditor.detectDictType(parentNode);
            var info = DictEditor.getDictTypeInfo(dictType);
            
            // Basic validation
            if (!newKey || newKey.trim().length === 0) {
                return { valid: false, message: 'Key cannot be empty' };
            }
            
            // Check for duplicate keys
            if (parentNode.children) {
                var exists = parentNode.children.some(function(child) {
                    return child.name === '/' + newKey || child.name === newKey;
                });
                if (exists) {
                    return { valid: false, message: 'Key "' + newKey + '" already exists' };
                }
            }
            
            return { valid: true, message: 'Key is valid for this dictionary type' };
        }
    };

    // ===================== KEYBOARD SHORTCUTS =====================

    var Keyboard = {
        init: function () {
            $(document).on('keydown', function (e) {
                var tag = e.target.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

                // Ctrl+O: Open file
                if ((e.ctrlKey || e.metaKey) && e.key === 'o') {
                    e.preventDefault();
                    $('#fileInput').trigger('click');
                }
                // Ctrl+F: Focus search
                if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
                    e.preventDefault();
                    var $search = $('#searchInput');
                    if (!$search.prop('disabled')) {
                        $search.trigger('focus').trigger('select');
                    }
                }
                // Ctrl+S: Download PDF
                if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                    e.preventDefault();
                    if (sessionId) {
                        window.open('/api/pdf/' + sessionId + '/download', '_blank');
                    }
                }
                // Ctrl+E: Toggle edit mode
                if ((e.ctrlKey || e.metaKey) && e.key === 'e') {
                    e.preventDefault();
                    if (sessionId) {
                        $('#editModeBtn').trigger('click');
                    }
                }
                // Escape: Exit edit mode or clear search
                if (e.key === 'Escape') {
                    if (editMode) {
                        $('#editModeBtn').trigger('click');
                    } else {
                        var $search = $('#searchInput');
                        if ($search.is(':focus')) {
                            $search.val('').trigger('blur');
                            if (treeData) Tree.render(treeData);
                        }
                    }
                }
                // 1-7: Switch tabs
                if (e.key >= '1' && e.key <= '7' && !e.ctrlKey && !e.metaKey && !e.altKey) {
                    var $tabs = $('.tab-btn');
                    var tabIndex = parseInt(e.key) - 1;
                    if (tabIndex < $tabs.length && sessionId) {
                        $tabs.eq(tabIndex).trigger('click');
                    }
                }
            });
        }
    };

    // ===================== EXPORT =====================

    var Export = {
        init: function () {
            $('#downloadBtn').on('click', function () {
                if (sessionId) {
                    window.open('/api/pdf/' + sessionId + '/download', '_blank');
                }
            });

            $('#exportTreeBtn').on('click', function () {
                if (sessionId) {
                    window.open('/api/tree/' + sessionId + '/export', '_blank');
                }
            });
        }
    };

    // ===================== ZOOM HANDLING =====================

    var Zoom = {
        init: function() {
            var $btn = $('#zoomModeBtn');
            $btn.on('click', function () {
                // cycle: off -> width -> height -> off
                if (autoZoomMode === 'off') {
                    Viewer.fitWidth();
                } else if (autoZoomMode === 'width') {
                    Viewer.fitHeight();
                } else {
                    Viewer.setScale(currentScale); // this will turn auto off
                }
            });

            // ctrl/cmd + wheel for manual zoom
            $('#pdfPane').on('wheel', function (e) {
                var ev = e.originalEvent;
                if (ev.ctrlKey || ev.metaKey) {
                    e.preventDefault();
                    var delta = ev.deltaY;
                    var factor = delta > 0 ? 0.9 : 1.1;
                    Viewer.setScale(currentScale * factor);
                }
            });

            // reapply auto zoom when container size changes
            $(window).on('resize', function () {
                Viewer.applyAutoZoom();
            });

            // initialize icon/tooltip state
            Zoom.updateButton();
        },
        updateButton: function() {
            var $btn = $('#zoomModeBtn');
            if (!$btn.length) return;
            var icons = {
                off: 'fa-expand-arrows-alt',
                width: 'fa-arrows-alt-h',
                height: 'fa-arrows-alt-v'
            };
            var title = 'Toggle auto zoom mode';
            if (autoZoomMode === 'width') title = 'Auto zoom: fit width';
            else if (autoZoomMode === 'height') title = 'Auto zoom: fit height';
            $btn.attr('title', title);
            $btn.find('i').attr('class', 'fas ' + icons[autoZoomMode]);
            $btn.toggleClass('active', autoZoomMode !== 'off');
        }
    };

    // ===================== DIVIDER RESIZE =====================

    var Divider = {
        init: function () {
            var dragging = false;
            var $treePane = $('#treePane');

            $('#divider').on('mousedown', function () {
                dragging = true;
                $(this).addClass('dragging');
                $('body').css({ cursor: 'col-resize', 'user-select': 'none' });
            });

            $(document).on('mousemove', function (e) {
                if (!dragging) return;
                var newWidth = document.body.clientWidth - e.clientX;
                newWidth = Math.max(250, Math.min(newWidth, window.innerWidth * 0.6));
                $treePane.css('width', newWidth + 'px');
            }).on('mouseup', function () {
                if (dragging) {
                    dragging = false;
                    $('#divider').removeClass('dragging');
                    $('body').css({ cursor: '', 'user-select': '' });
                    // after user finishes resizing, re-evaluate auto zoom
                    Viewer.applyAutoZoom();
                }
            });
        }
    };

    // ===================== INIT =====================

    function init() {
        Upload.init();
        Search.init();
        Tabs.init();
        EditMode.init();
        Divider.init();
        Keyboard.init();
        Export.init();
        Zoom.init();
    }

    $(init);

    return { init: init };
})(jQuery);
