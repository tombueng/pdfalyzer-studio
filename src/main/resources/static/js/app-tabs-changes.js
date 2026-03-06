/**
 * PDFalyzer Studio – Pending Changes tab.
 * Renders a detailed list of all pending changes with before/after values,
 * type-specific icons, and per-change undo buttons.
 */
PDFalyzer.ChangesTab = (function ($, P) {
    'use strict';

    var ICONS = {
        add:       'fa-plus-circle',
        'delete':  'fa-trash-alt',
        rect:      'fa-arrows-alt',
        options:   'fa-sliders-h',
        value:     'fa-pen',
        cos:       'fa-database',
        signature: 'fa-file-signature'
    };
    var LABELS = {
        add:       'Add Field',
        'delete':  'Delete Field',
        rect:      'Move / Resize',
        options:   'Options',
        value:     'Value',
        cos:       'COS Change',
        signature: 'Digital Signature'
    };
    var TYPE_CLASSES = {
        add:       'change-type-add',
        'delete':  'change-type-delete',
        rect:      'change-type-rect',
        options:   'change-type-options',
        value:     'change-type-value',
        cos:       'change-type-cos',
        signature: 'change-type-signature'
    };

    function esc(s) { return P.Utils.escapeHtml(String(s == null ? '' : s)); }
    function fmtNum(v) { return (typeof v === 'number') ? v.toFixed(1) : String(v == null ? '' : v); }

    function buildAttrRow(label, before, after, diffHtml) {
        if (String(before) === String(after)) return '';
        return '<tr class="attr-changed">' +
            '<td class="attr-label">' + esc(label) + '</td>' +
            '<td class="attr-before">' + esc(before) + '</td>' +
            '<td class="attr-after">' + esc(after) + '</td>' +
            '<td class="attr-diff">' + (diffHtml || '') + '</td></tr>';
    }

    function buildAttrTable(rows) {
        if (!rows) return '';
        return '<table class="changes-attr-table"><thead><tr>' +
            '<th>Attribute</th><th>Before</th><th>After</th><th>Diff</th></tr></thead><tbody>' +
            rows + '</tbody></table>';
    }

    function collectChanges() {
        var changes = [];
        var stacks = P.state.fieldUndoStacks || {};

        // Iterate undo stacks — each entry is a change
        Object.keys(stacks).forEach(function (fieldName) {
            var stack = stacks[fieldName];
            for (var i = 0; i < stack.length; i++) {
                var entry = stack[i];
                changes.push({
                    fieldName: fieldName,
                    type: entry.type,
                    entry: entry,
                    stackIndex: i,
                    isLatest: (i === stack.length - 1)
                });
            }
        });

        // COS changes (no undo stack entry — separate system)
        (P.state.pendingCosChanges || []).forEach(function (item, idx) {
            changes.push({
                fieldName: (item && item.summary) || 'COS change',
                type: 'cos',
                entry: item,
                cosIndex: idx,
                isLatest: true
            });
        });

        // Pending signatures
        (P.state.pendingSignatures || []).forEach(function (sig, idx) {
            changes.push({
                fieldName: sig.fieldName || 'Signature',
                type: 'signature',
                entry: sig,
                sigIndex: idx,
                isLatest: true
            });
        });

        return changes;
    }

    function renderRectDetails(entry, fieldName) {
        var oldRect = entry.oldRect;
        var currentRect = P.EditMode.getCurrentFieldRectByName(fieldName);
        if (!oldRect && !currentRect) return '';

        var rows = '';
        if (oldRect && currentRect) {
            rows += buildAttrRow('x', fmtNum(oldRect.x), fmtNum(currentRect.x));
            rows += buildAttrRow('y', fmtNum(oldRect.y), fmtNum(currentRect.y));
            rows += buildAttrRow('width', fmtNum(oldRect.width), fmtNum(currentRect.width));
            rows += buildAttrRow('height', fmtNum(oldRect.height), fmtNum(currentRect.height));
        } else if (oldRect) {
            rows += buildAttrRow('x', fmtNum(oldRect.x), '(removed)');
            rows += buildAttrRow('y', fmtNum(oldRect.y), '(removed)');
            rows += buildAttrRow('width', fmtNum(oldRect.width), '(removed)');
            rows += buildAttrRow('height', fmtNum(oldRect.height), '(removed)');
        }
        return buildAttrTable(rows);
    }

    function renderAddDetails(fieldName) {
        var pf = P.EditMode.findPendingFormAdd(fieldName);
        if (!pf) return '<div class="changes-detail-text">New field (pending save)</div>';
        var rows = '';
        rows += buildAttrRow('Type', '', pf.fieldType || '');
        rows += buildAttrRow('Page', '', String((pf.pageIndex || 0) + 1));
        rows += buildAttrRow('x', '', fmtNum(pf.x));
        rows += buildAttrRow('y', '', fmtNum(pf.y));
        rows += buildAttrRow('width', '', fmtNum(pf.width));
        rows += buildAttrRow('height', '', fmtNum(pf.height));
        return buildAttrTable(rows);
    }

    function renderDeleteDetails(entry, fieldName) {
        var delEntry = P.state.pendingFieldDeletes && P.state.pendingFieldDeletes[fieldName];
        if (!delEntry) return '<div class="changes-detail-text">Field queued for deletion</div>';
        if (delEntry.pending && delEntry.removedAdd) {
            var pa = delEntry.removedAdd;
            var rows = '';
            rows += buildAttrRow('Type', pa.fieldType || '', '(deleted)');
            rows += buildAttrRow('Page', String((pa.pageIndex || 0) + 1), '');
            return buildAttrTable(rows);
        }
        return '<div class="changes-detail-text">Existing field queued for deletion</div>';
    }

    function renderOptionsDetails(entry, fieldName) {
        if (entry.wasPendingAdd) {
            var oldOpts = entry.oldOptions || {};
            var pf = P.EditMode.findPendingFormAdd(fieldName);
            var newOpts = (pf && pf.options) || {};
            return renderOptsDiff(oldOpts, newOpts);
        }
        var oldOverrides = entry.oldOverrides;
        var overrides = P.EditMode.getPendingFieldOptionOverrides();
        var curOverrides = overrides ? overrides[fieldName] : null;
        if (oldOverrides || curOverrides) {
            return renderOptsDiff(oldOverrides || {}, curOverrides || {});
        }
        return '<div class="changes-detail-text">Options changed</div>';
    }

    function renderOptsDiff(oldOpts, newOpts) {
        var allKeys = {};
        Object.keys(oldOpts || {}).forEach(function (k) { allKeys[k] = true; });
        Object.keys(newOpts || {}).forEach(function (k) { allKeys[k] = true; });
        var keys = Object.keys(allKeys).sort();
        if (!keys.length) return '<div class="changes-detail-text">No option details</div>';
        var rows = '';
        keys.forEach(function (k) {
            if (k.charAt(0) === '_') return;
            var ov = oldOpts[k], nv = newOpts[k];
            var ovStr = ov == null ? '' : String(ov);
            var nvStr = nv == null ? '' : String(nv);
            if (ovStr !== nvStr) rows += buildAttrRow(k, ovStr, nvStr);
        });
        return rows ? buildAttrTable(rows) : '<div class="changes-detail-text">Options (no visible diff)</div>';
    }

    var DIFF_MIN_MATCH = 2;   // minimum chars for a common substring to count as context
    var DIFF_MAX_LEN   = 500; // skip diff for very long values

    /** Find the longest common substring between a[aS..aE) and b[bS..bE). */
    function findLCS(a, aS, aE, b, bS, bE) {
        var bestLen = 0, bestA = 0, bestB = 0;
        for (var i = aS; i < aE; i++) {
            for (var j = bS; j < bE; j++) {
                if (a[i] !== b[j]) continue;
                var len = 0;
                while (i + len < aE && j + len < bE && a[i + len] === b[j + len]) len++;
                if (len > bestLen) { bestLen = len; bestA = i; bestB = j; }
            }
        }
        if (bestLen < DIFF_MIN_MATCH) return null;
        return { aIdx: bestA, bIdx: bestB, length: bestLen };
    }

    /** Recursively diff a[aS..aE) vs b[bS..bE), appending segments. */
    function diffRecurse(a, aS, aE, b, bS, bE, segs) {
        if (aS >= aE && bS >= bE) return;
        if (aS >= aE) { segs.push({ type: 'added', text: b.substring(bS, bE) }); return; }
        if (bS >= bE) { segs.push({ type: 'removed', text: a.substring(aS, aE) }); return; }
        var m = findLCS(a, aS, aE, b, bS, bE);
        if (!m) {
            // No common substring — emit removed then added
            segs.push({ type: 'removed', text: a.substring(aS, aE) });
            segs.push({ type: 'added', text: b.substring(bS, bE) });
            return;
        }
        diffRecurse(a, aS, m.aIdx, b, bS, m.bIdx, segs);
        segs.push({ type: 'context', text: a.substring(m.aIdx, m.aIdx + m.length) });
        diffRecurse(a, m.aIdx + m.length, aE, b, m.bIdx + m.length, bE, segs);
    }

    /** Merge adjacent segments of the same type. */
    function mergeSegments(segs) {
        var merged = [];
        for (var i = 0; i < segs.length; i++) {
            if (merged.length && merged[merged.length - 1].type === segs[i].type) {
                merged[merged.length - 1].text += segs[i].text;
            } else {
                merged.push({ type: segs[i].type, text: segs[i].text });
            }
        }
        return merged;
    }

    function computeSimpleDiff(a, b) {
        if (a === b) return { segments: [{ type: 'context', text: a }] };
        if (a.length > DIFF_MAX_LEN || b.length > DIFF_MAX_LEN) return null;
        var segs = [];
        diffRecurse(a, 0, a.length, b, 0, b.length, segs);
        var merged = mergeSegments(segs);
        return merged.length ? { segments: merged } : null;
    }

    function renderDiffHtml(diff) {
        var html = '<div class="value-diff">';
        diff.segments.forEach(function (seg) {
            html += '<span class="diff-' + seg.type + '">' + esc(seg.text) + '</span>';
        });
        html += '</div>';
        return html;
    }

    function renderValueDetails(entry, fieldName) {
        var oldVal = entry.oldValue;
        var newVal = P.state.pendingFieldValues ? P.state.pendingFieldValues[fieldName] : undefined;
        var oldStr = oldVal == null ? '' : String(oldVal);
        var newStr = newVal == null ? '' : String(newVal);
        if (oldStr === newStr) return '<div class="changes-detail-text">No change</div>';

        var diff = computeSimpleDiff(oldStr, newStr);
        // Skip diff for total replacements (no shared context)
        var hasContext = diff && diff.segments.some(function (s) { return s.type === 'context'; });
        var diffCell = hasContext ? renderDiffHtml(diff) : '';
        var rows = buildAttrRow('value', oldStr || '(empty)', newStr || '(empty)', diffCell);
        return buildAttrTable(rows);
    }

    function renderCosDetails(item) {
        if (!item) return '';
        var rows = '';
        if (item.operation) rows += buildAttrRow('Operation', '', item.operation);
        if (item.summary) rows += buildAttrRow('Summary', '', item.summary);
        if (item.request) {
            var req = item.request;
            if (req.objectNumber != null) rows += buildAttrRow('Object', '', req.objectNumber + ' ' + (req.generationNumber || 0) + ' R');
            if (req.key) rows += buildAttrRow('Key', '', req.key);
            if (req.newValue !== undefined) rows += buildAttrRow('New Value', '', String(req.newValue));
        }
        if (item.lastError) rows += buildAttrRow('Error', '', item.lastError);
        return rows ? buildAttrTable(rows) : '';
    }

    function renderSignatureDetails(sig) {
        if (!sig) return '';
        var rows = '';
        if (sig.fieldName) rows += buildAttrRow('Field', '', sig.fieldName);
        if (sig.visualMode) rows += buildAttrRow('Visual', '', sig.visualMode);
        if (sig.signMode) rows += buildAttrRow('Mode', '', sig.signMode === 'certification' ? 'Certification (P=' + sig.docMdpLevel + ')' : 'Approval');
        if (sig.certSubjectDN) rows += buildAttrRow('Signer', '', sig.certSubjectDN);
        if (sig.certIssuerDN) rows += buildAttrRow('Issuer', '', sig.certIssuerDN);
        if (sig.reason) rows += buildAttrRow('Reason', '', sig.reason);
        if (sig.location) rows += buildAttrRow('Location', '', sig.location);
        return rows ? buildAttrTable(rows) : '<div class="changes-detail-text">Digital signature queued</div>';
    }

    function renderChangeDetails(change) {
        switch (change.type) {
            case 'add':       return renderAddDetails(change.fieldName);
            case 'delete':    return renderDeleteDetails(change.entry, change.fieldName);
            case 'rect':      return renderRectDetails(change.entry, change.fieldName);
            case 'options':   return renderOptionsDetails(change.entry, change.fieldName);
            case 'value':     return renderValueDetails(change.entry, change.fieldName);
            case 'cos':       return renderCosDetails(change.entry);
            case 'signature': return renderSignatureDetails(change.entry);
            default:          return '';
        }
    }

    function renderChanges() {
        var $c = $('#treeContent').empty();
        var changes = collectChanges();

        if (!changes.length) {
            $c.html('<div class="text-muted text-center mt-3">' +
                '<i class="fas fa-check-circle fa-2x mb-2"></i><br>No pending changes</div>');
            updateBadge(0);
            return;
        }

        updateBadge(changes.length);

        var html = '<div class="changes-tab-panel">' +
            '<div class="changes-tab-header">' +
            '<span class="changes-tab-count">' + changes.length + ' pending change' + (changes.length > 1 ? 's' : '') + '</span>' +
            '<button class="btn btn-sm btn-accent changes-tab-save" id="changesTabSaveBtn" title="Save all changes to PDF"><i class="fas fa-save me-1"></i>Save</button>' +
            '</div>';

        // Reverse so most recent changes appear first
        for (var i = changes.length - 1; i >= 0; i--) {
            var ch = changes[i];
            var icon = ICONS[ch.type] || 'fa-circle';
            var label = LABELS[ch.type] || ch.type;
            var typeCls = TYPE_CLASSES[ch.type] || '';

            html += '<div class="changes-entry ' + typeCls + '" data-field="' + esc(ch.fieldName) + '" data-idx="' + ch.stackIndex + '">' +
                '<div class="changes-entry-header">' +
                '<span class="changes-entry-icon"><i class="fas ' + icon + '"></i></span>' +
                '<span class="changes-entry-label">' + esc(label) + '</span>' +
                '<span class="changes-entry-field">' + esc(ch.fieldName) + '</span>';

            if (ch.type === 'signature' && ch.isLatest) {
                html += '<button class="changes-entry-undo-sig" data-sig-index="' + ch.sigIndex + '" title="Remove queued signature"><i class="fas fa-undo"></i></button>';
            } else if (ch.type !== 'cos' && ch.type !== 'signature' && ch.isLatest) {
                html += '<button class="changes-entry-undo" data-undo-field="' + esc(ch.fieldName) + '" title="Undo this change"><i class="fas fa-undo"></i></button>';
            }

            html += '</div>';
            html += '<div class="changes-entry-details">' + renderChangeDetails(ch) + '</div>';
            html += '</div>';
        }

        html += '</div>';
        $c.html(html);

        // Bind save button
        $c.find('#changesTabSaveBtn').on('click', function () {
            if (P.EditMode && P.EditMode.savePendingChanges) P.EditMode.savePendingChanges();
        });

        // Bind undo buttons
        $c.find('.changes-entry-undo').on('click', function (e) {
            e.stopPropagation();
            var fn = $(this).attr('data-undo-field');
            if (fn && P.EditMode && P.EditMode.popFieldUndo) {
                P.EditMode.popFieldUndo(fn);
            }
        });

        // Bind signature undo buttons
        $c.find('.changes-entry-undo-sig').on('click', function (e) {
            e.stopPropagation();
            var idx = parseInt($(this).attr('data-sig-index'), 10);
            if (!isNaN(idx) && P.state.pendingSignatures && P.state.pendingSignatures[idx]) {
                P.state.pendingSignatures.splice(idx, 1);
                if (P.EditMode && P.EditMode.updateSaveButton) P.EditMode.updateSaveButton();
                renderChanges();
            }
        });

        // Click on a change entry → scroll PDF viewer to field with locator effect
        $c.find('.changes-entry').on('click', function () {
            var fieldName = $(this).attr('data-field');
            if (!fieldName || !P.EditMode || !P.Viewer) return;
            var rect = P.EditMode.getCurrentFieldRectByName(fieldName);
            if (!rect || rect.pageIndex == null) return;
            P.Viewer.highlight(rect.pageIndex, [rect.x, rect.y, rect.width, rect.height], { locator: true });
            // Select the field without switching tabs
            P.state.selectedFieldNames = [fieldName];
            P.state.selectedImageNodeIds = [];
        });
    }

    function updateBadge(count) {
        var $btn = $('#changesTabBtn');
        var $badge = $btn.find('.changes-tab-badge');

        var total = count;
        if (total === undefined) {
            total = collectChanges().length;
        }

        if (total > 0) {
            $btn.show();
            $badge.text(String(total)).removeClass('d-none');
        } else {
            $badge.addClass('d-none').text('0');
            if (P.state.currentTab !== 'changes') $btn.hide();
        }

        // Status bar indicator
        var $sb = $('#statusPendingChanges');
        if (total > 0) {
            $('#statusPendingCount').text(String(total));
            $sb.show();
        } else {
            $sb.hide();
        }
    }

    function initStatusBarClick() {
        $('#statusPendingChanges').on('click', function () {
            if (!P.state.sessionId || !P.state.treeData) return;
            $('.tab-btn').removeClass('active');
            $('#changesTabBtn').addClass('active').show();
            P.Tabs.switchTab('changes');
        });
    }

    function refreshIfActive() {
        updateBadge();
        if (P.state.currentTab === 'changes') renderChanges();
    }

    // Auto-init status bar click when DOM is ready
    $(function () { initStatusBarClick(); });

    return { renderChanges: renderChanges, updateBadge: updateBadge, refreshIfActive: refreshIfActive };
})(jQuery, PDFalyzer);
