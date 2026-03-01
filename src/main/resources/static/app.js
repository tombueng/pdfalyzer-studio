/**
 * PDFalyzer – main entry point.
 *
 * All logic is in the /js/ sub-modules.  This file only wires them together
 * and calls init once jQuery's document-ready fires.
 *
 * Module load order (enforced by index.html <script> tags):
 *   js/app-state.js       → PDFalyzer (namespace + shared state)
 *   js/app-utils.js       → PDFalyzer.Utils
 *   js/app-resource.js    → PDFalyzer.Resource
 *   js/app-dict-editor.js → PDFalyzer.DictEditor
 *   js/app-cos-editor.js  → PDFalyzer.CosEditor
 *   js/app-tree.js        → PDFalyzer.Tree
 *   js/app-viewer.js      → PDFalyzer.Viewer
 *   js/app-search.js      → PDFalyzer.Search
 *   js/app-tabs.js        → PDFalyzer.Tabs
 *   js/app-edit-mode.js   → PDFalyzer.EditMode
 *   js/app-upload.js      → PDFalyzer.Upload
 *   js/app-zoom.js        → PDFalyzer.Zoom
 *   js/app-divider.js     → PDFalyzer.Divider
 *   js/app-keyboard.js    → PDFalyzer.Keyboard
 *   js/app-export.js      → PDFalyzer.Export
 *   app.js                ← this file (document-ready init)
 */
(function ($) {
    'use strict';
    var P = PDFalyzer;

    $(function () {
        P.Upload.init();
        P.Search.init();
        P.Tabs.init();
        P.EditMode.init();
        P.Divider.init();
        P.Keyboard.init();
        P.Export.init();
        P.Zoom.init();
        P.Upload.loadSampleOnInit();
    });
})(jQuery);
