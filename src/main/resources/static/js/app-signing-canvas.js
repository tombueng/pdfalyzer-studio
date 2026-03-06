/**
 * PDFalyzer Studio -- Freehand drawing canvas for signature visual representation.
 * Supports mouse, touch, and pointer events with pressure sensitivity,
 * velocity-based dynamic pen width, real-time bezier smoothing, and biometric data recording.
 */
PDFalyzer.SigningCanvas = (function ($, P) {
    'use strict';

    var _canvas = null;
    var _ctx = null;
    var _drawing = false;
    var _strokes = [];       // Array of stroke arrays for undo
    var _currentStroke = [];
    var _penColor = '#1a1a2e';
    var _basePenWidth = 2.0;
    var _maxPenWidth = 6.0;
    var _minPenWidth = 0.8;
    var _lastPoint = null;
    var _lastTime = 0;
    var _lastWidth = 2.0;

    // Smoothing
    var _smoothingLevel = 0.5; // 0 = no smoothing, 1 = max smoothing
    var _smoothingEnabled = true;

    // Biometric data recording
    var _biometricEnabled = true;
    var _biometricData = []; // Array of stroke biometric entries
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

        // Compute velocity (px/ms)
        var dx = pt.x - _lastPoint.x;
        var dy = pt.y - _lastPoint.y;
        var dt = now - _lastTime;
        var velocity = dt > 0 ? Math.sqrt(dx * dx + dy * dy) / dt : 0;

        // Dynamic width from pressure + velocity
        var targetWidth = computeWidth(pt.pressure, velocity);
        // Smooth width transitions to avoid jagged strokes
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

        // Draw with bezier smoothing
        if (_smoothingEnabled && _currentStroke.length >= 3) {
            drawSmoothedSegment(_currentStroke, w);
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
    }

    // ── Width computation ──────────────────────────────────────────────────

    function computeWidth(pressure, velocity) {
        // Pressure contribution (0.0 - 1.0)
        var p = (typeof pressure === 'number' && pressure > 0) ? pressure : 0.5;

        // Velocity dampening: faster movement = thinner lines (like real ink)
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

    function drawSmoothedSegment(stroke, w) {
        var len = stroke.length;
        if (len < 3) return;

        // Use last 3 points for quadratic bezier
        var p0 = stroke[len - 3];
        var p1 = stroke[len - 2];
        var p2 = stroke[len - 1];

        var smoothFactor = _smoothingLevel;

        // Midpoints
        var mx1 = (p0.x + p1.x) / 2;
        var my1 = (p0.y + p1.y) / 2;
        var mx2 = (p1.x + p2.x) / 2;
        var my2 = (p1.y + p2.y) / 2;

        // Interpolated control point
        var cpx = p1.x + smoothFactor * (((mx1 + mx2) / 2) - p1.x);
        var cpy = p1.y + smoothFactor * (((my1 + my2) / 2) - p1.y);

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

    // ── Undo / clear / redraw ─────────────────────────────────────────────

    function undoStroke() {
        if (_strokes.length === 0) return;
        _strokes.pop();
        if (_biometricData.length > 0) _biometricData.pop();
        redraw();
    }

    function clear() {
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
        _ctx.clearRect(0, 0, _canvas.width, _canvas.height);
        _ctx.fillStyle = '#ffffff';
        _ctx.fillRect(0, 0, _canvas.width, _canvas.height);

        for (var s = 0; s < _strokes.length; s++) {
            var stroke = _strokes[s];
            if (stroke.length < 2) continue;

            // Re-derive widths if needed, then draw with smoothing
            for (var i = 1; i < stroke.length; i++) {
                var prev = stroke[i - 1];
                var cur = stroke[i];
                var w = cur.width || (_basePenWidth + (cur.pressure || 0.5) * (_maxPenWidth - _basePenWidth));

                if (_smoothingEnabled && i >= 2) {
                    var pp = stroke[i - 2];
                    var mx1 = (pp.x + prev.x) / 2;
                    var my1 = (pp.y + prev.y) / 2;
                    var mx2 = (prev.x + cur.x) / 2;
                    var my2 = (prev.y + cur.y) / 2;
                    var cpx = prev.x + _smoothingLevel * (((mx1 + mx2) / 2) - prev.x);
                    var cpy = prev.y + _smoothingLevel * (((my1 + my2) / 2) - prev.y);
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
        _smoothingLevel = Math.max(0, Math.min(1, level || 0));
    }

    function setSmoothingEnabled(enabled) {
        _smoothingEnabled = !!enabled;
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
            // Mark as having content so isEmpty() returns false
            _strokes = [[ {x: 0, y: 0, pressure: 0.5} ]];
        };
        img.src = dataUrl;
    }

    return {
        init: init,
        destroy: destroy,
        undoStroke: undoStroke,
        clear: clear,
        toDataURL: toDataURL,
        isEmpty: isEmpty,
        hasStrokes: hasStrokes,
        setPenColor: setPenColor,
        setPenWidth: setPenWidth,
        setMaxPenWidth: setMaxPenWidth,
        setMinPenWidth: setMinPenWidth,
        setSmoothingLevel: setSmoothingLevel,
        setSmoothingEnabled: setSmoothingEnabled,
        setBiometricEnabled: setBiometricEnabled,
        getBiometricData: getBiometricData,
        loadImage: loadImage
    };
})(jQuery, PDFalyzer);
