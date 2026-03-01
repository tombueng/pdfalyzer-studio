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

    function installClientErrorCapture() {
        if (window.__pdfalyzerErrorCaptureInstalled) return;
        window.__pdfalyzerErrorCaptureInstalled = true;
        window.__pdfalyzerJsErrors = [];

        var lastFingerprint = null;
        var lastTimestamp = 0;

        function normalizeValue(value) {
            if (value === null || value === undefined) return '';
            return String(value);
        }

        function pushClientError(kind, message, source, line, column, stack) {
            var payload = {
                timestamp: new Date().toISOString(),
                kind: normalizeValue(kind || 'error'),
                message: normalizeValue(message || '(unknown error)'),
                source: normalizeValue(source || ''),
                line: normalizeValue(line || ''),
                column: normalizeValue(column || ''),
                stack: normalizeValue(stack || '')
            };

            var fingerprint = [payload.kind, payload.message, payload.source, payload.line, payload.column].join('|');
            var now = Date.now();
            if (fingerprint === lastFingerprint && (now - lastTimestamp) < 250) return;
            lastFingerprint = fingerprint;
            lastTimestamp = now;

            window.__pdfalyzerJsErrors.push(payload);
            if (window.__pdfalyzerJsErrors.length > 200) {
                window.__pdfalyzerJsErrors.shift();
            }

            if (P.Utils && P.Utils.reportClientError) {
                P.Utils.reportClientError(payload);
            }
        }

        window.addEventListener('error', function (event) {
            pushClientError(
                'error',
                event && event.message,
                event && event.filename,
                event && event.lineno,
                event && event.colno,
                event && event.error && event.error.stack
            );
        });

        window.addEventListener('unhandledrejection', function (event) {
            var reason = event ? event.reason : null;
            var message = reason && reason.message ? reason.message : reason;
            var stack = reason && reason.stack ? reason.stack : '';
            pushClientError('unhandledrejection', message, '', '', '', stack);
        });
    }

    $(function () {
        installClientErrorCapture();
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
