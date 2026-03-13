/**
 * PDFalyzer Studio – PDF viewer (PDF.js rendering, highlight, zoom).
 */
PDFalyzer.Viewer = (function ($, P) {
    'use strict';

    var activeRenderRequestId = 0;
    var rerenderTimer = null;
    var panListenersAttached = false;
    var panDragState = {
        active: false,
        moved: false,
        suppressClick: false,
        canvas: null,
        startClientX: 0,
        startClientY: 0,
        startScrollLeft: 0,
        startScrollTop: 0
    };

    function ensurePdfWorkerConfigured() {
        if (!window.pdfjsLib || !window.pdfjsLib.GlobalWorkerOptions) return;
        var options = window.pdfjsLib.GlobalWorkerOptions;
        if (options.workerSrc) return;
        var version = window.pdfjsLib.version || '3.11.174';
        options.workerSrc = 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/' + version + '/pdf.worker.min.js';
    }

    function beginRenderRequest() {
        activeRenderRequestId += 1;
        return activeRenderRequestId;
    }

    function isRenderRequestActive(requestId) {
        return requestId === activeRenderRequestId;
    }

    function isPanModeEnabled() {
        return !!(P.Zoom && P.Zoom.isPanModeActive && P.Zoom.isPanModeActive());
    }

    function isPlaceModeActive() {
        return !!(P.state && P.state.editMode && P.state.editFieldType);
    }

    function ensurePanListenersAttached() {
        if (panListenersAttached) return;
        panListenersAttached = true;

        $(document).on('mousemove.pdfviewerpan', function (e) {
            if (!panDragState.active) return;
            var pane = $('#pdfPane')[0];
            if (!pane) return;

            var deltaX = e.clientX - panDragState.startClientX;
            var deltaY = e.clientY - panDragState.startClientY;
            if (Math.abs(deltaX) > 3 || Math.abs(deltaY) > 3) {
                panDragState.moved = true;
            }

            pane.scrollLeft = panDragState.startScrollLeft - deltaX;
            pane.scrollTop = panDragState.startScrollTop - deltaY;
            e.preventDefault();
        });

        function finishPanDrag() {
            if (!panDragState.active) return;
            var pane = $('#pdfPane')[0];
            if (pane) pane.classList.remove('pdf-pane-panning');

            panDragState.active = false;
            if (panDragState.moved) {
                panDragState.suppressClick = true;
            }
            if (panDragState.canvas && !panDragState.suppressClick) {
                panDragState.canvas.style.cursor = isPanModeEnabled() ? 'grab' : '';
            }
            panDragState.canvas = null;
            panDragState.moved = false;
        }

        $(document).on('mouseup.pdfviewerpan', finishPanDrag);
        $(window).on('blur.pdfviewerpan', finishPanDrag);
    }

    function attachPageListeners(canvas, pageIndex, $wrapper) {
        ensurePanListenersAttached();

        $(canvas).on('click', function (e) { handleClick(e, pageIndex); })
            .on('mousedown', function (e) {
                if (tryStartPanDrag(e, pageIndex, canvas)) return;
                if (P.state.editMode && P.state.editFieldType) {
                    if (P.state.editFieldType === 'select') {
                        P.EditMode.startRectSelect(e, pageIndex, $wrapper[0], !!(e.ctrlKey || e.metaKey));
                    } else {
                        P.EditMode.startDraw(e, pageIndex, $wrapper[0]);
                    }
                }
            })
            .on('mousemove', function (e) {
                updateCanvasCursor(canvas, pageIndex, e);
            })
            .on('mouseleave', function () {
                if (!panDragState.active) {
                    canvas.style.cursor = '';
                }
            });
    }

    function loadPdf(sid, options) {
        ensurePdfWorkerConfigured();
        var opts = options || {};
        var url = '/api/pdf/' + sid;
        var $viewer = $('#pdfViewer').show();
        var viewState = opts.preserveView ? P.ViewerRender.capturePaneViewState() : null;
        var useSmoothSwap = !!opts.smoothSwap && $viewer.children().length > 0;
        var $target = $viewer;
        var $staging = null;

        if (useSmoothSwap) {
            $staging = P.ViewerRender.ensureStagingViewer();
            $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();
            $target = $staging;
        }

        $('#uploadSplash').hide();
        if (P.LoadingSpinner) P.LoadingSpinner.show();
        var nextViewports = [];
        var nextCanvases = [];

        pdfjsLib.getDocument(url).promise.then(function (pdf) {
            P.state.pdfDoc = pdf;
            pdf.getPage(1).then(function (p) {
                var vp = p.getViewport({ scale: 1 });
                P.state.basePageSize.width  = vp.width;
                P.state.basePageSize.height = vp.height;
            });
            return P.ViewerRender.renderPdfIntoContainer(pdf, $target, nextViewports, nextCanvases,
                { disableEntryAnimation: useSmoothSwap })
                .then(function () {
                    P.state.pageViewports = nextViewports;
                    P.state.pageCanvases = nextCanvases;
                    if (useSmoothSwap && $staging) {
                        P.ViewerRender.swapStagedViewer($staging, viewState);
                    } else {
                        P.ViewerRender.restorePaneViewState(viewState);
                        $(document).trigger('pdfviewer:rendered');
                    }
                    if (P.LoadingSpinner) P.LoadingSpinner.hide();
                });
        }).catch(function (err) {
            if (P.LoadingSpinner) P.LoadingSpinner.hide();
            if ($staging) {
                $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();
            }
            P.Utils.toast('Failed to render PDF: ' + err.message, 'danger');
        });
    }

    function renderAllPages(options) {
        if (!P.state.pdfDoc) return;
        var opts = options || {};
        var requestId = beginRenderRequest();
        var viewState = opts.preserveView ? P.ViewerRender.capturePaneViewState() : null;
        var useSmoothSwap = !!opts.smoothSwap && $('#pdfViewer').children().length > 0;
        var nextViewports = [];
        var nextCanvases = [];

        if (useSmoothSwap) {
            var $staging = P.ViewerRender.ensureStagingViewer();
            $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();

            return P.ViewerRender.renderPdfIntoContainer(P.state.pdfDoc, $staging, nextViewports, nextCanvases, {
                disableEntryAnimation: true,
                isCancelled: function () { return !isRenderRequestActive(requestId); }
            }).then(function () {
                if (!isRenderRequestActive(requestId)) {
                    $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();
                    return;
                }

                P.state.pageViewports = nextViewports;
                P.state.pageCanvases = nextCanvases;
                P.ViewerRender.swapStagedViewer($staging, viewState);
            }).catch(function () {
                if (!isRenderRequestActive(requestId)) return;
                $staging.removeClass('pdf-viewer-crossfade-in pdf-viewer-staging-active').empty();
            });
        }

        return P.ViewerRender.renderPdfIntoContainer(P.state.pdfDoc, $('#pdfViewer'), nextViewports, nextCanvases, {
            disableEntryAnimation: true,
            atomicCommit: true,
            isCancelled: function () { return !isRenderRequestActive(requestId); }
        }).then(function () {
            if (!isRenderRequestActive(requestId)) return;
            P.state.pageViewports = nextViewports;
            P.state.pageCanvases = nextCanvases;
            P.ViewerRender.restorePaneViewState(viewState);
            $(document).trigger('pdfviewer:rendered');
        });
    }

    function rescalePages(scale, focalScreenX, focalScreenY) {
        var pane = $('#pdfPane')[0];
        var viewer = document.getElementById('pdfViewer');
        var ratio = scale / P.state.currentScale;

        // Compute new scroll BEFORE any DOM writes (reads here are uncontaminated).
        // pane.scrollLeft alone is wrong when pages are centered (narrower than pane):
        // the viewer's left edge is offset by the centering margin, not just -scrollLeft.
        var newScrollLeft = null, newScrollTop = null;
        if (pane && viewer && typeof focalScreenX === 'number') {
            var paneRect  = pane.getBoundingClientRect();
            var viewerRect = viewer.getBoundingClientRect();
            var contentX = focalScreenX - (viewerRect.left - paneRect.left);
            var contentY = focalScreenY - (viewerRect.top  - paneRect.top);
            newScrollLeft = contentX * ratio - focalScreenX;
            newScrollTop  = contentY * ratio - focalScreenY;
        }

        P.state.currentScale = scale;
        var dpr = window.devicePixelRatio || 1;
        var fallbackRenderScale = (P.ViewerRender && P.ViewerRender.MAX_RENDER_SCALE || 4.0) * dpr;
        $('#pdfViewer .pdf-page-wrapper').each(function (i) {
            var canvas = $(this).find('canvas')[0];
            if (!canvas) return;
            var renderScale = canvas._pdfRenderScale || fallbackRenderScale;
            var newW = canvas.width  * scale / renderScale;
            var newH = canvas.height * scale / renderScale;
            canvas.style.width  = newW + 'px';
            canvas.style.height = newH + 'px';
            var vp = P.state.pageViewports[i];
            if (vp) { vp.scale = scale; vp.width = newW; vp.height = newH; }
        });
        if (newScrollLeft !== null) {
            pane.scrollLeft = newScrollLeft;
            pane.scrollTop  = newScrollTop;
        }
        var layerDef = P.Zoom && P.Zoom.LAYER_MODES ? P.Zoom.LAYER_MODES[P.state.layerMode || 0] : null;
        var showFormLayer = (layerDef ? layerDef.form : false) || !!P.state.editFieldType;
        if (showFormLayer && P.EditMode && P.EditMode.renderFieldHandlesForAllPages) P.EditMode.renderFieldHandlesForAllPages();
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
        $(document).trigger('pdfviewer:rendered');

        // Schedule quality re-render after zoom settles if we are above the render scale
        // (canvas was pre-rendered at MAX_RENDER_SCALE so > that is CSS upscale / blurry).
        clearTimeout(rerenderTimer);
        var maxRS = (P.ViewerRender && P.ViewerRender.MAX_RENDER_SCALE) || 4.0;
        if (scale > maxRS) {
            rerenderTimer = setTimeout(function () {
                rerenderTimer = null;
                renderAllPages({ preserveView: true, smoothSwap: true });
            }, 600);
        }
    }

    function canRescaleCSS() {
        return !!(P.state.pageCanvases && P.state.pageCanvases.length > 0);
    }

    function paneCenterFocal() {
        var pane = $('#pdfPane')[0];
        return pane ? { x: pane.clientWidth / 2, y: pane.clientHeight / 2 } : { x: 0, y: 0 };
    }

    function setScale(scale) {
        if (scale <= 0) return;
        if (Math.abs(scale - P.state.currentScale) < 0.0005) return;
        P.state.autoZoomMode = 'off';
        if (canRescaleCSS()) {
            var c = paneCenterFocal();
            rescalePages(scale, c.x, c.y);
            return;
        }
        P.state.currentScale = scale;
        renderAllPages({ preserveView: true, smoothSwap: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function setScaleAtPoint(scale, screenX, screenY) {
        if (scale <= 0) return;
        if (Math.abs(scale - P.state.currentScale) < 0.0005) return;
        P.state.autoZoomMode = 'off';
        if (canRescaleCSS()) { rescalePages(scale, screenX, screenY); return; }
        P.state.currentScale = scale;
        renderAllPages({ preserveView: true, smoothSwap: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function fitWidth() {
        if (!P.state.basePageSize.width) return;
        var scale = ($('#pdfPane').width() - 40) / P.state.basePageSize.width;
        P.state.autoZoomMode = 'width';
        if (canRescaleCSS()) { rescalePages(scale); return; }
        P.state.currentScale = scale;
        renderAllPages({ preserveView: true, smoothSwap: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function fitHeight() {
        if (!P.state.basePageSize.height) return;
        var scale = ($('#pdfPane').height() - 40) / P.state.basePageSize.height;
        P.state.autoZoomMode = 'height';
        if (canRescaleCSS()) { rescalePages(scale); return; }
        P.state.currentScale = scale;
        renderAllPages({ preserveView: true, smoothSwap: true });
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function applyAutoZoom() {
        if (P.state.autoZoomMode === 'width')  fitWidth();
        else if (P.state.autoZoomMode === 'height') fitHeight();
    }

    function highlight(pageIndex, bbox, opts) {
        clearHighlights();
        var $wrapper = $('[data-page="' + pageIndex + '"]');
        if (!$wrapper.length || !P.state.pageViewports[pageIndex]) return;
        var vp    = P.state.pageViewports[pageIndex];
        var scale = vp.scale;
        var left = bbox[0] * scale, top = vp.height - (bbox[1] + bbox[3]) * scale;
        var w = bbox[2] * scale, h = bbox[3] * scale;

        function showHighlightAndLocator() {
            $('<div>', { 'class': 'pdf-highlight' }).css({
                left: left + 'px', top: top + 'px', width: w + 'px', height: h + 'px'
            }).appendTo($wrapper);

            if (opts && opts.locator) {
                var cx = left + w / 2, cy = top + h / 2;
                var diag = Math.sqrt(w * w + h * h);
                var expand = diag * 0.7;
                var startW = w + expand * 2, startH = h + expand * 2;
                var $ring = $('<div>', { 'class': 'field-locator-ring' }).css({
                    left: (cx - startW / 2) + 'px', top: (cy - startH / 2) + 'px',
                    width: startW + 'px', height: startH + 'px'
                }).appendTo($wrapper);
                $ring[0].offsetWidth;
                var bw = 20;
                $ring.addClass('field-locator-contract').css({
                    left: (left - bw) + 'px', top: (top - bw) + 'px',
                    width: (w + bw * 2) + 'px', height: (h + bw * 2) + 'px'
                });
                setTimeout(function () {
                    $ring.removeClass('field-locator-contract').addClass('field-locator-flash');
                    $ring[0].offsetWidth;
                    setTimeout(function () {
                        $ring.removeClass('field-locator-flash').addClass('field-locator-fadeout');
                        $ring[0].offsetWidth;
                        setTimeout(function () { $ring.remove(); }, 220);
                    }, 100);
                }, 300);
            }
        }

        scrollToPage(pageIndex, showHighlightAndLocator);
    }

    function clearHighlights() { $('.pdf-highlight, .field-locator-ring').remove(); }

    /**
     * Scroll the PDF pane so the given page is visible.
     * Calls onDone() once scrolling has settled (or immediately if no scroll needed).
     */
    function scrollToPage(pageIndex, onDone) {
        var wrapper = $('[data-page="' + pageIndex + '"]')[0];
        if (!wrapper) { if (onDone) onDone(); return; }
        var pane = document.getElementById('pdfPane');
        if (!pane) {
            wrapper.scrollIntoView({ behavior: 'instant', block: 'center' });
            if (onDone) onDone();
            return;
        }
        var paneRect = pane.getBoundingClientRect();
        var wrapRect = wrapper.getBoundingClientRect();
        // Already visible — no scroll needed
        if (wrapRect.top >= paneRect.top && wrapRect.bottom <= paneRect.bottom) {
            if (onDone) onDone();
            return;
        }
        // Use instant scroll for speed, then call back
        wrapper.scrollIntoView({ behavior: 'instant', block: 'center' });
        // Wait one frame for layout to settle after instant scroll
        requestAnimationFrame(function () {
            if (onDone) onDone();
        });
    }

    function getPdfPointForEvent(e, pageIndex) {
        if (!P.state.pageViewports[pageIndex]) return null;
        var canvas = P.state.pageCanvases[pageIndex];
        if (!canvas) return null;

        var rect = canvas.getBoundingClientRect();
        var vp = P.state.pageViewports[pageIndex];
        var scale = vp.scale;
        return {
            x: (e.clientX - rect.left) / scale,
            y: (vp.height - (e.clientY - rect.top)) / scale
        };
    }

    function getHitTargetAtPoint(pageIndex, pdfX, pdfY) {
        if (!P.state.treeData) {
            return { clickable: false, fieldMatch: null, imageMatch: null, nodeMatch: null };
        }

        var fieldMatch = findFieldAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        if (fieldMatch) {
            return { clickable: true, fieldMatch: fieldMatch, imageMatch: null, nodeMatch: null };
        }

        var imageMatch = findImageAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        if (imageMatch) {
            return { clickable: true, fieldMatch: null, imageMatch: imageMatch, nodeMatch: null };
        }

        var nodeMatch = findNodeAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        return {
            clickable: !!nodeMatch,
            fieldMatch: null,
            imageMatch: null,
            nodeMatch: nodeMatch || null
        };
    }

    function updateCanvasCursor(canvas, pageIndex, e) {
        if (!canvas) return;
        if (isPlaceModeActive()) {
            canvas.style.cursor = '';
            return;
        }
        if (!isPanModeEnabled()) {
            canvas.style.cursor = '';
            return;
        }
        if (panDragState.active && panDragState.canvas === canvas) {
            canvas.style.cursor = 'grabbing';
            return;
        }

        var point = getPdfPointForEvent(e, pageIndex);
        if (!point) {
            canvas.style.cursor = 'grab';
            return;
        }

        var hit = getHitTargetAtPoint(pageIndex, point.x, point.y);
        canvas.style.cursor = hit.clickable ? 'default' : 'grab';
    }

    function tryStartPanDrag(e, pageIndex, canvas) {
        if (e.button !== 0) return false;
        if (!isPanModeEnabled()) return false;
        if (isPlaceModeActive()) return false;

        var pane = $('#pdfPane')[0];
        if (!pane) return false;

        var point = getPdfPointForEvent(e, pageIndex);
        if (!point) return false;

        var hit = getHitTargetAtPoint(pageIndex, point.x, point.y);
        if (hit.clickable) return false;

        panDragState.active = true;
        panDragState.moved = false;
        panDragState.canvas = canvas;
        panDragState.startClientX = e.clientX;
        panDragState.startClientY = e.clientY;
        panDragState.startScrollLeft = pane.scrollLeft;
        panDragState.startScrollTop = pane.scrollTop;
        pane.classList.add('pdf-pane-panning');
        canvas.style.cursor = 'grabbing';
        e.preventDefault();
        return true;
    }

    function handleClick(e, pageIndex) {
        if (panDragState.suppressClick) {
            panDragState.suppressClick = false;
            e.preventDefault();
            e.stopPropagation();
            return;
        }
        if (P.state.editMode && P.state.editFieldType === 'select' && P.EditMode && P.EditMode.isRectSelectDragJustFinished()) {
            return;
        }

        if (!P.state.treeData || !P.state.pageViewports[pageIndex]) return;
        var point = getPdfPointForEvent(e, pageIndex);
        if (!point) return;
        var pdfX = point.x;
        var pdfY = point.y;

        var additive = !!(e.ctrlKey || e.metaKey || e.shiftKey);
        var hit = getHitTargetAtPoint(pageIndex, pdfX, pdfY);
        var fieldMatch = hit.fieldMatch;
        if (fieldMatch) {
            if (P.state.editMode && P.EditMode && P.EditMode.selectFieldFromViewer) {
                P.EditMode.selectFieldFromViewer(fieldMatch, additive);
            } else {
                P.Tree.selectNode(fieldMatch, additive);
            }
            return;
        }

        var imageMatch = hit.imageMatch;
        if (imageMatch) {
            P.Tree.selectNode(imageMatch, additive);
            return;
        }

        var match = hit.nodeMatch;
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
             setScaleAtPoint: setScaleAtPoint,
             fitWidth: fitWidth, fitHeight: fitHeight, applyAutoZoom: applyAutoZoom,
             highlight: highlight, clearHighlights: clearHighlights,
             scrollToPage: scrollToPage, attachPageListeners: attachPageListeners };
})(jQuery, PDFalyzer);
