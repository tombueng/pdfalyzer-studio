/**
 * PDFalyzer – PDF file upload and drag-and-drop.
 */
PDFalyzer.Upload = (function ($, P) {
    'use strict';

    var _inFlight = false;

    function renderIdleButton() {
        $('#uploadBtn').html(
            '<i class="fas fa-upload me-1"></i> Upload PDF' +
            '<input type="file" id="fileInput" accept=".pdf" hidden />');
    }

    function renderUploadingButton() {
        $('#uploadBtn').html(
            '<span class="spinner-border spinner-border-sm"></span> Uploading...' +
            '<input type="file" id="fileInput" accept=".pdf" hidden disabled />');
    }

    function upload(file) {
        if (_inFlight) return;
        if (file.size > 100 * 1024 * 1024) {
            P.Utils.toast('File exceeds 100 MB limit', 'danger');
            return;
        }
        _inFlight = true;
        var formData = new FormData();
        formData.append('file', file);
        var $btn = $('#uploadBtn');
        $btn.addClass('disabled').attr('aria-disabled', 'true');
        renderUploadingButton();

        P.Utils.apiFetch('/api/upload', {
            method: 'POST', data: formData,
            processData: false, contentType: false, dataType: 'json'
        })
        .done(function (data) {
            P.state.sessionId = data.sessionId;
            P.state.treeData  = data.tree;
            $('#statusFilename').html('<i class="fas fa-file-pdf"></i> ' + data.filename);
            $('#statusPages').html('<i class="fas fa-copy"></i> ' + data.pageCount + ' pages');
            $('#statusSession').html('<i class="fas fa-clock"></i> Session active');
            $('#searchInput').prop('disabled', false);
            $('#downloadBtn').prop('disabled', false);
            $('#exportTreeBtn').prop('disabled', false);
            $('#zoomModeBtn').prop('disabled', false);
            $('#formSaveBtn').prop('disabled', true);
            if (P.EditMode && P.EditMode.resetPending) {
                P.EditMode.resetPending();
            }
            P.Tree.render(P.state.treeData);
            P.Viewer.loadPdf(P.state.sessionId);
            P.Utils.toast('PDF loaded: ' + data.filename + ' (' + data.pageCount + ' pages)', 'success');
        })
        .fail(function (xhr) {
            var msg = 'Upload failed';
            try { msg += ': ' + JSON.parse(xhr.responseText).error; } catch (e) {}
            P.Utils.toast(msg, 'danger');
        })
        .always(function () {
            _inFlight = false;
            $btn.removeClass('disabled').removeAttr('aria-disabled');
            renderIdleButton();
            $('#fileInput').val('');
        });
    }

    function loadSampleOnInit() {
        if (P.state.sessionId || _inFlight) return;
        fetch('/test.pdf', { cache: 'no-store' })
            .then(function (resp) {
                if (!resp.ok) throw new Error('not found');
                return resp.blob();
            })
            .then(function (blob) {
                if (P.state.sessionId || _inFlight) return;
                upload(new File([blob], 'test.pdf', { type: 'application/pdf' }));
            })
            .catch(function () {});
    }

    function init() {
        $(document).on('change', '#fileInput', function () {
            if (this.files.length > 0) upload(this.files[0]);
        });
        $(document).on('dragover', function (e) {
            e.preventDefault();
            $('#dropOverlay').addClass('active');
        });
        $('#pdfPane').on('dragleave', function (e) {
            if (!$.contains(this, e.relatedTarget)) $('#dropOverlay').removeClass('active');
        }).on('drop', function (e) {
            e.preventDefault();
            $('#dropOverlay').removeClass('active');
            var files = e.originalEvent.dataTransfer.files;
            if (files.length > 0 &&
                (files[0].type === 'application/pdf' || files[0].name.toLowerCase().endsWith('.pdf'))) {
                upload(files[0]);
            } else {
                P.Utils.toast('Please drop a PDF file', 'warning');
            }
        });
    }

    return { init: init, upload: upload, loadSampleOnInit: loadSampleOnInit };
})(jQuery, PDFalyzer);
