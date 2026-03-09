/**
 * PDFalyzer Studio -- Biometric Data Inspector
 *
 * Visualizes biometric signature data (x, y, pressure, tilt, timing) with multiple
 * rendering modes, animated replay, pseudo-3D projections, heatmaps, analytics charts,
 * and comparison capabilities.
 *
 * Data format (per stroke point): { x, y, p (pressure 0-1), t (ms), tiltX, tiltY }
 * Envelope: { canvasWidth, canvasHeight, strokes: [[point...]], totalPoints, totalStrokes, recordedAt }
 */
PDFalyzer.BioInspector = (function ($, P) {
    'use strict';

    // ── State ──────────────────────────────────────────────────────────────
    var _modal = null;
    var _bsModal = null;
    var _bioData = null;       // primary dataset
    var _bioDataB = null;      // comparison dataset (optional)
    var _flatPoints = [];      // flattened + enriched points (primary)
    var _flatPointsB = [];     // flattened + enriched points (comparison)
    var _currentView = 'pressure-heatmap';
    var _replayTimer = null;
    var _replayIdx = 0;
    var _replaySpeed = 1;
    var _replayPaused = false;
    var _iso = { rx: -25, ry: 35, scale: 1 }; // 3D rotation state
    var _zAxis = 'pressure';   // pressure|speed|tiltMag
    var _dragging3d = false;
    var _drag3dStart = null;
    var _drag3dIso = null;

    // Color ramps
    var RAMP_PRESSURE = [[0, '#2196f3'], [0.3, '#4caf50'], [0.6, '#ffeb3b'], [0.85, '#ff9800'], [1, '#f44336']];
    var RAMP_SPEED    = [[0, '#1a237e'], [0.25, '#1565c0'], [0.5, '#43a047'], [0.75, '#fdd835'], [1, '#d50000']];
    var RAMP_TILT     = [[0, '#e1bee7'], [0.5, '#7b1fa2'], [1, '#ff6f00']];

    // ── Public API ─────────────────────────────────────────────────────────

    function open(bioData, bioDataB) {
        _bioData = bioData;
        _bioDataB = bioDataB || null;
        if (!_bioData || !_bioData.strokes || !_bioData.strokes.length) {
            P.Utils.toast('No biometric data to inspect', 'warning');
            return;
        }
        _flatPoints = enrichData(_bioData);
        _flatPointsB = _bioDataB ? enrichData(_bioDataB) : [];
        ensureModal();
        render();
        _bsModal.show();
    }

    function close() {
        stopReplay();
        if (_bsModal) _bsModal.hide();
    }

    // ── Modal skeleton ─────────────────────────────────────────────────────

    function ensureModal() {
        if (_modal) return;
        var $m = $('#bioInspectorModal');
        if (!$m.length) return;
        _modal = $m;
        _bsModal = new bootstrap.Modal($m[0], { backdrop: true, keyboard: true });
        $m.on('hidden.bs.modal', function () { stopReplay(); });
    }

    function render() {
        if (!_modal) return;
        var html = '<div class="modal-content bio-inspector-content">';
        html += '<div class="modal-header bio-inspector-header">';
        html += '<h5 class="modal-title"><i class="fas fa-microscope me-2"></i>' + P.Utils.i18n('bio.inspector.title', 'Biometric Data Inspector') + '</h5>';
        html += '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>';
        html += '</div>';
        html += '<div class="bio-inspector-body">';
        html += buildToolbar();
        html += '<div class="bio-inspector-canvas-wrap"><canvas id="bioInspectorCanvas"></canvas></div>';
        html += buildMetricsPanel();
        html += buildLegend();
        html += '</div>';
        html += '</div>';
        _modal.find('.modal-dialog').html(html);
        bindEvents();
        drawView();
        updateMetrics();
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    function buildToolbar() {
        var views = [
            { id: 'pressure-heatmap', icon: 'fa-temperature-high', label: P.Utils.i18n('bio.view.pressure', 'Pressure') },
            { id: 'velocity-heatmap', icon: 'fa-tachometer-alt',   label: P.Utils.i18n('bio.view.velocity', 'Velocity') },
            { id: 'tilt-vectors',     icon: 'fa-compass',          label: P.Utils.i18n('bio.view.tilt', 'Tilt') },
            { id: 'width-pressure',   icon: 'fa-pen-nib',          label: P.Utils.i18n('bio.view.widthP', 'Width (P)') },
            { id: 'width-speed',      icon: 'fa-wind',             label: P.Utils.i18n('bio.view.widthV', 'Width (V)') },
            { id: 'temporal',         icon: 'fa-hourglass-half',   label: P.Utils.i18n('bio.view.temporal', 'Temporal') },
            { id: '3d',               icon: 'fa-cube',             label: '3D' },
            { id: 'charts',           icon: 'fa-chart-line',       label: P.Utils.i18n('bio.view.charts', 'Charts') },
            { id: 'replay',           icon: 'fa-play',             label: P.Utils.i18n('bio.view.replay', 'Replay') }
        ];
        if (_bioDataB) {
            views.push({ id: 'compare', icon: 'fa-code-compare', label: P.Utils.i18n('bio.view.compare', 'Compare') });
        }

        var html = '<div class="bio-toolbar">';
        for (var i = 0; i < views.length; i++) {
            var v = views[i];
            html += '<button class="bio-toolbar-btn' + (v.id === _currentView ? ' active' : '') + '" data-bio-view="' + v.id + '" title="' + v.label + '">';
            html += '<i class="fas ' + v.icon + '"></i><span>' + v.label + '</span></button>';
        }
        html += '<span class="bio-toolbar-sep"></span>';

        // 3D controls (Z-axis selector)
        html += '<select class="bio-toolbar-select" id="bioZAxis" title="' + P.Utils.i18n('bio.zaxis', 'Z-Axis for 3D view') + '">';
        html += '<option value="pressure"' + (_zAxis === 'pressure' ? ' selected' : '') + '>' + P.Utils.i18n('bio.zaxis.pressure', 'Z: Pressure') + '</option>';
        html += '<option value="speed"' + (_zAxis === 'speed' ? ' selected' : '') + '>' + P.Utils.i18n('bio.zaxis.speed', 'Z: Speed') + '</option>';
        html += '<option value="tiltMag"' + (_zAxis === 'tiltMag' ? ' selected' : '') + '>' + P.Utils.i18n('bio.zaxis.tilt', 'Z: Tilt') + '</option>';
        html += '</select>';

        // Replay speed
        html += '<select class="bio-toolbar-select" id="bioReplaySpeed" title="' + P.Utils.i18n('bio.replay.speed', 'Replay speed') + '">';
        html += '<option value="0.25">0.25x</option>';
        html += '<option value="0.5">0.5x</option>';
        html += '<option value="1" selected>1x</option>';
        html += '<option value="2">2x</option>';
        html += '<option value="4">4x</option>';
        html += '</select>';

        html += '</div>';
        return html;
    }

    // ── Metrics panel ──────────────────────────────────────────────────────

    function buildMetricsPanel() {
        return '<div class="bio-metrics" id="bioMetrics"></div>';
    }

    function updateMetrics() {
        var $m = $('#bioMetrics');
        if (!$m.length || !_flatPoints.length) return;

        var stats = computeStats(_flatPoints, _bioData);
        var html = '<div class="bio-metrics-grid">';
        html += metricCard('fa-clock',           P.Utils.i18n('bio.metric.duration', 'Duration'),       stats.duration.toFixed(1) + ' ms');
        html += metricCard('fa-pencil-alt',       P.Utils.i18n('bio.metric.strokes', 'Strokes'),        stats.strokeCount);
        html += metricCard('fa-circle',           P.Utils.i18n('bio.metric.points', 'Points'),          stats.pointCount);
        html += metricCard('fa-tachometer-alt',   P.Utils.i18n('bio.metric.avgSpeed', 'Avg Speed'),     stats.avgSpeed.toFixed(1) + ' px/ms');
        html += metricCard('fa-bolt',             P.Utils.i18n('bio.metric.maxSpeed', 'Max Speed'),     stats.maxSpeed.toFixed(1) + ' px/ms');
        html += metricCard('fa-compress-arrows-alt', P.Utils.i18n('bio.metric.avgPressure', 'Avg Pressure'), (stats.avgPressure * 100).toFixed(0) + '%');
        html += metricCard('fa-arrows-alt-v',     P.Utils.i18n('bio.metric.pressureRange', 'P Range'),  (stats.minPressure * 100).toFixed(0) + '% - ' + (stats.maxPressure * 100).toFixed(0) + '%');
        html += metricCard('fa-ruler-combined',   P.Utils.i18n('bio.metric.pathLen', 'Path Length'),    stats.pathLength.toFixed(0) + ' px');
        html += metricCard('fa-wave-square',      P.Utils.i18n('bio.metric.jerk', 'Avg Jerk'),         stats.avgJerk.toFixed(3));
        html += metricCard('fa-sliders-h',        P.Utils.i18n('bio.metric.consistency', 'Consistency'), (stats.consistency * 100).toFixed(0) + '%');
        html += metricCard('fa-compass',          P.Utils.i18n('bio.metric.avgTilt', 'Avg Tilt'),       stats.avgTilt.toFixed(1) + '\u00B0');
        html += metricCard('fa-ruler-horizontal',  P.Utils.i18n('bio.metric.bbox', 'Bounding Box'),     stats.bboxW.toFixed(0) + ' x ' + stats.bboxH.toFixed(0) + ' px');
        html += '</div>';

        if (_bioDataB && _flatPointsB.length) {
            var statsB = computeStats(_flatPointsB, _bioDataB);
            var sim = computeSimilarity(stats, statsB);
            html += '<div class="bio-compare-bar">';
            html += '<span class="bio-compare-label"><i class="fas fa-code-compare me-1"></i>' + P.Utils.i18n('bio.compare.similarity', 'Similarity Score') + '</span>';
            html += '<div class="bio-compare-gauge"><div class="bio-compare-fill" style="width:' + sim + '%;background:' + simColor(sim) + ';"></div></div>';
            html += '<span class="bio-compare-pct">' + sim.toFixed(0) + '%</span>';
            html += '</div>';
        }

        $m.html(html);
    }

    function metricCard(icon, label, value) {
        return '<div class="bio-metric-card"><i class="fas ' + icon + '"></i>' +
            '<div><span class="bio-metric-value">' + value + '</span><span class="bio-metric-label">' + label + '</span></div></div>';
    }

    // ── Legend ──────────────────────────────────────────────────────────────

    function buildLegend() {
        return '<div class="bio-legend" id="bioLegend"></div>';
    }

    function updateLegend(ramp, lowLabel, highLabel) {
        var $l = $('#bioLegend');
        if (!$l.length) return;
        if (!ramp) { $l.html(''); return; }
        var grad = ramp.map(function (s) { return s[1] + ' ' + (s[0] * 100) + '%'; }).join(', ');
        var html = '<div class="bio-legend-bar" style="background:linear-gradient(90deg,' + grad + ');"></div>';
        html += '<div class="bio-legend-labels"><span>' + lowLabel + '</span><span>' + highLabel + '</span></div>';
        $l.html(html);
    }

    // ── Event binding ──────────────────────────────────────────────────────

    function bindEvents() {
        _modal.off('.bioinsp');

        _modal.on('click.bioinsp', '.bio-toolbar-btn', function () {
            var view = $(this).data('bio-view');
            _currentView = view;
            _modal.find('.bio-toolbar-btn').removeClass('active');
            $(this).addClass('active');
            stopReplay();
            drawView();
            updateMetrics();
        });

        _modal.on('change.bioinsp', '#bioZAxis', function () {
            _zAxis = $(this).val();
            if (_currentView === '3d') drawView();
        });

        _modal.on('change.bioinsp', '#bioReplaySpeed', function () {
            _replaySpeed = parseFloat($(this).val()) || 1;
        });

        // 3D rotation drag
        var $wrap = _modal.find('.bio-inspector-canvas-wrap');
        $wrap.on('pointerdown.bioinsp', function (e) {
            if (_currentView !== '3d') return;
            _dragging3d = true;
            _drag3dStart = { x: e.clientX, y: e.clientY };
            _drag3dIso = { rx: _iso.rx, ry: _iso.ry };
            e.preventDefault();
        });
        $(document).on('pointermove.bioinsp', function (e) {
            if (!_dragging3d) return;
            _iso.rx = _drag3dIso.rx + (e.clientY - _drag3dStart.y) * 0.3;
            _iso.ry = _drag3dIso.ry + (e.clientX - _drag3dStart.x) * 0.3;
            drawView();
        });
        $(document).on('pointerup.bioinsp', function () { _dragging3d = false; });

        // Scroll zoom for 3D
        $wrap.on('wheel.bioinsp', function (e) {
            if (_currentView !== '3d') return;
            e.preventDefault();
            _iso.scale = Math.max(0.3, Math.min(3, _iso.scale - e.originalEvent.deltaY * 0.001));
            drawView();
        });
    }

    // ── View dispatcher ────────────────────────────────────────────────────

    function drawView() {
        var canvas = document.getElementById('bioInspectorCanvas');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        sizeCanvas(canvas);
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        switch (_currentView) {
            case 'pressure-heatmap': drawHeatmap(ctx, canvas, 'pressure', RAMP_PRESSURE); break;
            case 'velocity-heatmap': drawHeatmap(ctx, canvas, 'speed', RAMP_SPEED); break;
            case 'tilt-vectors':     drawTiltVectors(ctx, canvas); break;
            case 'width-pressure':   drawWidthModulated(ctx, canvas, 'pressure'); break;
            case 'width-speed':      drawWidthModulated(ctx, canvas, 'speed'); break;
            case 'temporal':         drawTemporal(ctx, canvas); break;
            case '3d':               draw3D(ctx, canvas); break;
            case 'charts':           drawCharts(ctx, canvas); break;
            case 'replay':           startReplay(ctx, canvas); break;
            case 'compare':          drawCompare(ctx, canvas); break;
        }
    }

    function sizeCanvas(canvas) {
        var wrap = canvas.parentElement;
        var dpr = window.devicePixelRatio || 1;
        var w = wrap.clientWidth;
        var h = wrap.clientHeight;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        canvas.getContext('2d').setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    // ── Data enrichment ────────────────────────────────────────────────────

    function enrichData(bio) {
        var flat = [];
        for (var s = 0; s < bio.strokes.length; s++) {
            var stroke = bio.strokes[s];
            for (var i = 0; i < stroke.length; i++) {
                var pt = {
                    x: stroke[i].x, y: stroke[i].y,
                    pressure: stroke[i].p || 0.5,
                    t: stroke[i].t || 0,
                    tiltX: stroke[i].tiltX || 0,
                    tiltY: stroke[i].tiltY || 0,
                    strokeIdx: s,
                    speed: 0, accel: 0, jerk: 0, tiltMag: 0
                };
                pt.tiltMag = Math.sqrt(pt.tiltX * pt.tiltX + pt.tiltY * pt.tiltY);

                if (i > 0) {
                    var prev = flat[flat.length - 1];
                    if (prev.strokeIdx === s) {
                        var dx = pt.x - prev.x;
                        var dy = pt.y - prev.y;
                        var dt = Math.max(pt.t - prev.t, 0.1);
                        pt.speed = Math.sqrt(dx * dx + dy * dy) / dt;
                    }
                }
                if (flat.length >= 2) {
                    var p1 = flat[flat.length - 1];
                    var p2 = flat[flat.length - 2];
                    if (p1.strokeIdx === s && p2.strokeIdx === s) {
                        var dt2 = Math.max(pt.t - p2.t, 0.2);
                        pt.accel = (pt.speed - p2.speed) / dt2;
                    }
                    if (flat.length >= 3) {
                        var p3 = flat[flat.length - 3];
                        if (p3.strokeIdx === s) {
                            var dt3 = Math.max(pt.t - p3.t, 0.3);
                            pt.jerk = (pt.accel - p3.accel) / dt3;
                        }
                    }
                }
                flat.push(pt);
            }
        }
        return flat;
    }

    // ── Statistics ──────────────────────────────────────────────────────────

    function computeStats(pts, bio) {
        var minP = 1, maxP = 0, sumP = 0, sumSpeed = 0, maxSpeed = 0, sumJerk = 0;
        var sumTilt = 0, pathLen = 0;
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        var speedArr = [];

        for (var i = 0; i < pts.length; i++) {
            var p = pts[i];
            if (p.pressure < minP) minP = p.pressure;
            if (p.pressure > maxP) maxP = p.pressure;
            sumP += p.pressure;
            sumSpeed += p.speed;
            speedArr.push(p.speed);
            if (p.speed > maxSpeed) maxSpeed = p.speed;
            sumJerk += Math.abs(p.jerk);
            sumTilt += p.tiltMag;
            if (p.x < minX) minX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.x > maxX) maxX = p.x;
            if (p.y > maxY) maxY = p.y;

            if (i > 0 && pts[i - 1].strokeIdx === p.strokeIdx) {
                var dx = p.x - pts[i - 1].x;
                var dy = p.y - pts[i - 1].y;
                pathLen += Math.sqrt(dx * dx + dy * dy);
            }
        }

        var n = pts.length || 1;
        var avgSpeed = sumSpeed / n;

        // Consistency: inverse of speed coefficient of variation (1 = perfectly consistent)
        var speedVariance = 0;
        for (var j = 0; j < speedArr.length; j++) {
            speedVariance += (speedArr[j] - avgSpeed) * (speedArr[j] - avgSpeed);
        }
        speedVariance = Math.sqrt(speedVariance / n);
        var cv = avgSpeed > 0 ? speedVariance / avgSpeed : 0;
        var consistency = Math.max(0, Math.min(1, 1 - cv));

        var duration = pts.length > 0 ? pts[pts.length - 1].t - pts[0].t : 0;

        return {
            duration: duration,
            strokeCount: bio.totalStrokes || bio.strokes.length,
            pointCount: pts.length,
            avgSpeed: avgSpeed,
            maxSpeed: maxSpeed,
            avgPressure: sumP / n,
            minPressure: minP,
            maxPressure: maxP,
            pathLength: pathLen,
            avgJerk: sumJerk / n,
            consistency: consistency,
            avgTilt: (sumTilt / n),
            bboxW: maxX - minX,
            bboxH: maxY - minY
        };
    }

    function computeSimilarity(a, b) {
        // Weighted comparison of normalized metrics
        function norm(va, vb, maxVal) {
            return 1 - Math.min(Math.abs(va - vb) / (maxVal || 1), 1);
        }
        var s = 0;
        s += norm(a.avgSpeed, b.avgSpeed, Math.max(a.avgSpeed, b.avgSpeed, 0.1)) * 15;
        s += norm(a.avgPressure, b.avgPressure, 1) * 15;
        s += norm(a.consistency, b.consistency, 1) * 15;
        s += norm(a.duration, b.duration, Math.max(a.duration, b.duration, 1)) * 10;
        s += norm(a.pathLength, b.pathLength, Math.max(a.pathLength, b.pathLength, 1)) * 15;
        s += norm(a.avgJerk, b.avgJerk, Math.max(a.avgJerk, b.avgJerk, 0.001)) * 10;
        s += norm(a.strokeCount, b.strokeCount, Math.max(a.strokeCount, b.strokeCount, 1)) * 10;
        s += norm(a.avgTilt, b.avgTilt, Math.max(a.avgTilt, b.avgTilt, 1)) * 10;
        return s;
    }

    function simColor(pct) {
        if (pct >= 80) return '#52c41a';
        if (pct >= 60) return '#faad14';
        return '#ff4d4f';
    }

    // ── Transform: map data coords to canvas coords ────────────────────────

    function dataTransform(canvas, pts, margin) {
        margin = margin || 20;
        if (!pts.length) return { sx: 1, sy: 1, ox: 0, oy: 0, w: canvas.width, h: canvas.height };
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (var i = 0; i < pts.length; i++) {
            if (pts[i].x < minX) minX = pts[i].x;
            if (pts[i].y < minY) minY = pts[i].y;
            if (pts[i].x > maxX) maxX = pts[i].x;
            if (pts[i].y > maxY) maxY = pts[i].y;
        }
        var dw = maxX - minX || 1;
        var dh = maxY - minY || 1;
        var w = (canvas.width / (window.devicePixelRatio || 1)) - margin * 2;
        var h = (canvas.height / (window.devicePixelRatio || 1)) - margin * 2;
        var scale = Math.min(w / dw, h / dh);
        return {
            sx: scale, sy: scale,
            ox: margin + (w - dw * scale) / 2 - minX * scale,
            oy: margin + (h - dh * scale) / 2 - minY * scale,
            w: w, h: h
        };
    }

    function tx(pt, tf) { return pt.x * tf.sx + tf.ox; }
    function ty(pt, tf) { return pt.y * tf.sy + tf.oy; }

    // ── Color interpolation ────────────────────────────────────────────────

    function rampColor(ramp, t) {
        t = Math.max(0, Math.min(1, t));
        for (var i = 1; i < ramp.length; i++) {
            if (t <= ramp[i][0]) {
                var lo = ramp[i - 1], hi = ramp[i];
                var f = (t - lo[0]) / (hi[0] - lo[0]);
                return lerpHex(lo[1], hi[1], f);
            }
        }
        return ramp[ramp.length - 1][1];
    }

    function lerpHex(a, b, t) {
        var ra = parseInt(a.slice(1, 3), 16), ga = parseInt(a.slice(3, 5), 16), ba = parseInt(a.slice(5, 7), 16);
        var rb = parseInt(b.slice(1, 3), 16), gb = parseInt(b.slice(3, 5), 16), bb = parseInt(b.slice(5, 7), 16);
        var r = Math.round(ra + (rb - ra) * t);
        var g = Math.round(ga + (gb - ga) * t);
        var bl = Math.round(ba + (bb - ba) * t);
        return '#' + ((1 << 24) + (r << 16) + (g << 8) + bl).toString(16).slice(1);
    }

    // ── Normalizers ────────────────────────────────────────────────────────

    function normalizeField(pts, field) {
        var min = Infinity, max = -Infinity;
        for (var i = 0; i < pts.length; i++) {
            var v = pts[i][field];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        var range = max - min || 1;
        return function (v) { return (v - min) / range; };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VISUALIZATION MODES
    // ══════════════════════════════════════════════════════════════════════

    // ── Heatmap (pressure or velocity) ─────────────────────────────────────

    function drawHeatmap(ctx, canvas, field, ramp) {
        var pts = _flatPoints;
        var tf = dataTransform(canvas, pts);
        var normFn = normalizeField(pts, field);
        var lowLabel = field === 'pressure' ? P.Utils.i18n('bio.low', 'Low') : P.Utils.i18n('bio.slow', 'Slow');
        var highLabel = field === 'pressure' ? P.Utils.i18n('bio.high', 'High') : P.Utils.i18n('bio.fast', 'Fast');
        updateLegend(ramp, lowLabel, highLabel);

        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        for (var i = 1; i < pts.length; i++) {
            if (pts[i].strokeIdx !== pts[i - 1].strokeIdx) continue;
            var t = normFn(pts[i][field]);
            ctx.strokeStyle = rampColor(ramp, t);
            ctx.lineWidth = 2.5;
            ctx.beginPath();
            ctx.moveTo(tx(pts[i - 1], tf), ty(pts[i - 1], tf));
            ctx.lineTo(tx(pts[i], tf), ty(pts[i], tf));
            ctx.stroke();
        }
    }

    // ── Tilt vectors ───────────────────────────────────────────────────────

    function drawTiltVectors(ctx, canvas) {
        var pts = _flatPoints;
        var tf = dataTransform(canvas, pts);
        updateLegend(RAMP_TILT, '0\u00B0', '90\u00B0');

        // Draw base path
        ctx.strokeStyle = 'rgba(255,255,255,0.15)';
        ctx.lineWidth = 1;
        for (var i = 1; i < pts.length; i++) {
            if (pts[i].strokeIdx !== pts[i - 1].strokeIdx) continue;
            ctx.beginPath();
            ctx.moveTo(tx(pts[i - 1], tf), ty(pts[i - 1], tf));
            ctx.lineTo(tx(pts[i], tf), ty(pts[i], tf));
            ctx.stroke();
        }

        // Draw tilt arrows at sampled points
        var step = Math.max(1, Math.floor(pts.length / 200));
        var normTilt = normalizeField(pts, 'tiltMag');
        for (var j = 0; j < pts.length; j += step) {
            var p = pts[j];
            var px = tx(p, tf), py = ty(p, tf);
            var mag = Math.min(p.tiltMag, 90);
            var len = 4 + (mag / 90) * 16;
            var angle = Math.atan2(p.tiltY, p.tiltX || 0.001);
            var ex = px + Math.cos(angle) * len;
            var ey = py + Math.sin(angle) * len;

            ctx.strokeStyle = rampColor(RAMP_TILT, normTilt(p.tiltMag));
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.moveTo(px, py);
            ctx.lineTo(ex, ey);
            ctx.stroke();

            // Arrowhead
            var headLen = 3;
            ctx.beginPath();
            ctx.moveTo(ex, ey);
            ctx.lineTo(ex - headLen * Math.cos(angle - 0.4), ey - headLen * Math.sin(angle - 0.4));
            ctx.moveTo(ex, ey);
            ctx.lineTo(ex - headLen * Math.cos(angle + 0.4), ey - headLen * Math.sin(angle + 0.4));
            ctx.stroke();
        }
    }

    // ── Width-modulated ────────────────────────────────────────────────────

    function drawWidthModulated(ctx, canvas, field) {
        var pts = _flatPoints;
        var tf = dataTransform(canvas, pts);
        var normFn = normalizeField(pts, field);
        var ramp = field === 'pressure' ? RAMP_PRESSURE : RAMP_SPEED;
        var lowLabel = field === 'pressure' ? P.Utils.i18n('bio.low', 'Low') : P.Utils.i18n('bio.slow', 'Slow');
        var highLabel = field === 'pressure' ? P.Utils.i18n('bio.high', 'High') : P.Utils.i18n('bio.fast', 'Fast');
        updateLegend(ramp, lowLabel, highLabel);

        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        for (var i = 1; i < pts.length; i++) {
            if (pts[i].strokeIdx !== pts[i - 1].strokeIdx) continue;
            var t = normFn(pts[i][field]);
            ctx.strokeStyle = rampColor(ramp, t);
            ctx.lineWidth = 1 + t * 7;
            ctx.beginPath();
            ctx.moveTo(tx(pts[i - 1], tf), ty(pts[i - 1], tf));
            ctx.lineTo(tx(pts[i], tf), ty(pts[i], tf));
            ctx.stroke();
        }
    }

    // ── Temporal (time-based color) ────────────────────────────────────────

    function drawTemporal(ctx, canvas) {
        var pts = _flatPoints;
        var tf = dataTransform(canvas, pts);
        if (!pts.length) return;
        var tMin = pts[0].t, tMax = pts[pts.length - 1].t;
        var tRange = tMax - tMin || 1;
        var ramp = [[0, '#1565c0'], [0.25, '#00897b'], [0.5, '#43a047'], [0.75, '#fdd835'], [1, '#d50000']];
        updateLegend(ramp, P.Utils.i18n('bio.start', 'Start'), P.Utils.i18n('bio.end', 'End'));

        ctx.lineCap = 'round';
        for (var i = 1; i < pts.length; i++) {
            if (pts[i].strokeIdx !== pts[i - 1].strokeIdx) continue;
            var t = (pts[i].t - tMin) / tRange;
            ctx.strokeStyle = rampColor(ramp, t);
            ctx.lineWidth = 2.5;
            ctx.beginPath();
            ctx.moveTo(tx(pts[i - 1], tf), ty(pts[i - 1], tf));
            ctx.lineTo(tx(pts[i], tf), ty(pts[i], tf));
            ctx.stroke();
        }

        // Stroke-break markers
        for (var j = 1; j < pts.length; j++) {
            if (pts[j].strokeIdx !== pts[j - 1].strokeIdx) {
                ctx.fillStyle = 'rgba(255,255,255,0.5)';
                ctx.beginPath();
                ctx.arc(tx(pts[j], tf), ty(pts[j], tf), 4, 0, Math.PI * 2);
                ctx.fill();
            }
        }
    }

    // ── 3D pseudo-isometric ────────────────────────────────────────────────

    function draw3D(ctx, canvas) {
        var pts = _flatPoints;
        if (!pts.length) return;
        updateLegend(null);

        var cw = canvas.width / (window.devicePixelRatio || 1);
        var ch = canvas.height / (window.devicePixelRatio || 1);
        var cx = cw / 2, cy = ch / 2;

        var normZ = normalizeField(pts, _zAxis);
        var ramp = _zAxis === 'pressure' ? RAMP_PRESSURE : (_zAxis === 'speed' ? RAMP_SPEED : RAMP_TILT);

        // Center data
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (var i = 0; i < pts.length; i++) {
            if (pts[i].x < minX) minX = pts[i].x;
            if (pts[i].y < minY) minY = pts[i].y;
            if (pts[i].x > maxX) maxX = pts[i].x;
            if (pts[i].y > maxY) maxY = pts[i].y;
        }
        var dataCx = (minX + maxX) / 2;
        var dataCy = (minY + maxY) / 2;
        var dataRange = Math.max(maxX - minX, maxY - minY) || 1;
        var baseScale = Math.min(cw, ch) * 0.35 / (dataRange / 2);
        var sc = baseScale * _iso.scale;

        var rxRad = _iso.rx * Math.PI / 180;
        var ryRad = _iso.ry * Math.PI / 180;
        var cosRx = Math.cos(rxRad), sinRx = Math.sin(rxRad);
        var cosRy = Math.cos(ryRad), sinRy = Math.sin(ryRad);
        var zScale = dataRange * 0.5;

        function project(p) {
            var x = (p.x - dataCx) * sc;
            var y = (p.y - dataCy) * sc;
            var z = normZ(p[_zAxis]) * zScale * sc;
            // Y-axis rotation
            var x1 = x * cosRy - z * sinRy;
            var z1 = x * sinRy + z * cosRy;
            // X-axis rotation
            var y1 = y * cosRx - z1 * sinRx;
            return { x: cx + x1, y: cy + y1 };
        }

        // Draw axes
        ctx.strokeStyle = 'rgba(255,255,255,0.15)';
        ctx.lineWidth = 1;
        var axLen = dataRange * 0.6 * sc;
        var origin = { x: dataCx, y: dataCy, pressure: 0, speed: 0, tiltMag: 0 };
        var o = project(origin);
        var axPts = [
            { x: dataCx + dataRange * 0.6, y: dataCy, pressure: 0, speed: 0, tiltMag: 0 },
            { x: dataCx, y: dataCy + dataRange * 0.6, pressure: 0, speed: 0, tiltMag: 0 }
        ];
        // Z axis pseudo-point
        var zPt = { x: dataCx, y: dataCy, pressure: 1, speed: pts.reduce(function (m, p) { return Math.max(m, p.speed); }, 0), tiltMag: pts.reduce(function (m, p) { return Math.max(m, p.tiltMag); }, 0) };

        for (var a = 0; a < axPts.length; a++) {
            var ap = project(axPts[a]);
            ctx.beginPath(); ctx.moveTo(o.x, o.y); ctx.lineTo(ap.x, ap.y); ctx.stroke();
        }
        var zp = project(zPt);
        ctx.beginPath(); ctx.moveTo(o.x, o.y); ctx.lineTo(zp.x, zp.y); ctx.stroke();

        // Axis labels
        ctx.fillStyle = 'rgba(255,255,255,0.5)';
        ctx.font = '10px sans-serif';
        var xAp = project(axPts[0]);
        ctx.fillText('X', xAp.x + 4, xAp.y);
        var yAp = project(axPts[1]);
        ctx.fillText('Y', yAp.x + 4, yAp.y);
        ctx.fillText(_zAxis === 'pressure' ? 'P' : (_zAxis === 'speed' ? 'V' : 'T'), zp.x + 4, zp.y);

        // Draw data
        ctx.lineCap = 'round';
        ctx.lineWidth = 2;
        for (var j = 1; j < pts.length; j++) {
            if (pts[j].strokeIdx !== pts[j - 1].strokeIdx) continue;
            var p0 = project(pts[j - 1]);
            var p1 = project(pts[j]);
            ctx.strokeStyle = rampColor(ramp, normZ(pts[j][_zAxis]));
            ctx.beginPath();
            ctx.moveTo(p0.x, p0.y);
            ctx.lineTo(p1.x, p1.y);
            ctx.stroke();
        }

        // Ground shadow
        ctx.globalAlpha = 0.12;
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 1;
        var shadowPts = pts.map(function (p) {
            var sp = Object.assign({}, p);
            sp.pressure = 0; sp.speed = 0; sp.tiltMag = 0;
            return sp;
        });
        for (var k = 1; k < shadowPts.length; k++) {
            if (shadowPts[k].strokeIdx !== shadowPts[k - 1].strokeIdx) continue;
            var s0 = project(shadowPts[k - 1]);
            var s1 = project(shadowPts[k]);
            ctx.beginPath();
            ctx.moveTo(s0.x, s0.y);
            ctx.lineTo(s1.x, s1.y);
            ctx.stroke();
        }
        ctx.globalAlpha = 1;

        // Hint text
        ctx.fillStyle = 'rgba(255,255,255,0.3)';
        ctx.font = '10px sans-serif';
        ctx.fillText(P.Utils.i18n('bio.3d.hint', 'Drag to rotate, scroll to zoom'), 8, ch - 8);
    }

    // ── Charts ─────────────────────────────────────────────────────────────

    function drawCharts(ctx, canvas) {
        var pts = _flatPoints;
        if (!pts.length) return;
        updateLegend(null);

        var cw = canvas.width / (window.devicePixelRatio || 1);
        var ch = canvas.height / (window.devicePixelRatio || 1);
        var chartH = (ch - 30) / 3;
        var margin = { l: 50, r: 10, t: 18 };

        drawChart(ctx, pts, 'pressure', 0, chartH, cw, margin, RAMP_PRESSURE, P.Utils.i18n('bio.chart.pressure', 'Pressure'), '0%', '100%');
        drawChart(ctx, pts, 'speed', chartH + 10, chartH, cw, margin, RAMP_SPEED, P.Utils.i18n('bio.chart.speed', 'Speed (px/ms)'), '', '');
        drawChart(ctx, pts, 'tiltMag', (chartH + 10) * 2, chartH, cw, margin, RAMP_TILT, P.Utils.i18n('bio.chart.tilt', 'Tilt magnitude'), '0\u00B0', '');
    }

    function drawChart(ctx, pts, field, yOff, h, cw, margin, ramp, title, minLabel, maxLabel) {
        var x0 = margin.l, xEnd = cw - margin.r;
        var y0 = yOff + margin.t, yEnd = yOff + h;
        var w = xEnd - x0;
        var plotH = yEnd - y0;

        // Title
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.font = '11px sans-serif';
        ctx.fillText(title, x0, yOff + 12);

        // Background
        ctx.fillStyle = 'rgba(255,255,255,0.03)';
        ctx.fillRect(x0, y0, w, plotH);

        // Grid
        ctx.strokeStyle = 'rgba(255,255,255,0.07)';
        ctx.lineWidth = 0.5;
        for (var g = 0; g <= 4; g++) {
            var gy = y0 + plotH * (g / 4);
            ctx.beginPath(); ctx.moveTo(x0, gy); ctx.lineTo(xEnd, gy); ctx.stroke();
        }

        var normFn = normalizeField(pts, field);
        var tMin = pts[0].t, tMax = pts[pts.length - 1].t, tRange = tMax - tMin || 1;

        // Data line
        ctx.lineWidth = 1.5;
        ctx.lineCap = 'round';
        for (var i = 1; i < pts.length; i++) {
            if (pts[i].strokeIdx !== pts[i - 1].strokeIdx) continue;
            var px0 = x0 + ((pts[i - 1].t - tMin) / tRange) * w;
            var py0 = yEnd - normFn(pts[i - 1][field]) * plotH;
            var px1 = x0 + ((pts[i].t - tMin) / tRange) * w;
            var py1 = yEnd - normFn(pts[i][field]) * plotH;
            ctx.strokeStyle = rampColor(ramp, normFn(pts[i][field]));
            ctx.beginPath();
            ctx.moveTo(px0, py0);
            ctx.lineTo(px1, py1);
            ctx.stroke();
        }

        // Labels
        ctx.fillStyle = 'rgba(255,255,255,0.35)';
        ctx.font = '9px sans-serif';
        if (minLabel) ctx.fillText(minLabel, x0 - 30, yEnd);
        if (maxLabel) ctx.fillText(maxLabel, x0 - 35, y0 + 10);
    }

    // ── Replay ─────────────────────────────────────────────────────────────

    function startReplay(ctx, canvas) {
        stopReplay();
        updateLegend(null);

        var pts = _flatPoints;
        if (!pts.length) return;
        var tf = dataTransform(canvas, pts);
        _replayIdx = 0;
        _replayPaused = false;

        var tStart = pts[0].t;

        function frame() {
            if (_replayPaused || _currentView !== 'replay') return;

            var batch = Math.max(1, Math.floor(3 * _replaySpeed));
            for (var b = 0; b < batch && _replayIdx < pts.length; b++) {
                var i = _replayIdx;
                if (i > 0 && pts[i].strokeIdx === pts[i - 1].strokeIdx) {
                    var normP = normalizeField(pts, 'pressure');
                    ctx.strokeStyle = rampColor(RAMP_PRESSURE, normP(pts[i].pressure));
                    ctx.lineWidth = 1 + pts[i].pressure * 4;
                    ctx.lineCap = 'round';
                    ctx.beginPath();
                    ctx.moveTo(tx(pts[i - 1], tf), ty(pts[i - 1], tf));
                    ctx.lineTo(tx(pts[i], tf), ty(pts[i], tf));
                    ctx.stroke();
                }
                _replayIdx++;
            }

            if (_replayIdx < pts.length) {
                // Calculate delay based on real timing
                var nextDt = pts[_replayIdx].t - pts[Math.max(0, _replayIdx - 1)].t;
                var delay = Math.max(1, (nextDt / _replaySpeed));
                _replayTimer = setTimeout(frame, delay);
            } else {
                // Flash the complete drawing briefly, then auto-restart after pause
                _replayTimer = setTimeout(function () {
                    if (_currentView === 'replay') {
                        ctx.clearRect(0, 0, canvas.width, canvas.height);
                        _replayIdx = 0;
                        frame();
                    }
                }, 1500);
            }
        }

        frame();
    }

    function stopReplay() {
        if (_replayTimer) { clearTimeout(_replayTimer); _replayTimer = null; }
        _replayPaused = true;
    }

    // ── Compare overlay ────────────────────────────────────────────────────

    function drawCompare(ctx, canvas) {
        var ptsA = _flatPoints;
        var ptsB = _flatPointsB;
        if (!ptsA.length || !ptsB.length) {
            ctx.fillStyle = 'rgba(255,255,255,0.4)';
            ctx.font = '14px sans-serif';
            var cw = canvas.width / (window.devicePixelRatio || 1);
            var ch = canvas.height / (window.devicePixelRatio || 1);
            ctx.fillText(P.Utils.i18n('bio.compare.nodata', 'No comparison data available'), cw / 2 - 100, ch / 2);
            return;
        }

        // Merge bounds for consistent transform
        var all = ptsA.concat(ptsB);
        var tf = dataTransform(canvas, all);
        updateLegend(null);

        // Draw A in cyan
        ctx.lineCap = 'round';
        ctx.lineWidth = 2;
        ctx.globalAlpha = 0.7;
        ctx.strokeStyle = '#00d4ff';
        for (var i = 1; i < ptsA.length; i++) {
            if (ptsA[i].strokeIdx !== ptsA[i - 1].strokeIdx) continue;
            ctx.beginPath();
            ctx.moveTo(tx(ptsA[i - 1], tf), ty(ptsA[i - 1], tf));
            ctx.lineTo(tx(ptsA[i], tf), ty(ptsA[i], tf));
            ctx.stroke();
        }

        // Draw B in magenta
        ctx.strokeStyle = '#ff4da6';
        for (var j = 1; j < ptsB.length; j++) {
            if (ptsB[j].strokeIdx !== ptsB[j - 1].strokeIdx) continue;
            ctx.beginPath();
            ctx.moveTo(tx(ptsB[j - 1], tf), ty(ptsB[j - 1], tf));
            ctx.lineTo(tx(ptsB[j], tf), ty(ptsB[j], tf));
            ctx.stroke();
        }
        ctx.globalAlpha = 1;

        // Legend
        var cw2 = canvas.width / (window.devicePixelRatio || 1);
        ctx.font = '11px sans-serif';
        ctx.fillStyle = '#00d4ff';
        ctx.fillText('\u25CF ' + P.Utils.i18n('bio.compare.primary', 'Primary'), 10, 16);
        ctx.fillStyle = '#ff4da6';
        ctx.fillText('\u25CF ' + P.Utils.i18n('bio.compare.secondary', 'Comparison'), 90, 16);
    }

    // ── Public interface ───────────────────────────────────────────────────

    return {
        open: open,
        close: close
    };

})(jQuery, PDFalyzer);
