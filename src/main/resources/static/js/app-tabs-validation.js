/**
 * PDFalyzer Studio – Validation tab: standard validation and veraPDF report rendering.
 */
PDFalyzer.ValidationTab = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;

    // ── Issue explainer knowledge base ────────────────────────────────────────
    var ISSUE_EXPLAINER = {
        'META-001': {
            why: 'XMP metadata is required for PDF/A compliance. Document management systems and search engines rely on it for indexing and discoverability.',
            fix: 'Embed an XMP metadata stream by setting document properties in your PDF authoring tool, or via PDFBox: PDMetadata / XMPMetadata APIs.',
            confidence: 'high', tab: null
        },
        'META-002': {
            why: 'A document title aids accessibility (screen readers announce the title) and improves search ranking in digital repositories.',
            fix: 'Set the /Title entry in the document information dictionary via File → Properties → Description in your PDF editor.',
            confidence: 'high', tab: null
        },
        'META-003': {
            why: 'Producer information identifies the software that created the PDF. This is used for auditing, troubleshooting, and compliance reporting.',
            fix: 'Set the /Producer entry programmatically or ensure your PDF creation tool writes it automatically.',
            confidence: 'low', tab: null
        },
        'FONT-001': {
            why: 'Non-embedded fonts cause rendering differences across systems. The document may display incorrectly or fail to print on machines that lack the font.',
            fix: 'Re-export the document with font embedding enabled. In most tools: File → Export → PDF Options → Embed all fonts.',
            confidence: 'high', tab: 'fonts'
        },
        'FONT-002': {
            why: 'Without a ToUnicode CMap, text cannot be reliably extracted (copy/paste, search, accessibility). Required for PDF/A-1b compliance.',
            fix: 'Regenerate the PDF with a tool that writes ToUnicode CMap entries, or use a subset-embedding option that includes them.',
            confidence: 'high', tab: 'fonts'
        },
        'FONT-ERR': {
            why: 'A corrupt or unloadable font will prevent text from rendering on that page and may cause viewer crashes.',
            fix: 'Identify and replace the problematic font. Use PDFalyzer\'s Fonts tab to locate the object, then re-embed or substitute.',
            confidence: 'medium', tab: 'fonts'
        },
        'PAGE-001': {
            why: 'A PDF with zero pages is invalid per the PDF specification and will be rejected by most viewers, printers, and workflows.',
            fix: 'The document may be corrupt. Try re-saving from the source application or repairing with a PDF repair tool.',
            confidence: 'high', tab: null
        },
        'PAGE-002': {
            why: 'Without a MediaBox, the viewer has no page dimensions and cannot render the page. This is a fatal structural error.',
            fix: 'Add a /MediaBox entry to the page dictionary. Use the COS editor in PDFalyzer to add [0 0 595 842] for A4.',
            confidence: 'high', tab: null
        },
        'ANNOT-001': {
            why: 'An annotation without a rectangle has no position and will not display or be interactive.',
            fix: 'Set the /Rect entry in the annotation dictionary. Use the COS editor to add the four-element array [x1 y1 x2 y2].',
            confidence: 'medium', tab: null
        },
        'ANNOT-002': {
            why: 'PDF/A-1b requires all annotations to have an appearance stream (/AP). Without one, the annotation may not render consistently across viewers.',
            fix: 'Open the PDF in a PDF editor and flatten or regenerate annotations. For form fields, use "Flatten Annotations" export option.',
            confidence: 'high', tab: null
        },
        'FORM-001': {
            why: 'Widget annotations represent interactive form fields rendered on the page, but without an AcroForm dictionary in the Document Catalog, their values cannot be read, filled, or submitted by any PDF viewer or processor.',
            fix: 'Reconstruct the AcroForm by linking the orphaned widget annotations as fields.',
            confidence: 'high', tab: 'fields', fixEndpoint: '/api/repair/acroform/'
        },
        'FORM-002': {
            why: 'The AcroForm dictionary exists but its /Fields array is empty, while widget annotations are present on pages. These fields are orphaned and invisible to form processors.',
            fix: 'Adopt the orphaned widget annotations into the AcroForm /Fields array.',
            confidence: 'high', tab: 'fields', fixEndpoint: '/api/repair/acroform/'
        }
    };

    function confidenceBadgeHtml(level) {
        var cls = level === 'high' ? 'bg-success' : level === 'medium' ? 'bg-warning text-dark' : 'bg-secondary';
        return '<span class="badge ' + cls + ' ms-1" style="font-size:10px;">Confidence: ' + level + '</span>';
    }

    function buildExplainerHtml(issue) {
        var ex = ISSUE_EXPLAINER[issue.ruleId];
        if (!ex) return '';
        var tabLink = '';
        if (ex.tab && P.Tabs && P.Tabs.switchTab) {
            tabLink = ' <a href="#" class="issue-tab-link ms-2" data-tab="' + ex.tab + '" style="font-size:11px;">' +
                '<i class="fas fa-arrow-right me-1"></i>Open ' + ex.tab + ' tab</a>';
        }
        var fixBtn = '';
        if (ex.fixEndpoint) {
            fixBtn = ' <button class="btn btn-warning btn-sm ms-2 issue-fix-btn" data-endpoint="' +
                ex.fixEndpoint + '" data-rule="' + issue.ruleId + '" style="font-size:11px;padding:2px 8px;">' +
                '<i class="fas fa-wrench me-1"></i>Fix Now</button>';
        }
        return '<details class="issue-explainer mt-1"><summary class="issue-explainer-summary">' +
            '<i class="fas fa-lightbulb me-1 text-warning"></i>Why this matters' +
            confidenceBadgeHtml(ex.confidence) + '</summary>' +
            '<div class="issue-explainer-body">' +
            '<p class="mb-1"><strong>Why:</strong> ' + P.Utils.escapeHtml(ex.why) + '</p>' +
            '<p class="mb-1"><strong>Likely fix:</strong> ' + P.Utils.escapeHtml(ex.fix) + tabLink + fixBtn + '</p>' +
            '</div></details>';
    }

    function bindExplainerLinks($container) {
        $container.find('.issue-tab-link').off('click').on('click', function (e) {
            e.preventDefault();
            var tab = $(this).data('tab');
            if (tab && P.Tabs && P.Tabs.switchTab) P.Tabs.switchTab(tab);
        });
        $container.find('.issue-fix-btn').off('click').on('click', function () {
            var $btn = $(this);
            var endpoint = $btn.data('endpoint') + P.state.sessionId;
            $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Fixing…');
            P.Utils.apiFetch(endpoint, { method: 'POST' })
                .done(function (res) {
                    if (res.success) {
                        P.Utils.toast('AcroForm repaired — ' + res.adopted + ' field(s) adopted. Re-running validation…', 'success');
                        runStandardValidation();
                    } else {
                        P.Utils.toast('No orphaned widget fields with a field name (/T) were found to adopt.', 'warning');
                        $btn.prop('disabled', false).html('<i class="fas fa-wrench me-1"></i>Fix Now');
                    }
                })
                .fail(function () {
                    P.Utils.toast('Repair failed', 'danger');
                    $btn.prop('disabled', false).html('<i class="fas fa-wrench me-1"></i>Fix Now');
                });
        });
    }

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
        $c.html(getValidationControlsHtml(true) + P.Utils.tabSkeleton('validation'));
        bindValidationControls();
        P.Utils.apiFetch('/api/validate/' + P.state.sessionId)
            .done(function (issues) { renderValidation(issues); })
            .fail(function () { P.Utils.toast('Validation error', 'danger'); });
    }

    function runVeraPdfValidation() {
        var $c = $('#treeContent');
        $c.html(getValidationControlsHtml(true) + P.Utils.tabSkeleton('validation'));
        bindValidationControls();
        P.Utils.apiFetch('/api/validate/' + P.state.sessionId + '/verapdf')
            .done(function (result) { renderVeraPdf(result || {}); })
            .fail(function () { P.Utils.toast('veraPDF validation error', 'danger'); });
    }

    function bindValidationControls() {
        $('#runValidateBtn').off('click').on('click', function () { runStandardValidation(); });
        $('#exportValidateBtn').off('click').on('click', function () {
            window.open('/api/validate/' + P.state.sessionId + '/export', '_blank');
        });
        $('#runVeraPdfBtn').off('click').on('click', function () { runVeraPdfValidation(); });
    }

    function loadValidation() {
        runStandardValidation();
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

        var html = controls + '<div style="padding:8px;">';
        var counts = {};
        issues.forEach(function (i) { counts[i.severity] = (counts[i.severity] || 0) + 1; });
        html += '<div class="mb-2" style="font-size:12px;">' +
            (counts.ERROR   ? '<span class="text-danger me-3"><i class="fas fa-times-circle"></i> ' + counts.ERROR + ' errors</span>' : '') +
            (counts.WARNING ? '<span class="text-warning me-3"><i class="fas fa-exclamation-triangle"></i> ' + counts.WARNING + ' warnings</span>' : '') +
            (counts.INFO    ? '<span class="text-info"><i class="fas fa-info-circle"></i> ' + counts.INFO + ' info</span>' : '') +
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
                ' &mdash; ' + P.Utils.escapeHtml(issue.location || '') + '</div>' +
                buildExplainerHtml(issue) +
                '</div>';
        });
        html += '</div>';
        $c.html(html);
        bindValidationControls();
        bindExplainerLinks($c);
    }

    // ======================== VERA PDF ========================

    function renderVeraPdf(result) {
        var available = !!result.available;
        var success = !!result.success;
        var report = result.report || '';
        var reportFormat = (result.reportFormat || '').toLowerCase();
        var looksLikeHtml = report.indexOf('<html') !== -1 || report.indexOf('<!DOCTYPE html') !== -1;
        var looksLikeXml = /^\s*<\?xml|^\s*<report[\s>]/i.test(report);
        var header = '<div class="mb-2 d-flex justify-content-between align-items-center gap-2" style="padding:8px;">' +
            '<div>' +
            '<span class="badge ' + (success ? 'bg-success' : 'bg-secondary') + ' me-2">' + (success ? 'PASS' : 'CHECK') + '</span>' +
            '<span class="text-muted">veraPDF ' + (available ? 'result' : 'not available') + '</span>' +
            '</div>' +
            '<div>' +
            '<button class="btn btn-outline-accent btn-sm me-1" id="exportVeraPdfHtmlBtn"><i class="fas fa-code me-1"></i>Export HTML</button>' +
            '<button class="btn btn-outline-accent btn-sm" id="exportVeraPdfPdfBtn"><i class="fas fa-file-pdf me-1"></i>Export PDF</button>' +
            '</div></div>';

        if (report && (reportFormat === 'html' || looksLikeHtml)) {
            $('#treeContent').html(header + '<div style="padding:8px;"><iframe title="veraPDF Report" style="width:100%;height:520px;border:1px solid var(--border);border-radius:4px;background:#fff;"></iframe></div>');
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

        $('#treeContent').html(header + '<div style="padding:8px;"><pre style="white-space:pre-wrap;max-height:480px;overflow:auto;">' + P.Utils.escapeHtml(report || 'No report output') + '</pre></div>');
        setVeraPdfExportContent(wrapHtmlDocument('veraPDF Report', '<pre>' + P.Utils.escapeHtml(report || 'No report output') + '</pre>'), 'veraPDF Report');
        bindVeraPdfExportButtons();
    }

    function renderVeraPdfXmlReport(header, xmlReport) {
        var parser = new DOMParser();
        var doc = parser.parseFromString(xmlReport, 'application/xml');
        if (doc.getElementsByTagName('parsererror').length) {
            $('#treeContent').html(header + '<div style="padding:8px;"><pre style="white-space:pre-wrap;max-height:520px;overflow:auto;">' + P.Utils.escapeHtml(xmlReport) + '</pre></div>');
            return;
        }

        var validationReport = doc.querySelector('jobs > job > validationReport');
        var details = validationReport ? validationReport.querySelector('details') : null;
        var rules = details ? Array.from(details.querySelectorAll('rule')) : [];
        var failedRules = rules.filter(function (r) { return String(r.getAttribute('status') || '').toLowerCase() !== 'passed'; });
        var passedRules = rules.filter(function (r) { return String(r.getAttribute('status') || '').toLowerCase() === 'passed'; });

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
            failedRules.forEach(function (rule) { html += buildRuleHtml(rule, true); });
        }
        html += '</div></div>';

        html += '<details id="verapdfPassedRules"><summary class="text-muted verapdf-section-title verapdf-section-passed" style="cursor:pointer;">Show passed rules (' + passedRules.length + ')</summary><div class="card mt-2"><div class="card-body py-2">';
        if (!passedRules.length) {
            html += '<div class="text-muted">No passed rules.</div>';
        } else {
            passedRules.forEach(function (rule) { html += buildRuleHtml(rule, false); });
        }
        html += '</div></div></details>';

        html += '<details class="mt-2"><summary class="text-muted" style="cursor:pointer;">Show raw XML</summary>' +
            '<pre style="white-space:pre-wrap;max-height:240px;overflow:auto;" class="mt-2">' + P.Utils.escapeHtml(xmlReport) + '</pre></details>';
        html += '</div>';

        $('#treeContent').html(html);
        bindVeraPdfSummaryJumps();
        setVeraPdfExportContent(wrapHtmlDocument('veraPDF Detailed Report', html), 'veraPDF Detailed Report');
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
            html += '<details class="mt-1"><summary style="cursor:pointer;">Check details (' + checks.length + ')</summary><div class="mt-1">';
            checks.forEach(function (checkEl) {
                var text = (checkEl.textContent || '').trim();
                if (text) { html += '<div class="small border-start ps-2 mb-1 verapdf-check-detail" style="border-color:#4b5563 !important;color:#e6ebf1;font-size:10px;line-height:1.35;">' + P.Utils.escapeHtml(text) + '</div>'; }
            });
            html += '</div></details>';
        }
        html += '</div>';
        return html;
    }

    function bindVeraPdfSummaryJumps() {
        $('#treeContent').find('.verapdf-jump-badge').off('click').on('click', function (e) {
            e.preventDefault();
            var selector = $(this).attr('data-target');
            if (!selector) return;
            var $target = $('#treeContent').find(selector).first();
            if (!$target.length) return;
            if ($target.is('details') && !$target.prop('open')) { $target.prop('open', true); }
            var element = $target[0];
            if (element && element.scrollIntoView) { element.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
        });
    }

    function setVeraPdfExportContent(html, title) {
        _p.veraPdfExportHtml = html || '';
        _p.veraPdfExportTitle = title || 'veraPDF Report';
    }

    function bindVeraPdfExportButtons() {
        $('#exportVeraPdfHtmlBtn').off('click').on('click', function () {
            if (!_p.veraPdfExportHtml) { P.Utils.toast('No veraPDF report available to export', 'warning'); return; }
            var htmlForExport = normalizeExportPadding(_p.veraPdfExportHtml, false);
            var blob = new Blob([htmlForExport], { type: 'text/html;charset=utf-8' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url; a.download = 'verapdf-report.html';
            document.body.appendChild(a); a.click(); a.remove();
            setTimeout(function () { URL.revokeObjectURL(url); }, 0);
        });

        $('#exportVeraPdfPdfBtn').off('click').on('click', function () {
            if (!_p.veraPdfExportHtml) { P.Utils.toast('No veraPDF report available to export', 'warning'); return; }
            var htmlForPdf = normalizeExportPadding(_p.veraPdfExportHtml, true);
            var printWin = window.open('', '_blank');
            if (!printWin) { P.Utils.toast('Popup blocked. Allow popups to export PDF.', 'warning'); return; }
            printWin.document.open();
            printWin.document.write(htmlForPdf);
            printWin.document.close();
            setTimeout(function () { printWin.focus(); printWin.print(); }, 250);
        });
    }

    function normalizeExportPadding(html, expandAllDetails) {
        var source = html || '';
        if (!source) return source;
        try {
            var parser = new DOMParser();
            var doc = parser.parseFromString(source, 'text/html');
            Array.from(doc.querySelectorAll('#exportVeraPdfHtmlBtn, #exportVeraPdfPdfBtn')).forEach(function (btn) { btn.remove(); });

            if (expandAllDetails) {
                Array.from(doc.querySelectorAll('details')).forEach(function (d) { d.setAttribute('open', 'open'); });
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
            return expandAllDetails ? source.replace(/<details(\s|>)/gi, '<details open$1') : source;
        }
    }

    function wrapHtmlDocument(title, bodyHtml) {
        return '<!doctype html><html><head><meta charset="utf-8"><title>' + P.Utils.escapeHtml(title) + '</title>' +
            '<style>body{font-family:Segoe UI,Arial,sans-serif;background:#111;color:#e9ecef;margin:0;padding:24px;} .card{border:1px solid #444;border-radius:10px;background:#1c1f24;margin-bottom:20px;} .card-body,.card-header{padding:20px;} .badge{display:inline-block;padding:6px 12px;border-radius:6px;font-size:12px;margin-right:6px;border:2px solid transparent;} .bg-danger{background:#b02a37;color:#fff;border-color:#b02a37;} .bg-success{background:#146c43;color:#fff;border-color:#146c43;} .bg-secondary{background:#495057;color:#fff;border-color:#495057;} .bg-dark{background:#212529;color:#fff;border-color:#212529;} .text-muted{color:#cfd4da;} .border{border:1px solid #444;} .rounded{border-radius:8px;} pre,code{font-family:Consolas,monospace;} pre{background:#0f1115;padding:18px;border-radius:6px;border:1px solid #333;} code{padding:2px 4px;border-radius:4px;} details{margin-top:14px;margin-bottom:14px;} details>summary{cursor:pointer;padding:8px 0;} .verapdf-rule-box{background:#161a1f !important;color:#eef2f6 !important;border-width:3px !important;padding:18px !important;margin-bottom:16px !important;} ' + getVeraPdfReportCss() + ' @media print{*{-webkit-print-color-adjust:exact !important;print-color-adjust:exact !important;} body{background:#111 !important;color:#e9ecef !important;}}</style>' +
            '</head><body><h2 style="margin-top:0;">' + P.Utils.escapeHtml(title) + '</h2>' + bodyHtml + '</body></html>';
    }

    function getVeraPdfReportCss() {
        return '.verapdf-section-title{font-weight:600;}' +
            '.verapdf-section-failed::before{content:"\u26a0 "; color:#dc3545;}' +
            '.verapdf-section-passed::before{content:"\u2713 "; color:#28a745;}' +
            '.verapdf-status-badge::before{margin-right:4px;}' +
            '.verapdf-rule-failed .verapdf-status-badge::before{content:"\u26a0";}' +
            '.verapdf-rule-passed .verapdf-status-badge::before{content:"\u2713";}' +
            '.verapdf-badge-failed::before{content:"\u26a0 ";}' +
            '.verapdf-badge-passed::before{content:"\u2713 ";}' +
            '.verapdf-check-detail{font-size:10px !important;line-height:1.35 !important;}';
    }

    return {
        loadValidation: loadValidation
    };
})(jQuery, PDFalyzer);
