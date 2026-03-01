/**
 * PDFalyzer – tab switching and per-tab content loading.
 */
PDFalyzer.Tabs = (function ($, P) {
    'use strict';

    function init() {
        $('.tab-btn').on('click', function () {
            $('.tab-btn').removeClass('active');
            $(this).addClass('active');
            P.state.currentTab = $(this).data('tab');
            switchTab(P.state.currentTab);
        });
    }

    function switchTab(tab) {
        if (!P.state.treeData || !P.state.sessionId) return;
        switch (tab) {
            case 'structure':   P.Tree.render(P.state.treeData); break;
            case 'forms':       P.Tree.renderSubtree(P.state.treeData, 'acroform'); break;
            case 'fonts':       loadFonts(); break;
            case 'validation':  loadValidation(); break;
            case 'rawcos':      loadRawCos(); break;
            case 'bookmarks':   P.Tree.renderSubtree(P.state.treeData, 'bookmarks'); break;
            case 'attachments': loadAttachments(); break;
        }
    }

    // ======================== FONTS TAB ========================

    function loadFonts() {
        clearFontUsageHighlights();
        P.Utils.apiFetch('/api/fonts/' + P.state.sessionId)
            .done(function (fonts) { renderFontTable(fonts); })
            .fail(function () { P.Utils.toast('Font analysis error', 'danger'); });
    }

    function renderFontTable(fonts) {
        var $c = $('#treeContent');
        if (!fonts.length) {
            $c.html('<div class="text-muted text-center mt-3">No fonts found</div>');
            return;
        }
        var html = '<table class="font-table"><thead><tr>' +
            '<th>Font</th><th>Type</th><th>Embedded</th><th>Subset</th>' +
            '<th>Page</th><th>Issues</th><th>Actions</th>' +
            '</tr></thead><tbody>';
        fonts.forEach(function (f) {
            var embIcon = f.embedded
                ? '<i class="fas fa-check-circle text-success"></i>'
                : '<i class="fas fa-times-circle text-danger"></i>';
            var subIcon = f.subset
                ? '<i class="fas fa-check text-muted"></i>'
                : '<i class="fas fa-minus text-muted"></i>';
            var issueHtml = (f.issues && f.issues.length > 0)
                ? '<i class="fas fa-exclamation-triangle text-warning me-1"></i>' +
                  '<span title="' + P.Utils.escapeHtml(f.issues.join('; ')) + '">' +
                  f.issues.length + '</span>'
                : '<i class="fas fa-check text-success"></i>';
            var actions = '';
            if (f.objectNumber >= 0) {
                actions += '<a class="btn btn-xs btn-outline-accent me-1" target="_blank" ' +
                    'href="/api/fonts/' + P.state.sessionId + '/charmap/' + f.pageIndex +
                    '/' + encodeURIComponent(f.objectId) + '" title="Character Map">' +
                    '<i class="fas fa-table"></i></a>';
                actions += '<button class="btn btn-xs btn-outline-accent me-1 font-usage-btn" ' +
                    'data-obj="' + f.objectNumber + '" data-gen="' + (f.generationNumber || 0) + '" ' +
                    'title="Visualize usage"><i class="fas fa-highlighter"></i></button>';
                if (f.embedded) {
                    actions += '<a class="btn btn-xs btn-outline-accent" target="_blank" ' +
                        'href="/api/fonts/' + P.state.sessionId + '/extract/' +
                        f.objectNumber + '/' + (f.generationNumber || 0) +
                        '" title="Extract font file"><i class="fas fa-download"></i></a>';
                }
            }
            var issueDetail = '';
            if (f.usageContext || f.fixSuggestion) {
                var issueParts = [];
                if (f.usageContext) issueParts.push('Context: ' + P.Utils.escapeHtml(f.usageContext));
                if (f.fixSuggestion) issueParts.push('Fix: ' + P.Utils.escapeHtml(f.fixSuggestion));
                issueDetail = '<div class="text-muted" style="font-size:11px;">' + issueParts.join('<br>') + '</div>';
            }
            html += '<tr>' +
                '<td>' + P.Utils.escapeHtml(f.fontName || '(unknown)') + '</td>' +
                '<td>' + P.Utils.escapeHtml(f.fontType || '') + '</td>' +
                '<td>' + embIcon + '</td><td>' + subIcon + '</td>' +
                '<td>' + (f.pageIndex + 1) + '</td>' +
                '<td>' + issueHtml + issueDetail + '</td>' +
                '<td>' + actions + '</td></tr>';
        });
        html += '</tbody></table>';
        $c.html(html);
        $c.find('.font-usage-btn').on('click', function () {
            var obj = parseInt($(this).data('obj'), 10);
            var gen = parseInt($(this).data('gen'), 10);
            P.Utils.apiFetch('/api/fonts/' + P.state.sessionId + '/usage/' + obj + '/' + gen)
                .done(function (areas) {
                    showFontUsageHighlights(areas || []);
                    P.Utils.toast('Font usage areas: ' + ((areas || []).length), 'info');
                })
                .fail(function () {
                    P.Utils.toast('Failed to load font usage', 'danger');
                });
        });
    }

    function clearFontUsageHighlights() {
        $('.pdf-font-usage').remove();
    }

    function showFontUsageHighlights(areas) {
        clearFontUsageHighlights();
        if (!areas || !areas.length) return;
        areas.forEach(function (area) {
            var pageIndex = area.pageIndex;
            var bbox = area.bbox;
            if (typeof pageIndex !== 'number' || !bbox || bbox.length < 4) return;
            var $wrapper = $('[data-page="' + pageIndex + '"]');
            var viewport = P.state.pageViewports[pageIndex];
            if (!$wrapper.length || !viewport) return;
            $('<div>', { 'class': 'pdf-font-usage' }).css({
                left: bbox[0] * viewport.scale + 'px',
                top: viewport.height - (bbox[1] + bbox[3]) * viewport.scale + 'px',
                width: bbox[2] * viewport.scale + 'px',
                height: bbox[3] * viewport.scale + 'px'
            }).appendTo($wrapper);
        });
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
        var header = '<div class="mb-2" style="padding:8px;">' +
            '<span class="badge ' + (success ? 'bg-success' : 'bg-secondary') + ' me-2">' +
            (success ? 'PASS' : 'CHECK') + '</span>' +
            '<span class="text-muted">veraPDF ' + (available ? 'result' : 'not available') + '</span></div>';

        if (report && (reportFormat === 'html' || looksLikeHtml)) {
            $('#treeContent').html(
                header +
                '<div style="padding:8px;">' +
                '<iframe title="veraPDF Report" style="width:100%;height:520px;border:1px solid var(--border);border-radius:4px;background:#fff;"></iframe>' +
                '</div>'
            );
            $('#treeContent iframe')[0].srcdoc = report;
            return;
        }

        $('#treeContent').html(
            header +
            '<div style="padding:8px;">' +
            '<pre style="white-space:pre-wrap;max-height:480px;overflow:auto;">' +
            P.Utils.escapeHtml(report || 'No report output') +
            '</pre></div>'
        );
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

    function loadRawCos() {
        P.Utils.apiFetch('/api/tree/' + P.state.sessionId + '/raw-cos')
            .done(function (rawTree) { P.Tree.render(rawTree); })
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
        P.Tree.renderSubtree(P.state.treeData, 'attachments');
    }

    return { init: init, switchTab: switchTab };
})(jQuery, PDFalyzer);
