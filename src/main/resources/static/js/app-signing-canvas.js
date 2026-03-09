/**
 * PDFalyzer Studio -- Freehand drawing canvas for signature visual representation.
 * Supports mouse, touch, and pointer events with pressure sensitivity,
 * velocity-based dynamic pen width, multiple smoothing algorithms, and biometric data recording.
 *
 * Smoothing is non-destructive: raw stroke points are always retained.
 * Changing the algorithm or level re-renders from the original data.
 * Heavy smoothing runs async with a progress overlay to avoid blocking the UI.
 *
 * Available algorithms:
 *   none     - Raw recorded points, no smoothing
 *   bezier   - Quadratic bezier with interpolated control points (original)
 *   chaikin  - Chaikin corner-cutting subdivision (iterative, extreme oversmoothing possible)
 *   gaussian - Gaussian kernel smoothing on X/Y coordinates
 *   catmull  - Catmull-Rom centripetal spline interpolation
 *   bspline  - Cubic B-spline approximation (control-point based)
 */
PDFalyzer.SigningCanvas = (function ($, P) {
    'use strict';

    var _canvas = null;
    var _ctx = null;
    var _drawing = false;
    var _strokes = [];       // Array of stroke arrays for undo (raw recorded points)
    var _currentStroke = [];
    var _penColor = '#1a1a2e';
    var _basePenWidth = 2.0;
    var _maxPenWidth = 6.0;
    var _minPenWidth = 0.8;
    var _lastPoint = null;
    var _lastTime = 0;
    var _lastWidth = 2.0;

    // Smoothing
    var _smoothingLevel = 0.5;
    var _smoothingAlgorithm = 'chaikin'; // none|bezier|chaikin|gaussian|catmull|bspline

    // Async redraw state
    var _redrawTimer = null;
    var _redrawDebounceMs = 80;
    var _asyncAbort = false;
    var MAX_OUTPUT_POINTS = 500000; // safety cap per stroke

    // Biometric data recording
    var _biometricEnabled = true;
    var _biometricData = [];
    var _currentBioStroke = [];
    var _startTimestamp = 0;

    function init(canvasEl) {
        _canvas = canvasEl;
        _ctx = _canvas.getContext('2d');
        _strokes = [];
        _currentStroke = [];
        _drawing = false;
        _biometricData = [];
        _currentBioStroke = [];
        _startTimestamp = 0;
        _lastPoint = null;
        _lastTime = 0;
        _lastWidth = _basePenWidth;

        _canvas.style.touchAction = 'none';
        _canvas.addEventListener('pointerdown', onPointerDown);
        _canvas.addEventListener('pointermove', onPointerMove);
        _canvas.addEventListener('pointerup', onPointerUp);
        _canvas.addEventListener('pointerleave', onPointerUp);

        clear();
    }

    function destroy() {
        if (!_canvas) return;
        _canvas.removeEventListener('pointerdown', onPointerDown);
        _canvas.removeEventListener('pointermove', onPointerMove);
        _canvas.removeEventListener('pointerup', onPointerUp);
        _canvas.removeEventListener('pointerleave', onPointerUp);
        cancelAsyncRedraw();
        _canvas = null;
        _ctx = null;
    }

    // ── Pointer events ─────────────────────────────────────────────────────

    function onPointerDown(e) {
        e.preventDefault();
        _drawing = true;
        _currentStroke = [];
        _currentBioStroke = [];
        if (!_startTimestamp) _startTimestamp = performance.now();

        var pt = getPoint(e);
        pt.t = performance.now() - _startTimestamp;
        _currentStroke.push(pt);
        _lastPoint = pt;
        _lastTime = performance.now();
        _lastWidth = computeWidth(pt.pressure, 0);

        if (_biometricEnabled) {
            _currentBioStroke.push({
                x: pt.x, y: pt.y, p: pt.pressure,
                t: pt.t, tiltX: e.tiltX || 0, tiltY: e.tiltY || 0
            });
        }

        _ctx.beginPath();
        _ctx.moveTo(pt.x, pt.y);
    }

    function onPointerMove(e) {
        if (!_drawing) return;
        e.preventDefault();
        var pt = getPoint(e);
        var now = performance.now();
        pt.t = now - _startTimestamp;

        var dx = pt.x - _lastPoint.x;
        var dy = pt.y - _lastPoint.y;
        var dt = now - _lastTime;
        var velocity = dt > 0 ? Math.sqrt(dx * dx + dy * dy) / dt : 0;

        var targetWidth = computeWidth(pt.pressure, velocity);
        var w = _lastWidth + (_lastWidth < targetWidth ? 0.3 : 0.5) * (targetWidth - _lastWidth);
        pt.width = w;
        _lastWidth = w;

        _currentStroke.push(pt);

        if (_biometricEnabled) {
            _currentBioStroke.push({
                x: pt.x, y: pt.y, p: pt.pressure,
                t: pt.t, tiltX: e.tiltX || 0, tiltY: e.tiltY || 0
            });
        }

        // Live preview: use bezier for real-time (fast), full algorithm on redraw
        if (_smoothingAlgorithm !== 'none' && _currentStroke.length >= 3) {
            drawBezierSegment(_currentStroke, w);
        } else {
            drawLineSegment(_lastPoint, pt, w);
        }

        _lastPoint = pt;
        _lastTime = now;
    }

    function onPointerUp(e) {
        if (!_drawing) return;
        _drawing = false;
        if (_currentStroke.length > 1) {
            _strokes.push(_currentStroke.slice());
        }
        if (_biometricEnabled && _currentBioStroke.length > 1) {
            _biometricData.push(_currentBioStroke.slice());
        }
        _currentStroke = [];
        _currentBioStroke = [];
        // Redraw with the selected algorithm applied to all strokes
        scheduleRedraw();
    }

    // ── Width computation ──────────────────────────────────────────────────

    function computeWidth(pressure, velocity) {
        var p = (typeof pressure === 'number' && pressure > 0) ? pressure : 0.5;
        var velocityFactor = 1.0 - Math.min(velocity * 0.3, 0.6);
        var w = _basePenWidth + p * (_maxPenWidth - _basePenWidth);
        w *= velocityFactor;
        return Math.max(_minPenWidth, Math.min(_maxPenWidth, w));
    }

    // ── Drawing primitives ────────────────────────────────────────────────

    function drawLineSegment(from, to, w) {
        _ctx.lineWidth = w;
        _ctx.strokeStyle = _penColor;
        _ctx.lineCap = 'round';
        _ctx.lineJoin = 'round';
        _ctx.beginPath();
        _ctx.moveTo(from.x, from.y);
        _ctx.lineTo(to.x, to.y);
        _ctx.stroke();
    }

    function drawBezierSegment(stroke, w) {
        var len = stroke.length;
        if (len < 3) return;
        var p0 = stroke[len - 3];
        var p1 = stroke[len - 2];
        var p2 = stroke[len - 1];
        var sf = Math.min(_smoothingLevel, 1.0);
        var mx1 = (p0.x + p1.x) / 2;
        var my1 = (p0.y + p1.y) / 2;
        var mx2 = (p1.x + p2.x) / 2;
        var my2 = (p1.y + p2.y) / 2;
        var cpx = p1.x + sf * (((mx1 + mx2) / 2) - p1.x);
        var cpy = p1.y + sf * (((my1 + my2) / 2) - p1.y);
        _ctx.lineWidth = w;
        _ctx.strokeStyle = _penColor;
        _ctx.lineCap = 'round';
        _ctx.lineJoin = 'round';
        _ctx.beginPath();
        _ctx.moveTo(mx1, my1);
        _ctx.quadraticCurveTo(cpx, cpy, mx2, my2);
        _ctx.stroke();
    }

    // ── Point extraction ──────────────────────────────────────────────────

    function getPoint(e) {
        var rect = _canvas.getBoundingClientRect();
        var scaleX = _canvas.width / rect.width;
        var scaleY = _canvas.height / rect.height;
        return {
            x: (e.clientX - rect.left) * scaleX,
            y: (e.clientY - rect.top) * scaleY,
            pressure: e.pressure || 0.5
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SMOOTHING ALGORITHMS
    //  Each takes an array of raw points and _smoothingLevel, returns a new
    //  array of smoothed {x, y} points. Width is interpolated separately.
    //  All algorithms guard against runaway output sizes.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Chaikin corner-cutting subdivision.
     * Each pass roughly doubles point count. We cap iterations so output
     * stays under MAX_OUTPUT_POINTS per stroke. At level 50 with a typical
     * 200-point stroke, this gives ~15 iterations (200 * 2^15 ≈ 6.5M capped).
     */
    function smoothChaikin(pts) {
        var iterations = Math.max(0, Math.floor(_smoothingLevel));
        if (iterations === 0 || pts.length < 3) return pts;

        // Cap iterations so output stays manageable
        var maxIter = Math.floor(Math.log2(MAX_OUTPUT_POINTS / Math.max(pts.length, 2)));
        maxIter = Math.max(1, Math.min(maxIter, 20)); // absolute cap at 20
        iterations = Math.min(iterations, maxIter);

        var result = pts;
        for (var iter = 0; iter < iterations; iter++) {
            var next = [result[0]];
            for (var i = 0; i < result.length - 1; i++) {
                var a = result[i];
                var b = result[i + 1];
                next.push({ x: 0.75 * a.x + 0.25 * b.x, y: 0.75 * a.y + 0.25 * b.y });
                next.push({ x: 0.25 * a.x + 0.75 * b.x, y: 0.25 * a.y + 0.75 * b.y });
            }
            next.push(result[result.length - 1]);
            result = next;
            if (result.length > MAX_OUTPUT_POINTS) break;
        }
        return result;
    }

    /**
     * Gaussian kernel smoothing on X/Y coordinates.
     * smoothingLevel controls kernel radius (sigma = smoothingLevel * 5).
     * Kernel radius capped to avoid O(n*r) blowup.
     */
    function smoothGaussian(pts) {
        if (pts.length < 3 || _smoothingLevel <= 0) return pts;
        var sigma = _smoothingLevel * 5;
        var radius = Math.min(Math.ceil(sigma * 2.5), 500); // cap radius
        if (radius < 1) return pts;

        var kernel = [];
        var kSum = 0;
        for (var k = -radius; k <= radius; k++) {
            var v = Math.exp(-(k * k) / (2 * sigma * sigma));
            kernel.push(v);
            kSum += v;
        }
        for (var ki = 0; ki < kernel.length; ki++) kernel[ki] /= kSum;

        var result = [];
        for (var i = 0; i < pts.length; i++) {
            var sx = 0, sy = 0;
            for (var j = 0; j < kernel.length; j++) {
                var idx = i + (j - radius);
                idx = Math.max(0, Math.min(pts.length - 1, idx));
                sx += pts[idx].x * kernel[j];
                sy += pts[idx].y * kernel[j];
            }
            result.push({ x: sx, y: sy });
        }
        return result;
    }

    /**
     * Catmull-Rom centripetal spline interpolation.
     * Density capped to keep output points bounded.
     */
    function smoothCatmullRom(pts) {
        if (pts.length < 3) return pts;
        var density = Math.max(1, Math.round(_smoothingLevel * 3));
        var maxDensity = Math.max(1, Math.floor(MAX_OUTPUT_POINTS / Math.max(pts.length, 1)));
        density = Math.min(density, maxDensity);
        var result = [pts[0]];

        for (var i = 0; i < pts.length - 1; i++) {
            var p0 = pts[Math.max(0, i - 1)];
            var p1 = pts[i];
            var p2 = pts[Math.min(pts.length - 1, i + 1)];
            var p3 = pts[Math.min(pts.length - 1, i + 2)];

            for (var s = 1; s <= density; s++) {
                var t = s / density;
                var t2 = t * t;
                var t3 = t2 * t;
                var x = 0.5 * ((2 * p1.x) +
                    (-p0.x + p2.x) * t +
                    (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                    (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
                var y = 0.5 * ((2 * p1.y) +
                    (-p0.y + p2.y) * t +
                    (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                    (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
                result.push({ x: x, y: y });
            }
        }
        return result;
    }

    /**
     * Cubic B-spline approximation. Density capped similarly.
     */
    function smoothBSpline(pts) {
        if (pts.length < 4) return pts;
        var density = Math.max(1, Math.round(_smoothingLevel * 3));
        var maxDensity = Math.max(1, Math.floor(MAX_OUTPUT_POINTS / Math.max(pts.length, 1)));
        density = Math.min(density, maxDensity);
        var result = [pts[0]];

        for (var i = 1; i < pts.length - 2; i++) {
            var p0 = pts[i - 1];
            var p1 = pts[i];
            var p2 = pts[i + 1];
            var p3 = pts[i + 2];

            for (var s = 0; s < density; s++) {
                var t = s / density;
                var t2 = t * t;
                var t3 = t2 * t;
                var b0 = (-t3 + 3 * t2 - 3 * t + 1) / 6;
                var b1 = (3 * t3 - 6 * t2 + 4) / 6;
                var b2 = (-3 * t3 + 3 * t2 + 3 * t + 1) / 6;
                var b3 = t3 / 6;
                result.push({
                    x: b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
                    y: b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y
                });
            }
        }
        result.push(pts[pts.length - 1]);
        return result;
    }

    /**
     * Apply the selected smoothing algorithm to a stroke.
     * Returns smoothed points array, or null if the algorithm draws inline (bezier).
     */
    function applySmoothing(rawStroke) {
        if (_smoothingAlgorithm === 'none' || _smoothingLevel <= 0) return null;
        switch (_smoothingAlgorithm) {
            case 'chaikin':  return smoothChaikin(rawStroke);
            case 'gaussian': return smoothGaussian(rawStroke);
            case 'catmull':  return smoothCatmullRom(rawStroke);
            case 'bspline':  return smoothBSpline(rawStroke);
            case 'bezier':   return null; // inline
            default:         return null;
        }
    }

    // ── Async redraw with progress ────────────────────────────────────────

    function cancelAsyncRedraw() {
        _asyncAbort = true;
        if (_redrawTimer) { clearTimeout(_redrawTimer); _redrawTimer = null; }
        hideProgress();
    }

    function scheduleRedraw() {
        cancelAsyncRedraw();
        _redrawTimer = setTimeout(function () {
            _redrawTimer = null;
            redraw();
        }, _redrawDebounceMs);
    }

    function estimateWork() {
        var totalPts = 0;
        for (var s = 0; s < _strokes.length; s++) totalPts += _strokes[s].length;
        if (_smoothingAlgorithm === 'chaikin') {
            var iters = Math.min(Math.floor(_smoothingLevel), 20);
            return totalPts * Math.pow(2, Math.min(iters, 15));
        }
        if (_smoothingAlgorithm === 'gaussian') {
            var r = Math.min(Math.ceil(_smoothingLevel * 5 * 2.5), 500);
            return totalPts * r * 2;
        }
        if (_smoothingAlgorithm === 'catmull' || _smoothingAlgorithm === 'bspline') {
            return totalPts * Math.max(1, Math.round(_smoothingLevel * 3));
        }
        return totalPts;
    }

    function showProgress(text) {
        var $c = $(_canvas).parent();
        if (!$c.find('.sig-smooth-progress').length) {
            $c.css('position', 'relative');
            $c.append('<div class="sig-smooth-progress"><i class="fas fa-spinner fa-spin me-1"></i><span class="sig-smooth-progress-text"></span></div>');
        }
        $c.find('.sig-smooth-progress-text').text(text || 'Smoothing...');
        $c.find('.sig-smooth-progress').show();
    }

    function hideProgress() {
        var $c = $(_canvas ? $(_canvas).parent() : []);
        $c.find('.sig-smooth-progress').hide();
    }

    // ── Undo / clear / redraw ─────────────────────────────────────────────

    function undoStroke() {
        if (_strokes.length === 0) return;
        _strokes.pop();
        if (_biometricData.length > 0) _biometricData.pop();
        scheduleRedraw();
    }

    function clear() {
        cancelAsyncRedraw();
        _strokes = [];
        _currentStroke = [];
        _biometricData = [];
        _currentBioStroke = [];
        _startTimestamp = 0;
        if (_ctx && _canvas) {
            _ctx.clearRect(0, 0, _canvas.width, _canvas.height);
            _ctx.fillStyle = '#ffffff';
            _ctx.fillRect(0, 0, _canvas.width, _canvas.height);
        }
    }

    function redraw() {
        if (!_ctx || !_canvas) return;
        cancelAsyncRedraw();
        _asyncAbort = false;

        var work = estimateWork();
        var isHeavy = work > 200000;

        if (isHeavy) {
            redrawAsync();
        } else {
            redrawSync();
        }
    }

    function redrawSync() {
        clearCanvas();
        for (var s = 0; s < _strokes.length; s++) {
            var rawStroke = _strokes[s];
            if (rawStroke.length < 2) continue;
            var smoothed = applySmoothing(rawStroke);
            if (smoothed && smoothed.length >= 2) {
                drawSmoothPath(smoothed, computeAverageWidth(rawStroke));
            } else {
                drawStrokeInline(rawStroke);
            }
        }
    }

    function redrawAsync() {
        clearCanvas();
        var strokeIdx = 0;
        var total = _strokes.length;
        var startTime = performance.now();

        showProgress('Smoothing stroke 1/' + total + '...');

        function processNext() {
            if (_asyncAbort || strokeIdx >= total) {
                hideProgress();
                return;
            }

            var rawStroke = _strokes[strokeIdx];
            strokeIdx++;

            if (rawStroke.length < 2) {
                scheduleNext();
                return;
            }

            var smoothed = applySmoothing(rawStroke);
            if (smoothed && smoothed.length >= 2) {
                drawSmoothPath(smoothed, computeAverageWidth(rawStroke));
            } else {
                drawStrokeInline(rawStroke);
            }

            var elapsed = performance.now() - startTime;
            if (elapsed > 2000) {
                showProgress('Smoothing stroke ' + strokeIdx + '/' + total + ' (' + Math.round(elapsed / 1000) + 's)...');
            }

            scheduleNext();
        }

        function scheduleNext() {
            if (_asyncAbort) { hideProgress(); return; }
            if (strokeIdx >= total) { hideProgress(); return; }
            setTimeout(processNext, 0);
        }

        processNext();
    }

    function clearCanvas() {
        _ctx.clearRect(0, 0, _canvas.width, _canvas.height);
        _ctx.fillStyle = '#ffffff';
        _ctx.fillRect(0, 0, _canvas.width, _canvas.height);
    }

    function computeAverageWidth(stroke) {
        var sum = 0, count = 0;
        for (var i = 0; i < stroke.length; i++) {
            if (stroke[i].width) { sum += stroke[i].width; count++; }
        }
        return count > 0 ? sum / count : _basePenWidth;
    }

    function drawSmoothPath(pts, w) {
        _ctx.lineWidth = w;
        _ctx.strokeStyle = _penColor;
        _ctx.lineCap = 'round';
        _ctx.lineJoin = 'round';
        _ctx.beginPath();
        _ctx.moveTo(pts[0].x, pts[0].y);
        for (var i = 1; i < pts.length; i++) {
            _ctx.lineTo(pts[i].x, pts[i].y);
        }
        _ctx.stroke();
    }

    function drawStrokeInline(stroke) {
        var isBezier = (_smoothingAlgorithm === 'bezier' && _smoothingLevel > 0);
        for (var i = 1; i < stroke.length; i++) {
            var prev = stroke[i - 1];
            var cur = stroke[i];
            var w = cur.width || (_basePenWidth + (cur.pressure || 0.5) * (_maxPenWidth - _basePenWidth));

            if (isBezier && i >= 2) {
                var pp = stroke[i - 2];
                var sf = _smoothingLevel;
                var mx1 = (pp.x + prev.x) / 2;
                var my1 = (pp.y + prev.y) / 2;
                var mx2 = (prev.x + cur.x) / 2;
                var my2 = (prev.y + cur.y) / 2;
                var cpx = prev.x + sf * (((mx1 + mx2) / 2) - prev.x);
                var cpy = prev.y + sf * (((my1 + my2) / 2) - prev.y);
                _ctx.lineWidth = w;
                _ctx.strokeStyle = _penColor;
                _ctx.lineCap = 'round';
                _ctx.lineJoin = 'round';
                _ctx.beginPath();
                _ctx.moveTo(mx1, my1);
                _ctx.quadraticCurveTo(cpx, cpy, mx2, my2);
                _ctx.stroke();
            } else {
                drawLineSegment(prev, cur, w);
            }
        }
    }

    // ── Data export ───────────────────────────────────────────────────────

    function toDataURL() {
        if (!_canvas) return null;
        return _canvas.toDataURL('image/png');
    }

    function isEmpty() {
        return _strokes.length === 0;
    }

    function hasStrokes() {
        return _strokes.length > 0;
    }

    function getBiometricData() {
        return {
            canvasWidth: _canvas ? _canvas.width : 0,
            canvasHeight: _canvas ? _canvas.height : 0,
            strokes: _biometricData.slice(),
            totalPoints: _biometricData.reduce(function (sum, s) { return sum + s.length; }, 0),
            totalStrokes: _biometricData.length,
            recordedAt: new Date().toISOString()
        };
    }

    // ── Configuration ─────────────────────────────────────────────────────

    function setPenColor(color) {
        _penColor = color || '#1a1a2e';
    }

    function setPenWidth(w) {
        _basePenWidth = w || 2.0;
    }

    function setMaxPenWidth(w) {
        _maxPenWidth = w || 6.0;
    }

    function setMinPenWidth(w) {
        _minPenWidth = w || 0.8;
    }

    function setSmoothingLevel(level) {
        _smoothingLevel = Math.max(0, level || 0);
        scheduleRedraw();
    }

    function setSmoothingAlgorithm(algo) {
        var valid = ['none', 'bezier', 'chaikin', 'gaussian', 'catmull', 'bspline'];
        _smoothingAlgorithm = valid.indexOf(algo) >= 0 ? algo : 'chaikin';
        scheduleRedraw();
    }

    function getSmoothingAlgorithm() {
        return _smoothingAlgorithm;
    }

    function setBiometricEnabled(enabled) {
        _biometricEnabled = !!enabled;
    }

    function loadImage(dataUrl) {
        if (!_ctx || !_canvas || !dataUrl) return;
        var img = new Image();
        img.onload = function () {
            clear();
            var scale = Math.min(_canvas.width / img.width, _canvas.height / img.height);
            var dw = img.width * scale;
            var dh = img.height * scale;
            var dx = (_canvas.width - dw) / 2;
            var dy = (_canvas.height - dh) / 2;
            _ctx.drawImage(img, dx, dy, dw, dh);
            _strokes = [[ {x: 0, y: 0, pressure: 0.5} ]];
        };
        img.src = dataUrl;
    }

    return {
        init: init,
        destroy: destroy,
        undoStroke: undoStroke,
        clear: clear,
        redraw: redraw,
        toDataURL: toDataURL,
        isEmpty: isEmpty,
        hasStrokes: hasStrokes,
        setPenColor: setPenColor,
        setPenWidth: setPenWidth,
        setMaxPenWidth: setMaxPenWidth,
        setMinPenWidth: setMinPenWidth,
        setSmoothingLevel: setSmoothingLevel,
        setSmoothingAlgorithm: setSmoothingAlgorithm,
        getSmoothingAlgorithm: getSmoothingAlgorithm,
        setBiometricEnabled: setBiometricEnabled,
        getBiometricData: getBiometricData,
        loadImage: loadImage
    };
})(jQuery, PDFalyzer);
