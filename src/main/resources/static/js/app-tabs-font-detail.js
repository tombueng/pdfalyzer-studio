/**
 * PDFalyzer Studio – Font detail rendering, glyph mapping rows, deferred table rendering.
 * Note: buildIssueDetail, buildFocusedFontCard are in app-tabs-fonts.js (P.FontsTab).
 */
PDFalyzer.FontDetail = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;
    var fontDiagnosticsState = _p.fontDiagnosticsState;

    function loadFontDiagnosticsDetail(obj, gen) {
        var $target = $('#fontDiagDetail');
        if (!$target.length) return;
        if (!(obj >= 0)) {
            $target.html('<div class="text-warning"><i class="fas fa-info-circle me-1"></i>This row has no indirect object reference. Showing table-level diagnostics only (deep object dictionary/stream inspection is unavailable for direct/unresolved references).</div>');
            return;
        }
        var detailKey = P.FontsTab.getFocusMetaKey(obj, gen || 0);
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
        var key = P.FontsTab.getFocusMetaKey(obj, gen || 0);
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
        var key = P.FontsTab.getFocusMetaKey(obj, gen || 0);
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
            .catch(function () {});
    }

    function renderFontDiagnosticsDetail(detail) {
        var f = detail.font || {};
        var encoding = detail.encoding || {};
        var allMappings = Array.isArray(detail.glyphMappings) ? detail.glyphMappings : [];
        var issues = Array.isArray(detail.usedCharacterIssues) ? detail.usedCharacterIssues : [];

        var problemCount = allMappings.filter(function (r) { return r && r.diagnosticStatus && r.diagnosticStatus !== 'OK'; }).length;
        var missingCount = allMappings.filter(function (r) { return r && r.usedCount > 0 && (!r.mapped || !r.glyphPresent); }).length;
        var usedCount = allMappings.filter(function (r) { return r && r.usedCount > 0; }).length;

        var fontFamily = (f.objectNumber >= 0) ? ('pdfdiagfont_' + f.objectNumber + '_' + (f.generationNumber || 0)) : '';
        if (f.embedded && f.objectNumber >= 0) {
            P.GlyphUI.ensureDiagnosticsFontFace(fontFamily, f.objectNumber, (f.generationNumber || 0));
        }

        var html = '<div class="font-diag-detail-content">' +
            '<div id="fontDetailTabContent" class="font-detail-tab-content"></div>' +
            '</div>';

        $('#fontDiagDetail').html(html);
        $('#fontDiagDetail').closest('.font-diag-detail-shell').addClass('has-detail');
        if (_p.activeFontDetailState && _p.activeFontDetailState.observer && typeof _p.activeFontDetailState.observer.disconnect === 'function') {
            _p.activeFontDetailState.observer.disconnect();
        }
        _p.activeFontDetailState = {
            allMappings: allMappings,
            mappings: allMappings,
            issues: issues,
            encoding: encoding,
            font: f,
            fontFamily: fontFamily,
            pendingQuery: '',
            pendingUsedOnly: false,
            filterMode: 'all',
            filterCounts: { all: allMappings.length, problems: problemCount, missing: missingCount, used: usedCount },
            mapRenderToken: 0,
            mapRenderContext: null,
            observer: null
        };

        var $encPanel = $('#fontFocusEncodingDetails');
        if ($encPanel.length && encoding) {
            var encHtml = '<details class="font-encoding-details"><summary><i class="fas fa-cogs me-1"></i>Encoding &amp; CMap Details</summary><div class="font-encoding-details-grid">';
            if (encoding.hasToUnicode !== undefined) {
                encHtml += '<div class="label">ToUnicode:</div><div>' +
                    (encoding.hasToUnicode ? '<i class="fas fa-check text-success"></i> present' : '<i class="fas fa-times text-danger"></i> missing') +
                    (encoding.toUnicodeObject ? ' <span class="text-muted">(' + P.Utils.escapeHtml(encoding.toUnicodeObject) + ')</span>' : '') + '</div>';
            }
            if (encoding.cmapName) { encHtml += '<div class="label">CMap name:</div><div>' + P.Utils.escapeHtml(encoding.cmapName) + '</div>'; }
            if (encoding.descendantSubtype) { encHtml += '<div class="label">Descendant subtype:</div><div>' + P.Utils.escapeHtml(encoding.descendantSubtype) + '</div>'; }
            if (encoding.encodingObject) { encHtml += '<div class="label">Encoding object:</div><div class="text-muted">' + P.Utils.escapeHtml(encoding.encodingObject) + '</div>'; }
            if (encoding.subtype) { encHtml += '<div class="label">Subtype:</div><div>' + P.Utils.escapeHtml(encoding.subtype) + '</div>'; }
            if (encoding.baseFont) { encHtml += '<div class="label">BaseFont:</div><div>' + P.Utils.escapeHtml(encoding.baseFont) + '</div>'; }
            if (encoding.descendantFont) { encHtml += '<div class="label">Descendant font:</div><div class="text-muted">' + P.Utils.escapeHtml(encoding.descendantFont) + '</div>'; }
            encHtml += '</div></details>';
            $encPanel.html(encHtml);
        }

        $('#fontDiagDetail').off('click', '.font-map-highlight-btn').on('click', '.font-map-highlight-btn', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            var $btn = $(this);
            var obj = P.GlyphUI.parseCodeValue($btn.attr('data-obj'));
            var gen = P.GlyphUI.parseCodeValue($btn.attr('data-gen'));
            var code = P.GlyphUI.parseCodeValue($btn.attr('data-code'));
            P.GlyphUI.toggleGlyphHighlightForCode(obj, gen, code);
        });
        P.GlyphUI.syncGlyphHighlightToggleButtons();
        setupDeferredFontDetailRendering();
        P.GlyphUI.applyGlobalDiagnosticsTableInteractions();
    }

    function renderFontDetailActiveTab() {
        var state = _p.activeFontDetailState;
        var $host = $('#fontDetailTabContent');
        if (!state || !$host.length) return;

        var counts = state.filterCounts || {};
        var fm = state.filterMode || 'all';

        var filterHtml =
            '<div class="font-detail-filter-group mb-2">' +
            '  <button class="btn btn-outline-accent btn-sm font-detail-filter-btn' + (fm === 'all' ? ' active' : '') + '" data-filter="all">All<span class="font-detail-filter-count"> (' + (counts.all || 0) + ')</span></button>' +
            '  <button class="btn btn-outline-accent btn-sm font-detail-filter-btn' + (fm === 'problems' ? ' active' : '') + '" data-filter="problems"><i class="fas fa-exclamation-triangle me-1"></i>Problems<span class="font-detail-filter-count"> (' + (counts.problems || 0) + ')</span></button>' +
            '  <button class="btn btn-outline-accent btn-sm font-detail-filter-btn' + (fm === 'used' ? ' active' : '') + '" data-filter="used">Used Only<span class="font-detail-filter-count"> (' + (counts.used || 0) + ')</span></button>' +
            '  <button class="btn btn-outline-accent btn-sm font-detail-filter-btn' + (fm === 'missing' ? ' active' : '') + '" data-filter="missing"><i class="fas fa-times-circle me-1"></i>Missing<span class="font-detail-filter-count"> (' + (counts.missing || 0) + ')</span></button>' +
            '</div>';

        var html =
            '<div class="font-diag-detail-title"><strong>Glyph mapping table (' + (state.allMappings || []).length + ')</strong></div>' +
            filterHtml +
            '<div class="font-detail-map-filter-row">' +
            '  <div class="font-detail-map-toolbar">' +
            '    <button id="fontDetailClearHighlightsBtn" class="btn btn-outline-accent btn-sm"><i class="fas fa-eraser me-1"></i>Clear highlights</button>' +
            '  </div>' +
            '  <input id="fontDetailMapHeaderFilter" class="form-control form-control-sm table-filter-input font-detail-header-filter" placeholder="Filter table..." />' +
            '</div>' +
            '<div class="font-detail-table-wrap font-detail-table-wrap-fill" id="fontDetailMapWrap">' +
            '<table class="font-detail-table" id="fontDetailMapTable" data-disable-header-filter="1"><thead><tr>' +
            '<th title="Character code used in PDF text operators.">Code</th>' +
            '<th title="Lazy-rendered preview of mapped glyphs.">Glyph</th>' +
            '<th title="PostScript glyph name or CID identifier.">Name</th>' +
            '<th title="Unicode text mapped from this code.">Unicode</th>' +
            '<th title="Unicode code point(s) in U+XXXX format.">Hex</th>' +
            '<th title="Glyph width reported by font metrics.">Width</th>' +
            '<th title="How many times this code appears in extracted text.">Used</th>' +
            '<th title="Combined diagnostic status: OK, No Unicode mapping, Glyph not in font, or Encoding mismatch.">Status</th>' +
            '<th title="Rendering status: whether the glyph will display correctly in a PDF viewer.">Render</th>' +
            '<th title="Extraction status: whether copy/paste and text search will work for this glyph.">Extract</th>' +
            '<th class="table-no-sort" title="Row actions.">Action</th>' +
            '</tr></thead><tbody id="fontDetailMapTableBody"><tr><td colspan="11" class="text-muted">Loading rows\u2026</td></tr></tbody></table></div>';

        $host.html(html);
        if (P.Utils && typeof P.Utils.initClearableInputs === 'function') {
            P.Utils.initClearableInputs();
        }

        $('#fontDetailClearHighlightsBtn').off('click').on('click', function () {
            P.GlyphUI.clearFontUsageHighlights({ resetGlyphToggles: true });
        });

        $host.off('click', '.font-detail-filter-btn').on('click', '.font-detail-filter-btn', function (ev) {
            ev.preventDefault();
            if (!_p.activeFontDetailState) return;
            var mode = String($(this).attr('data-filter') || 'all');
            _p.activeFontDetailState.filterMode = mode;
            $host.find('.font-detail-filter-btn').removeClass('active');
            $(this).addClass('active');
            renderMapTableNow(_p.activeFontDetailState.pendingQuery);
        });

        $('#fontDetailMapHeaderFilter').off('input').on('input', function () {
            if (!_p.activeFontDetailState) return;
            _p.activeFontDetailState.pendingQuery = String($(this).val() || '');
            renderMapTableNow(_p.activeFontDetailState.pendingQuery);
        });

        renderMapTableNow(state.pendingQuery);
        P.GlyphUI.applyGlobalDiagnosticsTableInteractions();
    }

    function renderMapTableNow(query) {
        var state = _p.activeFontDetailState;
        var tbody = document.getElementById('fontDetailMapTableBody');
        var wrap = document.getElementById('fontDetailMapWrap');
        if (!state || !tbody) return;

        state.mapRenderToken += 1;
        var token = state.mapRenderToken;
        var filterMode = state.filterMode || 'all';
        var normalizedQuery = String(query || '').toLowerCase();

        var filtered = state.allMappings.filter(function (row) {
            if (!row) return false;
            if (filterMode === 'problems' && (!row.diagnosticStatus || row.diagnosticStatus === 'OK')) return false;
            if (filterMode === 'used' && !(row.usedCount > 0)) return false;
            if (filterMode === 'missing' && !(!row.mapped || !row.glyphPresent || (row.usedCount > 0 && row.diagnosticStatus && row.diagnosticStatus !== 'OK'))) return false;
            if (!normalizedQuery) return true;
            var rowText = [row.code, row.unicode, row.unicodeHex, row.width, row.usedCount, row.glyphName, row.diagnosticStatus].join(' ').toLowerCase();
            return rowText.indexOf(normalizedQuery) !== -1;
        });

        if (!filtered.length) {
            tbody.innerHTML = '<tr><td colspan="11" class="text-muted">No rows match the current filter.</td></tr>';
            return;
        }

        tbody.innerHTML = '<tr><td colspan="11" class="text-muted">Rendering ' + filtered.length + ' rows\u2026 scroll to load more.</td></tr>';

        state.mapRenderContext = { token: token, filtered: filtered, index: 0, chunkSize: 120 };

        function appendNextChunk() {
            var ctx = state.mapRenderContext;
            if (!ctx || ctx.token !== state.mapRenderToken) return;
            if (ctx.index === 0) { tbody.innerHTML = ''; }
            var end = Math.min(ctx.index + ctx.chunkSize, ctx.filtered.length);
            var html = '';
            for (; ctx.index < end; ctx.index += 1) {
                html += buildMappingRowHtml(ctx.filtered[ctx.index], state);
            }
            if (html) {
                tbody.insertAdjacentHTML('beforeend', html);
                bindGlyphMappingRowClicks();
                P.GlyphViewer.bindLazyGlyphRendering();
                P.GlyphUI.bindGlyphHoverTooltips();
                P.GlyphUI.syncGlyphHighlightToggleButtons();
            }
        }

        function fillUntilScrollable() {
            if (!wrap) return;
            var guard = 0;
            while (state.mapRenderContext && state.mapRenderContext.index < state.mapRenderContext.filtered.length && wrap.scrollHeight <= wrap.clientHeight + 4 && guard < 12) {
                appendNextChunk();
                guard += 1;
            }
            P.GlyphUI.enableTableInteractions($('#fontDetailMapTable'));
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
        var state = _p.activeFontDetailState;
        if (!state) return;
        if (state.observer && typeof state.observer.disconnect === 'function') {
            state.observer.disconnect();
            state.observer = null;
        }
        renderFontDetailActiveTab();
    }

    function diagStatusBadge(status) {
        if (!status || status === 'UNKNOWN') return '<span class="diag-badge diag-badge-muted"><i class="fas fa-question"></i></span>';
        if (status === 'OK') return '<span class="diag-badge diag-badge-ok"><i class="fas fa-check-circle"></i></span>';
        if (status === 'NO_UNICODE_MAPPING') return '<span class="diag-badge diag-badge-warn"><i class="fas fa-question-circle me-1"></i>No Unicode</span>';
        if (status === 'GLYPH_NOT_IN_FONT') return '<span class="diag-badge diag-badge-danger"><i class="fas fa-times-circle me-1"></i>Not in font</span>';
        if (status === 'ENCODING_MISMATCH') return '<span class="diag-badge diag-badge-mismatch"><i class="fas fa-exchange-alt me-1"></i>Enc. mismatch</span>';
        return '<span class="diag-badge diag-badge-muted">' + P.Utils.escapeHtml(status) + '</span>';
    }

    function renderStatusIcon(status) {
        if (!status || status === 'UNKNOWN') return '<i class="fas fa-question diag-icon diag-icon-muted" title="Unknown"></i>';
        if (status === 'OK') return '<i class="fas fa-check diag-icon diag-icon-ok" title="OK"></i>';
        if (status === 'GLYPH_MISSING') return '<i class="fas fa-eye-slash diag-icon diag-icon-danger" title="Glyph missing \u2014 will render as tofu/.notdef"></i>';
        if (status === 'NOT_EMBEDDED') return '<i class="fas fa-unlink diag-icon diag-icon-warn" title="Font not embedded \u2014 depends on viewer substitution"></i>';
        if (status === 'NO_UNICODE_MAPPING') return '<i class="fas fa-file-excel diag-icon diag-icon-warn" title="No Unicode mapping \u2014 copy/paste/search will fail"></i>';
        if (status === 'ENCODING_MISMATCH') return '<i class="fas fa-not-equal diag-icon diag-icon-mismatch" title="Encoding mismatch \u2014 extracted text may be wrong"></i>';
        return '<i class="fas fa-question diag-icon diag-icon-muted" title="' + P.Utils.escapeHtml(status) + '"></i>';
    }

    function mappingRowClass(row) {
        var cls = 'font-map-row';
        var ds = row.diagnosticStatus || '';
        if (ds === 'NO_UNICODE_MAPPING') cls += ' font-map-row-warning';
        else if (ds === 'GLYPH_NOT_IN_FONT') cls += ' font-map-row-danger';
        else if (ds === 'ENCODING_MISMATCH') cls += ' font-map-row-mismatch';
        else if (ds === 'UNKNOWN') cls += ' font-map-row-unknown';
        if (row.usedCount > 0 && ds && ds !== 'OK') cls += ' font-map-row-used-problem';
        return cls;
    }

    function buildMappingRowHtml(row, state) {
        var obj = P.GlyphUI.parseCodeValue(state.font && state.font.objectNumber);
        var gen = P.GlyphUI.parseCodeValue((state.font && state.font.generationNumber) || 0);
        var code = P.GlyphUI.parseCodeValue(row.code);
        var unicode = row.unicode ? P.Utils.escapeHtml(row.unicode) : '<span class="diag-badge diag-badge-warn"><i class="fas fa-question-circle"></i> unmapped</span>';
        var glyphPreview;
        if (row.glyphPresent === false && !row.unicode) {
            glyphPreview = '<span class="font-glyph-absent" title="Glyph not present in font"><i class="far fa-square"></i></span>';
        } else {
            glyphPreview = '<span class="font-glyph-lazy" ' +
                'data-unicode="' + P.GlyphUI.escapeHtmlAttr(row.unicode || '') + '" ' +
                'data-unicode-hex="' + P.GlyphUI.escapeHtmlAttr(row.unicodeHex || '') + '" ' +
                'data-glyph-width="' + P.GlyphUI.escapeHtmlAttr(String(row.width == null ? '' : row.width)) + '" ' +
                'data-font-family="' + P.GlyphUI.escapeHtmlAttr((state.font.embedded ? state.fontFamily : '')) + '" ' +
                'title="Lazy-rendered glyph preview">' +
                (row.unicode ? '<span class="text-muted">\u2026</span>' : '<span class="text-danger">n/a</span>') +
                '</span>';
        }
        var glyphName = row.glyphName ? '<code class="font-map-glyph-name">' + P.Utils.escapeHtml(row.glyphName) + '</code>' : '<span class="text-muted">-</span>';
        var usedTd = row.usedCount > 0
            ? '<td>' + row.usedCount + '</td>'
            : '<td class="text-muted">' + (row.usedCount || 0) + '</td>';
        return '<tr class="' + mappingRowClass(row) + '" data-code="' + P.GlyphUI.escapeHtmlAttr(String(row.code == null ? '' : row.code)) + '" data-unicode="' + P.GlyphUI.escapeHtmlAttr(row.unicode || '') + '" data-unicode-hex="' + P.GlyphUI.escapeHtmlAttr(row.unicodeHex || '') + '">' +
            '<td>' + P.Utils.escapeHtml(String(row.code == null ? '' : row.code)) + '</td>' +
            '<td class="font-glyph-preview-cell">' + glyphPreview + '</td>' +
            '<td>' + glyphName + '</td>' +
            '<td class="font-map-unicode-cell">' + unicode + '</td>' +
            '<td>' + P.Utils.escapeHtml(row.unicodeHex || '') + '</td>' +
            '<td>' + (row.width == null ? '' : row.width) + '</td>' +
            usedTd +
            '<td>' + diagStatusBadge(row.diagnosticStatus) + '</td>' +
            '<td>' + renderStatusIcon(row.renderStatus) + '</td>' +
            '<td>' + renderStatusIcon(row.extractionStatus) + '</td>' +
            '<td><button class="btn btn-xs btn-outline-accent font-map-highlight-btn glyph-highlight-toggle" data-obj="' + P.GlyphUI.escapeHtmlAttr(String(obj)) + '" data-gen="' + P.GlyphUI.escapeHtmlAttr(String(gen)) + '" data-code="' + P.GlyphUI.escapeHtmlAttr(String(code)) + '" title="Toggle glyph highlight"><i class="fas fa-brush"></i></button></td>' +
            '</tr>';
    }

    function bindGlyphMappingRowClicks() {
        $('#fontDiagDetail .font-map-row').off('click').on('click', function (ev) {
            if ($(ev.target).closest('.font-map-highlight-btn').length) return;
            P.GlyphModal.ensureGlyphDetailModal();
            var state = _p.activeFontDetailState;
            if (!state || !state.font) return;

            $('#fontDiagDetail .font-map-row').removeClass('is-selected');
            $(this).addClass('is-selected');

            var obj = P.GlyphUI.parseCodeValue(state.font.objectNumber);
            var gen = P.GlyphUI.parseCodeValue(state.font.generationNumber || 0);
            var code = P.GlyphUI.parseCodeValue($(this).attr('data-code'));
            var unicode = String($(this).attr('data-unicode') || '');
            state.selectedGlyphCode = code;
            state.selectedGlyphUnicode = unicode;
            if (!(obj >= 0) || !(gen >= 0) || !(code >= 0)) {
                P.Utils.toast('Invalid glyph selection metadata for diagnostics.', 'warning');
                return;
            }
            P.GlyphModal.openGlyphDetailModal(obj, gen, code, unicode);
        });
    }

    function buildGlyphDiagnosticsSummaryHtml(glyph, detail) {
        var ds = glyph.diagnosticStatus || 'UNKNOWN';
        var rs = glyph.renderStatus || 'UNKNOWN';
        var es = glyph.extractionStatus || 'UNKNOWN';
        var explanations = [];
        if (ds === 'OK' && rs === 'OK' && es === 'OK') {
            explanations.push('This glyph maps correctly to Unicode, exists in the embedded font, and will render and extract correctly.');
        } else {
            if (rs === 'NOT_EMBEDDED') {
                explanations.push('Font not embedded. Rendering depends on the viewer\u2019s locally installed fonts.');
            } else if (rs === 'GLYPH_MISSING') {
                explanations.push('This glyph is not in the embedded font program. Viewers will show a .notdef glyph (tofu/box) or substitute.');
            }
            if (es === 'NO_UNICODE_MAPPING') {
                explanations.push('No Unicode mapping exists. Copy/paste and text search will fail for this glyph, though it may still render visually.');
            } else if (es === 'ENCODING_MISMATCH') {
                explanations.push('The encoding mapping doesn\u2019t match the expected glyph. Extracted text may show incorrect characters.');
            }
            if (!explanations.length) { explanations.push('Status could not be fully determined.'); }
        }

        return '' +
            '<details open class="mt-2 glyph-diag-summary"><summary>Diagnostics summary</summary>' +
            '<div class="glyph-diag-summary-grid">' +
            '  <div><span class="text-muted">Glyph name</span> <code>' + P.Utils.escapeHtml(String(glyph.glyphName || '-')) + '</code></div>' +
            '  <div><span class="text-muted">Present in font</span> ' + (glyph.glyphPresent ? '<span class="diag-badge diag-badge-ok"><i class="fas fa-check-circle"></i> yes</span>' : '<span class="diag-badge diag-badge-danger"><i class="fas fa-times-circle"></i> no</span>') + '</div>' +
            '  <div><span class="text-muted">Diagnostic status</span> ' + diagStatusBadge(ds) + '</div>' +
            '  <div><span class="text-muted">Render status</span> ' + renderStatusIcon(rs) + ' <span class="text-muted" style="font-size:11px;">' + P.Utils.escapeHtml(rs.replace(/_/g, ' ').toLowerCase()) + '</span></div>' +
            '  <div><span class="text-muted">Extraction status</span> ' + renderStatusIcon(es) + ' <span class="text-muted" style="font-size:11px;">' + P.Utils.escapeHtml(es.replace(/_/g, ' ').toLowerCase()) + '</span></div>' +
            '</div>' +
            '<div class="glyph-diag-explanation">' + P.Utils.escapeHtml(explanations.join(' ')) + '</div>' +
            '</details>';
    }

    return {
        loadFontDiagnosticsDetail: loadFontDiagnosticsDetail,
        renderFontDiagnosticsDetail: renderFontDiagnosticsDetail,
        buildMappingRowHtml: buildMappingRowHtml,
        buildGlyphDiagnosticsSummaryHtml: buildGlyphDiagnosticsSummaryHtml,
        diagStatusBadge: diagStatusBadge,
        renderStatusIcon: renderStatusIcon
    };
})(jQuery, PDFalyzer);
