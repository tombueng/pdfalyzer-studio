/**
 * PDFalyzer Studio – tab switching and per-tab content loading.
 */
PDFalyzer.Tabs = (function ($, P) {
    'use strict';

    var veraPdfExportHtml = '';
    var veraPdfExportTitle = 'veraPDF Report';

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
