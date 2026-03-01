/**
 * PDFalyzer – shared application state.
 * MUST be loaded before all other modules.
 */
var PDFalyzer = (function () {
    'use strict';
    return {
        state: {
            sessionId: null,
            pdfDoc: null,
            treeData: null,
            currentTab: 'structure',
            selectedNodeId: null,
            editMode: true,
            editFieldType: null,
            pendingFormAdds: [],
            pendingFieldRects: [],
            selectedFieldNames: [],
            pageViewports: [],
            pageCanvases: [],
            currentScale: 1.5,
            autoZoomMode: 'off',   // 'off' | 'width' | 'height'
            basePageSize: { width: 0, height: 0 }
        }
    };
}());
