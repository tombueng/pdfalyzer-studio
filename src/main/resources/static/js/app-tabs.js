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
        search: '',
        sort: 'issues-desc',
        focusObj: null,
        focusGen: null,
        focusMeta: {}
    };
    var activeGlyphObserver = null;
    var glyphMeasureCanvas = null;
    var activeFontDetailState = null;
    var activeGlyphCanvasState = null;

    function init() {
        $('.tab-btn').on('click', function () {
            $('.tab-btn').removeClass('active');
            $(this).addClass('active');
            switchTab($(this).data('tab'));
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

    function switchTab(tab) {
        if (!P.state.treeData || !P.state.sessionId) return;

        var previousTab = P.state.currentTab;
        captureTreeViewStateForTab(previousTab);

        var viewState = getTreeViewStateForTab(tab);

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
        clearFontUsageHighlights();
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

        if (focusedMode) {
            var focusObj = parseInt(fontDiagnosticsState.focusObj, 10);
            var focusGen = parseInt(fontDiagnosticsState.focusGen, 10);
            fonts = allFonts.filter(function (f) {
                return parseInt(f.objectNumber, 10) === focusObj && parseInt(f.generationNumber || 0, 10) === focusGen;
            });
            if (!fonts.length) {
                focusedMode = false;
                fontDiagnosticsState.focusObj = null;
                fontDiagnosticsState.focusGen = null;
            }
        }

        if (!focusedMode && fontDiagnosticsState.filterIssuesOnly) {
            fonts = fonts.filter(function (f) {
                return (f.issues && f.issues.length) || (f.unmappedUsedCodes > 0) || (f.unencodableUsedChars > 0);
            });
        }

        var query = focusedMode ? '' : (fontDiagnosticsState.search || '').trim().toLowerCase();
        if (query) {
            fonts = fonts.filter(function (f) {
                var hay = [
                    f.fontName,
                    f.fontType,
                    f.encoding,
                    (f.objectNumber >= 0 ? (f.objectNumber + ' ' + (f.generationNumber || 0)) : ''),
                    (f.issues || []).join(' ')
                ].join(' ').toLowerCase();
                return hay.indexOf(query) !== -1;
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
                '<div class="font-diag-controls">' +
                '<input id="fontDiagSearch" class="form-control form-control-sm" placeholder="Search font, encoding, issue..." value="' + P.Utils.escapeHtml(fontDiagnosticsState.search || '') + '" />' +
                '<select id="fontDiagSort" class="form-select form-select-sm">' +
                '<option value="issues-desc">Most issues first</option>' +
                '<option value="name-asc">Name A-Z</option>' +
                '<option value="usage-desc">Most used glyphs first</option>' +
                '<option value="unmapped-desc">Most unmapped codes first</option>' +
                '</select>' +
                '<label class="form-check form-check-inline m-0"><input id="fontDiagIssuesOnly" class="form-check-input" type="checkbox" ' + (fontDiagnosticsState.filterIssuesOnly ? 'checked' : '') + '><span class="form-check-label">Issues only</span></label>' +
                '<button id="fontDiagReload" class="btn btn-outline-accent btn-sm"><i class="fas fa-sync-alt me-1"></i>Refresh</button>' +
                '</div>';
        } else {
            html += '<div class="font-diag-controls font-diag-controls-focus">' +
                '<div class="text-muted" style="font-size:12px;"><i class="fas fa-microscope me-1"></i>Focused diagnostics mode</div>' +
                '<button id="fontDiagExitFocus" class="btn btn-outline-accent btn-sm"><i class="fas fa-arrow-left me-1"></i>Back to all fonts</button>' +
                '</div>';
        }

        html += '<table class="font-table' + (focusedMode ? ' font-table-focus' : '') + '">';
        if (!focusedMode) {
            html += '<thead><tr>' +
                '<th title="Resolved PDF font name.">Font</th>' +
                '<th title="Indirect object reference used to inspect raw font dictionary and stream.">Object</th>' +
                '<th title="Font subtype.">Subtype</th>' +
                '<th title="Observed glyph usage and where this font appears in the document.">Usage</th>' +
                '<th title="How many used character codes map to Unicode and where mapping gaps exist.">Coverage</th>' +
                '<th title="Detected risk signals and suggested remediations.">Issues</th>' +
                '<th title="Actions for deep inspection, usage overlay, and extraction.">Actions</th>' +
                '</tr></thead>';
        }
        html += '<tbody>';

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
            actions += '<button class="btn btn-xs btn-outline-accent me-1 font-detail-btn" ' +
                'data-obj="' + (f.objectNumber >= 0 ? f.objectNumber : '') + '" data-gen="' + (f.generationNumber || 0) + '" ' +
                'data-font-name="' + P.Utils.escapeHtml(f.fontName || '(unknown)') + '" ' +
                'title="Inspect full diagnostics for this row (glyph mapping, encoding, dictionary, and usage evidence).">' +
                '<i class="fas fa-microscope"></i></button>';
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
            var focusMeta = focusedMode ? getFocusedFontMeta(f) : null;
            var subtypeText = (focusMeta && focusMeta.subtype) ? focusMeta.subtype : (f.fontType || '');
            html += '<tr>' +
                '<td title="Font name reported by PDFBox for this resource.">' + P.Utils.escapeHtml(f.fontName || '(unknown)') + '</td>' +
                '<td class="font-obj-ref" title="' + (f.objectNumber >= 0 ? 'Indirect object can be inspected and extracted.' : 'Direct or unresolved reference: deep object-specific diagnostics may be limited.') + '">' + P.Utils.escapeHtml(objRef) + '</td>' +
                '<td>' + P.Utils.escapeHtml(subtypeText) + (focusedMode ? '' : ('<div class="text-muted" style="font-size:11px;">' +
                '<span title="Embedded fonts travel with the PDF for reliable rendering.">' + embIcon + ' embedded</span> &nbsp; ' +
                '<span title="Subset fonts include only some glyphs.">' + subIcon + ' subset</span><br>' +
                P.Utils.escapeHtml(f.encoding || '(no encoding)') +
                '</div>')) + '</td>' +
                '<td><span class="badge text-bg-secondary" title="Count of text positions observed with this font in extracted text flow.">Glyphs ' + (f.glyphCount || 0) + '</span> ' +
                '<div class="text-muted" style="font-size:11px;">Pages: ' + P.Utils.escapeHtml(pageText) + '</div></td>' +
                '<td><span class="badge ' + ((f.unmappedUsedCodes || 0) > 0 ? 'text-bg-danger' : 'text-bg-success') + '" title="Mapped used codes / distinct used codes.">Mapped ' + P.Utils.escapeHtml(coverage) + '</span>' +
                ((f.unencodableUsedChars || 0) > 0 ? '<div class="text-warning" style="font-size:11px;" title="Distinct used characters that cannot be encoded by this font.">Missing glyph chars: ' + f.unencodableUsedChars + '</div>' : '') +
                '</td>' +
                '<td>' + issueHtml + issueDetail + '</td>' +
                (focusedMode ? ('<td class="font-focus-info-cell">' + buildFocusedInfoColumn(f, focusMeta) + '</td>') : '') +
                '<td>' + actions + '</td></tr>';
        });
        html += '</tbody></table>' +
            '<div id="fontDiagDetail" class="font-diag-detail text-muted">Select a font row action <i class="fas fa-microscope"></i> to inspect full glyph and mapping tables.</div>' +
            '</div>';
        $c.html(html);

        if (!focusedMode) {
            $('#fontDiagSort').val(fontDiagnosticsState.sort || 'issues-desc');
        }
        bindFontDiagnosticsControls();
        $c.find('.font-usage-btn').on('click', function () {
            var obj = parseInt($(this).data('obj'), 10);
            var gen = parseInt($(this).data('gen'), 10);
            P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen)
                .done(function (areas) {
                    var usage = showFontUsageHighlights(areas || []);
                    P.Utils.toast('Found: ' + usage.found + ' | Highlighted: ' + usage.highlighted, 'info');
                })
                .fail(function () {
                    P.Utils.toast('Failed to load font usage', 'danger');
                });
        });

        $c.find('.font-detail-btn').on('click', function () {
            var obj = parseInt($(this).data('obj'), 10);
            var gen = parseInt($(this).data('gen'), 10);
            if (obj >= 0) {
                fontDiagnosticsState.focusObj = obj;
                fontDiagnosticsState.focusGen = gen;
                renderFontDiagnostics();
            }
            loadFontDiagnosticsDetail(obj, gen);
        });
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
        $('#fontDiagIssuesOnly').off('change').on('change', function () {
            fontDiagnosticsState.filterIssuesOnly = !!$(this).is(':checked');
            renderFontDiagnostics();
        });
        $('#fontDiagSearch').off('input').on('input', function () {
            fontDiagnosticsState.search = $(this).val() || '';
            renderFontDiagnostics();
        });
        $('#fontDiagSort').off('change').on('change', function () {
            fontDiagnosticsState.sort = $(this).val() || 'issues-desc';
            renderFontDiagnostics();
        });
        $('#fontDiagReload').off('click').on('click', function () {
            loadFonts();
        });
    }

    function loadFontDiagnosticsDetail(obj, gen) {
        var $target = $('#fontDiagDetail');
        if (!$target.length) return;
        if (!(obj >= 0)) {
            $target.html('<div class="text-warning"><i class="fas fa-info-circle me-1"></i>This row has no indirect object reference. Showing table-level diagnostics only (deep object dictionary/stream inspection is unavailable for direct/unresolved references).</div>');
            return;
        }
        $target.html('<div class="text-muted"><span class="spinner-border spinner-border-sm"></span> Loading deep font diagnostics...</div>');
        P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/diagnostics/' + obj + '/' + gen)
            .done(function (detail) {
                var safeDetail = detail || {};
                storeFocusedMetaFromDetail(obj, gen, safeDetail);
                if (fontDiagnosticsState.focusObj != null && fontDiagnosticsState.focusGen != null) {
                    renderFontDiagnostics();
                }
                renderFontDiagnosticsDetail(safeDetail);
                requestFocusedFontProgramSize(obj, gen);
            })
            .fail(function () {
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
                if (fontDiagnosticsState.focusObj != null && fontDiagnosticsState.focusGen != null) {
                    renderFontDiagnostics();
                }
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

        var dictRows = Object.keys(dictionary).map(function (k) {
            return '<tr><td>' + P.Utils.escapeHtml(k) + '</td><td>' + P.Utils.escapeHtml(String(dictionary[k] || '')) + '</td></tr>';
        }).join('');

        var html = '<div class="font-diag-detail-title">' +
            '<strong>' + P.Utils.escapeHtml(f.fontName || '(unknown)') + '</strong> &nbsp; ' +
            '<span class="text-muted">Object ' + (f.objectNumber >= 0 ? (f.objectNumber + ' ' + (f.generationNumber || 0) + ' R') : '(direct)') + '</span>' +
            '</div>' +
            '<div class="font-detail-grid">' +
            '<div><strong title="Encoding entry used to decode character codes.">Encoding</strong><div class="text-muted">' + P.Utils.escapeHtml(encoding.encodingObject || '(none)') + '</div></div>' +
            '<div><strong title="ToUnicode CMap maps character codes to Unicode.">ToUnicode</strong><div class="text-muted">' + (encoding.hasToUnicode ? '<span class="text-success">present</span>' : '<span class="text-danger">missing</span>') + ' ' + P.Utils.escapeHtml(encoding.toUnicodeObject || '') + '</div></div>' +
            '<div><strong title="Font subtype (/Type0, /TrueType, /Type1, etc.).">Subtype</strong><div class="text-muted">' + P.Utils.escapeHtml(encoding.subtype || '') + '</div></div>' +
            '<div><strong title="Declared base font name in the font dictionary.">BaseFont</strong><div class="text-muted">' + P.Utils.escapeHtml(encoding.baseFont || '') + '</div></div>' +
            '</div>' +
            '<details class="mt-2" id="fontDetailIssuesSection"><summary>Used character diagnostics (' + issues.length + ')</summary>' +
            '<div class="font-detail-table-wrap"><table class="font-detail-table"><thead><tr><th title="Extracted character.">Char</th><th title="Lazy-rendered preview.">Glyph</th><th title="Unicode code point(s).">Unicode</th><th title="Occurrence count in extracted text.">Count</th><th title="Diagnostic note.">Note</th></tr></thead><tbody id="fontDetailIssueTableBody">' +
            '<tr><td colspan="5" class="text-muted">Open this section to load rows.</td></tr>' +
            '</tbody></table></div></details>' +
            '<details class="mt-2" id="fontDetailMapSection"><summary>Glyph mapping table (' + mappings.length + ')</summary>' +
            '<div class="font-detail-tools"><label class="m-0"><input type="checkbox" id="fontDetailUsedOnly"> show used codes only</label> ' +
            '<input id="fontDetailFilter" class="form-control form-control-sm" placeholder="Filter code, unicode, hex" /></div>' +
            '<div class="font-detail-table-wrap"><table class="font-detail-table" id="fontDetailMapTable"><thead><tr><th title="Character code used in PDF text operators.">Code</th><th title="Lazy-rendered preview of mapped glyphs.">Glyph</th><th title="Unicode text mapped from this code.">Unicode</th><th title="Unicode code point(s) in U+XXXX format.">Hex</th><th title="Glyph width reported by font metrics.">Width</th><th title="How many times this code appears in extracted text.">Used</th><th title="Whether code maps to Unicode.">Mapped</th><th title="Open full diagnostics and actions for this glyph mapping.">Details</th></tr></thead><tbody id="fontDetailMapTableBody">' +
            '<tr><td colspan="8" class="text-muted">Open this section to load rows.</td></tr>' +
            '</tbody></table></div></details>' +
            '<details class="mt-2"><summary>Font dictionary (' + Object.keys(dictionary).length + ' keys)</summary>' +
            '<div class="font-detail-table-wrap"><table class="font-detail-table"><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>' +
            (dictRows || '<tr><td colspan="2" class="text-muted">No dictionary data.</td></tr>') +
            '</tbody></table></div></details>';

        $('#fontDiagDetail').html(html);
        activeFontDetailState = {
            mappings: mappings,
            issues: issues,
            font: f,
            fontFamily: fontFamily,
            pendingQuery: '',
            pendingUsedOnly: false,
            issuesRendered: false,
            mapRenderToken: 0,
            observer: null
        };
        bindFontDetailFilters();
        setupDeferredFontDetailRendering();
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
        var unicode = row.unicode ? P.Utils.escapeHtml(row.unicode) : '<span class="text-danger">(unmapped)</span>';
        var glyphPreview = '<span class="font-glyph-lazy" ' +
            'data-unicode="' + escapeHtmlAttr(row.unicode || '') + '" ' +
            'data-glyph-width="' + escapeHtmlAttr(String(row.width == null ? '' : row.width)) + '" ' +
            'data-font-family="' + escapeHtmlAttr((state.font.embedded ? state.fontFamily : '')) + '" ' +
            'title="Lazy-rendered glyph preview">' +
            (row.unicode ? '<span class="text-muted">…</span>' : '<span class="text-danger">n/a</span>') +
            '</span>';
        var glyphAction = (state.font.objectNumber >= 0)
            ? '<button class="btn btn-xs btn-outline-accent font-glyph-detail-btn" ' +
                'data-obj="' + state.font.objectNumber + '" data-gen="' + (state.font.generationNumber || 0) + '" ' +
                'data-code="' + escapeHtmlAttr(String(row.code == null ? '' : row.code)) + '" data-unicode="' + escapeHtmlAttr(row.unicode || '') + '" ' +
                'title="Open full glyph diagnostics and usage actions"><i class="fas fa-info-circle"></i></button>'
            : '<span class="text-muted">n/a</span>';

        return '<tr>' +
            '<td>' + P.Utils.escapeHtml(String(row.code == null ? '' : row.code)) + '</td>' +
            '<td class="font-glyph-preview-cell">' + glyphPreview + '</td>' +
            '<td>' + unicode + '</td>' +
            '<td>' + P.Utils.escapeHtml(row.unicodeHex || '') + '</td>' +
            '<td>' + (row.width == null ? '' : row.width) + '</td>' +
            '<td>' + (row.usedCount || 0) + '</td>' +
            '<td>' + (row.mapped ? '<span class="text-success">yes</span>' : '<span class="text-danger">no</span>') + '</td>' +
            '<td>' + glyphAction + '</td>' +
            '</tr>';
    }

    function isElementNearViewport(el) {
        if (!el) return false;
        var rect = el.getBoundingClientRect();
        return rect.bottom > -80 && rect.top < (window.innerHeight + 120);
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
        bindLazyGlyphRendering();
        bindGlyphHoverTooltips();
    }

    function renderMapTableNow(query, usedOnly) {
        var state = activeFontDetailState;
        var tbody = document.getElementById('fontDetailMapTableBody');
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

        tbody.innerHTML = '<tr><td colspan="8" class="text-muted">Rendering ' + filtered.length + ' rows…</td></tr>';

        var index = 0;
        var chunkSize = 180;

        function renderChunk() {
            if (!activeFontDetailState || token !== activeFontDetailState.mapRenderToken) return;

            if (index === 0) {
                tbody.innerHTML = '';
            }

            var end = Math.min(index + chunkSize, filtered.length);
            var html = '';
            for (; index < end; index += 1) {
                html += buildMappingRowHtml(filtered[index], state);
            }
            if (html) {
                tbody.insertAdjacentHTML('beforeend', html);
            }

            if (index < filtered.length) {
                window.requestAnimationFrame(renderChunk);
                return;
            }

            bindGlyphDetailButtons();
            bindLazyGlyphRendering();
            bindGlyphHoverTooltips();
        }

        window.requestAnimationFrame(renderChunk);
    }

    function setupDeferredFontDetailRendering() {
        var state = activeFontDetailState;
        if (!state) return;

        var issuesSection = document.getElementById('fontDetailIssuesSection');
        var mapSection = document.getElementById('fontDetailMapSection');

        function renderVisibleSections() {
            if (issuesSection && issuesSection.open && !state.issuesRendered && isElementNearViewport(issuesSection)) {
                renderIssueTableNow();
            }
            if (mapSection && mapSection.open && isElementNearViewport(mapSection)) {
                renderMapTableNow(state.pendingQuery, state.pendingUsedOnly);
            }
        }

        if (issuesSection) {
            issuesSection.addEventListener('toggle', renderVisibleSections);
        }
        if (mapSection) {
            mapSection.addEventListener('toggle', renderVisibleSections);
        }

        if ('IntersectionObserver' in window) {
            state.observer = new IntersectionObserver(function (entries) {
                entries.forEach(function (entry) {
                    if (!entry.isIntersecting) return;
                    renderVisibleSections();
                });
            }, { root: null, rootMargin: '120px 0px', threshold: 0.01 });

            if (issuesSection) state.observer.observe(issuesSection);
            if (mapSection) state.observer.observe(mapSection);
        }

        renderVisibleSections();
    }

    function ensureGlyphDetailModal() {
        if (document.getElementById('fontGlyphDetailModal')) return;
        var modalHtml = '' +
            '<div class="modal fade" id="fontGlyphDetailModal" tabindex="-1" aria-hidden="true">' +
            '  <div class="modal-dialog modal-lg modal-dialog-scrollable">' +
            '    <div class="modal-content">' +
            '      <div class="modal-header">' +
            '        <h5 class="modal-title"><i class="fas fa-font me-2"></i>Glyph diagnostics</h5>' +
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
    }

    function bindGlyphDetailButtons() {
        $('#fontDiagDetail .font-glyph-detail-btn').off('click').on('click', function () {
            ensureGlyphDetailModal();
            var obj = parseCodeValue($(this).attr('data-obj'));
            var gen = parseCodeValue($(this).attr('data-gen'));
            var code = parseCodeValue($(this).attr('data-code'));
            var unicode = String($(this).attr('data-unicode') || '');
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
        var modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('fontGlyphDetailModal'));
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
        var heroFontFamily = "'Segoe UI Symbol', 'Segoe UI', sans-serif";
        if (detail.embedded && obj >= 0) {
            var detailFontFamily = 'pdfdiagfont_' + obj + '_' + (gen || 0);
            ensureDiagnosticsFontFace(detailFontFamily, obj, gen || 0);
            heroFontFamily = "'" + detailFontFamily + "', 'Segoe UI Symbol', 'Segoe UI', sans-serif";
        }
        if (!hugeGlyph || hugeGlyph === '(unmapped)') {
            hugeGlyph = '∅';
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

        var html = '' +
            '<div class="font-diag-detail-title mb-2"><strong>' + P.Utils.escapeHtml(detail.fontName || '(unknown font)') + '</strong> ' +
            '<span class="text-muted">Object ' + P.Utils.escapeHtml(detail.objectRef || (obj + ' ' + gen + ' R')) + '</span></div>' +
            '<div class="font-glyph-diag-layout">' +
            '  <div class="font-glyph-guide-box">' +
            '    <div class="font-glyph-guide-title">Guide legend</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot ascent"></span>Ascent line</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot baseline"></span>Baseline</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot descent"></span>Descent line</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot bbox"></span>Measured glyph bounding box</div>' +
            '    <div class="font-glyph-guide-item"><span class="font-glyph-dot origin"></span>Text origin</div>' +
            '    <hr class="my-2" />' +
            '    <div class="font-glyph-guide-title">Advanced data</div>' +
            '    <div class="text-muted" style="font-size:11px;line-height:1.35;">' + P.Utils.escapeHtml(outlineText) + '</div>' +
            '    <div class="mt-2" style="font-size:11px;">' + extraGlyphDataHtml + '</div>' +
            '  </div>' +
            '  <div class="font-glyph-hero-wrap" title="Large glyph diagnostics preview">' +
            '    <div class="font-glyph-hero-toolbar">' +
            '      <button id="fontGlyphZoomOut" class="btn btn-xs btn-outline-accent" title="Zoom out"><i class="fas fa-search-minus"></i></button>' +
            '      <button id="fontGlyphZoomIn" class="btn btn-xs btn-outline-accent" title="Zoom in"><i class="fas fa-search-plus"></i></button>' +
            '      <button id="fontGlyphZoomReset" class="btn btn-xs btn-outline-accent" title="Reset zoom"><i class="fas fa-sync-alt"></i></button>' +
            '      <span id="fontGlyphZoomLabel" class="text-muted" style="font-size:11px;">100%</span>' +
            '      <span class="text-muted ms-2" style="font-size:11px;">Ctrl+Wheel zoom</span>' +
            '    </div>' +
            '    <div id="fontGlyphCanvasViewport" class="font-glyph-canvas-viewport">' +
            '      <canvas id="fontGlyphHeroCanvas" class="font-glyph-hero-canvas" ' +
            '        data-glyph="' + escapeHtmlAttr(hugeGlyph) + '" ' +
            '        data-font-family="' + escapeHtmlAttr(heroFontFamily) + '" ' +
            '        data-glyph-width="' + escapeHtmlAttr(String(glyph.width == null ? '' : glyph.width)) + '"></canvas>' +
            '    </div>' +
            '    <div id="fontGlyphHeroMetrics" class="font-glyph-hero-metrics text-muted"></div>' +
            '  </div>' +
            '</div>' +
            '<div class="font-detail-grid">' +
            '  <div><strong>Glyph</strong><div class="text-muted" style="font-size:22px;line-height:1.25;">' + P.Utils.escapeHtml(glyphText) + '</div></div>' +
            '  <div><strong>Code</strong><div class="text-muted">' + P.Utils.escapeHtml(String(glyph.code == null ? code : glyph.code)) + ' (' + P.Utils.escapeHtml(String(glyph.codeHex || '')) + ')</div></div>' +
            '  <div><strong>Unicode</strong><div class="text-muted">' + P.Utils.escapeHtml(String(glyph.unicode || '')) + ' ' + P.Utils.escapeHtml(String(glyph.unicodeHex || '')) + '</div></div>' +
            '  <div><strong>Usage</strong><div class="text-muted">Count: ' + P.Utils.escapeHtml(String(glyph.usedCount || 0)) + ' | Pages: ' + P.Utils.escapeHtml(usagePages.map(function (p) { return p + 1; }).join(', ') || '-') + '</div></div>' +
            '</div>' +
            '<div class="mt-2">' +
            '  <button class="btn btn-outline-accent btn-sm me-2" id="fontGlyphHighlightBtn"><i class="fas fa-highlighter me-1"></i>Highlight this glyph usage</button>' +
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
        if (heroCanvas) {
            initGlyphCanvasViewer(heroCanvas, heroViewport, heroMetrics);
        }

        $('#fontGlyphHighlightBtn').off('click').on('click', function () {
            P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen + '/glyph/' + code)
                .done(function (areas) {
                    var usage = showFontUsageHighlights(areas || []);
                    P.Utils.toast('Found: ' + usage.found + ' | Highlighted: ' + usage.highlighted, usage.highlighted ? 'info' : 'warning');
                })
                .fail(function () {
                    P.Utils.toast('Failed to load glyph usage overlays', 'danger');
                });
        });

        $('#fontGlyphClearHighlightsBtn').off('click').on('click', function () {
            clearFontUsageHighlights();
        });

        bindGlyphUsageInspector(obj, gen, code);
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
                left: fontSizePx * 0.05,
                right: fontSizePx * 0.55,
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
        var bboxWidth = left + right;
        var advanceWidth = m.width || bboxWidth || (fontSizePx * 0.6);
        return {
            advanceWidth: Math.max(1, advanceWidth),
            bboxWidth: Math.max(1, bboxWidth || advanceWidth),
            left: Math.max(0, left),
            right: Math.max(0, right),
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

    function drawGlyphDiagnosticsCanvas(canvas, options) {
        if (!canvas) return;
        var text = String(canvas.getAttribute('data-glyph') || '').trim();
        if (!text) return;

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
        if (!ctx) return;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssWidth, cssHeight);

        var inset = 8;
        var region = {
            x: inset,
            y: inset,
            width: cssWidth - inset * 2,
            height: cssHeight - inset * 2
        };

        var baseSize = 200;
        var mBase = measureGlyphMetrics(text, fontFamily, baseSize);
        var bboxWidthBase = mBase.bboxWidth;
        if (!isNaN(widthHintUnits) && widthHintUnits > 0) {
            bboxWidthBase = Math.max(bboxWidthBase, baseSize * (widthHintUnits / 1000));
        }
        var bboxHeightBase = Math.max(1, mBase.ascent + mBase.descent);

        var sizeByWidth = region.width * (baseSize / Math.max(1, bboxWidthBase));
        var sizeByHeight = region.height * (baseSize / Math.max(1, bboxHeightBase));
        var fontSize = clampNumber(Math.floor(Math.min(sizeByWidth, sizeByHeight)), 24, 260);

        var m = measureGlyphMetrics(text, fontFamily, fontSize);
        var bboxWidth = Math.max(1, m.bboxWidth);
        var bboxHeight = Math.max(1, m.ascent + m.descent);

        var bboxLeft = region.x + (region.width - bboxWidth) / 2;
        var bboxTop = region.y + (region.height - bboxHeight) / 2;
        var baselineY = bboxTop + m.ascent;
        var originX = bboxLeft + m.left;

        ctx.fillStyle = 'rgba(255,255,255,0.02)';
        ctx.fillRect(region.x, region.y, region.width, region.height);

        ctx.strokeStyle = 'rgba(255,255,255,0.18)';
        ctx.lineWidth = 1;
        ctx.setLineDash([5, 4]);
        ctx.strokeRect(region.x + 0.5, region.y + 0.5, region.width - 1, region.height - 1);
        ctx.setLineDash([]);

        ctx.strokeStyle = 'rgba(0,212,255,0.95)';
        ctx.beginPath();
        ctx.moveTo(region.x, baselineY - m.ascent + 0.5);
        ctx.lineTo(region.x + region.width, baselineY - m.ascent + 0.5);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(255,193,7,0.95)';
        ctx.beginPath();
        ctx.moveTo(region.x, baselineY + 0.5);
        ctx.lineTo(region.x + region.width, baselineY + 0.5);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(220,53,69,0.95)';
        ctx.beginPath();
        ctx.moveTo(region.x, baselineY + m.descent + 0.5);
        ctx.lineTo(region.x + region.width, baselineY + m.descent + 0.5);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(40,167,69,0.95)';
        ctx.lineWidth = 1.25;
        ctx.strokeRect(bboxLeft + 0.5, bboxTop + 0.5, bboxWidth - 1, bboxHeight - 1);

        ctx.strokeStyle = 'rgba(173,181,189,0.7)';
        ctx.beginPath();
        ctx.moveTo(originX + 0.5, region.y);
        ctx.lineTo(originX + 0.5, region.y + region.height);
        ctx.stroke();

        ctx.save();
        ctx.font = String(fontSize) + 'px ' + fontFamily;
        ctx.fillStyle = '#ffffff';
        ctx.textBaseline = 'alphabetic';
        ctx.fillText(text, originX, baselineY);
        ctx.restore();

        if (metricsEl) {
            var info = [
                'font-size ' + fontSize + 'px',
                'bbox ' + Math.round(bboxWidth) + 'x' + Math.round(bboxHeight) + 'px',
                'baseline y=' + Math.round(baselineY),
                'advance ' + Math.round(m.advanceWidth) + 'px'
            ];
            metricsEl.textContent = info.join('  |  ');
        }
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

        var viewportWidth = Math.max(320, viewport.clientWidth || 320);
        var viewportHeight = Math.max(260, viewport.clientHeight || 260);
        var baseWidth = Math.max(520, viewportWidth - 18);
        var baseHeight = Math.max(420, viewportHeight - 18);

        activeGlyphCanvasState = {
            canvas: canvas,
            viewport: viewport,
            metricsEl: metricsEl,
            baseWidth: baseWidth,
            baseHeight: baseHeight,
            zoom: 1,
            minZoom: 0.5,
            maxZoom: 4,
            widthHintUnits: parseFloat(canvas.getAttribute('data-glyph-width'))
        };

        function updateZoomLabel() {
            $('#fontGlyphZoomLabel').text(Math.round(activeGlyphCanvasState.zoom * 100) + '%');
        }

        function drawCurrent() {
            if (!activeGlyphCanvasState) return;
            drawGlyphDiagnosticsCanvas(canvas, {
                metricsEl: metricsEl,
                widthHintUnits: activeGlyphCanvasState.widthHintUnits,
                logicalWidth: Math.round(activeGlyphCanvasState.baseWidth * activeGlyphCanvasState.zoom),
                logicalHeight: Math.round(activeGlyphCanvasState.baseHeight * activeGlyphCanvasState.zoom)
            });
            updateZoomLabel();
        }

        function setZoom(nextZoom) {
            if (!activeGlyphCanvasState) return;
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
        }

        $('#fontGlyphZoomIn').off('click').on('click', function () {
            setZoom(activeGlyphCanvasState.zoom + 0.2);
        });
        $('#fontGlyphZoomOut').off('click').on('click', function () {
            setZoom(activeGlyphCanvasState.zoom - 0.2);
        });
        $('#fontGlyphZoomReset').off('click').on('click', function () {
            setZoom(1);
            viewport.scrollLeft = Math.max(0, (canvas.clientWidth - viewport.clientWidth) / 2);
            viewport.scrollTop = Math.max(0, (canvas.clientHeight - viewport.clientHeight) / 2);
        });

        $(viewport).off('wheel.glyphzoom').on('wheel.glyphzoom', function (ev) {
            if (!ev.ctrlKey) return;
            ev.preventDefault();
            var delta = ev.originalEvent && typeof ev.originalEvent.deltaY === 'number' ? ev.originalEvent.deltaY : 0;
            setZoom(activeGlyphCanvasState.zoom + (delta < 0 ? 0.15 : -0.15));
        });

        drawCurrent();
        viewport.scrollLeft = Math.max(0, (canvas.clientWidth - viewport.clientWidth) / 2);
        viewport.scrollTop = Math.max(0, (canvas.clientHeight - viewport.clientHeight) / 2);

        setTimeout(drawCurrent, 120);
        setTimeout(drawCurrent, 380);
        if (document.fonts && document.fonts.ready && typeof document.fonts.ready.then === 'function') {
            document.fonts.ready.then(function () {
                drawCurrent();
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
            if (!unicode) return;
            var fontFamily = node.getAttribute('data-font-family') || '';

            node.setAttribute('data-rendered', '1');
            node.innerHTML = '';

            var preview = document.createElement('span');
            preview.className = 'font-glyph-preview';
            preview.textContent = unicode;
            preview.title = 'Rendered glyph preview';
            if (fontFamily) {
                preview.style.fontFamily = "'" + fontFamily + "', 'Segoe UI Symbol', 'Segoe UI', sans-serif";
            }
            node.appendChild(preview);
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

    function bindFontDetailFilters() {
        function applyFilter() {
            var query = ($('#fontDetailFilter').val() || '').toLowerCase();
            var usedOnly = $('#fontDetailUsedOnly').is(':checked');
            if (!activeFontDetailState) return;

            activeFontDetailState.pendingQuery = query;
            activeFontDetailState.pendingUsedOnly = usedOnly;

            var mapSection = document.getElementById('fontDetailMapSection');
            if (mapSection && mapSection.open && isElementNearViewport(mapSection)) {
                renderMapTableNow(query, usedOnly);
            }
        }
        $('#fontDetailFilter').off('input').on('input', applyFilter);
        $('#fontDetailUsedOnly').off('change').on('change', applyFilter);
    }

    function clearFontUsageHighlights() {
        $('.pdf-font-usage').remove();
    }

    function showFontUsageHighlights(areas, options) {
        var clearExisting = !options || options.clearExisting !== false;
        if (clearExisting) {
            clearFontUsageHighlights();
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
            $('<div>', { 'class': 'pdf-font-usage' }).css({
                left: bbox[0] * viewport.scale + 'px',
                top: viewport.height - (bbox[1] + bbox[3]) * viewport.scale + 'px',
                width: bbox[2] * viewport.scale + 'px',
                height: bbox[3] * viewport.scale + 'px'
            }).appendTo($wrapper);
            highlighted += 1;
        });
        return { found: found, highlighted: highlighted };
    }

    // ======================== VALIDATION TAB ========================

    function loadValidation() {
        var $c = $('#treeContent');
        $c.html(
            '<div class="text-center mt-3">' +
            '<button class="btn btn-accent btn-sm me-2" id="runValidateBtn">' +
            '<i class="fas fa-play me-1"></i>Run Validation</button>' +
            '<button class="btn btn-outline-accent btn-sm me-2" id="runVeraPdfBtn">' +
            '<i class="fas fa-file-alt me-1"></i>Run veraPDF</button>' +
            '<button class="btn btn-outline-accent btn-sm" id="exportValidateBtn" disabled>' +
            '<i class="fas fa-download me-1"></i>Export Report</button></div>');

        $('#runValidateBtn').on('click', function () {
            $c.html('<div class="text-muted text-center mt-3">' +
                '<span class="spinner-border spinner-border-sm"></span> Validating...</div>');
            P.Utils.apiFetch('/api/validate/' + P.state.sessionId)
                .done(function (issues) { renderValidation(issues); })
                .fail(function () { P.Utils.toast('Validation error', 'danger'); });
        });
        $('#exportValidateBtn').on('click', function () {
            window.open('/api/validate/' + P.state.sessionId + '/export', '_blank');
        });

        $('#runVeraPdfBtn').on('click', function () {
            $c.html('<div class="text-muted text-center mt-3">' +
                '<span class="spinner-border spinner-border-sm"></span> Running veraPDF...</div>');
            P.Utils.apiFetch('/api/validate/' + P.state.sessionId + '/verapdf')
                .done(function (result) {
                    renderVeraPdf(result || {});
                })
                .fail(function () {
                    P.Utils.toast('veraPDF validation error', 'danger');
                });
        });
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
        var exportBtn = '<div class="text-end mb-2" style="padding:4px 8px;">' +
            '<button class="btn btn-outline-accent btn-sm" onclick="window.open(\'/api/validate/' +
            P.state.sessionId + '/export\', \'_blank\')">' +
            '<i class="fas fa-download me-1"></i>Export Report</button></div>';

        if (!issues.length) {
            $c.html(exportBtn + '<div class="text-center mt-3">' +
                '<i class="fas fa-check-circle text-success fa-2x"></i>' +
                '<p class="mt-2 text-success">No issues found!</p></div>');
            return;
        }
        var order = { 'ERROR': 0, 'WARNING': 1, 'INFO': 2 };
        issues.sort(function (a, b) { return (order[a.severity] || 3) - (order[b.severity] || 3); });

        var html   = exportBtn + '<div style="padding:8px;">';
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
