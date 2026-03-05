/**
 * PDFalyzer Studio – export (download PDF, export tree JSON).
 *
 * When the active session was originally encrypted, triggerDownload() shows
 * the export-protection modal so the user can choose whether to re-apply the
 * original protection, use different settings, or strip it entirely.
 */
PDFalyzer.Export = (function ($, P) {
    'use strict';

    var _protModal = null;

    function getProtModal() {
        if (!_protModal) {
            var el = document.getElementById('pdfExportProtectionModal');
            if (el && window.bootstrap) _protModal = new bootstrap.Modal(el);
        }
        return _protModal;
    }

    /** Entry point: called by the download button and Ctrl+S. */
    function triggerDownload() {
        if (!P.state.sessionId) return;
        var enc = P.state.encryptionInfo;
        if (enc && enc.encrypted) {
            showProtectionDialog(enc);
        } else {
            directDownload();
        }
    }

    function directDownload() {
        window.open('/api/pdf/' + P.state.sessionId + '/download', '_blank');
    }

    function showProtectionDialog(enc) {
        var pwType   = enc.passwordType || 'none';
        var algo     = enc.algorithm    || 'AES-256';
        var hasUserPw = (pwType === 'user' || pwType === 'owner');

        var desc;
        if (hasUserPw) {
            desc = 'This PDF was originally protected with ' + algo + ' and required a password to open.';
        } else {
            // empty-user: opened without a password but has permission restrictions
            desc = 'This PDF was originally encrypted with ' + algo +
                   ' to restrict permissions (no password required to open).';
        }
        $('#exportProtDesc').text(desc);

        // Reset to default: keep
        $('#exportProtKeep').prop('checked', true);
        $('#exportProtCustomSettings').hide();

        // Pre-select the original algorithm in the custom dropdown
        var $algoSel = $('#exportProtAlgorithm');
        if ($algoSel.find('option[value="' + algo + '"]').length) {
            $algoSel.val(algo);
        } else {
            $algoSel.val('AES-256');
        }

        $('#exportProtUserPw, #exportProtOwnerPw').val('').attr('type', 'password');
        $('#exportProtUserPwToggle, #exportProtOwnerPwToggle').find('i')
            .removeClass('fa-eye-slash').addClass('fa-eye');

        var modal = getProtModal();
        if (modal) {
            $(document.getElementById('pdfExportProtectionModal'))
                .one('shown.bs.modal', function () {
                    // No autofocus needed – radio buttons are the primary UI
                });
            modal.show();
        }
    }

    function doProtectedDownload() {
        var mode = $('input[name="exportProtMode"]:checked').val();

        if (mode === 'none') {
            directDownload();
            getProtModal().hide();
            return;
        }

        var body = { mode: mode };
        if (mode === 'custom') {
            body.userPassword  = $('#exportProtUserPw').val();
            var ownerVal       = $('#exportProtOwnerPw').val();
            body.ownerPassword = ownerVal || body.userPassword;
            body.algorithm     = $('#exportProtAlgorithm').val();
        }

        var $btn = $('#exportProtDownloadBtn');
        $btn.prop('disabled', true);

        fetch('/api/pdf/' + P.state.sessionId + '/download', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (resp) {
            if (!resp.ok) throw new Error('Server returned ' + resp.status);
            var disposition = resp.headers.get('Content-Disposition') || '';
            var m = disposition.match(/filename="([^"]+)"/);
            var filename = m ? m[1] : 'document.pdf';
            return resp.blob().then(function (blob) {
                return { blob: blob, filename: filename };
            });
        })
        .then(function (result) {
            var url = URL.createObjectURL(result.blob);
            var a   = document.createElement('a');
            a.href     = url;
            a.download = result.filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            getProtModal().hide();
        })
        .catch(function (err) {
            if (P.Utils && P.Utils.toast) P.Utils.toast('Download failed: ' + err.message, 'danger');
        })
        .finally(function () {
            $btn.prop('disabled', false);
        });
    }

    function init() {
        $('#downloadBtn').on('click', triggerDownload);

        $('#exportTreeBtn').on('click', function () {
            if (P.state.sessionId)
                window.open('/api/tree/' + P.state.sessionId + '/export', '_blank');
        });

        // Show / hide custom-settings panel
        $('input[name="exportProtMode"]').on('change', function () {
            $('#exportProtCustomSettings').toggle(this.value === 'custom');
        });

        // Password visibility toggles
        $('#exportProtUserPwToggle').on('click', function () {
            var $i = $('#exportProtUserPw');
            $i.attr('type', $i.attr('type') === 'password' ? 'text' : 'password');
            $(this).find('i').toggleClass('fa-eye fa-eye-slash');
        });
        $('#exportProtOwnerPwToggle').on('click', function () {
            var $i = $('#exportProtOwnerPw');
            $i.attr('type', $i.attr('type') === 'password' ? 'text' : 'password');
            $(this).find('i').toggleClass('fa-eye fa-eye-slash');
        });

        $('#exportProtCancelBtn').on('click', function () { getProtModal().hide(); });
        $('#exportProtDownloadBtn').on('click', doProtectedDownload);
    }

    return { init: init, triggerDownload: triggerDownload };
})(jQuery, PDFalyzer);
