/**
 * PDFalyzer Studio – tab switching and per-tab content loading.
 */
PDFalyzer.Tabs = (function ($, P) {
    'use strict';

    var veraPdfExportHtml = '';
    var veraPdfExportTitle = 'veraPDF Report';
    var fontDiagnosticsState = {
        data: null,
        filterIssuesOnly: false,
        sort: 'issues-desc',
        focusObj: null,
        focusGen: null,
        focusMeta: {},
        detailLoadingKey: null,
        detailLoadedKey: null,
        detailCache: {}
    };
    var activeGlyphObserver = null;
    var glyphMeasureCanvas = null;
    var activeFontDetailState = null;
    var activeGlyphCanvasState = null;
    var activeFontUsageAreas = [];
    var activeGlyphHighlightSelections = {};
    var glyphHighlightPalette = ['#ff4d4f', '#fa8c16', '#fadb14', '#52c41a', '#13c2c2', '#1677ff', '#722ed1', '#eb2f96', '#a0d911', '#2f54eb'];
    var nextGeneratedGlyphColorIndex = glyphHighlightPalette.length;

    function init() {
        $('.tab-btn').on('click', function () {
            $('.tab-btn').removeClass('active');
            $(this).addClass('active');
            switchTab($(this).data('tab'));
        });

        $(document).off('pdfviewer:rendered.fontusage').on('pdfviewer:rendered.fontusage', function () {
            if (!activeFontUsageAreas || !activeFontUsageAreas.length) return;
            showFontUsageHighlights(activeFontUsageAreas, { clearExisting: true, persist: false });
        });
    }

    function isTreeTab(tab) {
        return tab === 'structure' || tab === 'forms' || tab === 'bookmarks' ||
            tab === 'rawcos' || tab === 'attachments';
    }

    function captureTreeViewStateForTab(tab) {
        if (!isTreeTab(tab) || !P.Tree || !P.Tree.captureViewState) return;
        var viewState = P.Tree.captureViewState();
        if (!viewState) return;
        if (!P.state.tabTreeViewStates) P.state.tabTreeViewStates = {};
        P.state.tabTreeViewStates[tab] = viewState;
    }

    function getTreeViewStateForTab(tab) {
        if (!P.state.tabTreeViewStates) return null;
        return P.state.tabTreeViewStates[tab] || null;
    }

    function dismissTransientHoverPopups() {
        $('.image-tooltip-preview').remove();
        $('.glyph-tooltip-preview').remove();
        $(document).off('mousemove.imgtooltip');
        $(document).off('mousemove.glyphtooltip');
    }

    function updateStructureSearchControl(tab) {
        var isStructure = tab === 'structure';
        var hasSession = !!P.state.sessionId;
        var $tools = $('.tree-search-tools');
        var $search = $('#searchInput');

        $tools.toggleClass('d-none', !isStructure);
        $search.prop('disabled', !isStructure || !hasSession);

        if (!isStructure && $search.val()) {
            $search.val('');
        }
    }

    function switchTab(tab) {
        if (!P.state.treeData || !P.state.sessionId) return;

        dismissTransientHoverPopups();

        var previousTab = P.state.currentTab;
        captureTreeViewStateForTab(previousTab);

        var viewState = getTreeViewStateForTab(tab);
        updateStructureSearchControl(tab);

        switch (tab) {
            case 'structure':   P.Tree.render(P.state.treeData, { viewState: viewState }); break;
            case 'forms':       P.Tree.renderSubtree(P.state.treeData, 'acroform', { viewState: viewState }); break;
            case 'fonts':       loadFonts(); break;
            case 'validation':  loadValidation(); break;
            case 'rawcos':      loadRawCos(viewState); break;
            case 'bookmarks':   P.Tree.renderSubtree(P.state.treeData, 'bookmarks', { viewState: viewState }); break;
            case 'attachments': loadAttachments(); break;
        }

        P.state.currentTab = tab;
    }

    // ======================== FONTS TAB ========================

    function loadFonts() {
        clearFontUsageHighlights({ resetGlyphToggles: true });
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
            html += '<div class="font-diag-stats">' +
                metricCard('Fonts', model.totalFonts || model.fonts.length, 'fa-font', 'Total unique fonts found across all page and XObject resources.') +
                metricCard('With issues', model.fontsWithIssues || 0, 'fa-exclamation-triangle', 'Fonts flagged with one or more potential rendering, mapping, or embedding problems.') +
                metricCard('Missing glyphs', model.fontsWithMissingGlyphs || 0, 'fa-question-circle', 'Fonts where extracted text indicates characters that this font may not encode reliably.') +
                metricCard('Encoding issues', model.fontsWithEncodingProblems || 0, 'fa-code', 'Fonts where used character codes are missing Unicode mapping entries (often ToUnicode issues).') +
                '</div>' +
                '<div class="font-diag-controls"></div>';
        } else {
            html += '<div class="font-diag-controls font-diag-controls-focus">' +
                '<div class="text-muted" style="font-size:12px;"><i class="fas fa-microscope me-1"></i>Focused diagnostics mode</div>' +
                '<button id="fontDiagExitFocus" class="btn btn-outline-accent btn-sm"><i class="fas fa-arrow-left me-1"></i>Back to all fonts</button>' +
                '</div>';
        }

        if (!focusedMode) {
            html += '<div class="font-table-wrap"><table class="font-table"><thead><tr>' +
                '<th title="Resolved PDF font name.">Font</th>' +
                '<th title="Indirect object reference used to inspect raw font dictionary and stream.">Object</th>' +
                '<th title="Font subtype.">Subtype</th>' +
                '<th title="Observed text glyph positions with this font in extracted text flow.">Glyphs</th>' +
                '<th title="Distinct used character codes with this font.">Glyphs used</th>' +
                '<th title="Observed glyph usage and where this font appears in the document.">Usage</th>' +
                '<th title="How many used character codes map to Unicode and where mapping gaps exist.">Coverage</th>' +
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
                        actions += '<a class="btn btn-xs btn-outline-accent" target="_blank" ' +
                            'href="/api/fonts/' + P.state.sessionId + '/extract/' +
                            f.objectNumber + '/' + (f.generationNumber || 0) +
                            '" title="Download the embedded font program stream."><i class="fas fa-download"></i></a>';
                    }
                }
                var issueDetail = buildIssueDetail(f);
                var pageText = (f.pagesUsed || []).length ? 'P' + f.pagesUsed.map(function (p) { return p + 1; }).join(',') : '-';
                var objRef = (f.objectNumber >= 0) ? (f.objectNumber + ' ' + (f.generationNumber || 0) + ' R') : '(direct)';
                var coverage = (f.mappedUsedCodes || 0) + ' / ' + (f.distinctUsedCodes || 0);
                var glyphCount = (f.glyphCount || 0);
                var usedGlyphs = (f.distinctUsedCodes || 0);
                var subtypeText = f.fontType || '';
                html += '<tr class="font-diag-row" data-obj="' + (f.objectNumber >= 0 ? f.objectNumber : '') + '" data-gen="' + (f.generationNumber || 0) + '">' +
                    '<td title="Font name reported by PDFBox for this resource.">' + P.Utils.escapeHtml(f.fontName || '(unknown)') + '</td>' +
                    '<td class="font-obj-ref" title="' + (f.objectNumber >= 0 ? 'Indirect object can be inspected and extracted.' : 'Direct or unresolved reference: deep object-specific diagnostics may be limited.') + '">' + P.Utils.escapeHtml(objRef) + '</td>' +
                    '<td>' + P.Utils.escapeHtml(subtypeText) + '<div class="text-muted" style="font-size:11px;">' +
                    '<span title="Embedded fonts travel with the PDF for reliable rendering.">' + embIcon + ' embedded</span> &nbsp; ' +
                    '<span title="Subset fonts include only some glyphs.">' + subIcon + ' subset</span><br>' +
                    P.Utils.escapeHtml(f.encoding || '(no encoding)') +
                    '</div></td>' +
                    '<td><span class="badge text-bg-secondary" title="Count of text positions observed with this font in extracted text flow.">' + glyphCount + '</span></td>' +
                    '<td><span class="badge text-bg-secondary" title="Distinct used character codes observed for this font.">' + usedGlyphs + '</span></td>' +
                    '<td><div class="text-muted" style="font-size:11px;">Pages: ' + P.Utils.escapeHtml(pageText) + '</div></td>' +
                    '<td><span class="badge ' + ((f.unmappedUsedCodes || 0) > 0 ? 'text-bg-danger' : 'text-bg-success') + '" title="Mapped used codes / distinct used codes.">Mapped ' + P.Utils.escapeHtml(coverage) + '</span>' +
                    ((f.unencodableUsedChars || 0) > 0 ? '<div class="text-warning" style="font-size:11px;" title="Distinct used characters that cannot be encoded by this font.">Missing glyph chars: ' + f.unencodableUsedChars + '</div>' : '') +
                    '</td>' +
                    '<td>' + issueHtml + issueDetail + '</td>' +
                    '<td>' + actions + '</td></tr>';
            });

            html += '</tbody></table></div>';
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
            clearFontUsageHighlights({ resetGlyphToggles: true });
            P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen)
                .done(function (areas) {
                    var usage = showFontUsageHighlights(areas || []);
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
                loadFontDiagnosticsDetail(obj, gen);
            }
        });

        if (focusedMode && focusedDetail) {
            fontDiagnosticsState.detailLoadedKey = focusedKey;
            renderFontDiagnosticsDetail(focusedDetail);
        }

        if (focusedMode && fonts.length) {
            var focused = fonts[0];
            if (focused && focused.objectNumber >= 0) {
                var meta = getFocusedFontMeta(focused);
                var focusedKey = getFocusMetaKey(focused.objectNumber, focused.generationNumber || 0);
                if ((!focusedDetail || !meta || meta.glyphMappingsTotal == null || meta.glyphMappingsTotal < 0) &&
                    fontDiagnosticsState.detailLoadingKey !== focusedKey &&
                    fontDiagnosticsState.detailLoadedKey !== focusedKey) {
                    loadFontDiagnosticsDetail(focused.objectNumber, focused.generationNumber || 0);
                }
            }
        }

        applyGlobalDiagnosticsTableInteractions();
    }

    function getFocusMetaKey(obj, gen) {
        return String(obj) + ':' + String(gen || 0);
    }

    function getFocusedFontMeta(fontRow) {
        if (!fontRow || fontRow.objectNumber < 0) return null;
        return fontDiagnosticsState.focusMeta[getFocusMetaKey(fontRow.objectNumber, fontRow.generationNumber || 0)] || null;
    }

    function formatBytesHuman(bytes) {
        if (!(bytes >= 0)) return 'n/a';
        var units = ['B', 'KB', 'MB', 'GB'];
        var size = bytes;
        var idx = 0;
        while (size >= 1024 && idx < units.length - 1) {
            size /= 1024;
            idx += 1;
        }
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
        var embIcon = fontRow.embedded
            ? '<i class="fas fa-check-circle text-success"></i>'
            : '<i class="fas fa-times-circle text-danger"></i>';
        var subIcon = fontRow.subset
            ? '<i class="fas fa-check text-muted"></i>'
            : '<i class="fas fa-minus text-muted"></i>';
        var issueHtml = (fontRow.issues && fontRow.issues.length > 0)
            ? '<i class="fas fa-exclamation-triangle text-warning me-1" title="Potential issues detected for this font."></i>' +
              '<span title="' + P.Utils.escapeHtml(fontRow.issues.join('; ')) + '">' +
              fontRow.issues.length + '</span>'
            : '<i class="fas fa-check text-success" title="No immediate issues detected by current heuristics."></i>';
        var issueDetail = buildIssueDetail(fontRow);
        var objRef = (fontRow.objectNumber >= 0)
            ? (fontRow.objectNumber + ' ' + (fontRow.generationNumber || 0) + ' R')
            : '(direct)';
        var pageText = (fontRow.pagesUsed || []).length
            ? ('P' + fontRow.pagesUsed.map(function (p) { return p + 1; }).join(','))
            : '-';
        var coverage = (fontRow.mappedUsedCodes || 0) + ' / ' + (fontRow.distinctUsedCodes || 0);
        var actions = '';
        if (fontRow.objectNumber >= 0) {
            actions += '<button class="btn btn-xs btn-outline-accent me-1 font-usage-btn" ' +
                'data-obj="' + fontRow.objectNumber + '" data-gen="' + (fontRow.generationNumber || 0) + '" ' +
                'title="Draw usage overlays in the PDF viewer for this font object."><i class="fas fa-highlighter"></i></button>';
            if (fontRow.embedded) {
                actions += '<a class="btn btn-xs btn-outline-accent" target="_blank" ' +
                    'href="/api/fonts/' + P.state.sessionId + '/extract/' +
                    fontRow.objectNumber + '/' + (fontRow.generationNumber || 0) +
                    '" title="Download the embedded font program stream."><i class="fas fa-download"></i></a>';
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
            '<div><strong>Flags:</strong> ' + embIcon + ' embedded &nbsp; ' + subIcon + ' subset</div>' +
            '<div><strong>Encoding:</strong> <span class="text-muted">' + P.Utils.escapeHtml(fontRow.encoding || '(no encoding)') + '</span></div>' +
            '</div>' +
            '<div class="font-focus-issues"><strong>Issues:</strong> ' + issueHtml + issueDetail + '</div>' +
            (actions ? '<div class="font-focus-actions">' + actions + '</div>' : '') +
            '</div>' +
            '<div class="font-focus-card-side">' + buildFocusedInfoColumn(fontRow, meta) + '</div>' +
            '</div>';
    }

    function metricCard(label, value, icon, tooltip) {
        return '<div class="font-diag-stat">' +
            '<div class="font-diag-stat-label" title="' + P.Utils.escapeHtml(tooltip || '') + '"><i class="fas ' + icon + ' me-1"></i>' + P.Utils.escapeHtml(label) + '</div>' +
            '<div class="font-diag-stat-value">' + value + '</div>' +
            '</div>';
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

    function bindFontDiagnosticsControls() {
        $('#fontDiagExitFocus').off('click').on('click', function () {
            fontDiagnosticsState.focusObj = null;
            fontDiagnosticsState.focusGen = null;
            renderFontDiagnostics();
        });
    }

    function loadFontDiagnosticsDetail(obj, gen) {
        var $target = $('#fontDiagDetail');
        if (!$target.length) return;
        if (!(obj >= 0)) {
            $target.html('<div class="text-warning"><i class="fas fa-info-circle me-1"></i>This row has no indirect object reference. Showing table-level diagnostics only (deep object dictionary/stream inspection is unavailable for direct/unresolved references).</div>');
            return;
        }
        var detailKey = getFocusMetaKey(obj, gen || 0);
        if (fontDiagnosticsState.detailLoadingKey === detailKey) return;
        fontDiagnosticsState.detailLoadingKey = detailKey;
        $target.html('<div class="text-muted"><span class="spinner-border spinner-border-sm"></span> Loading deep font diagnostics...</div>');
        P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/diagnostics/' + obj + '/' + gen)
            .done(function (detail) {
                var safeDetail = detail || {};
                storeFocusedMetaFromDetail(obj, gen, safeDetail);
                fontDiagnosticsState.detailCache[detailKey] = safeDetail;
                fontDiagnosticsState.detailLoadedKey = detailKey;
                fontDiagnosticsState.detailLoadingKey = null;
                renderFontDiagnosticsDetail(safeDetail);
                requestFocusedFontProgramSize(obj, gen);
            })
            .fail(function () {
                fontDiagnosticsState.detailLoadingKey = null;
                $target.html('<div class="text-danger"><i class="fas fa-exclamation-circle me-1"></i>Failed to load font diagnostics detail.</div>');
            });
    }

    function storeFocusedMetaFromDetail(obj, gen, detail) {
        if (!(obj >= 0)) return;
        var key = getFocusMetaKey(obj, gen || 0);
        var encoding = detail.encoding || {};
        var mappings = Array.isArray(detail.glyphMappings) ? detail.glyphMappings : [];
        var dictionary = detail.fontDictionary || {};
        var existing = fontDiagnosticsState.focusMeta[key] || {};
        fontDiagnosticsState.focusMeta[key] = {
            subtype: encoding.subtype || existing.subtype || '',
            baseFont: encoding.baseFont || existing.baseFont || '',
            descendantFont: encoding.descendantFont || existing.descendantFont || '',
            glyphMappingsTotal: mappings.length,
            dictionaryKeys: Object.keys(dictionary).length,
            fontProgramBytes: (existing.fontProgramBytes >= 0) ? existing.fontProgramBytes : -1,
            license: existing.license || ''
        };
    }

    function requestFocusedFontProgramSize(obj, gen) {
        if (!(obj >= 0)) return;
        var key = getFocusMetaKey(obj, gen || 0);
        var meta = fontDiagnosticsState.focusMeta[key];
        if (meta && meta.fontProgramBytes >= 0) return;

        fetch('/api/fonts/' + encodeURIComponent(P.state.sessionId) + '/extract/' + obj + '/' + (gen || 0), { method: 'HEAD' })
            .then(function (resp) {
                if (!resp || !resp.ok) return;
                var rawLength = resp.headers.get('content-length');
                var parsed = rawLength == null ? NaN : parseInt(rawLength, 10);
                if (!(parsed >= 0)) return;
                if (!fontDiagnosticsState.focusMeta[key]) {
                    fontDiagnosticsState.focusMeta[key] = {};
                }
                fontDiagnosticsState.focusMeta[key].fontProgramBytes = parsed;
            })
            .catch(function () {
            });
    }

    function renderFontDiagnosticsDetail(detail) {
        var f = detail.font || {};
        var encoding = detail.encoding || {};
        var mappings = Array.isArray(detail.glyphMappings) ? detail.glyphMappings : [];
        var issues = Array.isArray(detail.usedCharacterIssues) ? detail.usedCharacterIssues : [];
        var dictionary = detail.fontDictionary || {};

        var fontFamily = (f.objectNumber >= 0) ? ('pdfdiagfont_' + f.objectNumber + '_' + (f.generationNumber || 0)) : '';
        if (f.embedded && f.objectNumber >= 0) {
            ensureDiagnosticsFontFace(fontFamily, f.objectNumber, (f.generationNumber || 0));
        }

        var html = '<div class="font-diag-detail-content">' +
            '<div class="font-diag-detail-title"><strong>Glyph mapping table (' + mappings.length + ')</strong></div>' +
            '<div class="font-detail-map-filter-row">' +
            '  <div class="font-detail-map-toolbar">' +
            '    <button id="fontDetailClearHighlightsBtn" class="btn btn-outline-accent btn-sm"><i class="fas fa-eraser me-1"></i>Clear highlights</button>' +
            '  </div>' +
            '  <input id="fontDetailMapHeaderFilter" class="form-control form-control-sm table-filter-input" placeholder="Filter table..." />' +
            '</div>' +
            '<div class="font-detail-table-wrap font-detail-table-wrap-fill" id="fontDetailMapWrap"><table class="font-detail-table" id="fontDetailMapTable" data-disable-header-filter="1"><thead><tr><th title="Character code used in PDF text operators.">Code</th><th title="Lazy-rendered preview of mapped glyphs.">Glyph</th><th title="Unicode text mapped from this code.">Unicode</th><th title="Unicode code point(s) in U+XXXX format.">Hex</th><th title="Glyph width reported by font metrics.">Width</th><th title="How many times this code appears in extracted text.">Used</th><th title="Whether code maps to Unicode.">Mapped</th><th class="table-no-sort" title="Row actions.">Action</th></tr></thead><tbody id="fontDetailMapTableBody">' +
            '<tr><td colspan="8" class="text-muted">Loading rows…</td></tr>' +
            '</tbody></table></div>' +
            '</div>';

        $('#fontDiagDetail').html(html);
        $('#fontDiagDetail').closest('.font-diag-detail-shell').addClass('has-detail');
        if (activeFontDetailState && activeFontDetailState.observer && typeof activeFontDetailState.observer.disconnect === 'function') {
            activeFontDetailState.observer.disconnect();
        }
        activeFontDetailState = {
            mappings: mappings,
            issues: issues,
            font: f,
            fontFamily: fontFamily,
            pendingQuery: '',
            pendingUsedOnly: false,
            mapRenderToken: 0,
            mapRenderContext: null,
            observer: null
        };
        $('#fontDetailMapHeaderFilter').off('input').on('input', function () {
            if (!activeFontDetailState) return;
            activeFontDetailState.pendingQuery = String($(this).val() || '');
            renderMapTableNow(activeFontDetailState.pendingQuery, activeFontDetailState.pendingUsedOnly);
        });
        $('#fontDetailClearHighlightsBtn').off('click').on('click', function () {
            clearFontUsageHighlights({ resetGlyphToggles: true });
        });
        $('#fontDiagDetail').off('click', '.font-map-highlight-btn').on('click', '.font-map-highlight-btn', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            var $btn = $(this);
            var obj = parseCodeValue($btn.attr('data-obj'));
            var gen = parseCodeValue($btn.attr('data-gen'));
            var code = parseCodeValue($btn.attr('data-code'));
            toggleGlyphHighlightForCode(obj, gen, code);
        });
        syncGlyphHighlightToggleButtons();
        setupDeferredFontDetailRendering();
        applyGlobalDiagnosticsTableInteractions();
    }

    function buildIssueRowHtml(row, state) {
        var issueGlyph = '<span class="font-glyph-lazy" ' +
            'data-unicode="' + escapeHtmlAttr(row.character || '') + '" ' +
            'data-glyph-width="' + escapeHtmlAttr(String(row.width == null ? '' : row.width)) + '" ' +
            'data-font-family="' + escapeHtmlAttr((state.font.embedded ? state.fontFamily : '')) + '" ' +
            'title="Lazy-rendered glyph preview">' +
            ((row.character && row.character.length) ? '<span class="text-muted">…</span>' : '<span class="text-danger">n/a</span>') +
            '</span>';
        return '<tr>' +
            '<td>' + P.Utils.escapeHtml(row.character || '') + '</td>' +
            '<td class="font-glyph-preview-cell">' + issueGlyph + '</td>' +
            '<td>' + P.Utils.escapeHtml(row.unicodeHex || '') + '</td>' +
            '<td>' + (row.count || 0) + '</td>' +
            '<td>' + P.Utils.escapeHtml(row.issue || '') + '</td>' +
            '</tr>';
    }

    function buildMappingRowHtml(row, state) {
        var obj = parseCodeValue(state.font && state.font.objectNumber);
        var gen = parseCodeValue((state.font && state.font.generationNumber) || 0);
        var code = parseCodeValue(row.code);
        var unicode = row.unicode ? P.Utils.escapeHtml(row.unicode) : '<span class="text-danger">(unmapped)</span>';
        var glyphPreview = '<span class="font-glyph-lazy" ' +
            'data-unicode="' + escapeHtmlAttr(row.unicode || '') + '" ' +
            'data-unicode-hex="' + escapeHtmlAttr(row.unicodeHex || '') + '" ' +
            'data-glyph-width="' + escapeHtmlAttr(String(row.width == null ? '' : row.width)) + '" ' +
            'data-font-family="' + escapeHtmlAttr((state.font.embedded ? state.fontFamily : '')) + '" ' +
            'title="Lazy-rendered glyph preview">' +
            (row.unicode ? '<span class="text-muted">…</span>' : '<span class="text-danger">n/a</span>') +
            '</span>';
        return '<tr class="font-map-row" data-code="' + escapeHtmlAttr(String(row.code == null ? '' : row.code)) + '" data-unicode="' + escapeHtmlAttr(row.unicode || '') + '" data-unicode-hex="' + escapeHtmlAttr(row.unicodeHex || '') + '">' +
            '<td>' + P.Utils.escapeHtml(String(row.code == null ? '' : row.code)) + '</td>' +
            '<td class="font-glyph-preview-cell">' + glyphPreview + '</td>' +
            '<td class="font-map-unicode-cell">' + unicode + '</td>' +
            '<td>' + P.Utils.escapeHtml(row.unicodeHex || '') + '</td>' +
            '<td>' + (row.width == null ? '' : row.width) + '</td>' +
            '<td>' + (row.usedCount || 0) + '</td>' +
            '<td>' + (row.mapped ? '<span class="text-success">yes</span>' : '<span class="text-danger">no</span>') + '</td>' +
            '<td><button class="btn btn-xs btn-outline-accent font-map-highlight-btn glyph-highlight-toggle" data-obj="' + escapeHtmlAttr(String(obj)) + '" data-gen="' + escapeHtmlAttr(String(gen)) + '" data-code="' + escapeHtmlAttr(String(code)) + '" title="Toggle glyph highlight"><i class="fas fa-brush"></i></button></td>' +
            '</tr>';
    }

    function isElementNearViewport(el) {
        if (!el) return false;
        var rect = el.getBoundingClientRect();
        return rect.bottom > -80 && rect.top < (window.innerHeight + 120);
    }

    function isControlCodePoint(cp) {
        return (cp >= 0 && cp <= 31) || cp === 127 || (cp >= 128 && cp <= 159);
    }

    function hasVisibleCodePoint(text) {
        if (!text) return false;
        for (var i = 0; i < text.length; i += 1) {
            var cp = text.codePointAt(i);
            if (cp > 0xFFFF) i += 1;
            if (!isControlCodePoint(cp)) return true;
        }
        return false;
    }

    function decodeUnicodeHexToText(unicodeHex) {
        var raw = String(unicodeHex || '').trim();
        if (!raw) return '';
        var matches = raw.match(/U\+[0-9A-Fa-f]{1,6}/g);
        if (!matches || !matches.length) return '';
        var out = '';
        for (var i = 0; i < matches.length; i += 1) {
            var cp = parseInt(matches[i].slice(2), 16);
            if (!isFinite(cp) || cp < 0 || cp > 0x10FFFF) continue;
            try {
                out += String.fromCodePoint(cp);
            } catch (e) {
            }
        }
        return out;
    }

    function resolveGlyphPreviewText(unicodeText, unicodeHex) {
        var text = String(unicodeText || '');
        if (hasVisibleCodePoint(text)) return text;
        var fromHex = decodeUnicodeHexToText(unicodeHex);
        if (hasVisibleCodePoint(fromHex)) return fromHex;
        return '';
    }

    function renderIssueTableNow() {
        var state = activeFontDetailState;
        var tbody = document.getElementById('fontDetailIssueTableBody');
        if (!state || !tbody || state.issuesRendered) return;

        if (!state.issues.length) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-muted">No extracted text characters found for this font.</td></tr>';
            state.issuesRendered = true;
            return;
        }

        var html = '';
        for (var i = 0; i < state.issues.length; i += 1) {
            html += buildIssueRowHtml(state.issues[i], state);
        }
        tbody.innerHTML = html;
        state.issuesRendered = true;
        enableTableInteractions($(tbody).closest('table'));
        bindLazyGlyphRendering();
        bindGlyphHoverTooltips();
    }

    function renderMapTableNow(query, usedOnly) {
        var state = activeFontDetailState;
        var tbody = document.getElementById('fontDetailMapTableBody');
        var wrap = document.getElementById('fontDetailMapWrap');
        if (!state || !tbody) return;

        state.mapRenderToken += 1;
        var token = state.mapRenderToken;

        var normalizedQuery = String(query || '').toLowerCase();
        var filtered = state.mappings.filter(function (row) {
            if (usedOnly && !(row.usedCount > 0)) return false;
            if (!normalizedQuery) return true;
            var rowText = [row.code, row.unicode, row.unicodeHex, row.width, row.usedCount].join(' ').toLowerCase();
            return rowText.indexOf(normalizedQuery) !== -1;
        });

        if (!filtered.length) {
            tbody.innerHTML = '<tr><td colspan="8" class="text-muted">No rows match the current filter.</td></tr>';
            return;
        }

        tbody.innerHTML = '<tr><td colspan="8" class="text-muted">Rendering ' + filtered.length + ' rows… scroll to load more.</td></tr>';

        state.mapRenderContext = {
            token: token,
            filtered: filtered,
            index: 0,
            chunkSize: 120
        };

        function appendNextChunk() {
            var ctx = state.mapRenderContext;
            if (!ctx || ctx.token !== state.mapRenderToken) return;
            if (ctx.index === 0) {
                tbody.innerHTML = '';
            }
            var end = Math.min(ctx.index + ctx.chunkSize, ctx.filtered.length);
            var html = '';
            for (; ctx.index < end; ctx.index += 1) {
                html += buildMappingRowHtml(ctx.filtered[ctx.index], state);
            }
            if (html) {
                tbody.insertAdjacentHTML('beforeend', html);
                bindGlyphMappingRowClicks();
                bindLazyGlyphRendering();
                bindGlyphHoverTooltips();
                syncGlyphHighlightToggleButtons();
            }
        }

        function fillUntilScrollable() {
            if (!wrap) return;
            var guard = 0;
            while (state.mapRenderContext && state.mapRenderContext.index < state.mapRenderContext.filtered.length && wrap.scrollHeight <= wrap.clientHeight + 4 && guard < 12) {
                appendNextChunk();
                guard += 1;
            }
            enableTableInteractions($('#fontDetailMapTable'));
        }

        if (wrap) {
            $(wrap).off('scroll.maplazy').on('scroll.maplazy', function () {
                var ctx = state.mapRenderContext;
                if (!ctx || ctx.token !== state.mapRenderToken) return;
                if (ctx.index >= ctx.filtered.length) return;
                if (wrap.scrollTop + wrap.clientHeight >= wrap.scrollHeight - 120) {
                    appendNextChunk();
                }
            });
        }

        appendNextChunk();
        fillUntilScrollable();
    }

    function setupDeferredFontDetailRendering() {
        var state = activeFontDetailState;
        if (!state) return;

        if (state.observer && typeof state.observer.disconnect === 'function') {
            state.observer.disconnect();
            state.observer = null;
        }

        renderMapTableNow(state.pendingQuery, state.pendingUsedOnly);
    }

    function wireModalFocusSafety(modalEl) {
        if (!modalEl || modalEl.__pdfalyzerFocusSafetyBound) return;

        modalEl.addEventListener('hide.bs.modal', function () {
            var active = document.activeElement;
            if (active && modalEl.contains(active) && typeof active.blur === 'function') {
                active.blur();
            }
        });

        modalEl.addEventListener('hidden.bs.modal', function () {
            var returnFocusEl = modalEl.__pdfalyzerReturnFocusEl;
            modalEl.__pdfalyzerReturnFocusEl = null;
            if (!returnFocusEl || !document.contains(returnFocusEl) || returnFocusEl.disabled) return;
            if (typeof returnFocusEl.focus === 'function') {
                returnFocusEl.focus({ preventScroll: true });
            }
        });

        modalEl.__pdfalyzerFocusSafetyBound = true;
    }

    function ensureGlyphDetailModal() {
        if (document.getElementById('fontGlyphDetailModal')) return;
        var modalHtml = '' +
            '<div class="modal fade" id="fontGlyphDetailModal" tabindex="-1" aria-hidden="true">' +
            '  <div class="modal-dialog modal-lg modal-dialog-scrollable">' +
            '    <div class="modal-content">' +
            '      <div class="modal-header">' +
            '        <h5 class="modal-title"><i class="fas fa-font me-2"></i>Glyph Diagnostics</h5>' +
            '        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>' +
            '      </div>' +
            '      <div class="modal-body" id="fontGlyphDetailBody"></div>' +
            '      <div class="modal-footer">' +
            '        <button type="button" class="btn btn-outline-accent btn-sm" data-bs-dismiss="modal">Close</button>' +
            '      </div>' +
            '    </div>' +
            '  </div>' +
            '</div>';
        $('body').append(modalHtml);
        wireModalFocusSafety(document.getElementById('fontGlyphDetailModal'));
    }

    function bindGlyphMappingRowClicks() {
        $('#fontDiagDetail .font-map-row').off('click').on('click', function (ev) {
            if ($(ev.target).closest('.font-map-highlight-btn').length) {
                return;
            }
            ensureGlyphDetailModal();
            var state = activeFontDetailState;
            if (!state || !state.font) return;

            $('#fontDiagDetail .font-map-row').removeClass('is-selected');
            $(this).addClass('is-selected');

            var obj = parseCodeValue(state.font.objectNumber);
            var gen = parseCodeValue(state.font.generationNumber || 0);
            var code = parseCodeValue($(this).attr('data-code'));
            var unicode = String($(this).attr('data-unicode') || '');
            state.selectedGlyphCode = code;
            state.selectedGlyphUnicode = unicode;
            if (!(obj >= 0) || !(gen >= 0) || !(code >= 0)) {
                P.Utils.toast('Invalid glyph selection metadata for diagnostics.', 'warning');
                return;
            }
            openGlyphDetailModal(obj, gen, code, unicode);
        });
    }

    function parseCodeValue(raw) {
        var text = String(raw == null ? '' : raw).trim();
        if (!text) return NaN;

        if (/^0x[0-9a-f]+$/i.test(text)) {
            return parseInt(text, 16);
        }
        if (/^u\+[0-9a-f]+$/i.test(text)) {
            return parseInt(text.slice(2), 16);
        }

        var numeric = Number(text);
        if (!Number.isNaN(numeric)) {
            return Math.floor(numeric);
        }

        return NaN;
    }

    function openGlyphDetailModal(obj, gen, code, unicode) {
        var $body = $('#fontGlyphDetailBody');
        if (!$body.length) return;

        var glyphText = unicode || '(unmapped)';
        $body.html('<div class="text-muted"><span class="spinner-border spinner-border-sm"></span> Loading glyph diagnostics…</div>');
        var modalEl = document.getElementById('fontGlyphDetailModal');
        wireModalFocusSafety(modalEl);
        modalEl.__pdfalyzerReturnFocusEl = document.activeElement;
        var modal = bootstrap.Modal.getOrCreateInstance(modalEl);
        modal.show();

        P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/diagnostics/' + obj + '/' + gen + '/glyph/' + code)
            .done(function (detail) {
                renderGlyphDetailModal(detail || {}, obj, gen, code, glyphText);
            })
            .fail(function () {
                $body.html('<div class="text-danger"><i class="fas fa-exclamation-circle me-1"></i>Failed to load glyph diagnostics.</div>');
            });
    }

    function renderGlyphDetailModal(detail, obj, gen, code, glyphText) {
        var glyph = detail.glyph || {};
        var descriptor = detail.fontDescriptor || {};
        var encoding = detail.encodingDiagnostics || {};
        var usagePages = Array.isArray(detail.usagePagesForCode) ? detail.usagePagesForCode : [];
        var unicodeSamples = Array.isArray(detail.unicodeSamplesForCode) ? detail.unicodeSamplesForCode : [];
        var clickedGlyph = String(glyphText || '').trim();
        var detailGlyph = String(glyph.unicode || '').trim();
        var hugeGlyph = clickedGlyph || detailGlyph;
        var noGlyphPresent = false;
        var heroFontFamily = "'Segoe UI Symbol', 'Segoe UI', sans-serif";
        var heroPrimaryFont = '';
        if (detail.embedded && obj >= 0) {
            var detailFontFamily = 'pdfdiagfont_' + obj + '_' + (gen || 0);
            ensureDiagnosticsFontFace(detailFontFamily, obj, gen || 0);
            heroFontFamily = "'" + detailFontFamily + "', 'Segoe UI Symbol', 'Segoe UI', sans-serif";
            heroPrimaryFont = detailFontFamily;
        }
        if (!hugeGlyph || hugeGlyph === '(unmapped)') {
            hugeGlyph = '';
            noGlyphPresent = true;
        }

        var outlineStats = extractOutlineStats(detail, glyph);
        var extraGlyphData = collectExtraGlyphDiagnostics(glyph);
        var extraGlyphDataHtml = extraGlyphData.length
            ? '<div class="font-glyph-extra-grid">' + extraGlyphData.map(function (entry) {
                return '<div><span class="text-muted">' + P.Utils.escapeHtml(entry.key) + ':</span> ' + P.Utils.escapeHtml(entry.value) + '</div>';
            }).join('') + '</div>'
            : '<div class="text-muted">No additional glyph-specific fields returned.</div>';

        var outlineText = outlineStats
            ? ('Contours: ' + outlineStats.contours + ' | Splines: ' + outlineStats.splines + ' | Segments: ' + outlineStats.segments)
            : 'Outline/spline data not exposed by API for this glyph.';
        var descriptorAscent = parseFloat(descriptor.ascent);
        var descriptorDescent = parseFloat(descriptor.descent);
        var legendAscent = isFinite(descriptorAscent) ? String(descriptorAscent) : 'n/a';
        var legendDescent = isFinite(descriptorDescent) ? String(descriptorDescent) : 'n/a';

        var html = '' +
            '<div class="font-diag-detail-title mb-2"><strong>' + P.Utils.escapeHtml(detail.fontName || '(unknown font)') + '</strong> ' +
            '<span class="text-muted">Object ' + P.Utils.escapeHtml(detail.objectRef || (obj + ' ' + gen + ' R')) + '</span></div>' +
            '<div class="font-glyph-diag-layout">' +
            '  <div class="font-glyph-guide-box">' +
            '    <div class="font-glyph-guide-title">Guide legend</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot ascent"></span>Ascent line (font units: ' + P.Utils.escapeHtml(legendAscent) + ')</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot baseline"></span>Baseline (font units: 0)</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot descent"></span>Descent line (font units: ' + P.Utils.escapeHtml(legendDescent) + ')</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot bbox"></span>Measured glyph bounding box</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot origin"></span>Text origin</div>' +
            '    <hr class="my-2" />' +
            '    <div class="font-glyph-guide-title">Advanced data</div>' +
            '    <div class="text-muted" style="font-size:11px;line-height:1.35;">' + P.Utils.escapeHtml(outlineText) + '</div>' +
            '    <div class="mt-2" style="font-size:11px;">' + extraGlyphDataHtml + '</div>' +
            '  </div>' +
            '  <div class="font-glyph-hero-wrap" title="Large glyph diagnostics preview">' +
            '    <div class="font-glyph-hero-stage">' +
            '      <div id="fontGlyphCanvasViewport" class="font-glyph-canvas-viewport">' +
            '        <canvas id="fontGlyphHeroCanvas" class="font-glyph-hero-canvas" ' +
            '          data-glyph="' + escapeHtmlAttr(hugeGlyph) + '" ' +
            '          data-font-family="' + escapeHtmlAttr(heroFontFamily) + '" ' +
            '          data-font-primary="' + escapeHtmlAttr(heroPrimaryFont) + '" ' +
            '          data-glyph-width="' + escapeHtmlAttr(String(glyph.width == null ? '' : glyph.width)) + '"></canvas>' +
            '      </div>' +
            '      <div class="font-glyph-hero-toolbar font-glyph-hero-toolbar-right">' +
            '        <button id="fontGlyphZoomOut" class="btn btn-xs btn-outline-accent glyph-zoom-btn" title="Zoom out"><i class="fas fa-search-minus"></i></button>' +
            '        <button id="fontGlyphZoomIn" class="btn btn-xs btn-outline-accent glyph-zoom-btn" title="Zoom in"><i class="fas fa-search-plus"></i></button>' +
            '        <button id="fontGlyphZoomFit" class="btn btn-xs btn-outline-accent glyph-zoom-btn" title="Fit to view"><i class="fas fa-compress-arrows-alt"></i></button>' +
            '        <span id="fontGlyphZoomLabel" class="text-muted">100%</span>' +
            '        <span class="text-muted font-glyph-zoom-hint">Ctrl+Wheel</span>' +
            '      </div>' +
            '    </div>' +
            '    <div id="fontGlyphHeroMetrics" class="font-glyph-hero-metrics text-muted"></div>' +
            '  </div>' +
            '</div>' +
            '<div class="font-detail-grid">' +
            '  <div style="grid-column:1 / -1;"><strong>Code / Unicode / Usage</strong><div class="text-muted">' +
            'Code: ' + P.Utils.escapeHtml(String(glyph.code == null ? code : glyph.code)) + ' (' + P.Utils.escapeHtml(String(glyph.codeHex || '')) + ')' +
            ' &nbsp;|&nbsp; Unicode: ' + P.Utils.escapeHtml(String(glyph.unicode || '')) + ' ' + P.Utils.escapeHtml(String(glyph.unicodeHex || '')) +
            ' &nbsp;|&nbsp; Count: ' + P.Utils.escapeHtml(String(glyph.usedCount || 0)) + ' | Pages: ' + P.Utils.escapeHtml(usagePages.map(function (p) { return p + 1; }).join(', ') || '-') +
            ' &nbsp;|&nbsp; Type/Embedded/Subset: ' + P.Utils.escapeHtml(String(detail.fontType || '')) + ' / ' + (detail.embedded ? 'yes' : 'no') + ' / ' + (detail.subset ? 'yes' : 'no') +
            ' &nbsp;|&nbsp; Encoding: ' + P.Utils.escapeHtml(String(detail.encoding || '')) +
            '</div></div>' +
            '</div>' +
            '<div class="mt-2">' +
            '  <button class="btn btn-outline-accent btn-sm me-2 glyph-highlight-toggle" id="fontGlyphHighlightBtn" data-obj="' + escapeHtmlAttr(String(obj)) + '" data-gen="' + escapeHtmlAttr(String(gen)) + '" data-code="' + escapeHtmlAttr(String(code)) + '"><i class="fas fa-brush me-1"></i>Toggle glyph highlight</button>' +
            '  <button class="btn btn-outline-accent btn-sm" id="fontGlyphClearHighlightsBtn"><i class="fas fa-eraser me-1"></i>Clear highlights</button>' +
            '</div>' +
            '<details class="mt-2" id="fontGlyphUsageSection"><summary>Usage positions by page (deferred)</summary>' +
            '  <div id="fontGlyphUsagePanel" class="font-glyph-usage-panel text-muted">Open this section to load usage positions.</div>' +
            '</details>' +
            '<details open class="mt-2"><summary>Glyph metrics</summary>' +
            '  <div class="font-detail-table-wrap"><table class="font-detail-table"><tbody>' +
            '    <tr><td>Width</td><td>' + P.Utils.escapeHtml(String(glyph.width == null ? '' : glyph.width)) + '</td></tr>' +
            '    <tr><td>Mapped</td><td>' + (glyph.mapped ? '<span class="text-success">yes</span>' : '<span class="text-danger">no</span>') + '</td></tr>' +
            '    <tr><td>Can encode mapped unicode</td><td>' + (glyph.canEncodeMappedUnicode ? '<span class="text-success">yes</span>' : '<span class="text-danger">no</span>') + '</td></tr>' +
            '    <tr><td>Displacement</td><td>' + P.Utils.escapeHtml(JSON.stringify(glyph.displacement || {})) + '</td></tr>' +
            '    <tr><td>Position vector</td><td>' + P.Utils.escapeHtml(JSON.stringify(glyph.positionVector || {})) + '</td></tr>' +
            '    <tr><td>Unicode samples</td><td>' + P.Utils.escapeHtml(unicodeSamples.join(' | ') || '-') + '</td></tr>' +
            '  </tbody></table></div>' +
            '</details>' +
            '<details class="mt-2"><summary>Font descriptor + rendering metrics</summary>' +
            '  <div class="font-detail-table-wrap"><table class="font-detail-table"><tbody>' +
            '    <tr><td>Type / Embedded / Subset</td><td>' + P.Utils.escapeHtml(String(detail.fontType || '')) + ' / ' + (detail.embedded ? 'yes' : 'no') + ' / ' + (detail.subset ? 'yes' : 'no') + '</td></tr>' +
            '    <tr><td>Encoding</td><td>' + P.Utils.escapeHtml(String(detail.encoding || '')) + '</td></tr>' +
            '    <tr><td>ToUnicode</td><td>' + (encoding.hasToUnicode ? '<span class="text-success">present</span>' : '<span class="text-danger">missing</span>') + ' ' + P.Utils.escapeHtml(String(encoding.toUnicodeObject || '')) + '</td></tr>' +
            '    <tr><td>Base / Descendant</td><td>' + P.Utils.escapeHtml(String(encoding.baseFont || '')) + '<br>' + P.Utils.escapeHtml(String(encoding.descendantFont || '')) + '</td></tr>' +
            '    <tr><td>Ascent / Descent</td><td>' + P.Utils.escapeHtml(String(descriptor.ascent || '')) + ' / ' + P.Utils.escapeHtml(String(descriptor.descent || '')) + '</td></tr>' +
            '    <tr><td>CapHeight / XHeight</td><td>' + P.Utils.escapeHtml(String(descriptor.capHeight || '')) + ' / ' + P.Utils.escapeHtml(String(descriptor.xHeight || '')) + '</td></tr>' +
            '    <tr><td>Avg / Max / Missing width</td><td>' + P.Utils.escapeHtml(String(descriptor.avgWidth || '')) + ' / ' + P.Utils.escapeHtml(String(descriptor.maxWidth || '')) + ' / ' + P.Utils.escapeHtml(String(descriptor.missingWidth || '')) + '</td></tr>' +
            '    <tr><td>Italic angle / StemV / Flags</td><td>' + P.Utils.escapeHtml(String(descriptor.italicAngle || '')) + ' / ' + P.Utils.escapeHtml(String(descriptor.stemV || '')) + ' / ' + P.Utils.escapeHtml(String(descriptor.flags || '')) + '</td></tr>' +
            '    <tr><td>Font BBox</td><td>' + P.Utils.escapeHtml(String(descriptor.fontBBox || '')) + '</td></tr>' +
            '    <tr><td>Kerning note</td><td>' + P.Utils.escapeHtml(String(detail.kerningNote || 'n/a')) + '</td></tr>' +
            '  </tbody></table></div>' +
            '</details>';

        $('#fontGlyphDetailBody').html(html);

        var heroCanvas = document.getElementById('fontGlyphHeroCanvas');
        var heroMetrics = document.getElementById('fontGlyphHeroMetrics');
        var heroViewport = document.getElementById('fontGlyphCanvasViewport');
        if (heroCanvas && !noGlyphPresent) {
            initGlyphCanvasViewer(heroCanvas, heroViewport, heroMetrics);
        } else {
            if (heroViewport) {
                heroViewport.setAttribute('title', 'No glyph present');
            }
            if (heroMetrics) {
                heroMetrics.textContent = 'No glyph present';
            }
        }

        $('#fontGlyphHighlightBtn').off('click').on('click', function () {
            toggleGlyphHighlightForCode(obj, gen, code);
        });

        $('#fontGlyphClearHighlightsBtn').off('click').on('click', function () {
            clearFontUsageHighlights({ resetGlyphToggles: true });
        });

        syncGlyphHighlightToggleButtons();

        bindGlyphUsageInspector(obj, gen, code);
        applyGlobalDiagnosticsTableInteractions();
    }

    function bindGlyphUsageInspector(obj, gen, code) {
        var $section = $('#fontGlyphUsageSection');
        var $panel = $('#fontGlyphUsagePanel');
        if (!$section.length || !$panel.length) return;

        var usageByPage = null;

        function renderPageGroupShell(groups) {
            if (!groups.length) {
                $panel.html('<div class="text-muted">No positions found for this glyph.</div>');
                return;
            }

            var html = '<div class="font-glyph-usage-pages">';
            groups.forEach(function (group) {
                html += '' +
                    '<details class="font-glyph-usage-page" data-page="' + group.pageIndex + '">' +
                    '  <summary>' +
                    '    <span><i class="fas fa-file-alt me-1"></i>Page ' + (group.pageIndex + 1) + '</span>' +
                    '    <span class="badge text-bg-secondary ms-2">' + group.items.length + ' hits</span>' +
                    '    <button class="btn btn-xs btn-outline-accent ms-2 glyph-usage-page-jump" data-page="' + group.pageIndex + '" title="Jump to page"><i class="fas fa-location-arrow"></i></button>' +
                    '    <button class="btn btn-xs btn-outline-accent ms-1 glyph-usage-page-highlight" data-page="' + group.pageIndex + '" title="Highlight page positions"><i class="fas fa-highlighter"></i></button>' +
                    '  </summary>' +
                    '  <div class="font-glyph-usage-page-body" data-page-body="' + group.pageIndex + '">Load this page group to see positions.</div>' +
                    '</details>';
            });
            html += '</div>';
            $panel.html(html);

            $panel.find('.font-glyph-usage-page').off('toggle').on('toggle', function () {
                if (!this.open) return;
                var pageIndex = parseInt($(this).attr('data-page'), 10);
                renderUsagePageRows(pageIndex);
            });
        }

        function renderUsagePageRows(pageIndex) {
            if (!usageByPage || !usageByPage[pageIndex]) return;
            var $body = $panel.find('[data-page-body="' + pageIndex + '"]');
            if (!$body.length || $body.attr('data-rendered') === '1') return;

            var rows = usageByPage[pageIndex].slice();
            rows.sort(function (a, b) {
                var ay = getUsageBBox(a)[1];
                var by = getUsageBBox(b)[1];
                if (ay !== by) return ay - by;
                return getUsageBBox(a)[0] - getUsageBBox(b)[0];
            });

            var html = '<table class="font-detail-table"><thead><tr>' +
                '<th>#</th><th>X</th><th>Y</th><th>W</th><th>H</th><th>Size</th><th>Unicode</th><th>Actions</th>' +
                '</tr></thead><tbody>';

            rows.forEach(function (item, idx) {
                var bb = getUsageBBox(item);
                html += '<tr>' +
                    '<td>' + (idx + 1) + '</td>' +
                    '<td>' + formatUsageNumber(bb[0]) + '</td>' +
                    '<td>' + formatUsageNumber(bb[1]) + '</td>' +
                    '<td>' + formatUsageNumber(bb[2]) + '</td>' +
                    '<td>' + formatUsageNumber(bb[3]) + '</td>' +
                    '<td>' + formatUsageNumber(item.fontSize) + '</td>' +
                    '<td>' + P.Utils.escapeHtml(String(item.unicode || '')) + '</td>' +
                    '<td>' +
                    '  <button class="btn btn-xs btn-outline-accent glyph-usage-jump" data-page="' + pageIndex + '" data-index="' + idx + '" title="Jump to this position"><i class="fas fa-location-arrow"></i></button>' +
                    '  <button class="btn btn-xs btn-outline-accent ms-1 glyph-usage-highlight" data-page="' + pageIndex + '" data-index="' + idx + '" title="Highlight this position"><i class="fas fa-highlighter"></i></button>' +
                    '</td>' +
                    '</tr>';
            });

            html += '</tbody></table>';
            $body.html(html);
            $body.attr('data-rendered', '1');
            enableTableInteractions($body.find('table'));
        }

        function groupByPage(areas) {
            var grouped = {};
            (areas || []).forEach(function (item) {
                var pageIndex = parseInt(item.pageIndex, 10);
                if (!(pageIndex >= 0)) return;
                if (!grouped[pageIndex]) grouped[pageIndex] = [];
                grouped[pageIndex].push(item);
            });
            return grouped;
        }

        function pageGroupList(grouped) {
            return Object.keys(grouped)
                .map(function (k) { return parseInt(k, 10); })
                .filter(function (k) { return k >= 0; })
                .sort(function (a, b) { return a - b; })
                .map(function (pageIndex) {
                    return { pageIndex: pageIndex, items: grouped[pageIndex] };
                });
        }

        function loadUsageIfNeeded() {
            if ($section.attr('data-loaded') === '1') return;
            $panel.html('<div class="text-muted"><span class="spinner-border spinner-border-sm"></span> Loading glyph usage positions...</div>');

            P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen + '/glyph/' + code)
                .done(function (areas) {
                    usageByPage = groupByPage(areas || []);
                    var groups = pageGroupList(usageByPage);
                    renderPageGroupShell(groups);
                    $section.attr('data-loaded', '1');
                })
                .fail(function () {
                    $panel.html('<div class="text-danger">Failed to load glyph usage positions.</div>');
                });
        }

        $section.off('toggle').on('toggle', function () {
            if (this.open) loadUsageIfNeeded();
        });

        $panel.off('click', '.glyph-usage-page-jump').on('click', '.glyph-usage-page-jump', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            jumpToUsagePage(pageIndex);
        });

        $panel.off('click', '.glyph-usage-page-highlight').on('click', '.glyph-usage-page-highlight', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            var pageAreas = (usageByPage && usageByPage[pageIndex]) ? usageByPage[pageIndex] : [];
            var usage = showFontUsageHighlights(pageAreas, { clearExisting: true });
            P.Utils.toast('Highlighted ' + usage.highlighted + ' positions on page ' + (pageIndex + 1), usage.highlighted ? 'info' : 'warning');
        });

        $panel.off('click', '.glyph-usage-jump').on('click', '.glyph-usage-jump', function () {
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            var rowIndex = parseInt($(this).attr('data-index'), 10);
            var pageAreas = (usageByPage && usageByPage[pageIndex]) ? usageByPage[pageIndex] : [];
            var area = pageAreas[rowIndex];
            if (!area) return;
            showFontUsageHighlights([area], { clearExisting: true });
            jumpToUsagePage(pageIndex);
        });

        $panel.off('click', '.glyph-usage-highlight').on('click', '.glyph-usage-highlight', function () {
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            var rowIndex = parseInt($(this).attr('data-index'), 10);
            var pageAreas = (usageByPage && usageByPage[pageIndex]) ? usageByPage[pageIndex] : [];
            var area = pageAreas[rowIndex];
            if (!area) return;
            var usage = showFontUsageHighlights([area], { clearExisting: true });
            P.Utils.toast('Highlighted position on page ' + (pageIndex + 1), usage.highlighted ? 'info' : 'warning');
        });
    }

    function getUsageBBox(area) {
        var bbox = area && Array.isArray(area.bbox) ? area.bbox : [];
        return [
            parseFloat(bbox[0]) || 0,
            parseFloat(bbox[1]) || 0,
            parseFloat(bbox[2]) || 0,
            parseFloat(bbox[3]) || 0
        ];
    }

    function formatUsageNumber(value) {
        var num = parseFloat(value);
        if (!isFinite(num)) return '-';
        return Math.round(num * 100) / 100;
    }

    function jumpToUsagePage(pageIndex) {
        if (!(pageIndex >= 0)) return;
        var wrapper = document.querySelector('#pdfViewer .pdf-page-wrapper[data-page="' + pageIndex + '"]');
        if (wrapper && wrapper.scrollIntoView) {
            wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    }

    function ensureDiagnosticsFontFace(fontFamily, obj, gen) {
        if (!fontFamily) return;
        var styleId = 'fontDiagFace_' + fontFamily;
        if (document.getElementById(styleId)) return;
        var style = document.createElement('style');
        style.id = styleId;
        style.textContent = "@font-face{" +
            "font-family:'" + fontFamily + "';" +
            "src:url('/api/fonts/" + encodeURIComponent(P.state.sessionId) + "/extract/" + obj + "/" + gen + "');" +
            "font-display:swap;" +
            "}";
        document.head.appendChild(style);
    }

    function getGlyphMeasureContext() {
        if (!glyphMeasureCanvas) {
            glyphMeasureCanvas = document.createElement('canvas');
        }
        return glyphMeasureCanvas.getContext('2d');
    }

    function measureGlyphBounds(text, fontFamily, fontSizePx) {
        var metrics = measureGlyphMetrics(text, fontFamily, fontSizePx);
        return {
            width: metrics.bboxWidth,
            height: metrics.ascent + metrics.descent,
            ascent: metrics.ascent,
            descent: metrics.descent
        };
    }

    function measureGlyphMetrics(text, fontFamily, fontSizePx) {
        var ctx = getGlyphMeasureContext();
        if (!ctx) {
            return {
                advanceWidth: Math.max(1, fontSizePx * 0.6),
                bboxWidth: Math.max(1, fontSizePx * 0.6),
                xMin: -fontSizePx * 0.05,
                xMax: fontSizePx * 0.55,
                ascent: fontSizePx * 0.8,
                descent: fontSizePx * 0.2
            };
        }
        ctx.font = String(fontSizePx) + 'px ' + (fontFamily || "'Segoe UI Symbol', 'Segoe UI', sans-serif");
        var m = ctx.measureText(text || ' ');
        var ascent = m.actualBoundingBoxAscent || (fontSizePx * 0.8);
        var descent = m.actualBoundingBoxDescent || (fontSizePx * 0.2);
        var left = (typeof m.actualBoundingBoxLeft === 'number') ? m.actualBoundingBoxLeft : 0;
        var right = (typeof m.actualBoundingBoxRight === 'number') ? m.actualBoundingBoxRight : (m.width || (fontSizePx * 0.6));
        var xMin = -left;
        var xMax = right;
        var bboxWidth = xMax - xMin;
        var advanceWidth = m.width || bboxWidth || (fontSizePx * 0.6);
        return {
            advanceWidth: Math.max(1, advanceWidth),
            bboxWidth: Math.max(1, bboxWidth || advanceWidth),
            xMin: xMin,
            xMax: xMax,
            ascent: ascent,
            descent: descent
        };
    }

    function clampNumber(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function fitGlyphInElement(element, options) {
        if (!element) return;
        var text = String((options && options.text) != null ? options.text : (element.textContent || '')).trim();
        if (!text) return;

        var computed = window.getComputedStyle(element);
        var fontFamily = (options && options.fontFamily) || (computed && computed.fontFamily) || "'Segoe UI Symbol', 'Segoe UI', sans-serif";
        var maxWidth = Math.max(1, (options && options.maxWidth) || element.clientWidth || 32);
        var maxHeight = Math.max(1, (options && options.maxHeight) || element.clientHeight || 24);
        var minFontSize = Math.max(6, (options && options.minFontSize) || 10);
        var maxFontSize = Math.max(minFontSize, (options && options.maxFontSize) || 220);
        var widthHintUnits = options && typeof options.widthHintUnits === 'number' ? options.widthHintUnits : NaN;

        var baseSize = 100;
        var bounds = measureGlyphBounds(text, fontFamily, baseSize);
        var widthAtBase = bounds.width;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            widthAtBase = Math.max(widthAtBase, baseSize * (widthHintUnits / 1000));
        }

        var sizeByWidth = maxWidth * (baseSize / Math.max(1, widthAtBase));
        var sizeByHeight = maxHeight * (baseSize / Math.max(1, bounds.height));
        var fitted = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), minFontSize, maxFontSize);

        element.style.fontSize = fitted + 'px';
        element.style.lineHeight = '1';
    }

    function drawGlyphPreviewCanvas(canvas, text, fontFamily, widthHintUnits) {
        if (!canvas || !text) return;
        var cssWidth = Math.max(24, canvas.clientWidth || 24);
        var cssHeight = Math.max(24, canvas.clientHeight || 24);
        var dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
        canvas.width = Math.floor(cssWidth * dpr);
        canvas.height = Math.floor(cssHeight * dpr);
        canvas.style.width = cssWidth + 'px';
        canvas.style.height = cssHeight + 'px';

        var ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssWidth, cssHeight);

        var inset = 3;
        var fitWidth = Math.max(1, (cssWidth - inset * 2) * 0.9);
        var fitHeight = Math.max(1, (cssHeight - inset * 2) * 0.9);
        var baseSize = 120;
        var mBase = measureGlyphMetrics(text, fontFamily, baseSize);
        var bboxWidthBase = mBase.bboxWidth;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            bboxWidthBase = Math.max(bboxWidthBase, baseSize * (widthHintUnits / 1000));
        }
        var bboxHeightBase = Math.max(1, mBase.ascent + mBase.descent);

        var sizeByWidth = fitWidth * (baseSize / Math.max(1, bboxWidthBase));
        var sizeByHeight = fitHeight * (baseSize / Math.max(1, bboxHeightBase));
        var fontSize = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), 8, 220);
        var m = measureGlyphMetrics(text, fontFamily, fontSize);
        var bboxWidth = Math.max(1, m.bboxWidth);
        var bboxHeight = Math.max(1, m.ascent + m.descent);

        var bboxLeft = (cssWidth - bboxWidth) / 2;
        var bboxTop = (cssHeight - bboxHeight) / 2;
        var baselineY = bboxTop + m.ascent;
        var originX = bboxLeft - m.xMin;

        ctx.save();
        ctx.font = String(fontSize) + 'px ' + fontFamily;
        ctx.fillStyle = '#ffffff';
        ctx.textBaseline = 'alphabetic';
        ctx.fillText(text, originX, baselineY);
        ctx.restore();
    }

    function drawGlyphDiagnosticsCanvas(canvas, options) {
        if (!canvas) return null;
        var text = String(canvas.getAttribute('data-glyph') || '').trim();
        if (!text) return null;

        var fontFamily = String(canvas.getAttribute('data-font-family') || "'Segoe UI Symbol', 'Segoe UI', sans-serif");
        var widthHintUnits = options && typeof options.widthHintUnits === 'number' ? options.widthHintUnits : NaN;
        var metricsEl = options ? options.metricsEl : null;

        var cssWidth = Math.max(220, (options && options.logicalWidth) || canvas.clientWidth || 300);
        var cssHeight = Math.max(220, (options && options.logicalHeight) || canvas.clientHeight || 260);
        var dpr = Math.max(1, Math.min(window.devicePixelRatio || 1, 2));
        canvas.style.width = cssWidth + 'px';
        canvas.style.height = cssHeight + 'px';
        canvas.width = Math.floor(cssWidth * dpr);
        canvas.height = Math.floor(cssHeight * dpr);

        var ctx = canvas.getContext('2d');
        if (!ctx) return null;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssWidth, cssHeight);

        var inset = 8;
        var region = {
            x: inset,
            y: inset,
            width: cssWidth - inset * 2,
            height: cssHeight - inset * 2
        };
        var fitPadding = 0.10;
        var fitRegion = {
            x: region.x + (region.width * fitPadding * 0.5),
            y: region.y + (region.height * fitPadding * 0.5),
            width: Math.max(1, region.width * (1 - fitPadding)),
            height: Math.max(1, region.height * (1 - fitPadding))
        };

        var baseSize = 200;
        var mBase = measureGlyphMetrics(text, fontFamily, baseSize);
        var bboxWidthBase = mBase.bboxWidth;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            bboxWidthBase = Math.max(bboxWidthBase, baseSize * (widthHintUnits / 1000));
        }
        var bboxHeightBase = Math.max(1, mBase.ascent + mBase.descent);

        var sizeByWidth = fitRegion.width * (baseSize / Math.max(1, bboxWidthBase));
        var sizeByHeight = fitRegion.height * (baseSize / Math.max(1, bboxHeightBase));
        var fontSize = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), 24, 260);

        var m = measureGlyphMetrics(text, fontFamily, fontSize);
        var bboxWidth = Math.max(1, m.bboxWidth);
        var bboxHeight = Math.max(1, m.ascent + m.descent);

        var bboxLeft = fitRegion.x + (fitRegion.width - bboxWidth) / 2;
        var bboxTop = fitRegion.y + (fitRegion.height - bboxHeight) / 2;
        var baselineY = bboxTop + m.ascent;
        var originX = bboxLeft - m.xMin;

        ctx.fillStyle = 'rgba(255,255,255,0.02)';
        ctx.fillRect(region.x, region.y, region.width, region.height);

        ctx.strokeStyle = 'rgba(255,255,255,0.18)';
        ctx.lineWidth = 1;
        ctx.setLineDash([5, 4]);
        ctx.strokeRect(region.x + 0.5, region.y + 0.5, region.width - 1, region.height - 1);
        ctx.setLineDash([]);

        var fullLeft = 0;
        var fullRight = cssWidth;
        var fullTop = 0;
        var fullBottom = cssHeight;

        ctx.strokeStyle = 'rgba(0,212,255,0.95)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(fullLeft, baselineY - m.ascent);
        ctx.lineTo(fullRight, baselineY - m.ascent);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(255,193,7,0.95)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(fullLeft, baselineY);
        ctx.lineTo(fullRight, baselineY);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(220,53,69,0.95)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(fullLeft, baselineY + m.descent);
        ctx.lineTo(fullRight, baselineY + m.descent);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(40,167,69,0.55)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(fullLeft, bboxTop);
        ctx.lineTo(fullRight, bboxTop);
        ctx.moveTo(fullLeft, bboxTop + bboxHeight);
        ctx.lineTo(fullRight, bboxTop + bboxHeight);
        ctx.moveTo(bboxLeft, fullTop);
        ctx.lineTo(bboxLeft, fullBottom);
        ctx.moveTo(bboxLeft + bboxWidth, fullTop);
        ctx.lineTo(bboxLeft + bboxWidth, fullBottom);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(173,181,189,0.7)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(originX, fullTop);
        ctx.lineTo(originX, fullBottom);
        ctx.stroke();

        ctx.save();
        ctx.font = String(fontSize) + 'px ' + fontFamily;
        ctx.fillStyle = '#ffffff';
        ctx.textBaseline = 'alphabetic';
        ctx.fillText(text, originX, baselineY);
        ctx.restore();

        if (metricsEl) {
            var info = [
                'bbox ' + Math.round(bboxWidth) + 'x' + Math.round(bboxHeight) + 'px',
                'advance ' + Math.round(m.advanceWidth) + 'px'
            ];
            metricsEl.textContent = info.join('  |  ');
        }

        return {
            contentLeft: region.x,
            contentTop: region.y,
            contentWidth: region.width,
            contentHeight: region.height,
            fitLeft: bboxLeft,
            fitTop: baselineY - m.ascent,
            fitWidth: bboxWidth,
            fitHeight: bboxHeight,
            bboxLeft: bboxLeft,
            bboxTop: bboxTop,
            bboxWidth: bboxWidth,
            bboxHeight: bboxHeight,
            baselineY: baselineY,
            ascentY: baselineY - m.ascent,
            descentY: baselineY + m.descent
        };
    }

    function collectExtraGlyphDiagnostics(glyph) {
        if (!glyph || typeof glyph !== 'object') return [];
        var known = {
            code: 1,
            codeHex: 1,
            unicode: 1,
            unicodeHex: 1,
            mapped: 1,
            usedCount: 1,
            width: 1,
            canEncodeMappedUnicode: 1,
            displacement: 1,
            positionVector: 1
        };
        var entries = [];
        Object.keys(glyph).forEach(function (key) {
            if (known[key]) return;
            var value = glyph[key];
            if (value == null || value === '') return;
            var printable;
            if (typeof value === 'object') {
                try {
                    printable = JSON.stringify(value);
                } catch (e) {
                    printable = String(value);
                }
            } else {
                printable = String(value);
            }
            entries.push({ key: key, value: printable });
        });
        return entries;
    }

    function extractOutlineStats(detail, glyph) {
        var candidate =
            (glyph && (glyph.outline || glyph.path || glyph.shape || glyph.vectorPath)) ||
            (detail && (detail.glyphOutline || detail.outline || detail.path || detail.vectorPath));
        if (!candidate || typeof candidate !== 'object') return null;

        var contours = 0;
        var splines = 0;
        var segments = 0;

        if (Array.isArray(candidate.contours)) {
            contours = candidate.contours.length;
            candidate.contours.forEach(function (c) {
                if (Array.isArray(c.splines)) splines += c.splines.length;
                if (Array.isArray(c.segments)) segments += c.segments.length;
                if (Array.isArray(c)) segments += c.length;
            });
        }
        if (Array.isArray(candidate.splines)) splines = Math.max(splines, candidate.splines.length);
        if (Array.isArray(candidate.segments)) segments = Math.max(segments, candidate.segments.length);
        if (Array.isArray(candidate.commands)) segments = Math.max(segments, candidate.commands.length);

        if (contours === 0 && splines === 0 && segments === 0) {
            return null;
        }
        return { contours: contours, splines: splines, segments: segments };
    }

    function initGlyphCanvasViewer(canvas, viewport, metricsEl) {
        if (!canvas || !viewport) return;

        var primaryFontFamily = String(canvas.getAttribute('data-font-primary') || '').trim();
        var glyphText = String(canvas.getAttribute('data-glyph') || '').trim() || ' ';
        var viewportWidth = Math.max(320, viewport.clientWidth || 320);
        var viewportHeight = Math.max(260, viewport.clientHeight || 260);
        var baseWidth = viewportWidth;
        var baseHeight = viewportHeight;
        var renderScale = 4;
        var fitPaddingRatio = 0.10;
        var defaultZoom = 1 / renderScale;

        activeGlyphCanvasState = {
            canvas: canvas,
            viewport: viewport,
            metricsEl: metricsEl,
            baseWidth: baseWidth,
            baseHeight: baseHeight,
            renderScale: renderScale,
            glyphScale: 4,
            defaultZoom: defaultZoom,
            initialZoom: defaultZoom,
            zoom: defaultZoom,
            minZoom: 0.1,
            maxZoom: 4,
            widthHintUnits: parseFloat(canvas.getAttribute('data-glyph-width')),
            fitPaddingRatio: fitPaddingRatio,
            userAdjustedZoom: false
        };

        function applyViewportOverflow() {
            if (!activeGlyphCanvasState) return;
            var epsilon = 0.001;
            viewport.style.overflow = (activeGlyphCanvasState.zoom > (activeGlyphCanvasState.initialZoom + epsilon)) ? 'auto' : 'hidden';
        }

        function updateZoomLabel() {
            $('#fontGlyphZoomLabel').text(Math.round(activeGlyphCanvasState.zoom * 100) + '%');
        }

        function drawCurrent() {
            if (!activeGlyphCanvasState) return;
            var layout = drawGlyphDiagnosticsCanvas(canvas, {
                metricsEl: metricsEl,
                widthHintUnits: activeGlyphCanvasState.widthHintUnits,
                glyphScale: activeGlyphCanvasState.glyphScale,
                logicalWidth: Math.round(activeGlyphCanvasState.baseWidth * activeGlyphCanvasState.renderScale),
                logicalHeight: Math.round(activeGlyphCanvasState.baseHeight * activeGlyphCanvasState.renderScale)
            });
            canvas.style.width = Math.round(activeGlyphCanvasState.baseWidth * activeGlyphCanvasState.renderScale * activeGlyphCanvasState.zoom) + 'px';
            canvas.style.height = Math.round(activeGlyphCanvasState.baseHeight * activeGlyphCanvasState.renderScale * activeGlyphCanvasState.zoom) + 'px';
            updateZoomLabel();
            return layout;
        }

        function computeFitZoom(layout) {
            if (!layout) return activeGlyphCanvasState.zoom;
            var contentWidth = Math.max(1, layout.fitWidth || layout.contentWidth);
            var contentHeight = Math.max(1, layout.fitHeight || layout.contentHeight);
            var paddedWidth = contentWidth * (1 + activeGlyphCanvasState.fitPaddingRatio);
            var paddedHeight = contentHeight * (1 + activeGlyphCanvasState.fitPaddingRatio);
            var fitByWidth = viewport.clientWidth / paddedWidth;
            var fitByHeight = viewport.clientHeight / paddedHeight;
            var fitZoom = Math.min(fitByWidth, fitByHeight);
            if (!isFinite(fitZoom) || fitZoom <= 0) return activeGlyphCanvasState.zoom;
            fitZoom = clampNumber(fitZoom, activeGlyphCanvasState.minZoom, activeGlyphCanvasState.maxZoom);
            return clampNumber(Math.min(activeGlyphCanvasState.defaultZoom, fitZoom) * 4, activeGlyphCanvasState.minZoom, activeGlyphCanvasState.maxZoom);
        }

        function setZoom(nextZoom, userInitiated) {
            if (!activeGlyphCanvasState) return;
            if (userInitiated) {
                activeGlyphCanvasState.userAdjustedZoom = true;
            }
            var prevWidth = canvas.clientWidth || activeGlyphCanvasState.baseWidth;
            var prevHeight = canvas.clientHeight || activeGlyphCanvasState.baseHeight;
            var centerX = viewport.scrollLeft + (viewport.clientWidth / 2);
            var centerY = viewport.scrollTop + (viewport.clientHeight / 2);
            var centerRatioX = prevWidth > 0 ? (centerX / prevWidth) : 0.5;
            var centerRatioY = prevHeight > 0 ? (centerY / prevHeight) : 0.5;

            activeGlyphCanvasState.zoom = clampNumber(nextZoom, activeGlyphCanvasState.minZoom, activeGlyphCanvasState.maxZoom);
            drawCurrent();

            var nextWidth = canvas.clientWidth || prevWidth;
            var nextHeight = canvas.clientHeight || prevHeight;
            var targetCenterX = nextWidth * centerRatioX;
            var targetCenterY = nextHeight * centerRatioY;
            viewport.scrollLeft = Math.max(0, targetCenterX - (viewport.clientWidth / 2));
            viewport.scrollTop = Math.max(0, targetCenterY - (viewport.clientHeight / 2));
            applyViewportOverflow();
        }

        function fitAndCenterInitial() {
            if (!activeGlyphCanvasState || activeGlyphCanvasState.userAdjustedZoom) return;
            var layout = drawCurrent();
            var computedInitialZoom = computeFitZoom(layout);
            activeGlyphCanvasState.initialZoom = computedInitialZoom;
            activeGlyphCanvasState.zoom = computedInitialZoom;
            drawCurrent();
            viewport.scrollLeft = Math.max(0, (canvas.clientWidth - viewport.clientWidth) / 2);
            viewport.scrollTop = Math.max(0, (canvas.clientHeight - viewport.clientHeight) / 2);
            applyViewportOverflow();
        }

        $('#fontGlyphZoomIn').off('click').on('click', function () {
            setZoom(activeGlyphCanvasState.zoom * 1.2, true);
        });
        $('#fontGlyphZoomOut').off('click').on('click', function () {
            setZoom(activeGlyphCanvasState.zoom / 1.2, true);
        });
        $('#fontGlyphZoomFit').off('click').on('click', function () {
            activeGlyphCanvasState.userAdjustedZoom = false;
            fitAndCenterInitial();
        });
        $(viewport).off('wheel.glyphzoom').on('wheel.glyphzoom', function (ev) {
            var oe = ev.originalEvent || ev;
            if (!oe.ctrlKey) return;
            ev.preventDefault();
            var delta = typeof oe.deltaY === 'number' ? oe.deltaY : 0;
            setZoom(delta < 0 ? (activeGlyphCanvasState.zoom * 1.15) : (activeGlyphCanvasState.zoom / 1.15), true);
        });

        var dragState = {
            active: false,
            startX: 0,
            startY: 0,
            startLeft: 0,
            startTop: 0
        };

        $(viewport).removeClass('glyph-pan-active')
            .off('mousedown.glyphpan')
            .on('mousedown.glyphpan', function (ev) {
                if (ev.button !== 0) return;
                dragState.active = true;
                dragState.startX = ev.clientX;
                dragState.startY = ev.clientY;
                dragState.startLeft = viewport.scrollLeft;
                dragState.startTop = viewport.scrollTop;
                $(viewport).addClass('glyph-pan-active');
                ev.preventDefault();
            });

        $(document).off('mousemove.glyphpan').on('mousemove.glyphpan', function (ev) {
            if (!dragState.active) return;
            var dx = ev.clientX - dragState.startX;
            var dy = ev.clientY - dragState.startY;
            viewport.scrollLeft = dragState.startLeft - dx;
            viewport.scrollTop = dragState.startTop - dy;
        });

        $(document).off('mouseup.glyphpan').on('mouseup.glyphpan', function () {
            if (!dragState.active) return;
            dragState.active = false;
            $(viewport).removeClass('glyph-pan-active');
        });

        $(viewport).off('mouseleave.glyphpan').on('mouseleave.glyphpan', function () {
            if (!dragState.active) return;
            dragState.active = false;
            $(viewport).removeClass('glyph-pan-active');
        });

        fitAndCenterInitial();
        setTimeout(fitAndCenterInitial, 120);
        setTimeout(fitAndCenterInitial, 380);
        if (document.fonts && document.fonts.ready && typeof document.fonts.ready.then === 'function') {
            document.fonts.ready.then(function () {
                fitAndCenterInitial();
            });
        }
        if (primaryFontFamily && document.fonts && typeof document.fonts.load === 'function') {
            document.fonts.load('64px "' + primaryFontFamily + '"', glyphText).then(function () {
                fitAndCenterInitial();
            }).catch(function () {
            });
        }
    }

    function bindLazyGlyphRendering() {
        var nodes = Array.from(document.querySelectorAll('#fontDiagDetail .font-glyph-lazy[data-unicode]'));
        if (!nodes.length) return;

        if (activeGlyphObserver && typeof activeGlyphObserver.disconnect === 'function') {
            activeGlyphObserver.disconnect();
            activeGlyphObserver = null;
        }

        function renderGlyphNode(node) {
            if (!node || node.getAttribute('data-rendered') === '1') return;
            var unicode = node.getAttribute('data-unicode') || '';
            var unicodeHex = node.getAttribute('data-unicode-hex') || '';
            var previewText = resolveGlyphPreviewText(unicode, unicodeHex);
            var fontFamily = node.getAttribute('data-font-family') || '';
            var widthHintUnits = parseFloat(node.getAttribute('data-glyph-width'));
            var resolvedFamily = fontFamily
                ? ("'" + fontFamily + "', 'Segoe UI Symbol', 'Segoe UI', sans-serif")
                : "'Segoe UI Symbol', 'Segoe UI', sans-serif";

            node.setAttribute('data-rendered', '1');
            node.innerHTML = '';

            var previewCanvas = document.createElement('canvas');
            previewCanvas.className = 'font-glyph-preview font-glyph-preview-canvas';
            previewCanvas.title = 'Rendered glyph preview';
            previewCanvas.setAttribute('data-unicode', previewText);
            previewCanvas.setAttribute('data-font-family', resolvedFamily);
            previewCanvas.setAttribute('data-font-primary', fontFamily);
            previewCanvas.setAttribute('data-glyph-width', String(isNaN(widthHintUnits) ? '' : widthHintUnits));
            node.appendChild(previewCanvas);
            if (!previewText) {
                node.setAttribute('title', 'No glyph present');
                return;
            }
            drawGlyphPreviewCanvas(previewCanvas, previewText, resolvedFamily, widthHintUnits);
            if (fontFamily && document.fonts && typeof document.fonts.load === 'function') {
                document.fonts.load('32px "' + fontFamily + '"', previewText).then(function () {
                    drawGlyphPreviewCanvas(previewCanvas, previewText, resolvedFamily, widthHintUnits);
                }).catch(function () {
                });
            }
        }

        function redrawAllPreviewCanvases() {
            var canvases = document.querySelectorAll('#fontDiagDetail .font-glyph-preview-canvas[data-unicode]');
            canvases.forEach(function (canvas) {
                var unicode = canvas.getAttribute('data-unicode') || '';
                var fontFamily = canvas.getAttribute('data-font-family') || "'Segoe UI Symbol', 'Segoe UI', sans-serif";
                var primaryFamily = String(canvas.getAttribute('data-font-primary') || '').trim();
                var widthHintUnits = parseFloat(canvas.getAttribute('data-glyph-width'));
                drawGlyphPreviewCanvas(canvas, unicode, fontFamily, widthHintUnits);
                if (primaryFamily && document.fonts && typeof document.fonts.load === 'function') {
                    document.fonts.load('32px "' + primaryFamily + '"', unicode).then(function () {
                        drawGlyphPreviewCanvas(canvas, unicode, fontFamily, widthHintUnits);
                    }).catch(function () {
                    });
                }
            });
        }

        function renderVisibleBatch() {
            var rendered = 0;
            nodes.forEach(function (node) {
                if (node.getAttribute('data-rendered') === '1') return;
                var rect = node.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return;
                if (rect.bottom < -100 || rect.top > (window.innerHeight + 300)) return;
                renderGlyphNode(node);
                rendered += 1;
            });
            return rendered;
        }

        renderVisibleBatch();

        if ('IntersectionObserver' in window) {
            activeGlyphObserver = new IntersectionObserver(function (entries) {
                entries.forEach(function (entry) {
                    if (!entry.isIntersecting) return;
                    renderGlyphNode(entry.target);
                    activeGlyphObserver.unobserve(entry.target);
                });
            }, { root: null, rootMargin: '180px 0px', threshold: 0.01 });

            nodes.forEach(function (node) {
                if (node.getAttribute('data-rendered') === '1') return;
                activeGlyphObserver.observe(node);
            });
        }

        if (!('IntersectionObserver' in window)) {
            setTimeout(function () {
                renderVisibleBatch();
            }, 250);
        }

        setTimeout(redrawAllPreviewCanvases, 120);
        setTimeout(redrawAllPreviewCanvases, 380);
        if (document.fonts && document.fonts.ready && typeof document.fonts.ready.then === 'function') {
            document.fonts.ready.then(function () {
                redrawAllPreviewCanvases();
            });
        }
    }

    function bindGlyphHoverTooltips() {
        var $scope = $('#fontDiagDetail');
        if (!$scope.length) return;

        $scope.off('.glyphtooltip');
        $(document).off('mousemove.glyphtooltip');

        function removeTooltip() {
            $('.glyph-tooltip-preview').remove();
            $(document).off('mousemove.glyphtooltip');
        }

        $scope.on('mouseenter.glyphtooltip', '.font-glyph-preview', function (e) {
            removeTooltip();

            var glyphText = $(this).text() || '';
            if (!glyphText.trim()) return;

            var computed = window.getComputedStyle(this);
            var fontFamily = computed && computed.fontFamily ? computed.fontFamily : "'Segoe UI Symbol', 'Segoe UI', sans-serif";
            var container = this.parentElement;
            var widthHintUnits = container ? parseFloat(container.getAttribute('data-glyph-width')) : NaN;

            var $tooltip = $('<div>', { 'class': 'glyph-tooltip-preview' });
            var $glyph = $('<div>', { 'class': 'glyph-tooltip-char', text: glyphText });
            $glyph.css('font-family', fontFamily);
            $tooltip.append($glyph).appendTo('body');

            var maxSide = Math.max(120, Math.min(260, Math.floor(Math.min(window.innerWidth, window.innerHeight) * 0.26)));
            $glyph.css({ width: maxSide + 'px', height: maxSide + 'px' });
            fitGlyphInElement($glyph[0], {
                text: glyphText,
                fontFamily: fontFamily,
                maxWidth: maxSide - 8,
                maxHeight: maxSide - 8,
                minFontSize: 22,
                maxFontSize: 220,
                widthHintUnits: isNaN(widthHintUnits) ? NaN : widthHintUnits
            });

            var updatePos = function (ev) {
                var tipW = $tooltip.outerWidth() || 0;
                var tipH = $tooltip.outerHeight() || 0;
                var viewLeft = window.scrollX;
                var viewTop = window.scrollY;
                var maxLeft = viewLeft + window.innerWidth - tipW - 8;
                var maxTop = viewTop + window.innerHeight - tipH - 8;
                var left = Math.min(ev.pageX + 16, maxLeft);
                var top = Math.min(ev.pageY + 16, maxTop);
                left = Math.max(viewLeft + 8, left);
                top = Math.max(viewTop + 8, top);
                $tooltip.css({ left: left + 'px', top: top + 'px' });
            };
            updatePos(e);
            $(document).on('mousemove.glyphtooltip', updatePos);
        });

        $scope.on('mouseleave.glyphtooltip', '.font-glyph-preview', function () {
            removeTooltip();
        });
    }

    function escapeHtmlAttr(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    function enableTableInteractions($table) {
        if (!$table || !$table.length) return;
        var $thead = $table.find('thead');
        if (!$thead.length) return;
        var $ths = $thead.find('th');
        if (!$ths.length) return;

        var $tbody = $table.find('tbody').first();
        if (!$tbody.length) return;

        var tableId = $table.attr('id') || ('tbl_' + Math.random().toString(36).slice(2));
        $table.attr('data-table-interactive-id', tableId);

        var disableHeaderFilter = $table.attr('data-disable-header-filter') === '1';
        if (!disableHeaderFilter) {
            var $filterHost = $thead.find('th.table-filter-col').first();
            if (!$filterHost.length) {
                $filterHost = $ths.last();
            }
            if (!$filterHost.find('.table-header-tools').length) {
                var $tools = $('<span class="table-header-tools"></span>');
                var $input = $('<input type="text" class="form-control form-control-sm table-filter-input" placeholder="Filter table..." />');
                $tools.append($input);
                $filterHost.append($tools);

                $input.off('input').on('input', function () {
                    var query = String($(this).val() || '').toLowerCase();
                    filterTableRows($table, query);
                });
            }
        }

        $ths.each(function (colIndex) {
            var $th = $(this);
            if ($th.hasClass('table-filter-col')) return;
            if ($th.hasClass('table-no-sort')) return;
            if ($th.find('.table-header-tools').length) return;
            $th.addClass('table-sortable').attr('title', ($th.attr('title') || '') + ' (click to sort)');
            $th.off('click.tablesort').on('click.tablesort', function (ev) {
                if ($(ev.target).closest('.table-header-tools').length) return;
                var currentIndex = parseInt($table.attr('data-sort-index'), 10);
                var currentDir = $table.attr('data-sort-dir') || 'none';
                var nextDir = 'asc';
                if (currentIndex === colIndex && currentDir === 'asc') nextDir = 'desc';
                else if (currentIndex === colIndex && currentDir === 'desc') nextDir = 'asc';

                sortTableByColumn($table, colIndex, nextDir);
                $table.attr('data-sort-index', String(colIndex));
                $table.attr('data-sort-dir', nextDir);

                $ths.removeClass('table-sort-asc table-sort-desc');
                $th.addClass(nextDir === 'asc' ? 'table-sort-asc' : 'table-sort-desc');
            });
        });
    }

    function getSortableDataRows($tbody) {
        return $tbody.children('tr').filter(function () {
            return $(this).children('td').length > 1;
        });
    }

    function sortTableByColumn($table, columnIndex, direction) {
        var $tbody = $table.find('tbody').first();
        if (!$tbody.length) return;

        var rows = getSortableDataRows($tbody).get();
        if (!rows.length) return;

        rows.sort(function (a, b) {
            var av = getCellSortValue(a, columnIndex);
            var bv = getCellSortValue(b, columnIndex);

            var an = parseFloat(av.replace(/[^0-9+\-.]/g, ''));
            var bn = parseFloat(bv.replace(/[^0-9+\-.]/g, ''));
            var bothNumeric = !Number.isNaN(an) && !Number.isNaN(bn);
            var cmp = 0;
            if (bothNumeric) {
                cmp = an - bn;
            } else {
                cmp = av.localeCompare(bv, undefined, { numeric: true, sensitivity: 'base' });
            }
            return direction === 'desc' ? -cmp : cmp;
        });

        rows.forEach(function (row) {
            $tbody.append(row);
        });
    }

    function getCellSortValue(row, columnIndex) {
        var $cell = $(row).children('td').eq(columnIndex);
        return String($cell.text() || '').trim();
    }

    function filterTableRows($table, query) {
        var $tbody = $table.find('tbody').first();
        if (!$tbody.length) return;
        var usedOnly = false;
        if (($table.attr('id') || '') === 'fontDetailMapTable') {
            usedOnly = !!$table.find('.table-used-only-toggle').is(':checked');
        }

        getSortableDataRows($tbody).each(function () {
            var $row = $(this);
            var text = String($row.text() || '').toLowerCase();
            var queryOk = !query || text.indexOf(query) !== -1;
            var usedOk = true;
            if (usedOnly) {
                var usedValue = parseFloat(String($row.children('td').eq(5).text() || '').replace(/[^0-9+\-.]/g, ''));
                usedOk = !Number.isNaN(usedValue) && usedValue > 0;
            }
            $row.toggle(queryOk && usedOk);
        });
    }

    function makeGlyphHighlightKey(obj, gen, code) {
        return String(obj) + ':' + String(gen || 0) + ':' + String(code);
    }

    function getGlyphHighlightColor(index) {
        if (index < glyphHighlightPalette.length) {
            return glyphHighlightPalette[index];
        }
        var hue = Math.round((index * 137.508) % 360);
        return 'hsl(' + hue + ', 85%, 52%)';
    }

    function nextGlyphHighlightColorIndex() {
        var used = {};
        Object.keys(activeGlyphHighlightSelections).forEach(function (key) {
            var item = activeGlyphHighlightSelections[key];
            if (item && item.colorIndex >= 0) used[item.colorIndex] = true;
        });
        for (var idx = 0; idx < glyphHighlightPalette.length; idx += 1) {
            if (!used[idx]) return idx;
        }
        while (used[nextGeneratedGlyphColorIndex]) {
            nextGeneratedGlyphColorIndex += 1;
        }
        var generated = nextGeneratedGlyphColorIndex;
        nextGeneratedGlyphColorIndex += 1;
        return generated;
    }

    function getGlyphHighlightEntry(obj, gen, code) {
        if (!(obj >= 0) || !(gen >= 0) || !(code >= 0)) return null;
        return activeGlyphHighlightSelections[makeGlyphHighlightKey(obj, gen, code)] || null;
    }

    function applyGlyphHighlightButtonState($btn, entry) {
        if (!$btn || !$btn.length) return;
        if (entry && entry.color) {
            $btn.addClass('is-active');
            $btn.css({ borderColor: entry.color, color: entry.color, backgroundColor: 'rgba(255,255,255,0.05)' });
        } else {
            $btn.removeClass('is-active');
            $btn.css({ borderColor: '', color: '', backgroundColor: '' });
        }
    }

    function syncGlyphHighlightToggleButtons() {
        $('#fontDiagDetail .font-map-highlight-btn').each(function () {
            var $btn = $(this);
            var obj = parseCodeValue($btn.attr('data-obj'));
            var gen = parseCodeValue($btn.attr('data-gen'));
            var code = parseCodeValue($btn.attr('data-code'));
            var entry = getGlyphHighlightEntry(obj, gen, code);
            applyGlyphHighlightButtonState($btn, entry);
        });

        var $popupBtn = $('#fontGlyphHighlightBtn');
        if ($popupBtn.length) {
            var pobj = parseCodeValue($popupBtn.attr('data-obj'));
            var pgen = parseCodeValue($popupBtn.attr('data-gen'));
            var pcode = parseCodeValue($popupBtn.attr('data-code'));
            applyGlyphHighlightButtonState($popupBtn, getGlyphHighlightEntry(pobj, pgen, pcode));
        }
    }

    function rebuildGlyphToggleHighlights() {
        var merged = [];
        Object.keys(activeGlyphHighlightSelections).forEach(function (key) {
            var entry = activeGlyphHighlightSelections[key];
            if (!entry || !entry.areas || !entry.areas.length) return;
            entry.areas.forEach(function (area) {
                merged.push($.extend({}, area, { _hlColor: entry.color, _hlKey: key }));
            });
        });

        if (!merged.length) {
            clearFontUsageHighlights({ resetState: true, resetGlyphToggles: false });
            syncGlyphHighlightToggleButtons();
            return { found: 0, highlighted: 0 };
        }

        var usage = showFontUsageHighlights(merged, { clearExisting: true, persist: true });
        syncGlyphHighlightToggleButtons();
        return usage;
    }

    function toggleGlyphHighlightForCode(obj, gen, code) {
        if (!(obj >= 0) || !(gen >= 0) || !(code >= 0)) {
            return;
        }

        var key = makeGlyphHighlightKey(obj, gen, code);
        if (activeGlyphHighlightSelections[key]) {
            delete activeGlyphHighlightSelections[key];
            rebuildGlyphToggleHighlights();
            return;
        }

        P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen + '/glyph/' + code)
            .done(function (areas) {
                var list = Array.isArray(areas) ? areas.slice() : [];
                if (!list.length) {
                    P.Utils.toast('No usage positions found for this glyph.', 'warning');
                    return;
                }
                var colorIndex = nextGlyphHighlightColorIndex();
                activeGlyphHighlightSelections[key] = {
                    obj: obj,
                    gen: gen,
                    code: code,
                    colorIndex: colorIndex,
                    color: getGlyphHighlightColor(colorIndex),
                    areas: list
                };
                var usage = rebuildGlyphToggleHighlights();
                P.Utils.toast('Found: ' + usage.found + ' | Highlighted: ' + usage.highlighted, usage.highlighted ? 'info' : 'warning');
            })
            .fail(function () {
                P.Utils.toast('Failed to load glyph usage overlays', 'danger');
            });
    }

    function clearFontUsageHighlights(options) {
        var resetState = !options || options.resetState !== false;
        var resetGlyphToggles = !!(options && options.resetGlyphToggles);
        $('.pdf-font-usage').remove();
        if (resetState) {
            activeFontUsageAreas = [];
        }
        if (resetGlyphToggles) {
            activeGlyphHighlightSelections = {};
            nextGeneratedGlyphColorIndex = glyphHighlightPalette.length;
            syncGlyphHighlightToggleButtons();
        }
    }

    function showFontUsageHighlights(areas, options) {
        var clearExisting = !options || options.clearExisting !== false;
        var persist = !options || options.persist !== false;
        if (clearExisting) {
            clearFontUsageHighlights({ resetState: persist });
        }
        if (persist) {
            activeFontUsageAreas = (areas || []).slice();
        }
        var found = 0;
        var highlighted = 0;
        if (!areas || !areas.length) return { found: 0, highlighted: 0 };
        areas.forEach(function (area) {
            var pageIndex = parseInt(area.pageIndex, 10);
            var bbox = getUsageBBox(area);
            found += (area.glyphCount || 1);
            if (!(pageIndex >= 0) || !bbox || bbox.length < 4) return;
            var $wrapper = $('#pdfViewer .pdf-page-wrapper[data-page="' + pageIndex + '"]');
            var viewport = P.state.pageViewports[pageIndex];
            if (!$wrapper.length || !viewport) return;
            var outlineColor = String(area._hlColor || '#dc3545');
            $('<div>', { 'class': 'pdf-font-usage' }).css({
                left: bbox[0] * viewport.scale + 'px',
                top: bbox[1] * viewport.scale + 'px',
                width: bbox[2] * viewport.scale + 'px',
                height: bbox[3] * viewport.scale + 'px',
                outlineColor: outlineColor
            }).appendTo($wrapper);
            highlighted += 1;
        });
        return { found: found, highlighted: highlighted };
    }

    function applyGlobalDiagnosticsTableInteractions() {
        var selectors = [
            '#treeContent .font-table',
            '#fontDiagDetail .font-detail-table',
            '#fontGlyphDetailBody .font-detail-table',
            '#fontGlyphUsagePanel .font-detail-table'
        ];
        selectors.forEach(function (selector) {
            $(selector).each(function () {
                enableTableInteractions($(this));
            });
        });
    }

    // ======================== VALIDATION TAB ========================

    function getValidationControlsHtml(disableExport) {
        return '<div class="text-center mt-3">' +
            '<button class="btn btn-accent btn-sm me-2" id="runValidateBtn">' +
            '<i class="fas fa-play me-1"></i>Run Standard Validation</button>' +
            '<button class="btn btn-outline-accent btn-sm me-2" id="runVeraPdfBtn">' +
            '<i class="fas fa-file-alt me-1"></i>Run veraPDF</button>' +
            '<button class="btn btn-outline-accent btn-sm" id="exportValidateBtn"' + (disableExport ? ' disabled' : '') + '>' +
            '<i class="fas fa-download me-1"></i>Export Report</button></div>';
    }

    function runStandardValidation() {
        var $c = $('#treeContent');
        $c.html('<div class="text-muted text-center mt-3">' +
            '<span class="spinner-border spinner-border-sm"></span> Running standard validation...</div>');
        P.Utils.apiFetch('/api/validate/' + P.state.sessionId)
            .done(function (issues) { renderValidation(issues); })
            .fail(function () { P.Utils.toast('Validation error', 'danger'); });
    }

    function runVeraPdfValidation() {
        var $c = $('#treeContent');
        $c.html('<div class="text-muted text-center mt-3">' +
            '<span class="spinner-border spinner-border-sm"></span> Running veraPDF...</div>');
        P.Utils.apiFetch('/api/validate/' + P.state.sessionId + '/verapdf')
            .done(function (result) {
                renderVeraPdf(result || {});
            })
            .fail(function () {
                P.Utils.toast('veraPDF validation error', 'danger');
            });
    }

    function bindValidationControls() {
        $('#runValidateBtn').off('click').on('click', function () {
            runStandardValidation();
        });
        $('#exportValidateBtn').off('click').on('click', function () {
            window.open('/api/validate/' + P.state.sessionId + '/export', '_blank');
        });
        $('#runVeraPdfBtn').off('click').on('click', function () {
            runVeraPdfValidation();
        });
    }

    function loadValidation() {
        var $c = $('#treeContent');
        $c.html(getValidationControlsHtml(true));
        bindValidationControls();

        runStandardValidation();
    }

    function renderVeraPdf(result) {
        var available = !!result.available;
        var success = !!result.success;
        var report = result.report || '';
        var reportFormat = (result.reportFormat || '').toLowerCase();
        var looksLikeHtml = report.indexOf('<html') !== -1 || report.indexOf('<!DOCTYPE html') !== -1;
        var looksLikeXml = /^\s*<\?xml|^\s*<report[\s>]/i.test(report);
        var header = '<div class="mb-2 d-flex justify-content-between align-items-center gap-2" style="padding:8px;">' +
            '<div>' +
            '<span class="badge ' + (success ? 'bg-success' : 'bg-secondary') + ' me-2">' +
            (success ? 'PASS' : 'CHECK') + '</span>' +
            '<span class="text-muted">veraPDF ' + (available ? 'result' : 'not available') + '</span>' +
            '</div>' +
            '<div>' +
            '<button class="btn btn-outline-accent btn-sm me-1" id="exportVeraPdfHtmlBtn"><i class="fas fa-code me-1"></i>Export HTML</button>' +
            '<button class="btn btn-outline-accent btn-sm" id="exportVeraPdfPdfBtn"><i class="fas fa-file-pdf me-1"></i>Export PDF</button>' +
            '</div>' +
            '</div>';

        if (report && (reportFormat === 'html' || looksLikeHtml)) {
            $('#treeContent').html(
                header +
                '<div style="padding:8px;">' +
                '<iframe title="veraPDF Report" style="width:100%;height:520px;border:1px solid var(--border);border-radius:4px;background:#fff;"></iframe>' +
                '</div>'
            );
            $('#treeContent iframe')[0].srcdoc = report;
            setVeraPdfExportContent(report, 'veraPDF Detailed HTML Report');
            bindVeraPdfExportButtons();
            return;
        }

        if (report && (reportFormat === 'xml' || reportFormat === 'mrr' || looksLikeXml)) {
            renderVeraPdfXmlReport(header, report);
            bindVeraPdfExportButtons();
            return;
        }

        $('#treeContent').html(
            header +
            '<div style="padding:8px;">' +
            '<pre style="white-space:pre-wrap;max-height:480px;overflow:auto;">' +
            P.Utils.escapeHtml(report || 'No report output') +
            '</pre></div>'
        );
        setVeraPdfExportContent(
            wrapHtmlDocument('veraPDF Report', '<pre>' + P.Utils.escapeHtml(report || 'No report output') + '</pre>'),
            'veraPDF Report'
        );
        bindVeraPdfExportButtons();
    }

    function renderVeraPdfXmlReport(header, xmlReport) {
        var parser = new DOMParser();
        var doc = parser.parseFromString(xmlReport, 'application/xml');
        if (doc.getElementsByTagName('parsererror').length) {
            $('#treeContent').html(
                header +
                '<div style="padding:8px;">' +
                '<pre style="white-space:pre-wrap;max-height:520px;overflow:auto;">' +
                P.Utils.escapeHtml(xmlReport) +
                '</pre></div>'
            );
            return;
        }

        var validationReport = doc.querySelector('jobs > job > validationReport');
        var details = validationReport ? validationReport.querySelector('details') : null;
        var rules = details ? Array.from(details.querySelectorAll('rule')) : [];
        var failedRules = rules.filter(function (r) {
            return String(r.getAttribute('status') || '').toLowerCase() !== 'passed';
        });
        var passedRules = rules.filter(function (r) {
            return String(r.getAttribute('status') || '').toLowerCase() === 'passed';
        });

        var totalFailedChecks = details ? parseInt(details.getAttribute('failedChecks') || '0', 10) : 0;
        var totalPassedChecks = details ? parseInt(details.getAttribute('passedChecks') || '0', 10) : 0;
        var profileName = validationReport ? (validationReport.getAttribute('profileName') || '') : '';
        var statement = validationReport ? (validationReport.getAttribute('statement') || '') : '';

        var html = header +
            '<style id="verapdfInlineStyle">' + getVeraPdfReportCss() + '</style>' +
            '<div style="padding:8px;">' +
            '<div class="card mb-2"><div class="card-body py-2">' +
            '<div class="d-flex flex-wrap gap-2 align-items-center">' +
            '<button class="badge bg-danger border-0 verapdf-jump-badge verapdf-badge-failed" data-target="#verapdfFailedRules" style="cursor:pointer;">Failed rules: ' + failedRules.length + '</button>' +
            '<button class="badge bg-success border-0 verapdf-jump-badge verapdf-badge-passed" data-target="#verapdfPassedRules" style="cursor:pointer;">Passed rules: ' + passedRules.length + '</button>' +
            '<button class="badge bg-danger-subtle text-danger border-0 verapdf-jump-badge verapdf-badge-failed" data-target="#verapdfFailedRules" style="cursor:pointer;">Failed checks: ' + (isNaN(totalFailedChecks) ? 0 : totalFailedChecks) + '</button>' +
            '<button class="badge bg-success-subtle text-success border-0 verapdf-jump-badge verapdf-badge-passed" data-target="#verapdfPassedRules" style="cursor:pointer;">Passed checks: ' + (isNaN(totalPassedChecks) ? 0 : totalPassedChecks) + '</button>' +
            '</div>' +
            (profileName ? '<div class="text-muted mt-2" style="font-size:12px;">Profile: ' + P.Utils.escapeHtml(profileName) + '</div>' : '') +
            (statement ? '<div class="mt-1">' + P.Utils.escapeHtml(statement) + '</div>' : '') +
            '</div></div>';

        html += '<div class="card mb-2" id="verapdfFailedRules"><div class="card-header py-2"><strong class="text-danger verapdf-section-title verapdf-section-failed">Failed rules</strong></div><div class="card-body py-2">';
        if (!failedRules.length) {
            html += '<div class="text-success">No failed rules.</div>';
        } else {
            failedRules.forEach(function (rule) {
                html += buildRuleHtml(rule, true);
            });
        }
        html += '</div></div>';

        html += '<details id="verapdfPassedRules"><summary class="text-muted verapdf-section-title verapdf-section-passed" style="cursor:pointer;">Show passed rules (' + passedRules.length + ')</summary>' +
            '<div class="card mt-2"><div class="card-body py-2">';
        if (!passedRules.length) {
            html += '<div class="text-muted">No passed rules.</div>';
        } else {
            passedRules.forEach(function (rule) {
                html += buildRuleHtml(rule, false);
            });
        }
        html += '</div></div></details>';

        html += '<details class="mt-2"><summary class="text-muted" style="cursor:pointer;">Show raw XML</summary>' +
            '<pre style="white-space:pre-wrap;max-height:240px;overflow:auto;" class="mt-2">' + P.Utils.escapeHtml(xmlReport) + '</pre></details>';

        html += '</div>';
        $('#treeContent').html(html);
        bindVeraPdfSummaryJumps();
        setVeraPdfExportContent(wrapHtmlDocument('veraPDF Detailed Report', html), 'veraPDF Detailed Report');
    }

    function bindVeraPdfSummaryJumps() {
        $('#treeContent').find('.verapdf-jump-badge').off('click').on('click', function (e) {
            e.preventDefault();
            var selector = $(this).attr('data-target');
            if (!selector) return;
            var $target = $('#treeContent').find(selector).first();
            if (!$target.length) return;

            if ($target.is('details') && !$target.prop('open')) {
                $target.prop('open', true);
            }

            var element = $target[0];
            if (element && element.scrollIntoView) {
                element.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        });
    }

    function buildRuleHtml(ruleEl, failed) {
        var spec = ruleEl.getAttribute('specification') || '';
        var clause = ruleEl.getAttribute('clause') || '';
        var testNumber = ruleEl.getAttribute('testNumber') || '';
        var status = ruleEl.getAttribute('status') || '';
        var passedChecks = ruleEl.getAttribute('passedChecks') || '0';
        var failedChecks = ruleEl.getAttribute('failedChecks') || '0';
        var descNode = ruleEl.querySelector('description');
        var objNode = ruleEl.querySelector('object');
        var testNode = ruleEl.querySelector('test');

        var checks = Array.from(ruleEl.querySelectorAll('check, failedCheck, passedCheck, assertion'));

        var borderColor = failed ? '#b02a37' : '#146c43';
        var html = '<div class="rounded p-2 mb-2 verapdf-rule-box ' + (failed ? 'verapdf-rule-failed' : 'verapdf-rule-passed') + '" style="background:#161a1f;color:#eef2f6;border:3px solid ' + borderColor + ';">' +
            '<div class="d-flex flex-wrap align-items-center gap-2 mb-1">' +
            '<span class="badge verapdf-status-badge ' + (failed ? 'bg-danger' : 'bg-success') + '">' + P.Utils.escapeHtml(status || (failed ? 'failed' : 'passed')) + '</span>' +
            (clause ? '<span class="badge bg-secondary">Clause ' + P.Utils.escapeHtml(clause) + '</span>' : '') +
            (testNumber ? '<span class="badge bg-secondary">Test ' + P.Utils.escapeHtml(testNumber) + '</span>' : '') +
            '<span class="badge ' + (failed ? 'bg-danger' : 'bg-success') + '">Checks ' + P.Utils.escapeHtml(failedChecks) + ' failed / ' + P.Utils.escapeHtml(passedChecks) + ' passed</span>' +
            (objNode ? '<span class="badge bg-dark">Object: ' + P.Utils.escapeHtml(objNode.textContent || '') + '</span>' : '') +
            '</div>' +
            (spec ? '<div style="font-size:12px;color:#d7dee6;">' + P.Utils.escapeHtml(spec) + '</div>' : '') +
            (descNode ? '<div class="mt-1">' + P.Utils.escapeHtml(descNode.textContent || '') + '</div>' : '') +
            (testNode ? '<div class="mt-1"><span class="text-muted" style="font-size:12px;">Check:</span> <code style="color:#eef2f6;background:#0f1115;">' + P.Utils.escapeHtml(testNode.textContent || '') + '</code></div>' : '');

        if (checks.length) {
            html += '<details class="mt-1"><summary style="cursor:pointer;">Check details (' + checks.length + ')</summary>' +
                '<div class="mt-1">';
            checks.forEach(function (checkEl) {
                var text = (checkEl.textContent || '').trim();
                if (text) {
                    html += '<div class="small border-start ps-2 mb-1 verapdf-check-detail" style="border-color:#4b5563 !important;color:#e6ebf1;font-size:10px;line-height:1.35;">' + P.Utils.escapeHtml(text) + '</div>';
                }
            });
            html += '</div></details>';
        }

        html += '</div>';
        return html;
    }

    function setVeraPdfExportContent(html, title) {
        veraPdfExportHtml = html || '';
        veraPdfExportTitle = title || 'veraPDF Report';
    }

    function bindVeraPdfExportButtons() {
        $('#exportVeraPdfHtmlBtn').off('click').on('click', function () {
            if (!veraPdfExportHtml) {
                P.Utils.toast('No veraPDF report available to export', 'warning');
                return;
            }
            var htmlForExport = normalizeExportPadding(veraPdfExportHtml, false);
            var blob = new Blob([htmlForExport], { type: 'text/html;charset=utf-8' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = 'verapdf-report.html';
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(function () { URL.revokeObjectURL(url); }, 0);
        });

        $('#exportVeraPdfPdfBtn').off('click').on('click', function () {
            if (!veraPdfExportHtml) {
                P.Utils.toast('No veraPDF report available to export', 'warning');
                return;
            }
            var htmlForPdf = normalizeExportPadding(veraPdfExportHtml, true);
            var printWin = window.open('', '_blank');
            if (!printWin) {
                P.Utils.toast('Popup blocked. Allow popups to export PDF.', 'warning');
                return;
            }
            printWin.document.open();
            printWin.document.write(htmlForPdf);
            printWin.document.close();
            setTimeout(function () {
                printWin.focus();
                printWin.print();
            }, 250);
        });
    }

    function normalizeExportPadding(html, expandAllDetails) {
        var source = html || '';
        if (!source) return source;
        try {
            var parser = new DOMParser();
            var doc = parser.parseFromString(source, 'text/html');

            Array.from(doc.querySelectorAll('#exportVeraPdfHtmlBtn, #exportVeraPdfPdfBtn')).forEach(function (btn) {
                btn.remove();
            });

            if (expandAllDetails) {
                Array.from(doc.querySelectorAll('details')).forEach(function (d) {
                    d.setAttribute('open', 'open');
                });

                Array.from(doc.querySelectorAll('button.verapdf-jump-badge')).forEach(function (btn) {
                    var span = doc.createElement('span');
                    span.className = btn.className;
                    span.innerHTML = btn.innerHTML;
                    span.setAttribute('style', (btn.getAttribute('style') || '') + ';display:inline-block;');
                    btn.parentNode.replaceChild(span, btn);
                });
            }

            var style = doc.createElement('style');
            style.textContent = '' +
                '.card{padding:0 !important; margin-bottom:20px !important;}' +
                '.card-header{padding:18px 20px !important;}' +
                '.card-body{padding:20px !important;}' +
                '.verapdf-rule-box{padding:18px !important; margin-bottom:16px !important; border-width:3px !important; border-style:solid !important; border-radius:10px !important;}' +
                'pre{padding:18px !important;}' +
                'code{padding:2px 4px; border-radius:4px;}' +
                'details{margin-top:14px !important; margin-bottom:14px !important;}' +
                'details > summary{padding:8px 0 !important;}' +
                '.badge{border:2px solid transparent !important;}' +
                '.bg-danger{background:#b02a37 !important;color:#fff !important;border-color:#b02a37 !important;}' +
                '.bg-success{background:#146c43 !important;color:#fff !important;border-color:#146c43 !important;}' +
                '.bg-secondary{background:#495057 !important;color:#fff !important;border-color:#495057 !important;}' +
                '.bg-dark{background:#212529 !important;color:#fff !important;border-color:#212529 !important;}' +
                getVeraPdfReportCss() +
                '@media print{*{-webkit-print-color-adjust:exact !important;print-color-adjust:exact !important;} body{background:#111 !important;color:#e9ecef !important;}}';

            if (doc.head) {
                doc.head.appendChild(style);
            } else {
                var head = doc.createElement('head');
                head.appendChild(style);
                if (doc.documentElement.firstChild) {
                    doc.documentElement.insertBefore(head, doc.documentElement.firstChild);
                } else {
                    doc.documentElement.appendChild(head);
                }
            }

            return '<!doctype html>' + doc.documentElement.outerHTML;
        } catch (e) {
            if (expandAllDetails) {
                return source.replace(/<details(\s|>)/gi, '<details open$1');
            }
            return source;
        }
    }

    function wrapHtmlDocument(title, bodyHtml) {
        return '<!doctype html><html><head><meta charset="utf-8"><title>' + P.Utils.escapeHtml(title) + '</title>' +
            '<style>body{font-family:Segoe UI,Arial,sans-serif;background:#111;color:#e9ecef;margin:0;padding:24px;} .card{border:1px solid #444;border-radius:10px;background:#1c1f24;margin-bottom:20px;} .card-body,.card-header{padding:20px;} .badge{display:inline-block;padding:6px 12px;border-radius:6px;font-size:12px;margin-right:6px;border:2px solid transparent;} .bg-danger{background:#b02a37;color:#fff;border-color:#b02a37;} .bg-success{background:#146c43;color:#fff;border-color:#146c43;} .bg-secondary{background:#495057;color:#fff;border-color:#495057;} .bg-dark{background:#212529;color:#fff;border-color:#212529;} .text-muted{color:#cfd4da;} .border{border:1px solid #444;} .rounded{border-radius:8px;} pre,code{font-family:Consolas,monospace;} pre{background:#0f1115;padding:18px;border-radius:6px;border:1px solid #333;} code{padding:2px 4px;border-radius:4px;} details{margin-top:14px;margin-bottom:14px;} details>summary{cursor:pointer;padding:8px 0;} .verapdf-rule-box{background:#161a1f !important;color:#eef2f6 !important;border-width:3px !important;padding:18px !important;margin-bottom:16px !important;} ' + getVeraPdfReportCss() + ' @media print{*{-webkit-print-color-adjust:exact !important;print-color-adjust:exact !important;} body{background:#111 !important;color:#e9ecef !important;}}</style>' +
            '</head><body><h2 style="margin-top:0;">' + P.Utils.escapeHtml(title) + '</h2>' + bodyHtml + '</body></html>';
    }

    function getVeraPdfReportCss() {
        return '.verapdf-section-title{font-weight:600;}'+
            '.verapdf-section-failed::before{content:"⚠ "; color:#dc3545;}'+
            '.verapdf-section-passed::before{content:"✓ "; color:#28a745;}'+
            '.verapdf-status-badge::before{margin-right:4px;}'+
            '.verapdf-rule-failed .verapdf-status-badge::before{content:"⚠";}'+
            '.verapdf-rule-passed .verapdf-status-badge::before{content:"✓";}'+
            '.verapdf-badge-failed::before{content:"⚠ ";}'+
            '.verapdf-badge-passed::before{content:"✓ ";}'+
            '.verapdf-check-detail{font-size:10px !important;line-height:1.35 !important;}';
    }

    function renderValidation(issues) {
        var $c = $('#treeContent');
        var controls = getValidationControlsHtml(false);

        if (!issues.length) {
            $c.html(controls + '<div class="text-center mt-3">' +
                '<i class="fas fa-check-circle text-success fa-2x"></i>' +
                '<p class="mt-2 text-success">No issues found!</p></div>');
            bindValidationControls();
            return;
        }
        var order = { 'ERROR': 0, 'WARNING': 1, 'INFO': 2 };
        issues.sort(function (a, b) { return (order[a.severity] || 3) - (order[b.severity] || 3); });

        var html   = controls + '<div style="padding:8px;">';
        var counts = {};
        issues.forEach(function (i) { counts[i.severity] = (counts[i.severity] || 0) + 1; });
        html += '<div class="mb-2" style="font-size:12px;">' +
            (counts.ERROR   ? '<span class="text-danger me-3"><i class="fas fa-times-circle"></i> ' +
                               counts.ERROR + ' errors</span>' : '') +
            (counts.WARNING ? '<span class="text-warning me-3"><i class="fas fa-exclamation-triangle"></i> ' +
                               counts.WARNING + ' warnings</span>' : '') +
            (counts.INFO    ? '<span class="text-info"><i class="fas fa-info-circle"></i> ' +
                               counts.INFO + ' info</span>' : '') +
            '</div>';
        issues.forEach(function (issue) {
            var cls  = issue.severity === 'ERROR' ? 'error' : issue.severity === 'WARNING' ? 'warning' : 'info';
            var icon = issue.severity === 'ERROR' ? 'fa-times-circle text-danger'
                     : issue.severity === 'WARNING' ? 'fa-exclamation-triangle text-warning'
                     : 'fa-info-circle text-info';
            html += '<div class="validation-issue ' + cls + '">' +
                '<div class="issue-header"><i class="fas ' + icon + '"></i>' +
                '<span class="issue-rule">' + P.Utils.escapeHtml(issue.ruleId) + '</span></div>' +
                '<div>' + P.Utils.escapeHtml(issue.message) + '</div>' +
                '<div class="issue-spec">' + P.Utils.escapeHtml(issue.specReference || '') +
                ' &mdash; ' + P.Utils.escapeHtml(issue.location || '') + '</div></div>';
        });
        html += '</div>';
        $c.html(html);
        bindValidationControls();
    }

    // ======================== RAW COS TAB ========================

    function loadRawCos(viewState) {
        if (P.state.rawCosTreeData) {
            P.Tree.render(P.state.rawCosTreeData, { viewState: viewState });
            return;
        }

        P.Utils.apiFetch('/api/tree/' + P.state.sessionId + '/raw-cos')
            .done(function (rawTree) {
                P.state.rawCosTreeData = rawTree;
                P.Tree.render(rawTree, { viewState: viewState });
            })
            .fail(function () { P.Utils.toast('Failed to load raw COS', 'danger'); });
    }

    // ======================== ATTACHMENTS TAB ========================

    function loadAttachments() {
        var attachNodes = P.Tree.findAllByCategory(P.state.treeData, 'attachment');
        if (!attachNodes.length) {
            $('#treeContent').html(
                '<div class="text-muted text-center mt-3">' +
                '<i class="fas fa-paperclip fa-2x mb-2"></i><br>No attachments found</div>');
            return;
        }
        P.Tree.renderSubtree(P.state.treeData, 'attachments', {
            viewState: getTreeViewStateForTab('attachments')
        });
    }

    return { init: init, switchTab: switchTab };
})(jQuery, PDFalyzer);
