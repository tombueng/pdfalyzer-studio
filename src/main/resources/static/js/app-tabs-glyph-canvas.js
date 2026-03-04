/**
 * PDFalyzer Studio – Canvas drawing functions for glyph previews.
 */
PDFalyzer.GlyphCanvas = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;

    function getGlyphMeasureContext() {
        if (!_p.glyphMeasureCanvas) {
            _p.glyphMeasureCanvas = document.createElement('canvas');
        }
        return _p.glyphMeasureCanvas.getContext('2d');
    }

    function clampNumber(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function measureGlyphMetrics(text, fontFamily, fontSizePx) {
        var ctx = getGlyphMeasureContext();
        if (!ctx) {
            return {
                advanceWidth: Math.max(1, fontSizePx * 0.6),
                bboxWidth: Math.max(1, fontSizePx * 0.6),
                xMin: -fontSizePx * 0.05,
                xMax: fontSizePx * 0.55,
                ascent: fontSizePx * 0.8,
                descent: fontSizePx * 0.2
            };
        }
        ctx.font = String(fontSizePx) + 'px ' + (fontFamily || "'Segoe UI Symbol', 'Segoe UI', sans-serif");
        var m = ctx.measureText(text || ' ');
        var ascent = m.actualBoundingBoxAscent || (fontSizePx * 0.8);
        var descent = m.actualBoundingBoxDescent || (fontSizePx * 0.2);
        var left = (typeof m.actualBoundingBoxLeft === 'number') ? m.actualBoundingBoxLeft : 0;
        var right = (typeof m.actualBoundingBoxRight === 'number') ? m.actualBoundingBoxRight : (m.width || (fontSizePx * 0.6));
        var xMin = -left;
        var xMax = right;
        var bboxWidth = xMax - xMin;
        var advanceWidth = m.width || bboxWidth || (fontSizePx * 0.6);
        return {
            advanceWidth: Math.max(1, advanceWidth),
            bboxWidth: Math.max(1, bboxWidth || advanceWidth),
            xMin: xMin,
            xMax: xMax,
            ascent: ascent,
            descent: descent
        };
    }

    function measureGlyphBounds(text, fontFamily, fontSizePx) {
        var metrics = measureGlyphMetrics(text, fontFamily, fontSizePx);
        return {
            width: metrics.bboxWidth,
            height: metrics.ascent + metrics.descent,
            ascent: metrics.ascent,
            descent: metrics.descent
        };
    }

    function fitGlyphInElement(element, options) {
        if (!element) return;
        var text = String((options && options.text) != null ? options.text : (element.textContent || '')).trim();
        if (!text) return;

        var computed = window.getComputedStyle(element);
        var fontFamily = (options && options.fontFamily) || (computed && computed.fontFamily) || "'Segoe UI Symbol', 'Segoe UI', sans-serif";
        var maxWidth = Math.max(1, (options && options.maxWidth) || element.clientWidth || 32);
        var maxHeight = Math.max(1, (options && options.maxHeight) || element.clientHeight || 24);
        var minFontSize = Math.max(6, (options && options.minFontSize) || 10);
        var maxFontSize = Math.max(minFontSize, (options && options.maxFontSize) || 220);
        var widthHintUnits = options && typeof options.widthHintUnits === 'number' ? options.widthHintUnits : NaN;

        var baseSize = 100;
        var bounds = measureGlyphBounds(text, fontFamily, baseSize);
        var widthAtBase = bounds.width;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            widthAtBase = Math.max(widthAtBase, baseSize * (widthHintUnits / 1000));
        }

        var sizeByWidth = maxWidth * (baseSize / Math.max(1, widthAtBase));
        var sizeByHeight = maxHeight * (baseSize / Math.max(1, bounds.height));
        var fitted = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), minFontSize, maxFontSize);

        element.style.fontSize = fitted + 'px';
        element.style.lineHeight = '1';
    }

    function drawGlyphPreviewCanvas(canvas, text, fontFamily, widthHintUnits) {
        if (!canvas || !text) return;
        var cssWidth = Math.max(24, canvas.clientWidth || 24);
        var cssHeight = Math.max(24, canvas.clientHeight || 24);
        var dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
        canvas.width = Math.floor(cssWidth * dpr);
        canvas.height = Math.floor(cssHeight * dpr);
        canvas.style.width = cssWidth + 'px';
        canvas.style.height = cssHeight + 'px';

        var ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssWidth, cssHeight);

        var inset = 3;
        var fitWidth = Math.max(1, (cssWidth - inset * 2) * 0.9);
        var fitHeight = Math.max(1, (cssHeight - inset * 2) * 0.9);
        var baseSize = 120;
        var mBase = measureGlyphMetrics(text, fontFamily, baseSize);
        var bboxWidthBase = mBase.bboxWidth;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            bboxWidthBase = Math.max(bboxWidthBase, baseSize * (widthHintUnits / 1000));
        }
        var bboxHeightBase = Math.max(1, mBase.ascent + mBase.descent);

        var sizeByWidth = fitWidth * (baseSize / Math.max(1, bboxWidthBase));
        var sizeByHeight = fitHeight * (baseSize / Math.max(1, bboxHeightBase));
        var fontSize = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), 8, 220);
        var m = measureGlyphMetrics(text, fontFamily, fontSize);
        var bboxWidth = Math.max(1, m.bboxWidth);
        var bboxHeight = Math.max(1, m.ascent + m.descent);

        var bboxLeft = (cssWidth - bboxWidth) / 2;
        var bboxTop = (cssHeight - bboxHeight) / 2;
        var baselineY = bboxTop + m.ascent;
        var originX = bboxLeft - m.xMin;

        ctx.save();
        ctx.font = String(fontSize) + 'px ' + fontFamily;
        ctx.fillStyle = '#ffffff';
        ctx.textBaseline = 'alphabetic';
        ctx.fillText(text, originX, baselineY);
        ctx.restore();
    }

    function drawGlyphDiagnosticsCanvas(canvas, options) {
        if (!canvas) return null;
        var text = String(canvas.getAttribute('data-glyph') || '').trim();
        if (!text) return null;

        var fontFamily = String(canvas.getAttribute('data-font-family') || "'Segoe UI Symbol', 'Segoe UI', sans-serif");
        var widthHintUnits = options && typeof options.widthHintUnits === 'number' ? options.widthHintUnits : NaN;
        var metricsEl = options ? options.metricsEl : null;

        var cssWidth = Math.max(220, (options && options.logicalWidth) || canvas.clientWidth || 300);
        var cssHeight = Math.max(220, (options && options.logicalHeight) || canvas.clientHeight || 260);
        var dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
        canvas.style.width = cssWidth + 'px';
        canvas.style.height = cssHeight + 'px';
        canvas.width = Math.floor(cssWidth * dpr);
        canvas.height = Math.floor(cssHeight * dpr);

        var ctx = canvas.getContext('2d');
        if (!ctx) return null;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssWidth, cssHeight);

        var inset = 8;
        var region = { x: inset, y: inset, width: cssWidth - inset * 2, height: cssHeight - inset * 2 };
        var fitPadding = 0.10;
        var fitRegion = {
            x: region.x + (region.width * fitPadding * 0.5),
            y: region.y + (region.height * fitPadding * 0.5),
            width: Math.max(1, region.width * (1 - fitPadding)),
            height: Math.max(1, region.height * (1 - fitPadding))
        };

        var baseSize = 200;
        var mBase = measureGlyphMetrics(text, fontFamily, baseSize);
        var bboxWidthBase = mBase.bboxWidth;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            bboxWidthBase = Math.max(bboxWidthBase, baseSize * (widthHintUnits / 1000));
        }
        var bboxHeightBase = Math.max(1, mBase.ascent + mBase.descent);

        var sizeByWidth = fitRegion.width * (baseSize / Math.max(1, bboxWidthBase));
        var sizeByHeight = fitRegion.height * (baseSize / Math.max(1, bboxHeightBase));
        var fontSize = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), 24, 260);

        var m = measureGlyphMetrics(text, fontFamily, fontSize);
        var bboxWidth = Math.max(1, m.bboxWidth);
        var bboxHeight = Math.max(1, m.ascent + m.descent);

        var bboxLeft = fitRegion.x + (fitRegion.width - bboxWidth) / 2;
        var bboxTop = fitRegion.y + (fitRegion.height - bboxHeight) / 2;
        var baselineY = bboxTop + m.ascent;
        var originX = bboxLeft - m.xMin;

        // Draw region background and border
        ctx.fillStyle = 'rgba(255,255,255,0.02)';
        ctx.fillRect(region.x, region.y, region.width, region.height);
        ctx.strokeStyle = 'rgba(255,255,255,0.18)';
        ctx.lineWidth = 1;
        ctx.setLineDash([5, 4]);
        ctx.strokeRect(region.x + 0.5, region.y + 0.5, region.width - 1, region.height - 1);
        ctx.setLineDash([]);

        var fullLeft = 0, fullRight = cssWidth, fullTop = 0, fullBottom = cssHeight;

        // Ascent line (cyan)
        ctx.strokeStyle = 'rgba(0,212,255,0.95)'; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(fullLeft, baselineY - m.ascent); ctx.lineTo(fullRight, baselineY - m.ascent); ctx.stroke();

        // Baseline (yellow)
        ctx.strokeStyle = 'rgba(255,193,7,0.95)'; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(fullLeft, baselineY); ctx.lineTo(fullRight, baselineY); ctx.stroke();

        // Descent line (red)
        ctx.strokeStyle = 'rgba(220,53,69,0.95)'; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(fullLeft, baselineY + m.descent); ctx.lineTo(fullRight, baselineY + m.descent); ctx.stroke();

        // BBox lines (green)
        ctx.strokeStyle = 'rgba(40,167,69,0.55)'; ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(fullLeft, bboxTop); ctx.lineTo(fullRight, bboxTop);
        ctx.moveTo(fullLeft, bboxTop + bboxHeight); ctx.lineTo(fullRight, bboxTop + bboxHeight);
        ctx.moveTo(bboxLeft, fullTop); ctx.lineTo(bboxLeft, fullBottom);
        ctx.moveTo(bboxLeft + bboxWidth, fullTop); ctx.lineTo(bboxLeft + bboxWidth, fullBottom);
        ctx.stroke();

        // Origin (grey)
        ctx.strokeStyle = 'rgba(173,181,189,0.7)'; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(originX, fullTop); ctx.lineTo(originX, fullBottom); ctx.stroke();

        // Draw glyph
        ctx.save();
        ctx.font = String(fontSize) + 'px ' + fontFamily;
        ctx.fillStyle = '#ffffff';
        ctx.textBaseline = 'alphabetic';
        ctx.fillText(text, originX, baselineY);
        ctx.restore();

        if (metricsEl) {
            var info = [
                'bbox ' + Math.round(bboxWidth) + 'x' + Math.round(bboxHeight) + 'px',
                'advance ' + Math.round(m.advanceWidth) + 'px'
            ];
            metricsEl.textContent = info.join('  |  ');
        }

        return {
            contentLeft: region.x, contentTop: region.y, contentWidth: region.width, contentHeight: region.height,
            fitLeft: bboxLeft, fitTop: baselineY - m.ascent, fitWidth: bboxWidth, fitHeight: bboxHeight,
            bboxLeft: bboxLeft, bboxTop: bboxTop, bboxWidth: bboxWidth, bboxHeight: bboxHeight,
            baselineY: baselineY, ascentY: baselineY - m.ascent, descentY: baselineY + m.descent
        };
    }

    return {
        clampNumber: clampNumber,
        measureGlyphMetrics: measureGlyphMetrics,
        measureGlyphBounds: measureGlyphBounds,
        fitGlyphInElement: fitGlyphInElement,
        drawGlyphPreviewCanvas: drawGlyphPreviewCanvas,
        drawGlyphDiagnosticsCanvas: drawGlyphDiagnosticsCanvas
    };
})(jQuery, PDFalyzer);
