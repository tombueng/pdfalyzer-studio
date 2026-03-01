/**
 * PDFalyzer Studio – tree search.
 */
PDFalyzer.Search = (function ($, P) {
    'use strict';
    var timer = null;

    function init() {
        $('#searchInput').on('input', function () {
            clearTimeout(timer);
            var query = $(this).val().trim();
            if (!query) { if (P.state.treeData) P.Tree.render(P.state.treeData); return; }
            timer = setTimeout(function () {
                if (!P.state.sessionId) return;
                P.Utils.apiFetch('/api/tree/' + P.state.sessionId + '/search',
                                  { data: { q: query } })
                    .done(function (results) { P.Tree.renderSearchResults(results); })
                    .fail(function () { P.Utils.toast('Search error', 'danger'); });
            }, 300);
        });
    }

    return { init: init };
})(jQuery, PDFalyzer);
