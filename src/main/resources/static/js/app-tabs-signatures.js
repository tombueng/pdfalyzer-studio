/**
 * PDFalyzer Studio – Signatures tab: signature field listing, validation and byte-range visualization.
 */
PDFalyzer.SignaturesTab = (function ($, P) {
    'use strict';

    function loadSignatures() {
        if (P.state.signatureData) {
            renderSignatures(P.state.signatureData);
            return;
        }
        P.Utils.apiFetch('/api/signatures/' + P.state.sessionId)
            .done(function (data) {
                P.state.signatureData = data;
                renderSignatures(data);
            })
            .fail(function () { P.Utils.toast('Signature analysis failed', 'danger'); });
    }

    function renderSignatures(data) {
        var $c = $('#treeContent');
        if (!data || !data.signatures || !data.signatures.length) {
            $c.html('<div class="text-muted text-center mt-3">' +
                '<i class="fas fa-signature fa-2x mb-2"></i><br>No signature fields found</div>');
            return;
        }

        var html = buildSummaryBar(data) + '<div class="sig-cards-container">';
        for (var i = 0; i < data.signatures.length; i++) {
            html += buildSignatureCard(data.signatures[i], i);
        }
        html += '</div>';
        $c.html(html);
        bindCardInteractions($c);

        // Auto-expand all cards when fewer than 10 signatures (and no saved state)
        if (!P.state.signatureTabState && data.signatures.length < 10) {
            $c.find('.sig-card').addClass('expanded');
        }
        restoreTabState();
    }

    // ── Summary bar ──────────────────────────────────────────────────────────

    function buildSummaryBar(data) {
        var html = '<div class="sig-summary-bar">';
        html += '<span class="sig-summary-badge total"><i class="fas fa-signature"></i> ' + data.totalSignatureFields + ' field' + (data.totalSignatureFields !== 1 ? 's' : '') + '</span>';
        if (data.signedCount) html += '<span class="sig-summary-badge valid"><i class="fas fa-file-signature"></i> ' + data.signedCount + ' signed</span>';
        if (data.unsignedCount) html += '<span class="sig-summary-badge unsigned"><i class="fas fa-file-circle-question"></i> ' + data.unsignedCount + ' unsigned</span>';
        if (data.validCount) html += '<span class="sig-summary-badge valid"><i class="fas fa-check-circle"></i> ' + data.validCount + ' valid</span>';
        if (data.invalidCount) html += '<span class="sig-summary-badge invalid"><i class="fas fa-times-circle"></i> ' + data.invalidCount + ' invalid</span>';
        if (data.indeterminateCount) html += '<span class="sig-summary-badge indeterminate"><i class="fas fa-question-circle"></i> ' + data.indeterminateCount + ' indeterminate</span>';
        if (data.hasCertificationSignature) html += '<span class="sig-summary-badge total"><i class="fas fa-certificate"></i> Certification</span>';
        html += '</div>';
        return html;
    }

    // ── Card building ────────────────────────────────────────────────────────

    function buildSignatureCard(sig, index) {
        var statusClass = getStatusClass(sig);
        var html = '<div class="sig-card ' + statusClass + '" data-sig-index="' + index + '">';

        // Header
        html += '<div class="sig-card-header">';
        html += '<i class="fas fa-chevron-right sig-expand-icon"></i>';
        html += '<span class="sig-status-badge ' + statusClass + '">' + getStatusLabel(sig) + '</span>';
        html += '<span class="sig-signer-name">' + P.Utils.escapeHtml(getDisplayName(sig)) + '</span>';
        if (sig.signingTime) {
            html += '<span class="sig-signing-time">' + formatTime(sig.signingTime) + '</span>';
        }
        if (sig.pageIndex >= 0 && sig.boundingBox) {
            html += '<button class="btn btn-outline-accent btn-sm sig-locate-btn ms-auto" data-sig-locate="' + index + '">' +
                '<i class="fas fa-crosshairs me-1"></i>Locate in PDF</button>';
        }
        html += '</div>';

        // Body
        html += '<div class="sig-card-body">';

        if (sig.signed) {
            html += buildSignedBody(sig);
        } else {
            html += '<div class="text-muted mb-2"><i class="fas fa-pen-nib me-1"></i>This signature field is empty and awaiting a signature.</div>';
            html += '<button class="btn btn-accent btn-sm sig-sign-btn" data-field="' + P.Utils.escapeHtml(sig.fieldName) + '"><i class="fas fa-file-signature me-1"></i>Sign this field</button>';
        }

        html += '</div></div>';
        return html;
    }

    function buildSignedBody(sig) {
        var html = '';

        // Certificate details
        if (sig.subjectDN || sig.issuerDN) {
            html += '<div class="sig-section-title">Certificate</div>';
            html += '<table class="sig-cert-table">';
            if (sig.subjectDN) html += '<tr><td>Subject</td><td>' + P.Utils.escapeHtml(formatDN(sig.subjectDN)) + '</td></tr>';
            if (sig.issuerDN) html += '<tr><td>Issuer</td><td>' + P.Utils.escapeHtml(formatDN(sig.issuerDN)) + '</td></tr>';
            if (sig.serialNumber) html += '<tr><td>Serial</td><td><code>' + P.Utils.escapeHtml(sig.serialNumber) + '</code></td></tr>';
            if (sig.notBefore) html += '<tr><td>Valid from</td><td>' + formatTime(sig.notBefore) + '</td></tr>';
            if (sig.notAfter) html += '<tr><td>Valid until</td><td>' + formatTime(sig.notAfter) + '</td></tr>';
            if (sig.signatureAlgorithm) html += '<tr><td>Algorithm</td><td>' + P.Utils.escapeHtml(sig.signatureAlgorithm) + '</td></tr>';
            if (sig.digestAlgorithm) html += '<tr><td>Digest</td><td>' + P.Utils.escapeHtml(sig.digestAlgorithm) + '</td></tr>';
            if (sig.subFilter) html += '<tr><td>SubFilter</td><td>' + P.Utils.escapeHtml(sig.subFilter) + '</td></tr>';
            html += '</table>';
        }

        // Signature type & DocMDP
        if (sig.signatureType === 'certification') {
            html += '<div class="sig-section-title">Certification</div>';
            html += '<span class="sig-docmdp-badge level-' + sig.docMdpPermissions + '">' + docMdpLabel(sig.docMdpPermissions) + '</span>';
        }

        // Lock info
        if (sig.lockInfo) {
            html += '<div class="sig-section-title">Field Lock</div>';
            html += '<div class="sig-lock-info">';
            html += '<span class="sig-lock-badge">Action: ' + P.Utils.escapeHtml(sig.lockInfo.action || 'All') + '</span>';
            if (sig.lockInfo.fields && sig.lockInfo.fields.length) {
                html += '<span class="sig-lock-badge">Fields: ' + sig.lockInfo.fields.map(function (f) { return P.Utils.escapeHtml(f); }).join(', ') + '</span>';
            }
            if (sig.lockInfo.permissions) {
                html += '<span class="sig-lock-badge">Permissions: ' + sig.lockInfo.permissions + '</span>';
            }
            html += '</div>';
        }

        // Byte range visualization
        if (sig.byteRange && sig.byteRange.length && sig.totalFileSize > 0) {
            html += '<div class="sig-section-title">Byte Range Coverage</div>';
            html += buildByteRangeBar(sig);
            if (sig.coversEntireFile) {
                html += '<div style="font-size:11px;color:#28a745;"><i class="fas fa-check me-1"></i>Signature covers the entire file (excluding only the signature value itself)</div>';
            }
        }

        // Validation
        html += '<div class="sig-validation-section">';
        html += '<div class="sig-section-title">Validation</div>';
        html += '<span class="sig-status-badge ' + getStatusClass(sig) + '" style="margin-bottom:4px;display:inline-flex;">' + getStatusLabel(sig) + '</span>';
        if (sig.validationMessage) {
            html += '<div class="sig-validation-message text-muted">' + P.Utils.escapeHtml(sig.validationMessage) + '</div>';
        }
        if (sig.validationErrors && sig.validationErrors.length) {
            html += '<ul class="sig-validation-list">';
            for (var i = 0; i < sig.validationErrors.length; i++) {
                html += '<li class="error"><i class="fas fa-times-circle me-1"></i>' + P.Utils.escapeHtml(sig.validationErrors[i]) + '</li>';
            }
            html += '</ul>';
        }
        if (sig.validationWarnings && sig.validationWarnings.length) {
            html += '<ul class="sig-validation-list">';
            for (var j = 0; j < sig.validationWarnings.length; j++) {
                html += '<li class="warning"><i class="fas fa-exclamation-triangle me-1"></i>' + P.Utils.escapeHtml(sig.validationWarnings[j]) + '</li>';
            }
            html += '</ul>';
        }
        html += '</div>';

        // Modification warnings
        if (sig.modifications && sig.modifications.length) {
            html += '<div class="sig-section-title">Post-Signature Modifications</div>';
            for (var k = 0; k < sig.modifications.length; k++) {
                var mod = sig.modifications[k];
                var modClass = mod.severity === 'DANGER' ? ' danger' : '';
                html += '<div class="sig-modification' + modClass + '">';
                html += '<div class="sig-modification-title"><i class="fas fa-exclamation-triangle me-1"></i>' + P.Utils.escapeHtml(mod.description) + '</div>';
                if (mod.detail) html += '<div class="sig-modification-detail">' + P.Utils.escapeHtml(mod.detail) + '</div>';
                html += '</div>';
            }
        }

        return html;
    }

    // ── Byte range bar ───────────────────────────────────────────────────────

    function buildByteRangeBar(sig) {
        var total = sig.totalFileSize;
        if (!total) return '';

        var html = '<div class="sig-byte-range-bar" title="Total file size: ' + formatBytes(total) + '">';

        // Build ordered segments: covered, gap, covered, [uncovered trailing]
        var segments = [];
        for (var i = 0; i < sig.byteRange.length; i++) {
            segments.push({ offset: sig.byteRange[i].offset, length: sig.byteRange[i].length, type: 'covered', label: sig.byteRange[i].label });
        }
        if (sig.coverageGaps) {
            for (var j = 0; j < sig.coverageGaps.length; j++) {
                var g = sig.coverageGaps[j];
                var type = g.description && g.description.indexOf('Uncovered') >= 0 ? 'uncovered' : 'gap';
                segments.push({ offset: g.offset, length: g.length, type: type, label: g.description });
            }
        }
        segments.sort(function (a, b) { return a.offset - b.offset; });

        for (var s = 0; s < segments.length; s++) {
            var pct = Math.max(0.5, (segments[s].length / total) * 100);
            html += '<div class="sig-byte-range-segment ' + segments[s].type + '" ' +
                'style="width:' + pct + '%;" ' +
                'title="' + P.Utils.escapeHtml(segments[s].label || '') + ': ' + formatBytes(segments[s].length) + ' at offset ' + segments[s].offset + '"></div>';
        }

        html += '</div>';
        html += '<div class="sig-byte-range-legend">';
        html += '<span class="covered">Signed</span>';
        html += '<span class="gap">Signature value</span>';
        if (sig.coverageGaps && sig.coverageGaps.some(function (g) { return g.description && g.description.indexOf('Uncovered') >= 0; })) {
            html += '<span class="uncovered">Uncovered trailing</span>';
        }
        html += '</div>';
        return html;
    }

    // ── Interactions ─────────────────────────────────────────────────────────

    function bindCardInteractions($c) {
        $c.find('.sig-card-header').off('click').on('click', function () {
            var $card = $(this).closest('.sig-card');
            $card.toggleClass('expanded');
            saveTabState();
        });

        $c.find('.sig-sign-btn').off('click').on('click', function (e) {
            e.stopPropagation();
            var fieldName = $(this).data('field');
            if (fieldName && P.Signing && P.Signing.openWizard) {
                P.Signing.openWizard(fieldName);
            }
        });

        $c.find('.sig-locate-btn').off('click').on('click', function (e) {
            e.stopPropagation();
            var idx = parseInt($(this).data('sig-locate'), 10);
            if (isNaN(idx) || !P.state.signatureData || !P.state.signatureData.signatures) return;
            var sig = P.state.signatureData.signatures[idx];
            if (!sig || sig.pageIndex < 0 || !sig.boundingBox || sig.boundingBox.length < 4) return;
            if (P.Viewer && P.Viewer.highlight) {
                P.Viewer.highlight(sig.pageIndex, sig.boundingBox, { locator: true });
            }
        });
    }

    // ── Tab state persistence ────────────────────────────────────────────────

    function saveTabState() {
        var expanded = [];
        $('.sig-card.expanded').each(function () {
            expanded.push(parseInt($(this).data('sig-index'), 10));
        });
        var scrollTop = $('#treeContent').scrollTop();
        P.state.signatureTabState = { expanded: expanded, scrollTop: scrollTop };
    }

    function restoreTabState() {
        var ts = P.state.signatureTabState;
        if (!ts) return;
        if (ts.expanded && ts.expanded.length) {
            for (var i = 0; i < ts.expanded.length; i++) {
                $('.sig-card[data-sig-index="' + ts.expanded[i] + '"]').addClass('expanded');
            }
        }
        if (typeof ts.scrollTop === 'number' && ts.scrollTop > 0) {
            $('#treeContent').scrollTop(ts.scrollTop);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    function getStatusClass(sig) {
        if (!sig.signed) return 'unsigned';
        var s = (sig.validationStatus || '').toUpperCase();
        if (s === 'TOTAL_PASSED' || s === 'PASSED' || s === 'VALID') return 'valid';
        if (s === 'TOTAL_FAILED' || s === 'FAILED' || s === 'INVALID') return 'invalid';
        if (s === 'INDETERMINATE') return 'indeterminate';
        return 'not-validated';
    }

    function getStatusLabel(sig) {
        if (!sig.signed) return 'Unsigned';
        var s = (sig.validationStatus || '').toUpperCase();
        if (s === 'TOTAL_PASSED' || s === 'PASSED' || s === 'VALID') return 'Valid';
        if (s === 'TOTAL_FAILED' || s === 'FAILED' || s === 'INVALID') return 'Invalid';
        if (s === 'INDETERMINATE') return 'Indeterminate';
        return 'Not Validated';
    }

    function getDisplayName(sig) {
        if (sig.subjectDN) {
            var cn = extractCN(sig.subjectDN);
            if (cn) return cn;
        }
        if (sig.signerName) return sig.signerName;
        return sig.fieldName || sig.fullyQualifiedName || 'Unknown';
    }

    function extractCN(dn) {
        if (!dn) return null;
        var match = dn.match(/CN=([^,]+)/i);
        return match ? match[1].trim() : null;
    }

    function formatDN(dn) {
        if (!dn) return '';
        return dn.replace(/,/g, ', ');
    }

    function formatTime(isoStr) {
        if (!isoStr) return '';
        try {
            var d = new Date(isoStr);
            return d.toLocaleString();
        } catch (e) {
            return isoStr;
        }
    }

    function formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        var units = ['B', 'KB', 'MB', 'GB'];
        var i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + ' ' + units[i];
    }

    function docMdpLabel(perms) {
        switch (perms) {
            case 1: return 'No changes allowed';
            case 2: return 'Form filling & signing allowed';
            case 3: return 'Form filling, signing & annotation allowed';
            default: return 'Certification (P=' + perms + ')';
        }
    }

    return { loadSignatures: loadSignatures };
})(jQuery, PDFalyzer);
