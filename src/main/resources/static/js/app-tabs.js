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
        var looksLikeXml = /^\s*<\?xml|^\s*<report[\s>]/i.test(report);
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

        if (report && (reportFormat === 'xml' || reportFormat === 'mrr' || looksLikeXml)) {
            renderVeraPdfXmlReport(header, report);
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
            '<div style="padding:8px;">' +
            '<div class="card mb-2"><div class="card-body py-2">' +
            '<div class="d-flex flex-wrap gap-2 align-items-center">' +
            '<span class="badge bg-danger">Failed rules: ' + failedRules.length + '</span>' +
            '<span class="badge bg-success">Passed rules: ' + passedRules.length + '</span>' +
            '<span class="badge bg-danger-subtle text-danger">Failed checks: ' + (isNaN(totalFailedChecks) ? 0 : totalFailedChecks) + '</span>' +
            '<span class="badge bg-success-subtle text-success">Passed checks: ' + (isNaN(totalPassedChecks) ? 0 : totalPassedChecks) + '</span>' +
            '</div>' +
            (profileName ? '<div class="text-muted mt-2" style="font-size:12px;">Profile: ' + P.Utils.escapeHtml(profileName) + '</div>' : '') +
            (statement ? '<div class="mt-1">' + P.Utils.escapeHtml(statement) + '</div>' : '') +
            '</div></div>';

        html += '<div class="card mb-2"><div class="card-header py-2"><strong class="text-danger">Failed rules</strong></div><div class="card-body py-2">';
        if (!failedRules.length) {
            html += '<div class="text-success">No failed rules.</div>';
        } else {
            failedRules.forEach(function (rule) {
                html += buildRuleHtml(rule, true);
            });
        }
        html += '</div></div>';

        html += '<details><summary class="text-muted" style="cursor:pointer;">Show passed rules (' + passedRules.length + ')</summary>' +
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

        var html = '<div class="border rounded p-2 mb-2 ' + (failed ? 'border-danger-subtle bg-danger-subtle' : 'border-success-subtle') + '">' +
            '<div class="d-flex flex-wrap align-items-center gap-2 mb-1">' +
            '<span class="badge ' + (failed ? 'bg-danger' : 'bg-success') + '">' + P.Utils.escapeHtml(status || (failed ? 'failed' : 'passed')) + '</span>' +
            (clause ? '<span class="badge bg-secondary">Clause ' + P.Utils.escapeHtml(clause) + '</span>' : '') +
            (testNumber ? '<span class="badge bg-secondary">Test ' + P.Utils.escapeHtml(testNumber) + '</span>' : '') +
            '<span class="badge bg-dark">Checks ' + P.Utils.escapeHtml(failedChecks) + ' failed / ' + P.Utils.escapeHtml(passedChecks) + ' passed</span>' +
            '</div>' +
            (spec ? '<div class="text-muted" style="font-size:12px;">' + P.Utils.escapeHtml(spec) + '</div>' : '') +
            (descNode ? '<div class="mt-1">' + P.Utils.escapeHtml(descNode.textContent || '') + '</div>' : '') +
            (objNode ? '<div class="text-muted" style="font-size:12px;">Object: ' + P.Utils.escapeHtml(objNode.textContent || '') + '</div>' : '') +
            (testNode ? '<div class="mt-1"><code>' + P.Utils.escapeHtml(testNode.textContent || '') + '</code></div>' : '');

        if (checks.length) {
            html += '<details class="mt-1"><summary style="cursor:pointer;">Check details (' + checks.length + ')</summary>' +
                '<div class="mt-1">';
            checks.forEach(function (checkEl) {
                var text = (checkEl.textContent || '').trim();
                if (text) {
                    html += '<div class="small border-start ps-2 mb-1">' + P.Utils.escapeHtml(text) + '</div>';
                }
            });
            html += '</div></details>';
        }

        html += '</div>';
        return html;
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
