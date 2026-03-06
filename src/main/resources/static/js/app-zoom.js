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
        P.state.panMode = panModeActive;
        if (panModeActive && P.state.editFieldType) {
            P.state.editFieldType = null;
            if (P.EditMode && P.EditMode.syncEditFieldTypeUI) P.EditMode.syncEditFieldTypeUI();
        }
        $('#panModeBtn').toggleClass('active', panModeActive);
        $('#pdfPane').toggleClass('pan-mode-active', panModeActive);
    }

    function togglePanMode() {
        setPanMode(!panModeActive);
    }

    var LAYER_MODES = [
        { icon: 'fa-eye-slash',    title: 'Layers: off',                        annot: false, form: false, active: false },
        { icon: 'fa-layer-group',  title: 'Layers: PDF annotations',            annot: true,  form: false, active: true  },
        { icon: 'fa-vector-square',title: 'Layers: form fields',                annot: false, form: true,  active: true  },
        { icon: 'fa-object-group', title: 'Layers: annotations + form fields',  annot: true,  form: true,  active: true  }
    ];

    function cycleLayerMode() {
        P.state.layerMode = ((P.state.layerMode || 0) + 1) % 4;
        updateLayerBtn();
        if (P.state.pdfDoc && P.Viewer && P.Viewer.renderAllPages) {
            P.Viewer.renderAllPages();
        }
    }

    function updateLayerBtn() {
        var mode = LAYER_MODES[P.state.layerMode || 0];
        var $btn = $('#annotationLayerBtn');
        $btn.attr('title', mode.title);
        $btn.find('i').attr('class', 'fas ' + mode.icon);
        $btn.toggleClass('active', mode.active);
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
        $('#annotationLayerBtn').on('click', function () { cycleLayerMode(); });

        $('#pdfPane').on('wheel', function (e) {
            var ev = e.originalEvent;
            if (!(ev.ctrlKey || ev.metaKey)) return;
            e.preventDefault();

            var delta = ev.deltaY;
            if (ev.deltaMode === 1) delta *= 16;   // line → pixel
            if (ev.deltaMode === 2) delta *= 400;  // page → pixel

            var newScale = clampScale((P.state.currentScale || 1) * Math.exp(-delta * 0.0005));
            if (P.Viewer && P.Viewer.setScaleAtPoint) {
                var pane = $('#pdfPane')[0];
                var rect = pane ? pane.getBoundingClientRect() : null;
                P.Viewer.setScaleAtPoint(newScale,
                    rect ? ev.clientX - rect.left : 0,
                    rect ? ev.clientY - rect.top  : 0);
            }
        });

        $(window).on('resize', function () { P.Viewer.applyAutoZoom(); });
        setPanMode(false);
        updateButton();
        updateLayerBtn();
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
        updateLayerBtn: updateLayerBtn,
        isPanModeActive: function () { return panModeActive; },
        setPanMode: setPanMode,
        LAYER_MODES: LAYER_MODES
    };
})(jQuery, PDFalyzer);
