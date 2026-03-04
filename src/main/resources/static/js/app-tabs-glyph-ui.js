/**
 * PDFalyzer Studio – Glyph UI: hover tooltips, table interactions, glyph highlighting, font-face registration.
 */
PDFalyzer.GlyphUI = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;

    // ======================== UTILITY HELPERS ========================

    function escapeHtmlAttr(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    function parseCodeValue(raw) {
        var text = String(raw == null ? '' : raw).trim();
        if (!text) return NaN;
        if (/^0x[0-9a-f]+$/i.test(text)) return parseInt(text, 16);
        if (/^u\+[0-9a-f]+$/i.test(text)) return parseInt(text.slice(2), 16);
        var numeric = Number(text);
        if (!Number.isNaN(numeric)) return Math.floor(numeric);
        return NaN;
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

    // ======================== HOVER TOOLTIPS ========================

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
            P.GlyphCanvas.fitGlyphInElement($glyph[0], {
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
            $('.glyph-tooltip-preview').remove();
            $(document).off('mousemove.glyphtooltip');
        });
    }

    // ======================== TABLE INTERACTIONS ========================

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
            if (!$filterHost.length) { $filterHost = $ths.last(); }
            if (!$filterHost.find('.table-header-tools').length) {
                var $tools = $('<span class="table-header-tools"></span>');
                var $input = $('<input type="text" class="form-control form-control-sm table-filter-input" placeholder="Filter table..." />');
                $tools.append($input);
                $filterHost.append($tools);
                $input.off('input').on('input', function () {
                    filterTableRows($table, String($(this).val() || '').toLowerCase());
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
            var av = String($(a).children('td').eq(columnIndex).text() || '').trim();
            var bv = String($(b).children('td').eq(columnIndex).text() || '').trim();
            var an = parseFloat(av.replace(/[^0-9+\-.]/g, ''));
            var bn = parseFloat(bv.replace(/[^0-9+\-.]/g, ''));
            var cmp = (!Number.isNaN(an) && !Number.isNaN(bn))
                ? (an - bn)
                : av.localeCompare(bv, undefined, { numeric: true, sensitivity: 'base' });
            return direction === 'desc' ? -cmp : cmp;
        });
        rows.forEach(function (row) { $tbody.append(row); });
    }

    function filterTableRows($table, query) {
        var $tbody = $table.find('tbody').first();
        if (!$tbody.length) return;
        getSortableDataRows($tbody).each(function () {
            var $row = $(this);
            var text = String($row.text() || '').toLowerCase();
            $row.toggle(!query || text.indexOf(query) !== -1);
        });
    }

    function applyGlobalDiagnosticsTableInteractions() {
        var selectors = [
            '#treeContent .font-table',
            '#fontDiagDetail .font-detail-table',
            '#fontGlyphDetailBody .font-detail-table',
            '#fontGlyphUsagePanel .font-detail-table'
        ];
        selectors.forEach(function (selector) {
            $(selector).each(function () { enableTableInteractions($(this)); });
        });
    }

    // ======================== GLYPH HIGHLIGHTING ========================

    function makeGlyphHighlightKey(obj, gen, code) {
        return String(obj) + ':' + String(gen || 0) + ':' + String(code);
    }

    function getGlyphHighlightColor(index) {
        if (index < _p.glyphHighlightPalette.length) {
            return _p.glyphHighlightPalette[index];
        }
        var hue = Math.round((index * 137.508) % 360);
        return 'hsl(' + hue + ', 85%, 52%)';
    }

    function nextGlyphHighlightColorIndex() {
        var used = {};
        Object.keys(_p.activeGlyphHighlightSelections).forEach(function (key) {
            var item = _p.activeGlyphHighlightSelections[key];
            if (item && item.colorIndex >= 0) used[item.colorIndex] = true;
        });
        for (var idx = 0; idx < _p.glyphHighlightPalette.length; idx += 1) {
            if (!used[idx]) return idx;
        }
        while (used[_p.nextGeneratedGlyphColorIndex]) {
            _p.nextGeneratedGlyphColorIndex += 1;
        }
        var generated = _p.nextGeneratedGlyphColorIndex;
        _p.nextGeneratedGlyphColorIndex += 1;
        return generated;
    }

    function getGlyphHighlightEntry(obj, gen, code) {
        if (!(obj >= 0) || !(gen >= 0) || !(code >= 0)) return null;
        return _p.activeGlyphHighlightSelections[makeGlyphHighlightKey(obj, gen, code)] || null;
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
            applyGlyphHighlightButtonState($btn, getGlyphHighlightEntry(obj, gen, code));
        });
        var $popupBtn = $('#fontGlyphHighlightBtn');
        if ($popupBtn.length) {
            var pobj = parseCodeValue($popupBtn.attr('data-obj'));
            var pgen = parseCodeValue($popupBtn.attr('data-gen'));
            var pcode = parseCodeValue($popupBtn.attr('data-code'));
            applyGlyphHighlightButtonState($popupBtn, getGlyphHighlightEntry(pobj, pgen, pcode));
        }
    }

    function getUsageBBox(area) {
        var bbox = area && Array.isArray(area.bbox) ? area.bbox : [];
        return [parseFloat(bbox[0]) || 0, parseFloat(bbox[1]) || 0, parseFloat(bbox[2]) || 0, parseFloat(bbox[3]) || 0];
    }

    function clearFontUsageHighlights(options) {
        var resetState = !options || options.resetState !== false;
        var resetGlyphToggles = !!(options && options.resetGlyphToggles);
        $('.pdf-font-usage').remove();
        if (resetState) { _p.activeFontUsageAreas = []; }
        if (resetGlyphToggles) {
            _p.activeGlyphHighlightSelections = {};
            _p.nextGeneratedGlyphColorIndex = _p.glyphHighlightPalette.length;
            syncGlyphHighlightToggleButtons();
        }
    }

    function showFontUsageHighlights(areas, options) {
        var clearExisting = !options || options.clearExisting !== false;
        var persist = !options || options.persist !== false;
        if (clearExisting) { clearFontUsageHighlights({ resetState: persist }); }
        if (persist) { _p.activeFontUsageAreas = (areas || []).slice(); }
        var found = 0, highlighted = 0;
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

    function rebuildGlyphToggleHighlights() {
        var merged = [];
        Object.keys(_p.activeGlyphHighlightSelections).forEach(function (key) {
            var entry = _p.activeGlyphHighlightSelections[key];
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
        if (!(obj >= 0) || !(gen >= 0) || !(code >= 0)) return;

        var key = makeGlyphHighlightKey(obj, gen, code);
        if (_p.activeGlyphHighlightSelections[key]) {
            delete _p.activeGlyphHighlightSelections[key];
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
                _p.activeGlyphHighlightSelections[key] = {
                    obj: obj, gen: gen, code: code,
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

    return {
        escapeHtmlAttr: escapeHtmlAttr,
        parseCodeValue: parseCodeValue,
        ensureDiagnosticsFontFace: ensureDiagnosticsFontFace,
        bindGlyphHoverTooltips: bindGlyphHoverTooltips,
        enableTableInteractions: enableTableInteractions,
        applyGlobalDiagnosticsTableInteractions: applyGlobalDiagnosticsTableInteractions,
        syncGlyphHighlightToggleButtons: syncGlyphHighlightToggleButtons,
        clearFontUsageHighlights: clearFontUsageHighlights,
        showFontUsageHighlights: showFontUsageHighlights,
        toggleGlyphHighlightForCode: toggleGlyphHighlightForCode
    };
})(jQuery, PDFalyzer);
