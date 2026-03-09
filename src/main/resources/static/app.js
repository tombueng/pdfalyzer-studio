/**
 * PDFalyzer Studio – main entry point.
 *
 * All logic is in the /js/ sub-modules.  This file only wires them together
 * and calls init once jQuery's document-ready fires.
 *
 * Module load order (enforced by index.html <script> tags):
 *   js/app-state.js         → PDFalyzer (namespace + shared state)
 *   js/app-utils.js         → PDFalyzer.Utils
 *   js/app-resource.js      → PDFalyzer.Resource
 *   js/app-dict-editor.js   → PDFalyzer.DictEditor
 *   js/app-cos-editor.js    → PDFalyzer.CosEditor
 *   js/app-tree.js          → PDFalyzer.Tree
 *   js/app-tree-render.js   → PDFalyzer.TreeRender
 *   js/app-viewer.js        → PDFalyzer.Viewer
 *   js/app-viewer-render.js → PDFalyzer.ViewerRender
 *   js/app-search.js        → PDFalyzer.Search
 *   js/app-tabs.js          → PDFalyzer.Tabs
 *   js/app-edit-mode.js     → PDFalyzer.EditMode
 *   js/app-upload.js        → PDFalyzer.Upload
 *   js/app-zoom.js          → PDFalyzer.Zoom
 *   js/app-divider.js       → PDFalyzer.Divider
 *   js/app-keyboard.js      → PDFalyzer.Keyboard
 *   js/app-export.js        → PDFalyzer.Export
 *   js/app-error-capture.js → PDFalyzer.ErrorCapture
 *   js/app-modals.js        → PDFalyzer.Modals
 *   app.js                  ← this file (document-ready init)
 */
(function ($) {
    'use strict';
    var P = PDFalyzer;

    $(function () {
        P.ErrorCapture.install();
        P.Modals.init();
        if (P.Utils && P.Utils.initClearableInputs) {
            P.Utils.initClearableInputs();
        }
        if (P.Utils && P.Utils.startRamMonitor) {
            P.Utils.startRamMonitor();
        }
        P.Upload.init();
        P.Search.init();
        P.Tabs.init();
        P.EditMode.init();
        P.EditDesigner.init();
        P.Divider.init();
        P.Keyboard.init();
        P.Export.init();
        P.Zoom.init();

        // Restore previous session from localStorage (page refresh, or browser extension
        // redirect via /open/{sessionId} which sets localStorage before redirecting here).
        if (P.Upload && P.Upload.restoreSessionOnInit) {
            P.Upload.restoreSessionOnInit();
        }
    });
})(jQuery);
