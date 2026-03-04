/**
 * PDFalyzer Studio – Glyph detail modal and usage inspector.
 */
PDFalyzer.GlyphModal = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;

    function ensureGlyphDetailModal() {
        if (document.getElementById('fontGlyphDetailModal')) return;
        var modalHtml = '' +
            '<div class="modal fade pdfa-modal" id="fontGlyphDetailModal" tabindex="-1" aria-hidden="true">' +
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
        if (P.Utils && P.Utils.prepareModal) {
            P.Utils.prepareModal(document.getElementById('fontGlyphDetailModal'));
        }
    }

    function openGlyphDetailModal(obj, gen, code, unicode) {
        var $body = $('#fontGlyphDetailBody');
        if (!$body.length) return;

        var glyphText = unicode || '(unmapped)';
        $body.html('<div class="text-muted"><span class="spinner-border spinner-border-sm"></span> Loading glyph diagnostics\u2026</div>');
        var modalEl = document.getElementById('fontGlyphDetailModal');
        if (P.Utils && P.Utils.prepareModal) {
            P.Utils.prepareModal(modalEl);
        }
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
            P.GlyphUI.ensureDiagnosticsFontFace(detailFontFamily, obj, gen || 0);
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

        var eha = P.GlyphUI.escapeHtmlAttr;
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
            '          data-glyph="' + eha(hugeGlyph) + '" ' +
            '          data-font-family="' + eha(heroFontFamily) + '" ' +
            '          data-font-primary="' + eha(heroPrimaryFont) + '" ' +
            '          data-glyph-width="' + eha(String(glyph.width == null ? '' : glyph.width)) + '"></canvas>' +
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
            P.FontDetail.buildGlyphDiagnosticsSummaryHtml(glyph, detail) +
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
            '  <button class="btn btn-outline-accent btn-sm me-2 glyph-highlight-toggle" id="fontGlyphHighlightBtn" data-obj="' + eha(String(obj)) + '" data-gen="' + eha(String(gen)) + '" data-code="' + eha(String(code)) + '"><i class="fas fa-brush me-1"></i>Toggle glyph highlight</button>' +
            '  <button class="btn btn-outline-accent btn-sm" id="fontGlyphClearHighlightsBtn"><i class="fas fa-eraser me-1"></i>Clear highlights</button>' +
            '</div>' +
            '<details class="mt-2" id="fontGlyphUsageSection"><summary>Usage positions by page (deferred)</summary>' +
            '  <div id="fontGlyphUsagePanel" class="font-glyph-usage-panel text-muted">Open this section to load usage positions.</div>' +
            '</details>' +
            '<details open class="mt-2"><summary>Glyph metrics</summary>' +
            '  <div class="font-detail-table-wrap"><table class="font-detail-table"><tbody>' +
            '    <tr><td>Glyph name</td><td><code>' + P.Utils.escapeHtml(String(glyph.glyphName || '-')) + '</code></td></tr>' +
            '    <tr><td>Present in font</td><td>' + (glyph.glyphPresent ? '<span class="text-success">yes</span>' : '<span class="text-danger">no</span>') + '</td></tr>' +
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
            (encoding.cmapName ? '    <tr><td>CMap name</td><td>' + P.Utils.escapeHtml(String(encoding.cmapName)) + '</td></tr>' : '') +
            (encoding.descendantSubtype ? '    <tr><td>Descendant subtype</td><td>' + P.Utils.escapeHtml(String(encoding.descendantSubtype)) + '</td></tr>' : '') +
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
            P.GlyphViewer.initGlyphCanvasViewer(heroCanvas, heroViewport, heroMetrics);
        } else {
            if (heroViewport) { heroViewport.setAttribute('title', 'No glyph present'); }
            if (heroMetrics) { heroMetrics.textContent = 'No glyph present'; }
        }

        $('#fontGlyphHighlightBtn').off('click').on('click', function () {
            P.GlyphUI.toggleGlyphHighlightForCode(obj, gen, code);
        });
        $('#fontGlyphClearHighlightsBtn').off('click').on('click', function () {
            P.GlyphUI.clearFontUsageHighlights({ resetGlyphToggles: true });
        });
        P.GlyphUI.syncGlyphHighlightToggleButtons();

        bindGlyphUsageInspector(obj, gen, code);
        P.GlyphUI.applyGlobalDiagnosticsTableInteractions();
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
                    '</td></tr>';
            });
            html += '</tbody></table>';
            $body.html(html);
            $body.attr('data-rendered', '1');
            P.GlyphUI.enableTableInteractions($body.find('table'));
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
                .map(function (pageIndex) { return { pageIndex: pageIndex, items: grouped[pageIndex] }; });
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

        $section.off('toggle').on('toggle', function () { if (this.open) loadUsageIfNeeded(); });

        $panel.off('click', '.glyph-usage-page-jump').on('click', '.glyph-usage-page-jump', function (e) {
            e.preventDefault(); e.stopPropagation();
            jumpToUsagePage(parseInt($(this).attr('data-page'), 10));
        });

        $panel.off('click', '.glyph-usage-page-highlight').on('click', '.glyph-usage-page-highlight', function (e) {
            e.preventDefault(); e.stopPropagation();
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            var pageAreas = (usageByPage && usageByPage[pageIndex]) ? usageByPage[pageIndex] : [];
            var usage = P.GlyphUI.showFontUsageHighlights(pageAreas, { clearExisting: true });
            P.Utils.toast('Highlighted ' + usage.highlighted + ' positions on page ' + (pageIndex + 1), usage.highlighted ? 'info' : 'warning');
        });

        $panel.off('click', '.glyph-usage-jump').on('click', '.glyph-usage-jump', function () {
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            var rowIndex = parseInt($(this).attr('data-index'), 10);
            var pageAreas = (usageByPage && usageByPage[pageIndex]) ? usageByPage[pageIndex] : [];
            var area = pageAreas[rowIndex];
            if (!area) return;
            P.GlyphUI.showFontUsageHighlights([area], { clearExisting: true });
            jumpToUsagePage(pageIndex);
        });

        $panel.off('click', '.glyph-usage-highlight').on('click', '.glyph-usage-highlight', function () {
            var pageIndex = parseInt($(this).attr('data-page'), 10);
            var rowIndex = parseInt($(this).attr('data-index'), 10);
            var pageAreas = (usageByPage && usageByPage[pageIndex]) ? usageByPage[pageIndex] : [];
            var area = pageAreas[rowIndex];
            if (!area) return;
            var usage = P.GlyphUI.showFontUsageHighlights([area], { clearExisting: true });
            P.Utils.toast('Highlighted position on page ' + (pageIndex + 1), usage.highlighted ? 'info' : 'warning');
        });
    }

    function getUsageBBox(area) {
        var bbox = area && Array.isArray(area.bbox) ? area.bbox : [];
        return [parseFloat(bbox[0]) || 0, parseFloat(bbox[1]) || 0, parseFloat(bbox[2]) || 0, parseFloat(bbox[3]) || 0];
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

    function collectExtraGlyphDiagnostics(glyph) {
        if (!glyph || typeof glyph !== 'object') return [];
        var known = { code: 1, codeHex: 1, unicode: 1, unicodeHex: 1, mapped: 1, usedCount: 1, width: 1, canEncodeMappedUnicode: 1, displacement: 1, positionVector: 1 };
        var entries = [];
        Object.keys(glyph).forEach(function (key) {
            if (known[key]) return;
            var value = glyph[key];
            if (value == null || value === '') return;
            var printable;
            if (typeof value === 'object') {
                try { printable = JSON.stringify(value); } catch (e) { printable = String(value); }
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
        var contours = 0, splines = 0, segments = 0;
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
        if (contours === 0 && splines === 0 && segments === 0) return null;
        return { contours: contours, splines: splines, segments: segments };
    }

    return {
        ensureGlyphDetailModal: ensureGlyphDetailModal,
        openGlyphDetailModal: openGlyphDetailModal
    };
})(jQuery, PDFalyzer);
