/**
 * PDFalyzer – PDF form field edit mode (draw new fields on the viewer).
 */
PDFalyzer.EditMode = (function ($, P) {
    'use strict';

    var drawing  = false;
    var startX   = 0, startY = 0;
    var $drawRect = null;
    var targetPage = -1;

    function init() {
        $('#editModeBtn').on('click', function () {
            P.state.editMode = !P.state.editMode;
            $(this).toggleClass('active', P.state.editMode);
            $('#editToolbar').toggleClass('active', P.state.editMode);
            if (!P.state.editMode) {
                P.state.editFieldType = null;
                $('.edit-field-btn').removeClass('active');
            }
        });

        $('.edit-field-btn').on('click', function () {
            $('.edit-field-btn').removeClass('active');
            $(this).addClass('active');
            P.state.editFieldType = $(this).data('type');
        });
    }

    function startDraw(e, pageIndex, wrapper) {
        drawing    = true;
        targetPage = pageIndex;
        var rect   = wrapper.getBoundingClientRect();
        startX     = e.clientX - rect.left;
        startY     = e.clientY - rect.top;

        $drawRect = $('<div>', { 'class': 'draw-rect' })
            .css({ left: startX + 'px', top: startY + 'px' })
            .appendTo(wrapper);

        var moveHandler = function (ev) {
            if (!drawing) return;
            var cx = ev.clientX - rect.left, cy = ev.clientY - rect.top;
            $drawRect.css({
                left:   Math.min(startX, cx) + 'px',
                top:    Math.min(startY, cy) + 'px',
                width:  Math.abs(cx - startX) + 'px',
                height: Math.abs(cy - startY) + 'px'
            });
        };

        var upHandler = function (ev) {
            drawing = false;
            $(document).off('mousemove', moveHandler).off('mouseup', upHandler);
            var cx = ev.clientX - rect.left, cy = ev.clientY - rect.top;
            var x = Math.min(startX, cx), y = Math.min(startY, cy);
            var w = Math.abs(cx - startX), h = Math.abs(cy - startY);
            if ($drawRect) $drawRect.remove();
            if (w < 10 || h < 10) return;

            var vp    = P.state.pageViewports[pageIndex];
            var scale = vp.scale;
            var pdfX  = x / scale;
            var pdfY  = (vp.height - y - h) / scale;
            var pdfW  = w / scale;
            var pdfH  = h / scale;

            var fieldName = prompt('Field name:', P.state.editFieldType + '_' + Date.now());
            if (!fieldName) return;
            addField(pageIndex, pdfX, pdfY, pdfW, pdfH, fieldName);
        };

        $(document).on('mousemove', moveHandler).on('mouseup', upHandler);
    }

    function addField(pageIndex, x, y, w, h, fieldName) {
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/add-field', {
            method: 'POST', contentType: 'application/json',
            data: JSON.stringify({
                fieldType: P.state.editFieldType,
                fieldName: fieldName,
                pageIndex: pageIndex,
                x: x, y: y, width: w, height: h
            })
        })
        .done(function (data) {
            P.state.treeData = data.tree;
            P.Tree.render(P.state.treeData);
            P.Viewer.loadPdf(P.state.sessionId);
            P.Utils.toast('Field "' + fieldName + '" added', 'success');
        })
        .fail(function () { P.Utils.toast('Add field failed', 'danger'); });
    }

    /**
     * Delete a form field by fully-qualified name.
     */
    function deleteField(fieldName) {
        if (!P.state.sessionId) return;
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                          encodeURIComponent(fieldName), { method: 'DELETE' })
            .done(function (data) {
                P.state.treeData = data.tree;
                P.Tree.render(P.state.treeData);
                P.Viewer.loadPdf(P.state.sessionId);
                P.Utils.toast('Field "' + fieldName + '" deleted', 'success');
            })
            .fail(function () { P.Utils.toast('Delete field failed', 'danger'); });
    }

    /**
     * Update a form field value by fully-qualified name.
     */
    function setFieldValue(fieldName, value) {
        if (!P.state.sessionId) return;
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                          encodeURIComponent(fieldName) + '/value', {
            method: 'POST', contentType: 'application/json',
            data: JSON.stringify({ value: value })
        })
        .done(function (data) {
            P.state.treeData = data.tree;
            P.Tree.render(P.state.treeData);
            P.Utils.toast('Field value updated', 'success');
        })
        .fail(function () { P.Utils.toast('Update field value failed', 'danger'); });
    }

    return { init: init, startDraw: startDraw, deleteField: deleteField,
             setFieldValue: setFieldValue };
})(jQuery, PDFalyzer);
