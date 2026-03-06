/**
 * PDFalyzer Studio – PDF viewer rendering helpers (page rendering, staging, view state).
 */
PDFalyzer.ViewerRender = (function ($, P) {
    'use strict';

    var MAX_RENDER_SCALE = 4.0;

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
                $(document).trigger('pdfviewer:rendered');
            }, 190);
        });
    }

    function renderPageToContainer(pdf, pageNum, $container, pageViewports, pageCanvases, disableEntryAnimation) {
        return pdf.getPage(pageNum).then(function (page) {
            var scale = P.state.currentScale;
            var dpr = window.devicePixelRatio || 1;
            var viewport = page.getViewport({ scale: scale });
            var canvasRenderScale = Math.max(scale, MAX_RENDER_SCALE) * dpr;
            var hiDpiViewport = page.getViewport({ scale: canvasRenderScale });
            pageViewports[pageNum - 1] = viewport;

            var $wrapper = $('<div>', { 'class': 'pdf-page-wrapper', 'data-page': pageNum - 1 })
                .css('animation-delay', ((pageNum - 1) * 0.08) + 's');
            if (disableEntryAnimation) {
                $wrapper.addClass('pdf-page-wrapper-static').css('animation-delay', '0s');
            }
            var canvas = document.createElement('canvas');
            canvas.width = hiDpiViewport.width;
            canvas.height = hiDpiViewport.height;
            canvas._pdfRenderScale = canvasRenderScale;
            canvas.style.width = viewport.width + 'px';
            canvas.style.height = viewport.height + 'px';
            $wrapper.append(canvas);
            pageCanvases[pageNum - 1] = canvas;
            $wrapper.append($('<div>', { 'class': 'pdf-page-label', text: 'Page ' + pageNum }));
            $container.append($wrapper);

            P.Viewer.attachPageListeners(canvas, pageNum - 1, $wrapper);

            var layerMode = (P.state && P.state.layerMode) || 0;
            var layerDef = PDFalyzer.Zoom && PDFalyzer.Zoom.LAYER_MODES ? PDFalyzer.Zoom.LAYER_MODES[layerMode] : null;
            return page.render({ canvasContext: canvas.getContext('2d'), viewport: hiDpiViewport, annotationMode: 0 }).promise
                .then(function () {
                    var showFormLayer = (layerDef ? layerDef.form : false) || !!(P.state && P.state.editFieldType);
                    if (showFormLayer && P.EditMode && P.EditMode.renderFieldHandles) {
                        P.EditMode.renderFieldHandles(pageNum - 1, $wrapper[0], viewport);
                    }
                });
        });
    }

    function renderPdfIntoContainer(pdf, $container, pageViewports, pageCanvases, options) {
        var opts = options || {};
        var disableEntryAnimation = !!opts.disableEntryAnimation;
        var isCancelled = typeof opts.isCancelled === 'function' ? opts.isCancelled : null;
        var atomicCommit = !!opts.atomicCommit;
        var $renderTarget = atomicCommit ? $('<div>') : $container;

        if (!atomicCommit) {
            $container.empty();
        }

        var chain = Promise.resolve();
        for (var i = 1; i <= pdf.numPages; i++) {
            chain = chain.then((function (pageNum) {
                return function () {
                    if (isCancelled && isCancelled()) return;
                    return renderPageToContainer(pdf, pageNum, $renderTarget, pageViewports, pageCanvases,
                        disableEntryAnimation);
                };
            })(i));
        }

        return chain.then(function () {
            if (isCancelled && isCancelled()) return;
            if (atomicCommit) {
                $container.empty().append($renderTarget.children());
            }
        });
    }

    return {
        ensureStagingViewer: ensureStagingViewer,
        capturePaneViewState: capturePaneViewState,
        restorePaneViewState: restorePaneViewState,
        waitForPaneSettle: waitForPaneSettle,
        swapStagedViewer: swapStagedViewer,
        renderPageToContainer: renderPageToContainer,
        renderPdfIntoContainer: renderPdfIntoContainer,
        MAX_RENDER_SCALE: MAX_RENDER_SCALE
    };
})(jQuery, PDFalyzer);
