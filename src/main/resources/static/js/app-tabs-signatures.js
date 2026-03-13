/**
 * PDFalyzer Studio – Signatures tab: signature analysis, certificate chain display,
 * revision graph visualization, and on-demand trust validation.
 */
PDFalyzer.SignaturesTab = (function ($, P) {
    'use strict';

    var trustPollTimer = null;

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

        var html = buildSummaryBar(data);
        var completedTrustData = P.state.trustValidationResult ? {
            status: 'COMPLETED', message: 'Validation complete', completed: true,
            eutlStatus: P.state.trustValidationResult.eutlStatus || null,
            aatlStatus: P.state.trustValidationResult.aatlStatus || null
        } : null;
        html += buildTrustProgressSection(completedTrustData);

        // Revision graph (above all signature cards)
        if (data.revisions && data.revisions.length > 1) {
            html += buildRevisionGraph(data);
        }

        html += '<div class="sig-cards-container">';
        for (var i = 0; i < data.signatures.length; i++) {
            html += buildSignatureCard(data.signatures[i], i);
        }
        html += '</div>';

        html += buildTrustStatusBar(data);

        $c.html(html);
        bindCardInteractions($c);
        bindTrustValidation($c);

        // Auto-expand all cards when fewer than 10 signatures (and no saved state)
        if (!P.state.signatureTabState && data.signatures.length < 10) {
            $c.find('.sig-card').addClass('expanded');
        }
        restoreTabState();

        // Trigger validation if no result yet and none in progress
        if (!P.state.trustValidationResult && !P.state.trustValidationInProgress) {
            triggerTrustValidation();
        }
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
        html += '<button class="btn btn-outline-accent btn-sm sig-validate-trust-btn ms-auto" title="Validate against EUTL and AATL trust lists">';
        html += '<i class="fas fa-shield-alt me-1"></i>Validate Trust</button>';
        html += '</div>';
        return html;
    }

    // ── Trust progress section ───────────────────────────────────────────────

    function buildTrustProgressInner(statusData) {
        var headerLabel, headerIcon;
        if (!statusData || statusData.status === 'NOT_STARTED') {
            headerLabel = 'Trust validation not yet started';
            headerIcon = '<span class="text-muted ms-2"><i class="fas fa-clock"></i></span>';
        } else if (statusData.completed) {
            headerLabel = statusData.message || 'Validation complete';
            headerIcon = '<i class="fas fa-check-circle text-success ms-2"></i>';
        } else {
            headerLabel = statusData.message || 'Validating\u2026';
            headerIcon = '<span class="sig-trust-spinner ms-2"></span>';
        }

        var html = '<div class="sig-trust-progress-inner">';
        html += '<div class="sig-trust-progress-header"><i class="fas fa-shield-alt me-1"></i>';
        html += '<span class="sig-trust-progress-label">' + P.Utils.escapeHtml(headerLabel) + '</span>';
        html += headerIcon + '</div>';

        var eutlStatus = (statusData && statusData.eutlStatus) ? statusData.eutlStatus : null;
        var aatlStatus = (statusData && statusData.aatlStatus) ? statusData.aatlStatus : null;
        html += buildTrustListProgressLine(eutlStatus, 'EUTL');
        html += buildTrustListProgressLine(aatlStatus, 'AATL');
        html += '</div>';
        return html;
    }

    function buildTrustProgressSection(statusData) {
        // Renders the correct state immediately — no two-step render+update needed
        var inner;
        if (statusData) {
            inner = buildTrustProgressInner(statusData);
        } else {
            inner = '<div class="sig-trust-progress-inner">'
                + '<div class="sig-trust-progress-header"><i class="fas fa-shield-alt me-1"></i>'
                + '<span class="sig-trust-progress-label">Contacting trust lists\u2026</span>'
                + '<span class="sig-trust-spinner ms-2"></span></div>'
                + '<div class="sig-trust-progress-line"><span class="sig-trust-progress-tl-label">EUTL:</span> <span class="text-muted">Connecting\u2026</span></div>'
                + '<div class="sig-trust-progress-line"><span class="sig-trust-progress-tl-label">AATL:</span> <span class="text-muted">Connecting\u2026</span></div>'
                + '</div>';
        }
        return '<div class="sig-trust-progress-section">' + inner + '</div>';
    }

    function updateTrustProgress(statusData) {
        $('.sig-trust-progress-section').html(buildTrustProgressInner(statusData));
    }

    function buildTrustListProgressLine(tlStatus, label) {
        var html = '<div class="sig-trust-progress-line">';
        html += '<span class="sig-trust-progress-tl-label">' + label + ':</span> ';

        if (!tlStatus || tlStatus.status === 'NOT_LOADED') {
            html += '<span class="text-muted"><span class="sig-trust-spinner me-1"></span>Downloading\u2026</span>';
        } else if (tlStatus.status === 'LOADING') {
            var loadMsg = P.Utils.escapeHtml(tlStatus.currentlyFetching || tlStatus.statusMessage || 'Loading\u2026');
            html += '<span class="text-warning"><span class="sig-trust-spinner me-1"></span>' + loadMsg + '</span>';
            if (tlStatus.totalToFetch > 0) {
                var pct = Math.round((tlStatus.fetchedCount / tlStatus.totalToFetch) * 100);
                html += ' <div class="sig-trust-progress-bar"><div class="sig-trust-progress-fill" style="width:' + pct + '%"></div></div>';
                html += ' <span class="text-muted" style="font-size:10px;">' + tlStatus.fetchedCount + '/' + tlStatus.totalToFetch + '</span>';
            }
        } else if (tlStatus.status === 'LOADED') {
            var hasAnchors = tlStatus.totalTrustAnchors > 0;
            html += '<span class="' + (hasAnchors ? 'text-success' : 'text-warning') + '">';
            html += '<i class="fas fa-' + (hasAnchors ? 'check' : 'exclamation-triangle') + ' me-1"></i>';
            html += tlStatus.totalTrustAnchors + ' anchors';
            if (hasAnchors && tlStatus.loadedCountries && tlStatus.loadedCountries.length) {
                html += ' (' + tlStatus.loadedCountries.join(', ') + ')';
            }
            html += '</span>';
        } else if (tlStatus.status === 'ERROR') {
            html += '<span class="text-danger"><i class="fas fa-exclamation-circle me-1"></i>' + P.Utils.escapeHtml(tlStatus.statusMessage || 'Error') + '</span>';
        } else {
            html += '<span class="text-muted">Not loaded</span>';
        }
        html += '</div>';
        return html;
    }

    // ── Revision Graph ───────────────────────────────────────────────────────

    function buildRevisionGraph(data) {
        var revisions = data.revisions;
        var signedSigs = [];
        for (var i = 0; i < data.signatures.length; i++) {
            if (data.signatures[i].signed) signedSigs.push({ sig: data.signatures[i], index: i });
        }
        if (signedSigs.length === 0 || revisions.length < 2) return '';

        var totalSize = data.signatures[0].totalFileSize || revisions[revisions.length - 1].endOffset;
        if (totalSize <= 0) return '';

        var html = '<div class="sig-revision-graph">';
        html += '<div class="sig-revision-graph-title"><i class="fas fa-layer-group me-1"></i>Revision & Signature Coverage</div>';

        // Revision labels row
        html += '<div class="sig-revision-labels-row">';
        for (var r = 0; r < revisions.length; r++) {
            var leftPct = (revisions[r].endOffset / totalSize) * 100;
            html += '<span class="sig-revision-label" style="left:' + leftPct + '%">R' + revisions[r].revisionIndex + '</span>';
        }
        html += '</div>';

        // Lanes (one per signed signature)
        html += '<div class="sig-revision-lanes">';

        // Revision boundary lines (vertical)
        for (var r2 = 0; r2 < revisions.length; r2++) {
            var leftPct2 = (revisions[r2].endOffset / totalSize) * 100;
            html += '<div class="sig-revision-boundary" style="left:' + leftPct2 + '%" title="Revision ' + revisions[r2].revisionIndex + ' ends at ' + formatBytes(revisions[r2].endOffset) + '"></div>';
        }

        for (var s = 0; s < signedSigs.length; s++) {
            var sig = signedSigs[s].sig;
            var sigIdx = signedSigs[s].index;
            var statusClass = getStatusClass(sig);
            var displayName = getDisplayName(sig);

            html += '<div class="sig-revision-lane" data-sig-index="' + sigIdx + '">';
            html += '<span class="sig-revision-lane-label" title="' + P.Utils.escapeHtml(displayName) + '">' + P.Utils.escapeHtml(displayName) + '</span>';
            html += '<div class="sig-revision-lane-bar">';

            // Coverage bar
            if (sig.byteRange && sig.byteRange.length >= 2) {
                var coverEnd = sig.byteRange[1].offset + sig.byteRange[1].length;
                var coverPct = (coverEnd / totalSize) * 100;
                html += '<div class="sig-revision-coverage ' + statusClass + '" style="width:' + coverPct + '%"' +
                    ' title="' + P.Utils.escapeHtml(displayName) + ': covers ' + formatBytes(coverEnd) + ' of ' + formatBytes(totalSize) + '"></div>';
            }

            // Signature marker dot
            if (sig.coveredRevisions && sig.coveredRevisions.length) {
                var lastRev = sig.coveredRevisions[sig.coveredRevisions.length - 1];
                var dotPct = (lastRev.endOffset / totalSize) * 100;
                html += '<div class="sig-revision-marker ' + statusClass + '" style="left:' + dotPct + '%"' +
                    ' data-sig-marker="' + sigIdx + '"' +
                    ' title="' + P.Utils.escapeHtml(displayName) + ' — covers through revision R' + lastRev.revisionIndex + '"></div>';
            }

            html += '</div></div>';
        }

        html += '</div>';

        // Legend
        html += '<div class="sig-revision-graph-legend">';
        html += '<span><span class="sig-legend-dot valid"></span> Signed coverage</span>';
        html += '<span><span class="sig-legend-line"></span> Revision boundary</span>';
        if (signedSigs.some(function (s) { return !s.sig.coversEntireFile; })) {
            html += '<span><span class="sig-legend-dot indeterminate"></span> Partial coverage</span>';
        }
        html += '</div>';

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

        // Certificate chain (replaces flat cert table)
        if (sig.certificateChain && sig.certificateChain.length) {
            html += '<div class="sig-section-title"><i class="fas fa-link me-1"></i>Certificate Chain</div>';
            html += buildCertificateChain(sig.certificateChain);
        } else if (sig.subjectDN || sig.issuerDN) {
            // Fallback to flat cert table if no chain available
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

        // TSA Certificate Chain
        if (sig.hasTsa && sig.tsaCertificateChain && sig.tsaCertificateChain.length) {
            html += '<div class="sig-section-title"><i class="fas fa-clock me-1"></i>Timestamp Authority Chain';
            if (sig.tsaSigningTime) {
                html += ' <span class="sig-tsa-time">(' + formatTime(sig.tsaSigningTime) + ')</span>';
            }
            html += '</div>';
            html += buildCertificateChain(sig.tsaCertificateChain);
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
            // Show covered revisions
            if (sig.coveredRevisions && sig.coveredRevisions.length) {
                html += '<div style="font-size:11px;color:var(--accent);">';
                html += '<i class="fas fa-layer-group me-1"></i>Covers revision' + (sig.coveredRevisions.length > 1 ? 's' : '') + ' ';
                var revLabels = [];
                for (var r = 0; r < sig.coveredRevisions.length; r++) {
                    revLabels.push('R' + sig.coveredRevisions[r].revisionIndex);
                }
                html += revLabels.join(', ');
                html += '</div>';
            }
        }

        // Trust validation report
        if (sig.trustValidation) {
            html += buildTrustValidationReport(sig.trustValidation);
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

    // ── Certificate Chain ────────────────────────────────────────────────────

    function buildCertificateChain(chain) {
        var html = '<div class="sig-cert-chain">';

        // Reverse display: root at top, end-entity at bottom
        var displayOrder = chain.slice().reverse();

        for (var i = 0; i < displayOrder.length; i++) {
            var entry = displayOrder[i];
            var isLast = (i === displayOrder.length - 1);
            html += buildChainNode(entry, isLast);
        }

        html += '</div>';
        return html;
    }

    function buildChainNode(entry, isLast) {
        var roleBadge = getRoleBadge(entry.role);
        var cn = extractCN(entry.subjectDN) || entry.subjectDN || 'Unknown';
        var html = '<div class="sig-cert-chain-node' + (isLast ? ' end-entity' : '') + '" data-chain-index="' + entry.chainIndex + '">';

        // Connector line (not for last node)
        if (!isLast) {
            html += '<div class="sig-cert-chain-connector"></div>';
        }

        // Node header (clickable to expand)
        html += '<div class="sig-cert-chain-node-header">';
        html += '<i class="fas fa-chevron-right sig-chain-expand-icon"></i>';
        html += roleBadge;

        // Trust anchor badge
        if (entry.isTrustAnchor || entry.trustAnchor) {
            html += '<span class="sig-trust-badge trusted" title="Trusted: ' + P.Utils.escapeHtml(entry.trustListSource || '') + '">';
            html += '<i class="fas fa-shield-alt"></i> ' + P.Utils.escapeHtml(entry.trustListSource || 'Trusted') + '</span>';
        }

        // DSS coverage badge
        if (entry.presentInDss !== undefined) {
            if (entry.presentInDss) {
                html += '<span class="sig-dss-badge in-dss" title="Certificate present in Document Security Store"><i class="fas fa-database"></i> DSS</span>';
            } else {
                html += '<span class="sig-dss-badge missing-dss" title="Certificate NOT in Document Security Store"><i class="fas fa-database"></i> Not in DSS</span>';
            }
        }

        // Revocation badge
        if (entry.revocationStatus) {
            html += buildRevocationBadge(entry.revocationStatus);
        }

        html += '<span class="sig-cert-chain-cn">' + P.Utils.escapeHtml(cn) + '</span>';

        // Key info summary
        html += '<span class="sig-cert-chain-key-info">';
        if (entry.publicKeyAlgorithm) {
            html += P.Utils.escapeHtml(entry.publicKeyAlgorithm);
            if (entry.publicKeyBitLength) html += ' ' + entry.publicKeyBitLength;
        }
        html += '</span>';

        html += '</div>';

        // Node detail body (collapsed by default)
        html += '<div class="sig-cert-chain-node-body">';
        html += '<table class="sig-cert-table">';
        html += '<tr><td>Subject</td><td>' + P.Utils.escapeHtml(formatDN(entry.subjectDN || '')) + '</td></tr>';
        html += '<tr><td>Issuer</td><td>' + P.Utils.escapeHtml(formatDN(entry.issuerDN || '')) + '</td></tr>';
        html += '<tr><td>Serial</td><td><code>' + P.Utils.escapeHtml(entry.serialNumber || '') + '</code></td></tr>';
        html += '<tr><td>Valid from</td><td>' + formatTime(entry.notBefore) + '</td></tr>';
        html += '<tr><td>Valid until</td><td>' + formatTime(entry.notAfter) + '</td></tr>';
        html += '<tr><td>Signature Alg</td><td>' + P.Utils.escapeHtml(entry.signatureAlgorithm || '') + '</td></tr>';
        html += '<tr><td>Public Key</td><td>' + P.Utils.escapeHtml((entry.publicKeyAlgorithm || '') + (entry.publicKeyBitLength ? ' ' + entry.publicKeyBitLength + ' bit' : '')) + '</td></tr>';

        if (entry.isCA !== undefined) {
            html += '<tr><td>CA</td><td>' + (entry.isCA || entry.ca ? 'Yes' : 'No');
            if ((entry.isCA || entry.ca) && entry.pathLengthConstraint >= 0) {
                html += ' (path length: ' + entry.pathLengthConstraint + ')';
            }
            html += '</td></tr>';
        }

        if (entry.keyUsage && entry.keyUsage.length) {
            html += '<tr><td>Key Usage</td><td>' + entry.keyUsage.map(function (u) { return P.Utils.escapeHtml(u); }).join(', ') + '</td></tr>';
        }

        if (entry.subjectKeyIdentifier) {
            html += '<tr><td>SKI</td><td><code class="sig-cert-ski">' + P.Utils.escapeHtml(entry.subjectKeyIdentifier) + '</code></td></tr>';
        }
        if (entry.authorityKeyIdentifier) {
            html += '<tr><td>AKI</td><td><code class="sig-cert-aki">' + P.Utils.escapeHtml(entry.authorityKeyIdentifier) + '</code></td></tr>';
        }

        // OCSP URLs
        if (entry.ocspResponderUrls && entry.ocspResponderUrls.length) {
            html += '<tr><td>OCSP</td><td>';
            for (var o = 0; o < entry.ocspResponderUrls.length; o++) {
                html += '<div class="sig-cert-url">' + P.Utils.escapeHtml(entry.ocspResponderUrls[o]) + '</div>';
            }
            html += '</td></tr>';
        }

        // CRL URLs
        if (entry.crlDistributionPoints && entry.crlDistributionPoints.length) {
            html += '<tr><td>CRL</td><td>';
            for (var c = 0; c < entry.crlDistributionPoints.length; c++) {
                html += '<div class="sig-cert-url">' + P.Utils.escapeHtml(entry.crlDistributionPoints[c]) + '</div>';
            }
            html += '</td></tr>';
        }

        // AIA URLs
        if (entry.authorityInfoAccessUrls && entry.authorityInfoAccessUrls.length) {
            html += '<tr><td>AIA</td><td>';
            for (var a = 0; a < entry.authorityInfoAccessUrls.length; a++) {
                html += '<div class="sig-cert-url">' + P.Utils.escapeHtml(entry.authorityInfoAccessUrls[a]) + '</div>';
            }
            html += '</td></tr>';
        }

        html += '</table>';
        html += '</div>'; // node body

        html += '</div>'; // node
        return html;
    }

    function getRoleBadge(role) {
        var cls, label;
        switch (role) {
            case 'ROOT': cls = 'root'; label = 'Root CA'; break;
            case 'INTERMEDIATE': cls = 'intermediate'; label = 'Intermediate'; break;
            case 'END_ENTITY': cls = 'end-entity'; label = 'End Entity'; break;
            default: cls = ''; label = role || 'Unknown';
        }
        return '<span class="sig-cert-role-badge ' + cls + '">' + label + '</span>';
    }

    function buildRevocationBadge(revStatus) {
        if (!revStatus || !revStatus.status) return '';
        var cls, icon, label;
        switch (revStatus.status) {
            case 'GOOD': cls = 'good'; icon = 'fa-check-circle'; label = 'Not revoked'; break;
            case 'REVOKED': cls = 'revoked'; icon = 'fa-times-circle'; label = 'Revoked'; break;
            case 'UNKNOWN': cls = 'unknown'; icon = 'fa-question-circle'; label = 'Unknown'; break;
            case 'NOT_CHECKED': cls = 'not-checked'; icon = 'fa-minus-circle'; label = 'Not checked'; break;
            default: cls = 'error'; icon = 'fa-exclamation-circle'; label = revStatus.status; break;
        }
        var title = label;
        if (revStatus.checkedVia) title += ' (via ' + revStatus.checkedVia + ')';
        if (revStatus.revokedAt) title += ' — revoked at ' + formatTime(revStatus.revokedAt);
        return '<span class="sig-revocation-badge ' + cls + '" title="' + P.Utils.escapeHtml(title) + '">' +
            '<i class="fas ' + icon + '"></i></span>';
    }

    // ── Trust Validation Report ──────────────────────────────────────────────

    function buildTrustValidationReport(report) {
        if (!report || !report.checks || !report.checks.length) return '';

        var html = '<div class="sig-trust-validation-report">';
        html += '<div class="sig-section-title"><i class="fas fa-shield-alt me-1"></i>Trust Validation</div>';

        var overallClass = report.overallStatus === 'VALID' ? 'valid' :
            report.overallStatus === 'INVALID' ? 'invalid' : 'indeterminate';
        html += '<span class="sig-status-badge ' + overallClass + '" style="margin-bottom:4px;display:inline-flex;">' +
            P.Utils.escapeHtml(report.overallStatus || 'Unknown') + '</span>';
        if (report.overallMessage) {
            html += '<div class="sig-validation-message text-muted">' + P.Utils.escapeHtml(report.overallMessage) + '</div>';
        }

        html += '<div class="sig-trust-checks">';
        for (var i = 0; i < report.checks.length; i++) {
            var check = report.checks[i];
            var checkIcon, checkClass;
            switch (check.status) {
                case 'PASS': checkIcon = 'fa-check-circle'; checkClass = 'pass'; break;
                case 'FAIL': checkIcon = 'fa-times-circle'; checkClass = 'fail'; break;
                case 'WARNING': checkIcon = 'fa-exclamation-triangle'; checkClass = 'warning'; break;
                default: checkIcon = 'fa-minus-circle'; checkClass = 'skip'; break;
            }
            html += '<div class="sig-trust-check ' + checkClass + '">';
            html += '<i class="fas ' + checkIcon + ' me-1"></i>';
            html += '<span class="sig-trust-check-name">' + P.Utils.escapeHtml(formatCheckName(check.checkName)) + '</span>';
            html += '<span class="sig-trust-check-message">' + P.Utils.escapeHtml(check.message || '') + '</span>';
            html += '</div>';
        }
        html += '</div></div>';
        return html;
    }

    function formatCheckName(name) {
        var map = {
            'CERT_VALIDITY': 'Certificate Validity',
            'CHAIN_COMPLETE': 'Chain Completeness',
            'TRUST_ANCHOR': 'Trust Anchor',
            'REVOCATION': 'Revocation Status',
            'TSA_CERT_VALIDITY': 'TSA Certificate Validity',
            'TSA_TRUST_ANCHOR': 'TSA Trust Anchor',
            'TSA_REVOCATION': 'TSA Revocation Status',
            'DSS_CERT_COVERAGE': 'DSS Certificate Coverage',
            'BYTE_RANGE': 'Byte Range Coverage',
            'DSS_PRESENT': 'Document Security Store',
            'DOCMDP': 'DocMDP Permissions'
        };
        return map[name] || name;
    }

    // ── Byte range bar ───────────────────────────────────────────────────────

    function buildByteRangeBar(sig) {
        var total = sig.totalFileSize;
        if (!total) return '';

        var html = '<div class="sig-byte-range-container">';

        // Revision markers (if available)
        var data = P.state.signatureData;
        if (data && data.revisions && data.revisions.length > 1) {
            html += '<div class="sig-byte-range-revisions">';
            for (var r = 0; r < data.revisions.length; r++) {
                var leftPct = (data.revisions[r].endOffset / total) * 100;
                html += '<span class="sig-byte-range-revision-marker" style="left:' + leftPct + '%"' +
                    ' title="Revision R' + data.revisions[r].revisionIndex + ' ends at ' + formatBytes(data.revisions[r].endOffset) + '">' +
                    'R' + data.revisions[r].revisionIndex + '</span>';
            }
            html += '</div>';
        }

        html += '<div class="sig-byte-range-bar" title="Total file size: ' + formatBytes(total) + '">';

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
        html += '</div>';
        return html;
    }

    // ── Trust Status Bar ─────────────────────────────────────────────────────

    function buildTrustStatusBar(data) {
        var html = '<div class="sig-trust-status-bar">';
        html += '<span class="sig-trust-status-item">';
        html += '<i class="fas fa-shield-alt me-1"></i>';

        if (data.eutlStatus && data.eutlStatus.status === 'LOADED') {
            html += '<span class="text-success">EUTL: ' + data.eutlStatus.totalTrustAnchors + ' anchors';
            if (data.eutlStatus.loadedCountries && data.eutlStatus.loadedCountries.length) {
                html += ' (' + data.eutlStatus.loadedCountries.join(', ') + ')';
            }
            html += '</span>';
            if (data.eutlStatus.ageMinutes > 0) {
                html += ' <span class="text-muted">| Age: ' + formatAge(data.eutlStatus.ageMinutes) + '</span>';
            }
        } else {
            html += '<span class="text-muted">EUTL: Not loaded</span>';
        }

        html += '</span>';
        html += '<span class="sig-trust-status-item">';

        if (data.aatlStatus && data.aatlStatus.status === 'LOADED') {
            html += '<span class="text-success">AATL: ' + data.aatlStatus.totalTrustAnchors + ' anchors</span>';
        } else {
            html += '<span class="text-muted">AATL: Not loaded</span>';
        }

        html += '</span>';

        html += '<button class="btn btn-outline-secondary btn-sm sig-trust-refresh-btn ms-auto" title="Refresh trust lists">';
        html += '<i class="fas fa-sync-alt"></i></button>';

        html += '</div>';
        return html;
    }

    // ── Trust validation ─────────────────────────────────────────────────────

    function triggerTrustValidation() {
        P.state.trustValidationInProgress = true;
        updateTrustProgress({ status: 'STARTED', message: 'Starting trust validation\u2026' });
        P.Utils.apiFetch('/api/trust/' + P.state.sessionId + '/validate', { method: 'POST' })
            .done(function () {
                startTrustPolling();
            })
            .fail(function () {
                P.state.trustValidationInProgress = false;
                P.Utils.toast('Trust validation failed to start', 'danger');
            });
    }

    function startTrustPolling() {
        if (trustPollTimer) clearInterval(trustPollTimer);
        trustPollTimer = setInterval(function () {
            P.Utils.apiFetch('/api/trust/' + P.state.sessionId + '/status')
                .done(function (statusData) {
                    updateTrustProgress(statusData);
                    if (statusData.completed) {
                        clearInterval(trustPollTimer);
                        trustPollTimer = null;
                        P.state.trustValidationInProgress = false;
                        loadTrustResult();
                    }
                });
        }, 1500);
    }

    function loadTrustResult() {
        P.Utils.apiFetch('/api/trust/' + P.state.sessionId + '/result')
            .done(function (result) {
                if (result) {
                    P.state.signatureData = result;
                    P.state.trustValidationResult = result;
                    // renderSignatures reads P.state.trustValidationResult to build the correct initial state
                    renderSignatures(result);
                }
            });
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

        // Certificate chain node expansion
        $c.find('.sig-cert-chain-node-header').off('click').on('click', function (e) {
            e.stopPropagation();
            $(this).closest('.sig-cert-chain-node').toggleClass('expanded');
        });

        // Revision graph markers — click to scroll to signature card
        $c.find('.sig-revision-marker').off('click').on('click', function () {
            var idx = parseInt($(this).data('sig-marker'), 10);
            var $card = $('.sig-card[data-sig-index="' + idx + '"]');
            if ($card.length) {
                $card.addClass('expanded');
                $card[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        });

        // Revision lane click
        $c.find('.sig-revision-lane').off('click').on('click', function () {
            var idx = parseInt($(this).data('sig-index'), 10);
            var $card = $('.sig-card[data-sig-index="' + idx + '"]');
            if ($card.length) {
                $card.addClass('expanded');
                $card[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        });
    }

    function bindTrustValidation($c) {
        $c.find('.sig-validate-trust-btn').off('click').on('click', function () {
            triggerTrustValidation();
        });

        $c.find('.sig-trust-refresh-btn').off('click').on('click', function () {
            P.state.trustValidationResult = null;
            triggerTrustValidation();
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

    function formatAge(minutes) {
        if (minutes < 60) return minutes + 'm';
        var h = Math.floor(minutes / 60);
        var m = minutes % 60;
        return h + 'h ' + m + 'm';
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
