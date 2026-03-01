/**
 * PDFalyzer – keyboard shortcuts.
 */
PDFalyzer.Keyboard = (function ($, P) {
    'use strict';

    function init() {
        $(document).on('keydown', function (e) {
            var tag = e.target.tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

            if ((e.ctrlKey || e.metaKey) && e.key === 'o') {
                e.preventDefault(); $('#fileInput').trigger('click');
            }
            if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
                e.preventDefault();
                var $s = $('#searchInput');
                if (!$s.prop('disabled')) $s.trigger('focus').trigger('select');
            }
            if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                e.preventDefault();
                if (P.state.sessionId)
                    window.open('/api/pdf/' + P.state.sessionId + '/download', '_blank');
            }
            if ((e.ctrlKey || e.metaKey) && e.key === 'e') {
                e.preventDefault();
                if (P.state.sessionId) $('.edit-field-btn').first().trigger('click');
            }
            if (e.key === 'Escape') {
                var $s2 = $('#searchInput');
                if ($s2.is(':focus')) {
                    $s2.val('').trigger('blur');
                    if (P.state.treeData) P.Tree.render(P.state.treeData);
                }
            }
            if (e.key >= '1' && e.key <= '7' && !e.ctrlKey && !e.metaKey && !e.altKey) {
                var idx = parseInt(e.key, 10) - 1;
                var $tabs = $('.tab-btn');
                if (idx < $tabs.length && P.state.sessionId) $tabs.eq(idx).trigger('click');
            }
        });
    }

    return { init: init };
})(jQuery, PDFalyzer);
