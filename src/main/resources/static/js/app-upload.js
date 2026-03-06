/**
 * PDFalyzer Studio – PDF file upload, drag-and-drop, and password unlock.
 */
PDFalyzer.Upload = (function ($, P) {
    'use strict';

    var _inFlight = false;
    var _passwordModal = null;  // BS modal instance

    function selectActiveTab(tab) {
        var nextTab = tab || 'structure';
        $('.tab-btn').removeClass('active');
        $('.tab-btn[data-tab="' + nextTab + '"]').addClass('active');
    }

    function restorePendingDraftForSession() {
        if (!P.Storage || !P.state.sessionId) return;
        var draft = P.Storage.loadDraft(P.state.sessionId);
        if (!draft) return;
        P.Storage.applyDraftToState(P.state, draft);
    }

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

    function updateEncryptionStatus(encInfo) {
        P.state.encryptionInfo = encInfo || null;
        var $el = $('#statusEncryption');
        if (!encInfo || !encInfo.encrypted) {
            $el.hide().html('');
            return;
        }
        var algo = encInfo.algorithm ? ' · ' + encInfo.algorithm : '';
        var modText = encInfo.canModify ? '' :
            ' <span class="enc-badge-readonly" title="Write operations restricted by PDF permissions">read-only</span>';
        $el.html('<i class="fas fa-lock me-1"></i>Encrypted' + algo + modText).show();
        if (!encInfo.canModify && P.EditMode && P.EditMode.setReadOnlyWarning) {
            P.EditMode.setReadOnlyWarning(true);
        }
    }

    function applyUploadSuccess(data, bytesSize, options) {
        var opts = options || {};
        P.state.sessionId = data.sessionId;
        P.state.treeData  = data.tree;
        P.state.rawCosTreeData = null;
        P.state.tabTreeViewStates = {};
        if (P.Storage && P.Storage.setCurrentSessionId) {
            P.Storage.setCurrentSessionId(P.state.sessionId);
        }
        if (opts.restoreDraft !== false) {
            restorePendingDraftForSession();
            // Apply UI state that depends on restored values
            if (P.Zoom && P.Zoom.setPanMode) { P.Zoom.setPanMode(!!P.state.panMode); }
            if (P.Zoom && P.Zoom.updateButton) { P.Zoom.updateButton(); }
            if (P.Zoom && P.Zoom.updateLayerBtn) { P.Zoom.updateLayerBtn(); }
            if (P.EditMode && P.EditMode.syncEditFieldTypeUI) { P.EditMode.syncEditFieldTypeUI(); }
        }
        updateEncryptionStatus(data.encryptionInfo);

        var sizeValue = typeof bytesSize === 'number' ? bytesSize : 0;
        var humanSize = P.Utils.formatBytes(sizeValue);
        $('#statusFilename').html('<i class="fas fa-file-pdf"></i> ' + data.filename);
        $('#statusPages').html('<i class="fas fa-copy"></i> ' + data.pageCount + ' pages • ' + humanSize);
        $('#statusSession').html('<i class="fas fa-clock"></i> Session active');
        $('#searchInput').prop('disabled', false);
        $('#downloadBtn').prop('disabled', false);
        $('#exportTreeBtn').prop('disabled', false);
        $('#zoomModeBtn').prop('disabled', false);
        $('#zoomInBtn').prop('disabled', false);
        $('#zoomOutBtn').prop('disabled', false);
        $('#panModeBtn').prop('disabled', false);
        $('#annotationLayerBtn').prop('disabled', false);
        if (opts.resetPending !== false && P.EditMode && P.EditMode.resetPending) {
            P.EditMode.resetPending();
        }
        // After the PDF renders: apply auto-zoom, then restore the viewer scroll position
        var savedScroll = P.state.viewerScrollState;
        $(document).off('pdfviewer:rendered.autorestore').one('pdfviewer:rendered.autorestore', function () {
            if (P.state.autoZoomMode !== 'off' && P.Viewer) {
                // Auto-zoom triggers a re-render; restore scroll after that second render
                P.Viewer.applyAutoZoom();
                if (savedScroll && P.ViewerRender) {
                    $(document).one('pdfviewer:rendered.scrollrestore', function () {
                        P.ViewerRender.restorePaneViewState(savedScroll);
                    });
                }
            } else if (savedScroll && P.ViewerRender) {
                P.ViewerRender.restorePaneViewState(savedScroll);
            }
        });
        // Let switchTab handle initial tree rendering — no premature render here,
        // otherwise captureTreeViewStateForTab overwrites the restored tabTreeViewStates
        P.Viewer.loadPdf(P.state.sessionId);
        var nextTab = P.state.currentTab || 'structure';
        selectActiveTab(nextTab);
        if (P.Tabs && P.Tabs.switchTab) {
            // Restore selected node inside the callback so it runs after the rAF-deferred render
            var selectedId = P.state.selectedNodeId;
            P.Tabs.switchTab(nextTab, function () {
                if (selectedId != null && P.Tabs.isTreeTab && P.Tabs.isTreeTab(nextTab) &&
                        P.Tree && P.Tree.selectNodeById) {
                    P.Tree.selectNodeById(selectedId);
                }
            });
        }
        if (P.EditMode && P.EditMode.updateSaveButton) {
            P.EditMode.updateSaveButton();
        }
        if (P.Storage && P.Storage.saveDraft) {
            P.Storage.saveDraft(P.state);
        }
    }

    // ── password prompt ───────────────────────────────────────────────────────

    function getPasswordModal() {
        if (!_passwordModal) {
            var el = document.getElementById('pdfPasswordModal');
            if (el && window.bootstrap) _passwordModal = new bootstrap.Modal(el);
        }
        return _passwordModal;
    }

    function showPasswordPrompt(sessionId, filename, bytesSize) {
        var modal = getPasswordModal();
        if (!modal) {
            P.Utils.toast('This PDF requires a password but the prompt could not be shown.', 'danger');
            return;
        }
        $('#pdfPasswordHint').text('Enter the password to open "' + filename + '".');
        $('#pdfPasswordInput').val('').attr('type', 'password');
        $('#pdfPasswordError').hide().text('');
        $('#pdfPasswordToggle').find('i').removeClass('fa-eye-off').addClass('fa-eye');
        $(document.getElementById('pdfPasswordModal')).one('shown.bs.modal', function () {
            document.getElementById('pdfPasswordInput').focus();
        });
        modal.show();

        $('#pdfPasswordSubmitBtn').off('click.pw').on('click.pw', function () {
            doUnlock(sessionId, filename, bytesSize);
        });
        $('#pdfPasswordCancelBtn').off('click.pw').on('click.pw', function () {
            modal.hide();
            renderIdleButton();
            _inFlight = false;
        });
        $('#pdfPasswordInput').off('keydown.pw').on('keydown.pw', function (e) {
            if (e.key === 'Enter') doUnlock(sessionId, filename, bytesSize);
        });
    }

    function doUnlock(sessionId, filename, bytesSize) {
        var password = $('#pdfPasswordInput').val();
        if (!password) {
            $('#pdfPasswordError').text('Please enter a password.').show();
            return;
        }
        $('#pdfPasswordError').hide();
        var $btn = $('#pdfPasswordSubmitBtn');
        $btn.prop('disabled', true).html('<span class="spinner-border spinner-border-sm"></span> Unlocking…');

        P.Utils.apiFetch('/api/session/' + encodeURIComponent(sessionId) + '/unlock', {
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ password: password }),
            dataType: 'json'
        })
        .done(function (data) {
            getPasswordModal().hide();
            applyUploadSuccess(data, bytesSize, { restoreDraft: false, resetPending: true });
            P.Utils.toast('PDF unlocked: ' + filename, 'success');
        })
        .fail(function (xhr) {
            var msg = 'Wrong password';
            try { msg = JSON.parse(xhr.responseText).error || msg; } catch (e) {}
            $('#pdfPasswordError').text(msg).show();
        })
        .always(function () {
            $btn.prop('disabled', false).html('<i class="fas fa-unlock me-1"></i>Unlock');
            _inFlight = false;
        });
    }

    // ── upload ────────────────────────────────────────────────────────────────

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
            if (data.encryptionInfo && data.encryptionInfo.requiresPassword) {
                // Tree is absent; show password prompt, keep _inFlight=true
                renderIdleButton();
                $btn.removeClass('disabled').removeAttr('aria-disabled');
                showPasswordPrompt(data.sessionId, data.filename, file.size);
                $('#fileInput').val('');
                return;
            }
            applyUploadSuccess(data, file && file.size ? file.size : 0, {
                restoreDraft: false, resetPending: true
            });
            P.Utils.toast('PDF loaded: ' + data.filename + ' (' + data.pageCount + ' pages)', 'success');
            _inFlight = false;
            $btn.removeClass('disabled').removeAttr('aria-disabled');
            renderIdleButton();
            $('#fileInput').val('');
        })
        .fail(function (xhr) {
            var msg = 'Upload failed';
            try { msg += ': ' + JSON.parse(xhr.responseText).error; } catch (e) {}
            P.Utils.toast(msg, 'danger');
            _inFlight = false;
            $btn.removeClass('disabled').removeAttr('aria-disabled');
            renderIdleButton();
            $('#fileInput').val('');
        });
    }

    function loadSample(filename) {
        if (_inFlight) return;
        _inFlight = true;
        renderUploadingButton();

        P.Utils.apiFetch('/api/sample/load/' + encodeURIComponent(filename), { method: 'POST', dataType: 'json' })
            .done(function (data) {
                if (data.encryptionInfo && data.encryptionInfo.requiresPassword) {
                    renderIdleButton();
                    showPasswordPrompt(data.sessionId, data.filename, data.fileSize || 0);
                    return;
                }
                var sampleSize = typeof data.fileSize === 'number' ? data.fileSize : 0;
                applyUploadSuccess(data, sampleSize, { restoreDraft: false, resetPending: true });
                P.Utils.toast('PDF loaded: ' + data.filename + ' (' + data.pageCount + ' pages)', 'success');
            })
            .fail(function (xhr) {
                var msg = 'Failed to load sample';
                try { msg += ': ' + JSON.parse(xhr.responseText).error; } catch (e) {}
                P.Utils.toast(msg, 'danger');
            })
            .always(function () {
                _inFlight = false;
                renderIdleButton();
            });
    }

    function initSamplesMenu() {
        P.Utils.apiFetch('/api/sample/list', { method: 'GET', dataType: 'json' })
            .done(function (names) {
                var items = names.length
                    ? names.map(function (name) {
                        return '<li><button class="dropdown-item sample-item" data-name="' +
                            $('<div>').text(name).html() + '">' +
                            $('<div>').text(name).html() + '</button></li>';
                    }).join('')
                    : '<li><span class="dropdown-item text-muted small">No samples found</span></li>';
                $('#samplesDropdownMenu, #mobileSamplesMenu').html(items);
            })
            .fail(function () {
                var err = '<li><span class="dropdown-item text-muted small">Failed to load</span></li>';
                $('#samplesDropdownMenu, #mobileSamplesMenu').html(err);
            });
    }

    function loadSampleOnInit() {
        if (P.state.sessionId || _inFlight) return;
        _inFlight = true;
        renderUploadingButton();

        P.Utils.apiFetch('/api/sample/load', { method: 'POST', dataType: 'json' })
            .done(function (data) {
                if (P.state.sessionId) return;
                var sampleSize = typeof data.fileSize === 'number' ? data.fileSize : 0;
                applyUploadSuccess(data, sampleSize, { restoreDraft: false, resetPending: true });
                P.Utils.toast('PDF loaded: ' + data.filename + ' (' + data.pageCount + ' pages)', 'success');
            })
            .always(function () {
                _inFlight = false;
                renderIdleButton();
            });
    }

    function restoreSessionOnInit() {
        if (_inFlight || P.state.sessionId || !P.Storage || !P.Storage.getCurrentSessionId) {
            return $.Deferred().resolve(false).promise();
        }
        var storedSessionId = P.Storage.getCurrentSessionId();
        if (!storedSessionId) {
            return $.Deferred().resolve(false).promise();
        }
        _inFlight = true;
        renderUploadingButton();

        var deferred = $.Deferred();
        P.Utils.apiFetch('/api/session/' + encodeURIComponent(storedSessionId) + '/restore', {
            method: 'GET', dataType: 'json'
        })
        .done(function (data) {
            var size = typeof data.fileSize === 'number' ? data.fileSize : 0;
            applyUploadSuccess(data, size, { restoreDraft: true, resetPending: false });
            P.Utils.toast('Restored previous session', 'success');
            deferred.resolve(true);
        })
        .fail(function () {
            if (P.Storage) {
                P.Storage.clearDraft(storedSessionId);
                P.Storage.clearCurrentSessionId();
            }
            deferred.resolve(false);
        })
        .always(function () {
            _inFlight = false;
            renderIdleButton();
        });

        return deferred.promise();
    }

    function init() {
        $(document).on('change', '#fileInput', function () {
            if (this.files.length > 0) upload(this.files[0]);
        });
        $(document).on('click', '.sample-item', function () {
            loadSample($(this).data('name'));
        });
        initSamplesMenu();
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

        // Password toggle eye button
        $(document).on('click', '#pdfPasswordToggle', function () {
            var $input = $('#pdfPasswordInput');
            var isPassword = $input.attr('type') === 'password';
            $input.attr('type', isPassword ? 'text' : 'password');
            $(this).find('i')
                .toggleClass('fa-eye', !isPassword)
                .toggleClass('fa-eye-off', isPassword);
        });
    }

    return {
        init: init,
        upload: upload,
        loadSample: loadSample,
        loadSampleOnInit: loadSampleOnInit,
        restoreSessionOnInit: restoreSessionOnInit
    };
})(jQuery, PDFalyzer);
