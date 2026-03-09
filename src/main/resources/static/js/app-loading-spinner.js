/**
 * PDFalyzer Studio – Loading spinner (Rainbow Layers daisy wheel).
 *
 * Shows a canvas-based spirograph animation centered in the PDF viewer pane
 * (and optionally the tree pane) while content is loading.
 */
PDFalyzer.LoadingSpinner = (function ($, P) {
    'use strict';

    var PDF_LINES = [
        '%PDF-1.7', '1 0 obj', '<< /Type /Catalog', '   /Pages 2 0 R >>',
        'endobj', '2 0 obj', '<< /Type /Pages', '   /Kids [3 0 R]',
        '   /Count 1 >>', 'endobj', '3 0 obj', '<< /Type /Page',
        '   /MediaBox [0 0 612 792]', '   /Contents 4 0 R',
        '   /Resources << >> >>', 'endobj', 'xref', '0 5',
        '0000000000 65535 f', '0000000010 00000 n', 'trailer',
        '<< /Size 5', '   /Root 1 0 R >>', 'startxref', '142', '%%EOF'
    ];
    var fullText = PDF_LINES.join('\n');

    // Each spinner instance is independent
    function createInstance() {
        var animId = null;
        var canvas = null;
        var ctx = null;
        var t = 0;
        var stamps = [];
        var layers = [
            { ci: 0, R: 40, r: 15, p: 25, speed: 0.07, hue: 180, angle: 0 },
            { ci: 50, R: 60, r: 22, p: 36, speed: 0.055, hue: 210, angle: 0 },
            { ci: 100, R: 80, r: 29, p: 50, speed: 0.043, hue: 250, angle: 0 },
            { ci: 150, R: 100, r: 37, p: 62, speed: 0.034, hue: 290, angle: 0 }
        ];

        function resetState() {
            t = 0;
            stamps = [];
            layers[0].ci = 0;   layers[0].angle = 0;
            layers[1].ci = 50;  layers[1].angle = 0;
            layers[2].ci = 100; layers[2].angle = 0;
            layers[3].ci = 150; layers[3].angle = 0;
        }

        function animate() {
            if (!canvas) return;
            var W = canvas.width;
            var C = W / 2;

            ctx.fillStyle = 'rgba(27,25,62,0.04)';
            ctx.fillRect(0, 0, W, W);
            t += 0.016;

            for (var li = 0; li < layers.length; li++) {
                var L = layers[li];
                L.angle += L.speed;
                var R = L.R + Math.sin(t * 0.3 + li) * 5;
                var r = L.r;
                var p = L.p;
                var hx = C + (R - r) * Math.cos(L.angle) + p * Math.cos((R - r) / r * L.angle);
                var hy = C + (R - r) * Math.sin(L.angle) - p * Math.sin((R - r) / r * L.angle);

                if (Math.floor(t * 45 + li * 10) % 4 === 0) {
                    var ch = fullText[L.ci % fullText.length];
                    L.ci++;
                    if (ch !== '\n') {
                        stamps.push({ x: hx, y: hy, ch: ch, age: 0, hue: L.hue });
                    }
                }

                for (var i = 0; i < 30; i++) {
                    var a = L.angle - i * 0.04;
                    var tx = C + (R - r) * Math.cos(a) + p * Math.cos((R - r) / r * a);
                    var ty = C + (R - r) * Math.sin(a) - p * Math.sin((R - r) / r * a);
                    ctx.beginPath();
                    ctx.arc(tx, ty, 1 - i * 0.025, 0, 6.28);
                    ctx.fillStyle = 'hsla(' + (L.hue + i) + ',60%,60%,' + (0.5 - i * 0.015) + ')';
                    ctx.fill();
                }

                ctx.beginPath();
                ctx.arc(hx, hy, 2.5, 0, 6.28);
                ctx.fillStyle = 'hsla(' + L.hue + ',75%,82%,0.85)';
                ctx.fill();
            }

            for (var j = stamps.length - 1; j >= 0; j--) {
                var s = stamps[j];
                s.age += 0.0025;
                if (s.age > 1) { stamps.splice(j, 1); continue; }
                ctx.font = (9 + s.age * 2) + 'px monospace';
                ctx.fillStyle = 'hsla(' + s.hue + ',50%,67%,' + ((1 - s.age) * 0.6) + ')';
                ctx.fillText(s.ch, s.x, s.y);
            }

            while (stamps.length > 700) stamps.shift();

            animId = requestAnimationFrame(animate);
        }

        function start($overlay, maxSize) {
            if (!$overlay.length) return;
            $overlay.removeClass('d-none');
            canvas = $overlay.find('canvas')[0];
            if (!canvas) return;

            var $parent = $overlay.parent();
            var size = Math.min($parent.width(), $parent.height(), maxSize);
            size = Math.max(size, 120);
            canvas.width = size;
            canvas.height = size;
            canvas.style.width = size + 'px';
            canvas.style.height = size + 'px';
            ctx = canvas.getContext('2d');
            ctx.fillStyle = '#1B193E';
            ctx.fillRect(0, 0, size, size);

            resetState();
            if (animId) cancelAnimationFrame(animId);
            animId = requestAnimationFrame(animate);
        }

        function stop($overlay) {
            if (animId) { cancelAnimationFrame(animId); animId = null; }
            canvas = null;
            ctx = null;
            if ($overlay) $overlay.addClass('d-none');
        }

        return { start: start, stop: stop };
    }

    var pdfSpinner = createInstance();
    var treeSpinner = createInstance();

    function show() {
        pdfSpinner.start($('#loadingSpinnerOverlay'), 420);
        treeSpinner.start($('#treeSpinnerOverlay'), 250);
    }

    function hide() {
        pdfSpinner.stop($('#loadingSpinnerOverlay'));
        treeSpinner.stop($('#treeSpinnerOverlay'));
    }

    return { show: show, hide: hide };
})(jQuery, PDFalyzer);
