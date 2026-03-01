/**
 * PDFalyzer – PDF viewer (PDF.js rendering, highlight, zoom).
 */
PDFalyzer.Viewer = (function ($, P) {
    'use strict';

    function loadPdf(sid) {
        var url = '/api/pdf/' + sid;
        var $viewer = $('#pdfViewer').empty().show();
        $('#uploadSplash').hide();
        P.state.pageViewports = [];
        P.state.pageCanvases  = [];

        pdfjsLib.getDocument(url).promise.then(function (pdf) {
            P.state.pdfDoc = pdf;
            pdf.getPage(1).then(function (p) {
                var vp = p.getViewport({ scale: 1 });
                P.state.basePageSize.width  = vp.width;
                P.state.basePageSize.height = vp.height;
            });
            renderAllPages();
        }).catch(function (err) {
            P.Utils.toast('Failed to render PDF: ' + err.message, 'danger');
        });
    }

    function renderAllPages() {
        if (!P.state.pdfDoc) return;
        $('#pdfViewer').empty();
        var chain = Promise.resolve();
        for (var i = 1; i <= P.state.pdfDoc.numPages; i++) {
            chain = chain.then((function (pageNum) {
                return function () { return renderPage(pageNum); };
            })(i));
        }
        return chain;
    }

    function renderPage(pageNum) {
        return P.state.pdfDoc.getPage(pageNum).then(function (page) {
            var scale    = P.state.currentScale;
            var viewport = page.getViewport({ scale: scale });
            P.state.pageViewports[pageNum - 1] = viewport;

            var $wrapper = $('<div>', { 'class': 'pdf-page-wrapper', 'data-page': pageNum - 1 })
                .css('animation-delay', ((pageNum - 1) * 0.08) + 's');
            var canvas  = document.createElement('canvas');
            canvas.width  = viewport.width;
            canvas.height = viewport.height;
            $wrapper.append(canvas);
            P.state.pageCanvases[pageNum - 1] = canvas;
            $wrapper.append($('<div>', { 'class': 'pdf-page-label', text: 'Page ' + pageNum }));
            $('#pdfViewer').append($wrapper);

            $(canvas).on('click', function (e) { handleClick(e, pageNum - 1); })
                     .on('mousedown', function (e) {
                         if (P.state.editMode && P.state.editFieldType) {
                             P.EditMode.startDraw(e, pageNum - 1, $wrapper[0]);
                         }
                     });

            return page.render({ canvasContext: canvas.getContext('2d'), viewport: viewport }).promise;
        });
    }

    function setScale(scale) {
        if (scale <= 0) return;
        P.state.currentScale  = scale;
        P.state.autoZoomMode  = 'off';
        renderAllPages();
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function fitWidth() {
        if (!P.state.basePageSize.width) return;
        var avail = $('#pdfPane').width() - 40;
        P.state.currentScale = avail / P.state.basePageSize.width;
        P.state.autoZoomMode = 'width';
        renderAllPages();
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function fitHeight() {
        if (!P.state.basePageSize.height) return;
        var avail = $('#pdfPane').height() - 40;
        P.state.currentScale = avail / P.state.basePageSize.height;
        P.state.autoZoomMode = 'height';
        renderAllPages();
        if (P.Zoom && P.Zoom.updateButton) P.Zoom.updateButton();
    }

    function applyAutoZoom() {
        if (P.state.autoZoomMode === 'width')  fitWidth();
        else if (P.state.autoZoomMode === 'height') fitHeight();
    }

    function highlight(pageIndex, bbox) {
        scrollToPage(pageIndex);
        var $wrapper = $('[data-page="' + pageIndex + '"]');
        if (!$wrapper.length || !P.state.pageViewports[pageIndex]) return;
        var vp    = P.state.pageViewports[pageIndex];
        var scale = vp.scale;
        $('<div>', { 'class': 'pdf-highlight' }).css({
            left:   bbox[0] * scale + 'px',
            top:    vp.height - (bbox[1] + bbox[3]) * scale + 'px',
            width:  bbox[2] * scale + 'px',
            height: bbox[3] * scale + 'px'
        }).appendTo($wrapper);
    }

    function clearHighlights() { $('.pdf-highlight').remove(); }

    function scrollToPage(pageIndex) {
        var wrapper = $('[data-page="' + pageIndex + '"]')[0];
        if (wrapper) wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    function handleClick(e, pageIndex) {
        if (P.state.editMode) return;
        if (!P.state.treeData || !P.state.pageViewports[pageIndex]) return;
        var canvas = P.state.pageCanvases[pageIndex];
        if (!canvas) return;
        var rect  = canvas.getBoundingClientRect();
        var vp    = P.state.pageViewports[pageIndex];
        var scale = vp.scale;
        var pdfX  = (e.clientX - rect.left) / scale;
        var pdfY  = (vp.height - (e.clientY - rect.top)) / scale;
        var match = findNodeAtPoint(P.state.treeData, pageIndex, pdfX, pdfY);
        if (match) P.Tree.selectNode(match);
    }

    function findNodeAtPoint(node, pageIndex, x, y) {
        var best = null;
        if (node.pageIndex === pageIndex && node.boundingBox) {
            var bb = node.boundingBox;
            if (x >= bb[0] && x <= bb[0] + bb[2] && y >= bb[1] && y <= bb[1] + bb[3]) best = node;
        }
        if (node.children) {
            node.children.forEach(function (c) {
                var m = findNodeAtPoint(c, pageIndex, x, y);
                if (m) best = m;
            });
        }
        return best;
    }

    return { loadPdf: loadPdf, renderAllPages: renderAllPages, setScale: setScale,
             fitWidth: fitWidth, fitHeight: fitHeight, applyAutoZoom: applyAutoZoom,
             highlight: highlight, clearHighlights: clearHighlights,
             scrollToPage: scrollToPage };
})(jQuery, PDFalyzer);
