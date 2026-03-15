/**
 * PDFalyzer Studio – Fonts tab: loadFonts, renderFontDiagnostics, summary helpers.
 */
PDFalyzer.FontsTab = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;
    var fontDiagnosticsState = _p.fontDiagnosticsState;

    function loadFonts() {
        P.GlyphUI.clearFontUsageHighlights({ resetGlyphToggles: true });
        P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/diagnostics')
            .done(function (data) {
                fontDiagnosticsState.data = data || { fonts: [] };
                renderFontDiagnostics();
            })
            .fail(function () { P.Utils.toast('Font analysis error', 'danger'); });
    }

    function renderFontDiagnostics() {
        var model = fontDiagnosticsState.data || { fonts: [] };
        var allFonts = Array.isArray(model.fonts) ? model.fonts.slice() : [];
        var fonts = allFonts.slice();
        var focusedMode = fontDiagnosticsState.focusObj != null && fontDiagnosticsState.focusGen != null;
        var focusedKey = null;
        var focusedDetail = null;

        if (focusedMode) {
            var focusObj = parseInt(fontDiagnosticsState.focusObj, 10);
            var focusGen = parseInt(fontDiagnosticsState.focusGen, 10);
            focusedKey = getFocusMetaKey(focusObj, focusGen);
            focusedDetail = fontDiagnosticsState.detailCache[focusedKey] || null;
            fonts = allFonts.filter(function (f) {
                return parseInt(f.objectNumber, 10) === focusObj && parseInt(f.generationNumber || 0, 10) === focusGen;
            });
            if (!fonts.length) {
                focusedMode = false;
                fontDiagnosticsState.focusObj = null;
                fontDiagnosticsState.focusGen = null;
                focusedKey = null;
                focusedDetail = null;
            }
        }

        if (!focusedMode && fontDiagnosticsState.filterIssuesOnly) {
            fonts = fonts.filter(function (f) {
                return (f.issues && f.issues.length) || (f.unmappedUsedCodes > 0) || (f.unencodableUsedChars > 0);
            });
        }

        if (!focusedMode) {
            sortFontRows(fonts, fontDiagnosticsState.sort);
        }

        var $c = $('#treeContent');
        if (!model.fonts || !model.fonts.length) {
            $c.html('<div class="text-muted text-center mt-3">No fonts found</div>');
            return;
        }

        var html = '<div class="font-diag-wrap">';
        if (!focusedMode) {
            var notEmbeddedCount = (model.fonts || []).filter(function (f) { return !f.embedded; }).length;
            html += '<div class="font-diag-stats">' +
                metricCard('Fonts', model.totalFonts || model.fonts.length, 'fa-letter-case', 'Total unique fonts found across all page and XObject resources.') +
                metricCard('With issues', model.fontsWithIssues || 0, 'fa-alert-triangle', 'Fonts flagged with one or more potential rendering, mapping, or embedding problems.') +
                metricCard('Missing glyphs', model.fontsWithMissingGlyphs || 0, 'fa-help-circle', 'Fonts with missing glyph chars. This metric counts used character codes that have no Unicode mapping.') +
                metricCard('Encoding issues', model.fontsWithEncodingProblems || 0, 'fa-code', 'Fonts where used character codes have no Unicode mapping (typically ToUnicode/encoding gaps).') +
                metricCard('Not embedded', notEmbeddedCount, 'fa-unlink', 'Fonts not embedded in the PDF. Rendering depends on viewer font substitution.') +
                '</div>' +
                '<div class="font-diag-controls"></div>';
        } else {
            html += '<div class="font-diag-controls font-diag-controls-focus">' +
                '<div class="text-muted" style="font-size:12px;"><i class="fas fa-microscope me-1"></i>Focused diagnostics mode</div>' +
                '<button id="fontDiagExitFocus" class="btn btn-outline-accent btn-sm"><i class="fas fa-arrow-left me-1"></i>Back to all fonts</button>' +
                '</div>';
        }

        if (!focusedMode) {
            html += buildFontTableHtml(fonts);
        } else {
            var focusedFont = fonts.length ? fonts[0] : null;
            if (focusedFont) {
                html += buildFocusedFontCard(focusedFont, getFocusedFontMeta(focusedFont));
            }
        }

        html += '<div class="font-diag-detail-shell">' +
            '<div id="fontDiagHint" class="font-diag-detail-hint text-muted">Click a font row to open the glyph mapping table.</div>' +
            '<div id="fontDiagDetail" class="font-diag-detail-host">' + (focusedMode && !focusedDetail ? '<div class="text-muted">Loading focused glyph mappings...</div>' : '') + '</div>' +
            '</div>' +
            '</div>';

        $c.html(html);
        bindFontDiagnosticsControls();

        $c.find('.font-usage-btn').on('click', function () {
            var obj = parseInt($(this).data('obj'), 10);
            var gen = parseInt($(this).data('gen'), 10);
            P.GlyphUI.clearFontUsageHighlights({ resetGlyphToggles: true });
            P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen)
                .done(function (areas) {
                    var usage = P.GlyphUI.showFontUsageHighlights(areas || []);
                    P.Utils.toast('Found: ' + usage.found + ' | Highlighted: ' + usage.highlighted, 'info');
                })
                .fail(function () {
                    P.Utils.toast('Failed to load font usage', 'danger');
                });
        });

        $c.find('.font-diag-row').on('click', function (ev) {
            if ($(ev.target).closest('button, a, input, select, label').length) return;
            var obj = parseInt($(this).data('obj'), 10);
            var gen = parseInt($(this).data('gen'), 10);
            if (obj >= 0) {
                fontDiagnosticsState.focusObj = obj;
                fontDiagnosticsState.focusGen = gen;
                fontDiagnosticsState.detailLoadedKey = null;
                renderFontDiagnostics();
            } else {
                P.FontDetail.loadFontDiagnosticsDetail(obj, gen);
            }
        });

        if (focusedMode && focusedDetail) {
            fontDiagnosticsState.detailLoadedKey = focusedKey;
            P.FontDetail.renderFontDiagnosticsDetail(focusedDetail);
        }

        if (focusedMode && fonts.length) {
            var focused = fonts[0];
            if (focused && focused.objectNumber >= 0) {
                var meta = getFocusedFontMeta(focused);
                var fKey = getFocusMetaKey(focused.objectNumber, focused.generationNumber || 0);
                if ((!focusedDetail || !meta || meta.glyphMappingsTotal == null || meta.glyphMappingsTotal < 0) &&
                    fontDiagnosticsState.detailLoadingKey !== fKey &&
                    fontDiagnosticsState.detailLoadedKey !== fKey) {
                    P.FontDetail.loadFontDiagnosticsDetail(focused.objectNumber, focused.generationNumber || 0);
                }
            }
        }

        P.GlyphUI.applyGlobalDiagnosticsTableInteractions();
    }

    function buildFontTableHtml(fonts) {
        var html = '<div class="font-table-wrap"><table class="font-table"><thead><tr>' +
            '<th title="Resolved PDF font name.">Font</th>' +
            '<th title="Indirect object reference used to inspect raw font dictionary and stream.">Object</th>' +
            '<th title="Font subtype, embedding, and encoding.">Type / Encoding</th>' +
            '<th title="Total text positions and distinct used character codes.">Glyphs</th>' +
            '<th title="How many used character codes map to Unicode and where mapping gaps exist.">Coverage</th>' +
            '<th title="Overall diagnostic status for this font.">Status</th>' +
            '<th title="Detected risk signals and suggested remediations.">Issues</th>' +
            '<th title="Actions for usage overlay and extraction.">Actions</th>' +
            '</tr></thead><tbody>';

        fonts.forEach(function (f) {
            var embIcon = f.embedded
                ? '<i class="fas fa-check-circle text-success"></i>'
                : '<i class="fas fa-times-circle text-danger"></i>';
            var subIcon = f.subset
                ? '<i class="fas fa-check text-muted"></i>'
                : '<i class="fas fa-minus text-muted"></i>';
            var subsetBadge = '';
            if (f.subset) {
                subsetBadge = f.subsetComplete
                    ? ' <span class="subset-badge subset-badge-ok" title="All used glyphs present in subset."><i class="fas fa-puzzle-piece"></i> OK</span>'
                    : ' <span class="subset-badge subset-badge-warn" title="Subset may be incomplete — not all used glyphs found."><i class="fas fa-puzzle-piece"></i> incomplete</span>';
            }
            var issueHtml = (f.issues && f.issues.length > 0)
                ? '<i class="fas fa-exclamation-triangle text-warning me-1" title="Potential issues detected for this font."></i>' +
                  '<span title="' + P.Utils.escapeHtml(f.issues.join('; ')) + '">' +
                  f.issues.length + '</span>'
                : '<i class="fas fa-check text-success" title="No immediate issues detected by current heuristics."></i>';
            var actions = '';
            if (f.objectNumber >= 0) {
                actions += '<button class="btn btn-xs btn-outline-accent me-1 font-usage-btn" ' +
                    'data-obj="' + f.objectNumber + '" data-gen="' + (f.generationNumber || 0) + '" ' +
                    'title="Draw usage overlays in the PDF viewer for this font object."><i class="fas fa-highlighter"></i></button>';
                if (f.embedded) {
                    var fontFt = (f.fontType || '').replace(/\s+/g, '').toUpperCase() || 'BIN';
                    actions += '<a class="btn btn-xs btn-outline-accent" target="_blank" ' +
                        'href="/api/fonts/' + P.state.sessionId + '/extract/' +
                        f.objectNumber + '/' + (f.generationNumber || 0) +
                        '" title="Download the embedded font program stream."><i class="fas fa-download"></i><span class="dl-filetype">' + P.Utils.escapeHtml(fontFt) + '</span></a>';
                }
            }
            var issueDetail = buildIssueDetail(f);
            var pageText = (f.pagesUsed || []).length ? 'P' + f.pagesUsed.map(function (p) { return p + 1; }).join(',') : '-';
            var objRef = (f.objectNumber >= 0) ? (f.objectNumber + ' ' + (f.generationNumber || 0) + ' R') : '(direct)';
            var coverage = (f.mappedUsedCodes || 0) + ' / ' + (f.distinctUsedCodes || 0);
            var glyphCount = (f.glyphCount || 0);
            var usedGlyphs = (f.distinctUsedCodes || 0);
            var subtypeText = f.fontType || '';

            var statusIcon, statusText, rowStatusClass;
            if (!f.embedded) {
                statusIcon = '<i class="fas fa-times-circle text-danger"></i>';
                statusText = 'Not embedded';
                rowStatusClass = 'font-diag-row-danger';
            } else if ((f.unmappedUsedCodes || 0) > 0 || (f.unencodableUsedChars || 0) > 0) {
                statusIcon = '<i class="fas fa-exclamation-triangle text-warning"></i>';
                statusText = 'Missing glyphs';
                rowStatusClass = 'font-diag-row-warn';
            } else if (f.subset && f.subsetComplete === false) {
                statusIcon = '<i class="fas fa-puzzle-piece" style="color:#fadb14;"></i>';
                statusText = 'Subset gap';
                rowStatusClass = 'font-diag-row-warn';
            } else {
                statusIcon = '<i class="fas fa-check-circle text-success"></i>';
                statusText = 'OK';
                rowStatusClass = 'font-diag-row-ok';
            }

            html += '<tr class="font-diag-row ' + rowStatusClass + '" data-obj="' + (f.objectNumber >= 0 ? f.objectNumber : '') + '" data-gen="' + (f.generationNumber || 0) + '">' +
                '<td title="Font name reported by PDFBox for this resource.">' + P.Utils.escapeHtml(f.fontName || '(unknown)') + '</td>' +
                '<td class="font-obj-ref" title="' + (f.objectNumber >= 0 ? 'Indirect object can be inspected and extracted.' : 'Direct or unresolved reference.') + '">' + P.Utils.escapeHtml(objRef) + '</td>' +
                '<td>' + P.Utils.escapeHtml(subtypeText) + '<div class="text-muted" style="font-size:11px;">' +
                '<span title="Embedded fonts travel with the PDF for reliable rendering.">' + embIcon + ' emb</span> &nbsp; ' +
                '<span title="Subset fonts include only some glyphs.">' + subIcon + ' sub</span>' + subsetBadge + '<br>' +
                P.Utils.escapeHtml(f.encoding || '(no encoding)') +
                '</div></td>' +
                '<td><span class="badge text-bg-secondary" title="Total text positions / distinct used codes.">' + glyphCount + '</span>' +
                '<div class="text-muted" style="font-size:11px;">' + usedGlyphs + ' used &middot; ' + P.Utils.escapeHtml(pageText) + '</div></td>' +
                '<td><span class="badge ' + ((f.unmappedUsedCodes || 0) > 0 ? 'text-bg-danger' : 'text-bg-success') + '" title="Coverage = mapped used codes / distinct used codes.">Mapped ' + P.Utils.escapeHtml(coverage) + '</span>' +
                ((f.unencodableUsedChars || 0) > 0 ? '<div class="text-warning" style="font-size:11px;" title="Used codes with no Unicode mapping.">Missing: ' + f.unencodableUsedChars + '</div>' : '') +
                '</td>' +
                '<td>' + statusIcon + ' <span style="font-size:11px;">' + statusText + '</span></td>' +
                '<td>' + issueHtml + issueDetail + '</td>' +
                '<td>' + actions + '</td></tr>';
        });

        html += '</tbody></table></div>';
        return html;
    }

    function getFocusMetaKey(obj, gen) {
        return String(obj) + ':' + String(gen || 0);
    }

    function getFocusedFontMeta(fontRow) {
        if (!fontRow || fontRow.objectNumber < 0) return null;
        return fontDiagnosticsState.focusMeta[getFocusMetaKey(fontRow.objectNumber, fontRow.generationNumber || 0)] || null;
    }

    function metricCard(label, value, icon, tooltip) {
        var colorClass = 'text-bg-secondary';
        if (label === 'With issues' || label === 'Missing glyphs' || label === 'Encoding issues') {
            colorClass = (value === 0) ? 'text-bg-success' : 'text-bg-danger';
        } else if (label === 'Fonts') {
            colorClass = 'text-bg-primary';
        } else if (label === 'Not embedded') {
            colorClass = (value === 0) ? 'text-bg-success' : 'text-bg-danger';
        }
        return '<span class="badge ' + colorClass + '" title="' + P.Utils.escapeHtml(tooltip || '') + '">' +
            '<i class="fas ' + icon + ' me-1"></i>' + P.Utils.escapeHtml(label) + ': <span>' + value + '</span>' +
            '</span>';
    }

    function sortFontRows(fonts, mode) {
        fonts.sort(function (a, b) {
            if (mode === 'name-asc') {
                return String(a.fontName || '').localeCompare(String(b.fontName || ''));
            }
            if (mode === 'usage-desc') {
                return (b.glyphCount || 0) - (a.glyphCount || 0);
            }
            if (mode === 'unmapped-desc') {
                return (b.unmappedUsedCodes || 0) - (a.unmappedUsedCodes || 0);
            }
            var issueCmp = (b.issues || []).length - (a.issues || []).length;
            if (issueCmp !== 0) return issueCmp;
            return (b.unmappedUsedCodes || 0) - (a.unmappedUsedCodes || 0);
        });
    }

    function bindFontDiagnosticsControls() {
        $('#fontDiagExitFocus').off('click').on('click', function () {
            fontDiagnosticsState.focusObj = null;
            fontDiagnosticsState.focusGen = null;
            renderFontDiagnostics();
        });
    }

    // ======================== FOCUSED FONT CARD HELPERS ========================

    function buildIssueDetail(fontRow) {
        var issueParts = [];
        if (fontRow.usageContexts && fontRow.usageContexts.length) {
            issueParts.push('Context: ' + P.Utils.escapeHtml(fontRow.usageContexts.join(', ')));
        }
        if (fontRow.fixSuggestion) {
            issueParts.push('Fix: ' + P.Utils.escapeHtml(fontRow.fixSuggestion));
        }
        if (!issueParts.length) return '';
        return '<div class="text-muted" style="font-size:11px;">' + issueParts.join('<br>') + '</div>';
    }

    function formatBytesHuman(bytes) {
        if (!(bytes >= 0)) return 'n/a';
        var units = ['B', 'KB', 'MB', 'GB'];
        var size = bytes;
        var idx = 0;
        while (size >= 1024 && idx < units.length - 1) { size /= 1024; idx += 1; }
        return (idx === 0 ? Math.round(size) : (Math.round(size * 10) / 10)) + ' ' + units[idx];
    }

    function buildFocusedInfoColumn(fontRow, meta) {
        var glyphTotal = meta && meta.glyphMappingsTotal >= 0 ? meta.glyphMappingsTotal : null;
        var usedDistinct = fontRow.distinctUsedCodes || 0;
        var usedGlyphsText = glyphTotal != null ? (usedDistinct + ' / ' + glyphTotal) : (usedDistinct + ' / n/a');
        var sizeBytes = meta && meta.fontProgramBytes >= 0 ? meta.fontProgramBytes : null;
        var sizeHuman = sizeBytes == null ? 'n/a' : formatBytesHuman(sizeBytes) + ' (' + sizeBytes + ' B)';
        var licenseLabel = meta && meta.license ? meta.license : 'unknown (not exposed)';
        var subtype = meta && meta.subtype ? meta.subtype : (fontRow.fontType || '');
        var baseFont = meta && meta.baseFont ? meta.baseFont : 'n/a';
        var descFont = meta && meta.descendantFont ? meta.descendantFont : 'n/a';
        var dictionaryKeys = meta && meta.dictionaryKeys >= 0 ? meta.dictionaryKeys : 'n/a';
        return '<details><summary>Additional font info</summary>' +
            '<div class="text-muted" style="font-size:11px;line-height:1.35;margin-top:6px;">' +
            '<div><strong>Subtype:</strong> ' + P.Utils.escapeHtml(subtype) + '</div>' +
            '<div><strong>BaseFont:</strong> ' + P.Utils.escapeHtml(baseFont) + '</div>' +
            '<div><strong>Descendant:</strong> ' + P.Utils.escapeHtml(descFont) + '</div>' +
            '<div><strong>Glyphs used/known:</strong> ' + P.Utils.escapeHtml(String(usedGlyphsText)) + '</div>' +
            '<div><strong>Embedded font size:</strong> ' + P.Utils.escapeHtml(sizeHuman) + '</div>' +
            '<div><strong>Dictionary keys:</strong> ' + P.Utils.escapeHtml(String(dictionaryKeys)) + '</div>' +
            '<div><strong>License:</strong> ' + P.Utils.escapeHtml(licenseLabel) + '</div>' +
            '<div><strong>Encoding:</strong> ' + P.Utils.escapeHtml(fontRow.encoding || 'n/a') + '</div>' +
            '<div><strong>Embedded:</strong> ' + (fontRow.embedded ? 'yes' : 'no') + ' &nbsp; <strong>Subset:</strong> ' + (fontRow.subset ? 'yes' : 'no') + '</div>' +
            '</div></details>';
    }

    function buildFocusedFontCard(fontRow, meta) {
        var embIcon = fontRow.embedded ? '<i class="fas fa-check-circle text-success"></i>' : '<i class="fas fa-times-circle text-danger"></i>';
        var subIcon = fontRow.subset ? '<i class="fas fa-check text-muted"></i>' : '<i class="fas fa-minus text-muted"></i>';
        var issueHtml = (fontRow.issues && fontRow.issues.length > 0)
            ? '<i class="fas fa-exclamation-triangle text-warning me-1" title="Potential issues detected for this font."></i><span title="' + P.Utils.escapeHtml(fontRow.issues.join('; ')) + '">' + fontRow.issues.length + '</span>'
            : '<i class="fas fa-check text-success" title="No immediate issues detected by current heuristics."></i>';
        var issueDetail = buildIssueDetail(fontRow);
        var objRef = (fontRow.objectNumber >= 0) ? (fontRow.objectNumber + ' ' + (fontRow.generationNumber || 0) + ' R') : '(direct)';
        var pageText = (fontRow.pagesUsed || []).length ? ('P' + fontRow.pagesUsed.map(function (p) { return p + 1; }).join(',')) : '-';
        var coverage = (fontRow.mappedUsedCodes || 0) + ' / ' + (fontRow.distinctUsedCodes || 0);
        var actions = '';
        if (fontRow.objectNumber >= 0) {
            actions += '<button class="btn btn-xs btn-outline-accent me-1 font-usage-btn" data-obj="' + fontRow.objectNumber + '" data-gen="' + (fontRow.generationNumber || 0) + '" title="Draw usage overlays in the PDF viewer for this font object."><i class="fas fa-highlighter"></i></button>';
            if (fontRow.embedded) {
                var fontFt2 = (fontRow.fontType || '').replace(/\s+/g, '').toUpperCase() || 'BIN';
                actions += '<a class="btn btn-xs btn-outline-accent" target="_blank" href="/api/fonts/' + P.state.sessionId + '/extract/' + fontRow.objectNumber + '/' + (fontRow.generationNumber || 0) + '" title="Download the embedded font program stream."><i class="fas fa-download"></i><span class="dl-filetype">' + P.Utils.escapeHtml(fontFt2) + '</span></a>';
            }
        }
        return '<div class="font-focus-card">' +
            '<div class="font-focus-card-main">' +
            '<div class="font-focus-title">' + P.Utils.escapeHtml(fontRow.fontName || '(unknown)') + '</div>' +
            '<div class="font-focus-grid">' +
            '<div><strong>Object:</strong> <span class="font-obj-ref">' + P.Utils.escapeHtml(objRef) + '</span></div>' +
            '<div><strong>Subtype:</strong> ' + P.Utils.escapeHtml((meta && meta.subtype) ? meta.subtype : (fontRow.fontType || '')) + '</div>' +
            '<div><strong>Glyphs:</strong> ' + (fontRow.glyphCount || 0) + '</div>' +
            '<div><strong>Glyphs used:</strong> ' + (fontRow.distinctUsedCodes || 0) + '</div>' +
            '<div><strong>Coverage:</strong> <span class="badge ' + ((fontRow.unmappedUsedCodes || 0) > 0 ? 'text-bg-danger' : 'text-bg-success') + '">Mapped ' + P.Utils.escapeHtml(coverage) + '</span></div>' +
            '<div><strong>Usage:</strong> <span class="text-muted">Pages ' + P.Utils.escapeHtml(pageText) + '</span></div>' +
            '<div><strong>Flags:</strong> ' + embIcon + ' emb &nbsp; ' + subIcon + ' sub' +
            (fontRow.subset ? (fontRow.subsetComplete ? ' <span class="subset-badge subset-badge-ok" title="All used glyphs present in subset."><i class="fas fa-puzzle-piece"></i> OK</span>' : ' <span class="subset-badge subset-badge-warn" title="Subset may be incomplete."><i class="fas fa-puzzle-piece"></i> incomplete</span>') : '') +
            '</div>' +
            '<div><strong>Encoding:</strong> <span class="text-muted">' + P.Utils.escapeHtml(fontRow.encoding || '(no encoding)') + '</span></div>' +
            '</div>' +
            '<div id="fontFocusEncodingDetails"></div>' +
            '<div class="font-focus-issues"><strong>Issues:</strong> ' + issueHtml + issueDetail + '</div>' +
            (actions ? '<div class="font-focus-actions">' + actions + '</div>' : '') +
            '</div>' +
            '<div class="font-focus-card-side">' + buildFocusedInfoColumn(fontRow, meta) + '</div>' +
            '</div>';
    }

    return {
        loadFonts: loadFonts,
        renderFontDiagnostics: renderFontDiagnostics,
        getFocusMetaKey: getFocusMetaKey,
        getFocusedFontMeta: getFocusedFontMeta,
        buildIssueDetail: buildIssueDetail,
        buildFocusedFontCard: buildFocusedFontCard
    };
})(jQuery, PDFalyzer);
