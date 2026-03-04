/**
 * PDFalyzer Studio – Canvas zoom/pan viewer and lazy glyph rendering.
 */
PDFalyzer.GlyphViewer = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;

    function initGlyphCanvasViewer(canvas, viewport, metricsEl) {
        if (!canvas || !viewport) return;

        var primaryFontFamily = String(canvas.getAttribute('data-font-primary') || '').trim();
        var glyphText = String(canvas.getAttribute('data-glyph') || '').trim() || ' ';
        var viewportWidth = Math.max(320, viewport.clientWidth || 320);
        var viewportHeight = Math.max(260, viewport.clientHeight || 260);
        var renderScale = 4;
        var fitPaddingRatio = 0.10;
        var defaultZoom = 1 / renderScale;

        _p.activeGlyphCanvasState = {
            canvas: canvas,
            viewport: viewport,
            metricsEl: metricsEl,
            baseWidth: viewportWidth,
            baseHeight: viewportHeight,
            renderScale: renderScale,
            glyphScale: 4,
            defaultZoom: defaultZoom,
            initialZoom: defaultZoom,
            zoom: defaultZoom,
            minZoom: 0.1,
            maxZoom: 4,
            widthHintUnits: parseFloat(canvas.getAttribute('data-glyph-width')),
            fitPaddingRatio: fitPaddingRatio,
            userAdjustedZoom: false
        };

        function applyViewportOverflow() {
            if (!_p.activeGlyphCanvasState) return;
            var epsilon = 0.001;
            viewport.style.overflow = (_p.activeGlyphCanvasState.zoom > (_p.activeGlyphCanvasState.initialZoom + epsilon)) ? 'auto' : 'hidden';
        }

        function updateZoomLabel() {
            $('#fontGlyphZoomLabel').text(Math.round(_p.activeGlyphCanvasState.zoom * 100) + '%');
        }

        function drawCurrent() {
            if (!_p.activeGlyphCanvasState) return;
            var st = _p.activeGlyphCanvasState;
            var layout = P.GlyphCanvas.drawGlyphDiagnosticsCanvas(canvas, {
                metricsEl: metricsEl,
                widthHintUnits: st.widthHintUnits,
                glyphScale: st.glyphScale,
                logicalWidth: Math.round(st.baseWidth * st.renderScale),
                logicalHeight: Math.round(st.baseHeight * st.renderScale)
            });
            canvas.style.width = Math.round(st.baseWidth * st.renderScale * st.zoom) + 'px';
            canvas.style.height = Math.round(st.baseHeight * st.renderScale * st.zoom) + 'px';
            updateZoomLabel();
            return layout;
        }

        function computeFitZoom(layout) {
            var st = _p.activeGlyphCanvasState;
            if (!layout) return st.zoom;
            var contentWidth = Math.max(1, layout.fitWidth || layout.contentWidth);
            var contentHeight = Math.max(1, layout.fitHeight || layout.contentHeight);
            var paddedWidth = contentWidth * (1 + st.fitPaddingRatio);
            var paddedHeight = contentHeight * (1 + st.fitPaddingRatio);
            var fitByWidth = viewport.clientWidth / paddedWidth;
            var fitByHeight = viewport.clientHeight / paddedHeight;
            var fitZoom = Math.min(fitByWidth, fitByHeight);
            if (!isFinite(fitZoom) || fitZoom <= 0) return st.zoom;
            fitZoom = P.GlyphCanvas.clampNumber(fitZoom, st.minZoom, st.maxZoom);
            return P.GlyphCanvas.clampNumber(Math.min(st.defaultZoom, fitZoom) * 4, st.minZoom, st.maxZoom);
        }

        function setZoom(nextZoom, userInitiated) {
            var st = _p.activeGlyphCanvasState;
            if (!st) return;
            if (userInitiated) { st.userAdjustedZoom = true; }
            var prevWidth = canvas.clientWidth || st.baseWidth;
            var prevHeight = canvas.clientHeight || st.baseHeight;
            var centerX = viewport.scrollLeft + (viewport.clientWidth / 2);
            var centerY = viewport.scrollTop + (viewport.clientHeight / 2);
            var centerRatioX = prevWidth > 0 ? (centerX / prevWidth) : 0.5;
            var centerRatioY = prevHeight > 0 ? (centerY / prevHeight) : 0.5;

            st.zoom = P.GlyphCanvas.clampNumber(nextZoom, st.minZoom, st.maxZoom);
            drawCurrent();

            var nextWidth = canvas.clientWidth || prevWidth;
            var nextHeight = canvas.clientHeight || prevHeight;
            viewport.scrollLeft = Math.max(0, nextWidth * centerRatioX - (viewport.clientWidth / 2));
            viewport.scrollTop = Math.max(0, nextHeight * centerRatioY - (viewport.clientHeight / 2));
            applyViewportOverflow();
        }

        function fitAndCenterInitial() {
            var st = _p.activeGlyphCanvasState;
            if (!st || st.userAdjustedZoom) return;
            var layout = drawCurrent();
            var computedInitialZoom = computeFitZoom(layout);
            st.initialZoom = computedInitialZoom;
            st.zoom = computedInitialZoom;
            drawCurrent();
            viewport.scrollLeft = Math.max(0, (canvas.clientWidth - viewport.clientWidth) / 2);
            viewport.scrollTop = Math.max(0, (canvas.clientHeight - viewport.clientHeight) / 2);
            applyViewportOverflow();
        }

        $('#fontGlyphZoomIn').off('click').on('click', function () {
            setZoom(_p.activeGlyphCanvasState.zoom * 1.2, true);
        });
        $('#fontGlyphZoomOut').off('click').on('click', function () {
            setZoom(_p.activeGlyphCanvasState.zoom / 1.2, true);
        });
        $('#fontGlyphZoomFit').off('click').on('click', function () {
            _p.activeGlyphCanvasState.userAdjustedZoom = false;
            fitAndCenterInitial();
        });
        $(viewport).off('wheel.glyphzoom').on('wheel.glyphzoom', function (ev) {
            var oe = ev.originalEvent || ev;
            if (!oe.ctrlKey) return;
            ev.preventDefault();
            var delta = typeof oe.deltaY === 'number' ? oe.deltaY : 0;
            setZoom(delta < 0 ? (_p.activeGlyphCanvasState.zoom * 1.15) : (_p.activeGlyphCanvasState.zoom / 1.15), true);
        });

        var dragState = { active: false, startX: 0, startY: 0, startLeft: 0, startTop: 0 };

        $(viewport).removeClass('glyph-pan-active')
            .off('mousedown.glyphpan')
            .on('mousedown.glyphpan', function (ev) {
                if (ev.button !== 0) return;
                dragState.active = true;
                dragState.startX = ev.clientX;
                dragState.startY = ev.clientY;
                dragState.startLeft = viewport.scrollLeft;
                dragState.startTop = viewport.scrollTop;
                $(viewport).addClass('glyph-pan-active');
                ev.preventDefault();
            });

        $(document).off('mousemove.glyphpan').on('mousemove.glyphpan', function (ev) {
            if (!dragState.active) return;
            viewport.scrollLeft = dragState.startLeft - (ev.clientX - dragState.startX);
            viewport.scrollTop = dragState.startTop - (ev.clientY - dragState.startY);
        });

        $(document).off('mouseup.glyphpan').on('mouseup.glyphpan', function () {
            if (!dragState.active) return;
            dragState.active = false;
            $(viewport).removeClass('glyph-pan-active');
        });

        $(viewport).off('mouseleave.glyphpan').on('mouseleave.glyphpan', function () {
            if (!dragState.active) return;
            dragState.active = false;
            $(viewport).removeClass('glyph-pan-active');
        });

        fitAndCenterInitial();
        setTimeout(fitAndCenterInitial, 120);
        setTimeout(fitAndCenterInitial, 380);
        if (document.fonts && document.fonts.ready && typeof document.fonts.ready.then === 'function') {
            document.fonts.ready.then(function () { fitAndCenterInitial(); });
        }
        if (primaryFontFamily && document.fonts && typeof document.fonts.load === 'function') {
            document.fonts.load('64px "' + primaryFontFamily + '"', glyphText).then(function () {
                fitAndCenterInitial();
            }).catch(function () {});
        }
    }

    function isControlCodePoint(cp) {
        return (cp >= 0 && cp <= 31) || cp === 127 || (cp >= 128 && cp <= 159);
    }

    function hasVisibleCodePoint(text) {
        if (!text) return false;
        for (var i = 0; i < text.length; i += 1) {
            var cp = text.codePointAt(i);
            if (cp > 0xFFFF) i += 1;
            if (!isControlCodePoint(cp)) return true;
        }
        return false;
    }

    function decodeUnicodeHexToText(unicodeHex) {
        var raw = String(unicodeHex || '').trim();
        if (!raw) return '';
        var matches = raw.match(/U\+[0-9A-Fa-f]{1,6}/g);
        if (!matches || !matches.length) return '';
        var out = '';
        for (var i = 0; i < matches.length; i += 1) {
            var cp = parseInt(matches[i].slice(2), 16);
            if (!isFinite(cp) || cp < 0 || cp > 0x10FFFF) continue;
            try { out += String.fromCodePoint(cp); } catch (e) {}
        }
        return out;
    }

    function resolveGlyphPreviewText(unicodeText, unicodeHex) {
        var text = String(unicodeText || '');
        if (hasVisibleCodePoint(text)) return text;
        var fromHex = decodeUnicodeHexToText(unicodeHex);
        if (hasVisibleCodePoint(fromHex)) return fromHex;
        return '';
    }

    function bindLazyGlyphRendering() {
        var nodes = Array.from(document.querySelectorAll('#fontDiagDetail .font-glyph-lazy[data-unicode]'));
        if (!nodes.length) return;

        if (_p.activeGlyphObserver && typeof _p.activeGlyphObserver.disconnect === 'function') {
            _p.activeGlyphObserver.disconnect();
            _p.activeGlyphObserver = null;
        }

        function renderGlyphNode(node) {
            if (!node || node.getAttribute('data-rendered') === '1') return;
            var unicode = node.getAttribute('data-unicode') || '';
            var unicodeHex = node.getAttribute('data-unicode-hex') || '';
            var previewText = resolveGlyphPreviewText(unicode, unicodeHex);
            var fontFamily = node.getAttribute('data-font-family') || '';
            var widthHintUnits = parseFloat(node.getAttribute('data-glyph-width'));
            var resolvedFamily = fontFamily
                ? ("'" + fontFamily + "', 'Segoe UI Symbol', 'Segoe UI', sans-serif")
                : "'Segoe UI Symbol', 'Segoe UI', sans-serif";

            node.setAttribute('data-rendered', '1');
            node.innerHTML = '';

            var previewCanvas = document.createElement('canvas');
            previewCanvas.className = 'font-glyph-preview font-glyph-preview-canvas';
            previewCanvas.title = 'Rendered glyph preview';
            previewCanvas.setAttribute('data-unicode', previewText);
            previewCanvas.setAttribute('data-font-family', resolvedFamily);
            previewCanvas.setAttribute('data-font-primary', fontFamily);
            previewCanvas.setAttribute('data-glyph-width', String(isNaN(widthHintUnits) ? '' : widthHintUnits));
            node.appendChild(previewCanvas);
            if (!previewText) {
                node.setAttribute('title', 'No glyph present');
                return;
            }
            P.GlyphCanvas.drawGlyphPreviewCanvas(previewCanvas, previewText, resolvedFamily, widthHintUnits);
            if (fontFamily && document.fonts && typeof document.fonts.load === 'function') {
                document.fonts.load('32px "' + fontFamily + '"', previewText).then(function () {
                    P.GlyphCanvas.drawGlyphPreviewCanvas(previewCanvas, previewText, resolvedFamily, widthHintUnits);
                }).catch(function () {});
            }
        }

        function redrawAllPreviewCanvases() {
            var canvases = document.querySelectorAll('#fontDiagDetail .font-glyph-preview-canvas[data-unicode]');
            canvases.forEach(function (canvas) {
                var unicode = canvas.getAttribute('data-unicode') || '';
                var fontFamily = canvas.getAttribute('data-font-family') || "'Segoe UI Symbol', 'Segoe UI', sans-serif";
                var primaryFamily = String(canvas.getAttribute('data-font-primary') || '').trim();
                var widthHintUnits = parseFloat(canvas.getAttribute('data-glyph-width'));
                P.GlyphCanvas.drawGlyphPreviewCanvas(canvas, unicode, fontFamily, widthHintUnits);
                if (primaryFamily && document.fonts && typeof document.fonts.load === 'function') {
                    document.fonts.load('32px "' + primaryFamily + '"', unicode).then(function () {
                        P.GlyphCanvas.drawGlyphPreviewCanvas(canvas, unicode, fontFamily, widthHintUnits);
                    }).catch(function () {});
                }
            });
        }

        function renderVisibleBatch() {
            nodes.forEach(function (node) {
                if (node.getAttribute('data-rendered') === '1') return;
                var rect = node.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return;
                if (rect.bottom < -100 || rect.top > (window.innerHeight + 300)) return;
                renderGlyphNode(node);
            });
        }

        renderVisibleBatch();

        if ('IntersectionObserver' in window) {
            _p.activeGlyphObserver = new IntersectionObserver(function (entries) {
                entries.forEach(function (entry) {
                    if (!entry.isIntersecting) return;
                    renderGlyphNode(entry.target);
                    _p.activeGlyphObserver.unobserve(entry.target);
                });
            }, { root: null, rootMargin: '180px 0px', threshold: 0.01 });

            nodes.forEach(function (node) {
                if (node.getAttribute('data-rendered') === '1') return;
                _p.activeGlyphObserver.observe(node);
            });
        }

        if (!('IntersectionObserver' in window)) {
            setTimeout(function () { renderVisibleBatch(); }, 250);
        }

        setTimeout(redrawAllPreviewCanvases, 120);
        setTimeout(redrawAllPreviewCanvases, 380);
        if (document.fonts && document.fonts.ready && typeof document.fonts.ready.then === 'function') {
            document.fonts.ready.then(function () { redrawAllPreviewCanvases(); });
        }
    }

    return {
        initGlyphCanvasViewer: initGlyphCanvasViewer,
        bindLazyGlyphRendering: bindLazyGlyphRendering,
        resolveGlyphPreviewText: resolveGlyphPreviewText
    };
})(jQuery, PDFalyzer);
