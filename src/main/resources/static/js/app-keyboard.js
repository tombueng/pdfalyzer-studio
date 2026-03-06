/**
 * PDFalyzer Studio – keyboard shortcuts.
 */
PDFalyzer.Keyboard = (function ($, P) {
    'use strict';

    function init() {
        $(document).on('keydown', function (e) {
            var tag = e.target.tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
            // Don't fire app shortcuts when any modal overlay is open
            if (document.querySelector('.modal.show')) return;

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
                if (P.state.sessionId) P.Export.triggerDownload();
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
            if (e.key >= '1' && e.key <= '8' && !e.ctrlKey && !e.metaKey && !e.altKey) {
                var idx = parseInt(e.key, 10) - 1;
                var $tabs = $('.tab-btn');
                if (idx < $tabs.length && P.state.sessionId) $tabs.eq(idx).trigger('click');
            }
            if (P.state.editMode && (e.key === 's' || e.key === 'S') && !e.ctrlKey && !e.metaKey && !e.altKey) {
                e.preventDefault(); $('.edit-field-btn[data-type="select"]').first().trigger('click');
            }
            if (P.state.editMode && (e.ctrlKey || e.metaKey) && e.key === 'z') {
                e.preventDefault();
                var sel = P.state.selectedFieldNames;
                if (sel && sel.length === 1 && P.EditMode && P.EditMode.popFieldUndo) P.EditMode.popFieldUndo(sel[0]);
            }
            if (P.state.editMode && P.EditDesigner) {
                if ((e.ctrlKey || e.metaKey) && e.key === 'c') {
                    e.preventDefault(); P.EditDesigner.copySelected();
                }
                if ((e.ctrlKey || e.metaKey) && e.key === 'v') {
                    e.preventDefault(); P.EditDesigner.pasteFields();
                }
                if ((e.key === 'g' || e.key === 'G') && !e.ctrlKey && !e.metaKey && !e.altKey) {
                    e.preventDefault(); P.EditDesigner.toggleGrid();
                }
            }
        });
    }

    return { init: init };
})(jQuery, PDFalyzer);
