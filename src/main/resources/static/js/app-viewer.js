/**
 * PDFalyzer Studio – PDF viewer (PDF.js rendering, highlight, zoom).
 */
PDFalyzer.Viewer = (function ($, P) {
    'use strict';

    function ensureStagingViewer() {
        var $staging = $('#pdfViewerStaging');
        if ($staging.length) return $staging;
        $staging = $('<div>', { id: 'pdfViewerStaging', 'class': 'pdf-viewer-staging' });
        $('#pdfPane').append($staging);
        return $staging;
    }

    function capturePaneViewState() {
        var pane = $('#pdfPane')[0];
        var $viewer = $('#pdfViewer');
        if (!pane || !$viewer.length) return null;

        var centerY = pane.scrollTop + (pane.clientHeight / 2);
        var bestPage = -1;
        var bestDistance = Number.POSITIVE_INFINITY;
        var bestOffset = 0;

        $viewer.find('.pdf-page-wrapper').each(function () {
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            if (!isFinite(pageIndex)) return;
            var top = this.offsetTop;
            var bottom = top + this.offsetHeight;
            var distance;
            if (centerY < top) distance = top - centerY;
            else if (centerY > bottom) distance = centerY - bottom;
            else distance = 0;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPage = pageIndex;
                bestOffset = centerY - top;
            }
        });

        return {
            scrollTop: pane.scrollTop,
            scrollLeft: pane.scrollLeft,
            anchorPage: bestPage,
            anchorOffset: bestOffset
        };
    }

    function restorePaneViewState(viewState, exactOnly) {
        if (!viewState) return;
        var pane = $('#pdfPane')[0];
        if (!pane) return;

        pane.scrollLeft = typeof viewState.scrollLeft === 'number' ? viewState.scrollLeft : 0;

        var restored = false;
        if (!exactOnly && typeof viewState.anchorPage === 'number' && viewState.anchorPage >= 0) {
            var wrapper = $('#pdfViewer .pdf-page-wrapper[data-page="' + viewState.anchorPage + '"]')[0];
            if (wrapper) {
                var center = wrapper.offsetTop + (viewState.anchorOffset || 0);
                var desiredTop = center - (pane.clientHeight / 2);
                pane.scrollTop = Math.max(0, desiredTop);
                restored = true;
            }
        }

        if (!restored && typeof viewState.scrollTop === 'number') {
            pane.scrollTop = viewState.scrollTop;
        }
    }

    function waitForPaneSettle(targetScrollTop, maxWaitMs) {
        var pane = $('#pdfPane')[0];
        if (!pane) return Promise.resolve();

        var timeoutMs = typeof maxWaitMs === 'number' ? maxWaitMs : 1000;
        var start = (window.performance && window.performance.now) ? window.performance.now() : Date.now();
        var stableFrames = 0;
        var lastTop = pane.scrollTop;

        return new Promise(function (resolve) {
            function tick() {
                var now = (window.performance && window.performance.now) ? window.performance.now() : Date.now();
                var currentTop = pane.scrollTop;
                var referenceTop = typeof targetScrollTop === 'number' ? targetScrollTop : lastTop;
                var isStable = Math.abs(currentTop - referenceTop) <= 1;

                stableFrames = isStable ? (stableFrames + 1) : 0;
                lastTop = currentTop;

                if (stableFrames >= 3 || (now - start) >= timeoutMs) {
                    resolve();
                    return;
                }

                window.requestAnimationFrame(tick);
            }

            window.requestAnimationFrame(tick);
        });
    }

    function swapStagedViewer($staging, viewState) {
        var $viewer = $('#pdfViewer');
        if (!$viewer.length || !$staging.length) return;
        $staging.removeClass('pdf-viewer-crossfade-in').addClass('pdf-viewer-staging-active');

        restorePaneViewState(viewState, true);
        var targetTop = viewState && typeof viewState.scrollTop === 'number' ? viewState.scrollTop : null;

        waitForPaneSettle(targetTop, 1000).then(function () {
            window.requestAnimationFrame(function () {
                window.requestAnimationFrame(function () {
                    $staging.addClass('pdf-viewer-crossfade-in');
                });
            });

            window.setTimeout(function () {
                $viewer.remove();
                $staging
                    .attr('id', 'pdfViewer')
                    .removeClass('pdf-viewer-staging pdf-viewer-staging-active pdf-viewer-crossfade-in')
                    .css({ visibility: '', pointerEvents: '', opacity: '', zIndex: '', position: '', inset: '', overflow: '' });
            }, 190);
        });
    }

    function renderPageToContainer(pdf, pageNum, $container, pageViewports, pageCanvases, disableEntryAnimation) {
        return pdf.getPage(pageNum).then(function (page) {
            var scale = P.state.currentScale;
            var viewport = page.getViewport({ scale: scale });
            pageViewports[pageNum - 1] = viewport;

            var $wrapper = $('<div>', { 'class': 'pdf-page-wrapper', 'data-page': pageNum - 1 })
                .css('animation-delay', ((pageNum - 1) * 0.08) + 's');
            if (disableEntryAnimation) {
                $wrapper.addClass('pdf-page-wrapper-static').css('animation-delay', '0s');
            }
            var canvas = document.createElement('canvas');
            canvas.width = viewport.width;
            canvas.height = viewport.height;
            $wrapper.append(canvas);
            pageCanvases[pageNum - 1] = canvas;
            $wrapper.append($('<div>', { 'class': 'pdf-page-label', text: 'Page ' + pageNum }));
            $container.append($wrapper);

            $(canvas).on('click', function (e) { handleClick(e, pageNum - 1); })
                .on('mousedown', function (e) {
                    if (P.state.editMode && P.state.editFieldType) {
                        P.EditMode.startDraw(e, pageNum - 1, $wrapper[0]);
                    }
                });

            return page.render({ canvasContext: canvas.getContext('2d'), viewport: viewport }).promise
                .then(function () {
                    if (P.EditMode && P.EditMode.renderFieldHandles) {
                        P.EditMode.renderFieldHandles(pageNum - 1, $wrapper[0]);
                    }
                });
        });
    }

    function renderPdfIntoContainer(pdf, $container, pageViewports, pageCanvases, options) {
        var opts = options || {};
        var disableEntryAnimation = !!opts.disableEntryAnimation;
        $container.empty();
        var chain = Promise.resolve();
        for (var i = 1; i <= pdf.numPages; i++) {
            chain = chain.then((function (pageNum) {
                return function () {
                    return renderPageToContainer(pdf, pageNum, $container, pageViewports, pageCanvases,
                        disableEntryAnimation);
                };
            })(i));
        }
        return chain;
    }

    function loadPdf(sid, options) {
        var opts = options || {};
        var url = '/api/pdf/' + sid;
        var $viewer = $('#pdfViewer').show();
        var viewState = opts.preserveView ? capturePaneViewState() : null;
        var useSmoothSwap = !!opts.smoothSwap && $viewer.children().length > 0;
        var $target = $viewer;
        var $staging = null;

        if (useSmoothSwap) {
            $staging = ensureStagingViewer();
            $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();
            $target = $staging;
        }

        $('#uploadSplash').hide();
        var nextViewports = [];
        var nextCanvases = [];

        pdfjsLib.getDocument(url).promise.then(function (pdf) {
            P.state.pdfDoc = pdf;
            pdf.getPage(1).then(function (p) {
                var vp = p.getViewport({ scale: 1 });
                P.state.basePageSize.width  = vp.width;
                P.state.basePageSize.height = vp.height;
            });
            return renderPdfIntoContainer(pdf, $target, nextViewports, nextCanvases,
                { disableEntryAnimation: useSmoothSwap })
                .then(function () {
                    P.state.pageViewports = nextViewports;
                    P.state.pageCanvases = nextCanvases;
                    if (useSmoothSwap && $staging) {
                        swapStagedViewer($staging, viewState);
                    } else {
                        restorePaneViewState(viewState);
                    }
                });
        }).catch(function (err) {
            if ($staging) {
                $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();
            }
            P.Utils.toast('Failed to render PDF: ' + err.message, 'danger');
        });
    }

    function renderAllPages(options) {
        if (!P.state.pdfDoc) return;
        var opts = options || {};
        var viewState = opts.preserveView ? capturePaneViewState() : null;
        var nextViewports = [];
        var nextCanvases = [];

        return renderPdfIntoContainer(P.state.pdfDoc, $('#pdfViewer'), nextViewports, nextCanvases)
            .then(function () {
                P.state.pageViewports = nextViewports;
                P.state.pageCanvases = nextCanvases;
                restorePaneViewState(viewState);
            });
    }

    function setScale(scale) {
        if (scale <= 0) return;
        P.state.currentScale  = scale;
        P.state.autoZoomMode  = 'off';
        renderAllPages({ preserveView: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function fitWidth() {
        if (!P.state.basePageSize.width) return;
        var avail = $('#pdfPane').width() - 40;
        P.state.currentScale = avail / P.state.basePageSize.width;
        P.state.autoZoomMode = 'width';
        renderAllPages({ preserveView: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function fitHeight() {
        if (!P.state.basePageSize.height) return;
        var avail = $('#pdfPane').height() - 40;
        P.state.currentScale = avail / P.state.basePageSize.height;
        P.state.autoZoomMode = 'height';
        renderAllPages({ preserveView: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function applyAutoZoom() {
        if (P.state.autoZoomMode === 'width')  fitWidth();
        else if (P.state.autoZoomMode === 'height') fitHeight();
    }

    function highlight(pageIndex, bbox) {
        scrollToPage(pageIndex);
        var $wrapper = $('[data-page="' + pageIndex + '"]');
        if (!$wrapper.length || !P.state.pageViewports[pageIndex]) return;
        var vp    = P.state.pageViewports[pageIndex];
        var scale = vp.scale;
        $('<div>', { 'class': 'pdf-highlight' }).css({
            left:   bbox[0] * scale + 'px',
            top:    vp.height - (bbox[1] + bbox[3]) * scale + 'px',
            width:  bbox[2] * scale + 'px',
            height: bbox[3] * scale + 'px'
        }).appendTo($wrapper);
    }

    function clearHighlights() { $('.pdf-highlight').remove(); }

    function scrollToPage(pageIndex) {
        var wrapper = $('[data-page="' + pageIndex + '"]')[0];
        if (wrapper) wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    function handleClick(e, pageIndex) {
        if (!P.state.treeData || !P.state.pageViewports[pageIndex]) return;
        var canvas = P.state.pageCanvases[pageIndex];
        if (!canvas) return;
        var rect  = canvas.getBoundingClientRect();
        var vp    = P.state.pageViewports[pageIndex];
        var scale = vp.scale;
        var pdfX  = (e.clientX - rect.left) / scale;
        var pdfY  = (vp.height - (e.clientY - rect.top)) / scale;

        var additive = !!(e.ctrlKey || e.metaKey || e.shiftKey);
        var fieldMatch = findFieldAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        if (fieldMatch) {
            if (P.state.editMode && P.EditMode && P.EditMode.selectFieldFromViewer) {
                P.EditMode.selectFieldFromViewer(fieldMatch, additive);
            } else {
                P.Tree.selectNode(fieldMatch, additive);
            }
            return;
        }

        var imageMatch = findImageAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        if (imageMatch) {
            P.Tree.selectNode(imageMatch, additive);
            return;
        }

        var match = findNodeAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        if (!match) return;

        P.Tree.selectNode(match, additive);
    }

    function findFieldAtPoint(node, pageIndex, x, y, currentBest) {
        var best = currentBest || null;
        if (node.pageIndex === pageIndex && node.nodeCategory === 'field' && node.boundingBox) {
            var bb = node.boundingBox;
            if (x >= bb[0] && x <= bb[0] + bb[2] && y >= bb[1] && y <= bb[1] + bb[3]) {
                if (!best || (bb[2] * bb[3]) <= (best.boundingBox[2] * best.boundingBox[3])) {
                    best = node;
                }
            }
        }
        if (node.children) {
            node.children.forEach(function (c) {
                best = findFieldAtPoint(c, pageIndex, x, y, best);
            });
        }
        return best;
    }

    function findImageAtPoint(node, pageIndex, x, y, currentBest) {
        var best = currentBest || null;
        if (node.pageIndex === pageIndex && node.nodeCategory === 'image' && node.boundingBox) {
            var bb = node.boundingBox;
            if (x >= bb[0] && x <= bb[0] + bb[2] && y >= bb[1] && y <= bb[1] + bb[3]) {
                if (!best || (bb[2] * bb[3]) <= (best.boundingBox[2] * best.boundingBox[3])) {
                    best = node;
                }
            }
        }
        if (node.children) {
            node.children.forEach(function (c) {
                best = findImageAtPoint(c, pageIndex, x, y, best);
            });
        }
        return best;
    }

    function findNodeAtPoint(node, pageIndex, x, y) {
        var best = null;
        if (node.pageIndex === pageIndex && node.boundingBox) {
            var bb = node.boundingBox;
            if (x >= bb[0] && x <= bb[0] + bb[2] && y >= bb[1] && y <= bb[1] + bb[3]) best = node;
        }
        if (node.children) {
            node.children.forEach(function (c) {
                var m = findNodeAtPoint(c, pageIndex, x, y);
                if (m) best = m;
            });
        }
        return best;
    }

    return { loadPdf: loadPdf, renderAllPages: renderAllPages, setScale: setScale,
             fitWidth: fitWidth, fitHeight: fitHeight, applyAutoZoom: applyAutoZoom,
             highlight: highlight, clearHighlights: clearHighlights,
             scrollToPage: scrollToPage };
})(jQuery, PDFalyzer);
