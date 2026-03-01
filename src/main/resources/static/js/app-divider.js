/**
 * PDFalyzer – horizontal divider drag-resize between PDF pane and tree pane.
 */
PDFalyzer.Divider = (function ($, P) {
    'use strict';

    function init() {
        var dragging  = false;
        var $treePane = $('#treePane');

        $('#divider').on('mousedown', function () {
            dragging = true;
            $(this).addClass('dragging');
            $('body').css({ cursor: 'col-resize', 'user-select': 'none' });
        });

        $(document).on('mousemove', function (e) {
            if (!dragging) return;
            var newWidth = document.body.clientWidth - e.clientX;
            newWidth = Math.max(250, Math.min(newWidth, window.innerWidth * 0.6));
            $treePane.css('width', newWidth + 'px');
        }).on('mouseup', function () {
            if (dragging) {
                dragging = false;
                $('#divider').removeClass('dragging');
                $('body').css({ cursor: '', 'user-select': '' });
                P.Viewer.applyAutoZoom();
            }
        });
    }

    return { init: init };
})(jQuery, PDFalyzer);
