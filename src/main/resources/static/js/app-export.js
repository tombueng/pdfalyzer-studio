/**
 * PDFalyzer Studio – export (download PDF, export tree JSON).
 */
PDFalyzer.Export = (function ($, P) {
    'use strict';

    function init() {
        $('#downloadBtn').on('click', function () {
            if (P.state.sessionId)
                window.open('/api/pdf/' + P.state.sessionId + '/download', '_blank');
        });
        $('#exportTreeBtn').on('click', function () {
            if (P.state.sessionId)
                window.open('/api/tree/' + P.state.sessionId + '/export', '_blank');
        });
    }

    return { init: init };
})(jQuery, PDFalyzer);
