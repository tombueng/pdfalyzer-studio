/**
 * PDFalyzer – zoom controls and auto-zoom modes.
 */
PDFalyzer.Zoom = (function ($, P) {
    'use strict';

    function init() {
        $('#zoomModeBtn').on('click', function () {
            if (P.state.autoZoomMode === 'off')    P.Viewer.fitWidth();
            else if (P.state.autoZoomMode === 'width') P.Viewer.fitHeight();
            else P.Viewer.setScale(P.state.currentScale);
        });

        $('#pdfPane').on('wheel', function (e) {
            var ev = e.originalEvent;
            if (ev.ctrlKey || ev.metaKey) {
                e.preventDefault();
                P.Viewer.setScale(P.state.currentScale * (ev.deltaY > 0 ? 0.9 : 1.1));
            }
        });

        $(window).on('resize', function () { P.Viewer.applyAutoZoom(); });
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

    return { init: init, updateButton: updateButton };
})(jQuery, PDFalyzer);
