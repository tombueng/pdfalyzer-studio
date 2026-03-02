/**
 * PDFalyzer Studio – zoom controls and auto-zoom modes.
 */
PDFalyzer.Zoom = (function ($, P) {
    'use strict';

    var panModeActive = false;
    var ZOOM_IN_FACTOR = 1.25;
    var ZOOM_OUT_FACTOR = 0.8;

    function clampScale(scale) {
        return Math.max(0.1, Math.min(8, scale));
    }

    function stepZoom(factor) {
        if (!P.Viewer || !P.Viewer.setScale) return;
        var baseScale = P.state && typeof P.state.currentScale === 'number' ? P.state.currentScale : 1;
        P.Viewer.setScale(clampScale(baseScale * factor));
    }

    function setPanMode(active) {
        panModeActive = !!active;
        $('#panModeBtn').toggleClass('active', panModeActive);
        $('#pdfPane').toggleClass('pan-mode-active', panModeActive);
    }

    function togglePanMode() {
        setPanMode(!panModeActive);
    }

    function init() {
        $('#zoomModeBtn').on('click', function () {
            if (P.state.autoZoomMode === 'off')    P.Viewer.fitWidth();
            else if (P.state.autoZoomMode === 'width') P.Viewer.fitHeight();
            else P.Viewer.setScale(P.state.currentScale);
        });

        $('#zoomOutBtn').on('click', function () { stepZoom(ZOOM_OUT_FACTOR); });
        $('#zoomInBtn').on('click', function () { stepZoom(ZOOM_IN_FACTOR); });
        $('#panModeBtn').on('click', function () { togglePanMode(); });

        $('#pdfPane').on('wheel', function (e) {
            var ev = e.originalEvent;
            if (ev.ctrlKey || ev.metaKey) {
                e.preventDefault();
            }
        });

        $(window).on('resize', function () { P.Viewer.applyAutoZoom(); });
        setPanMode(false);
        updateButton();
    }

    function updateButton() {
        var $btn = $('#zoomModeBtn');
        if (!$btn.length) return;
        var icons  = { off: 'fa-expand-arrows-alt', width: 'fa-arrows-alt-h', height: 'fa-arrows-alt-v' };
        var titles = { off: 'Toggle auto zoom mode', width: 'Auto zoom: fit width',
                       height: 'Auto zoom: fit height' };
        $btn.attr('title', titles[P.state.autoZoomMode] || titles.off);
        $btn.find('i').attr('class', 'fas ' + (icons[P.state.autoZoomMode] || icons.off));
        $btn.toggleClass('active', P.state.autoZoomMode !== 'off');
    }

    return {
        init: init,
        updateButton: updateButton,
        isPanModeActive: function () { return panModeActive; },
        setPanMode: setPanMode
    };
})(jQuery, PDFalyzer);
