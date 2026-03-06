/**
 * PDFalyzer Studio – Pending Changes tab.
 * Renders a detailed list of all pending changes with before/after values,
 * type-specific icons, and per-change undo buttons.
 */
PDFalyzer.ChangesTab = (function ($, P) {
    'use strict';

    var ICONS = {
        add:     'fa-plus-circle',
        'delete': 'fa-trash-alt',
        rect:    'fa-arrows-alt',
        options: 'fa-sliders-h',
        value:   'fa-pen',
        cos:     'fa-database'
    };
    var LABELS = {
        add:     'Add Field',
        'delete': 'Delete Field',
        rect:    'Move / Resize',
        options: 'Options',
        value:   'Value',
        cos:     'COS Change'
    };
    var TYPE_CLASSES = {
        add:     'change-type-add',
        'delete': 'change-type-delete',
        rect:    'change-type-rect',
        options: 'change-type-options',
        value:   'change-type-value',
        cos:     'change-type-cos'
    };

    function esc(s) { return P.Utils.escapeHtml(String(s == null ? '' : s)); }
    function fmtNum(v) { return (typeof v === 'number') ? v.toFixed(1) : String(v == null ? '' : v); }

    function buildAttrRow(label, before, after) {
        if (String(before) === String(after)) return '';
        return '<tr class="attr-changed">' +
            '<td class="attr-label">' + esc(label) + '</td>' +
            '<td class="attr-before">' + esc(before) + '</td>' +
            '<td class="attr-after">' + esc(after) + '</td></tr>';
    }

    function buildAttrTable(rows) {
        if (!rows) return '';
        return '<table class="changes-attr-table"><thead><tr>' +
            '<th>Attribute</th><th>Before</th><th>After</th></tr></thead><tbody>' +
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

    function computeSimpleDiff(a, b) {
        // Find common prefix
        var prefixLen = 0;
        while (prefixLen < a.length && prefixLen < b.length && a[prefixLen] === b[prefixLen]) prefixLen++;
        // Find common suffix (not overlapping prefix)
        var suffixLen = 0;
        while (suffixLen < (a.length - prefixLen) && suffixLen < (b.length - prefixLen) &&
               a[a.length - 1 - suffixLen] === b[b.length - 1 - suffixLen]) suffixLen++;
        var removed = a.substring(prefixLen, a.length - suffixLen);
        var added = b.substring(prefixLen, b.length - suffixLen);
        // Count "segments" — if both removed and added are non-empty it's a replacement (2 ops)
        var ops = (removed ? 1 : 0) + (added ? 1 : 0);
        return {
            prefix: a.substring(0, prefixLen),
            removed: removed,
            added: added,
            suffix: a.substring(a.length - suffixLen),
            ops: ops
        };
    }

    function renderDiffHtml(diff) {
        var html = '<div class="value-diff">';
        html += '<span class="diff-context">' + esc(diff.prefix) + '</span>';
        if (diff.removed) html += '<span class="diff-removed">' + esc(diff.removed) + '</span>';
        if (diff.added) html += '<span class="diff-added">' + esc(diff.added) + '</span>';
        html += '<span class="diff-context">' + esc(diff.suffix) + '</span>';
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
        // Simple diff: at most 2 ops (one removal + one addition at the same position)
        if (diff.ops <= 2) return renderDiffHtml(diff);

        // Complex: fall back to before/after table
        var rows = buildAttrRow('value', oldStr || '(empty)', newStr || '(empty)');
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

    function renderChangeDetails(change) {
        switch (change.type) {
            case 'add':     return renderAddDetails(change.fieldName);
            case 'delete':  return renderDeleteDetails(change.entry, change.fieldName);
            case 'rect':    return renderRectDetails(change.entry, change.fieldName);
            case 'options': return renderOptionsDetails(change.entry, change.fieldName);
            case 'value':   return renderValueDetails(change.entry, change.fieldName);
            case 'cos':     return renderCosDetails(change.entry);
            default:        return '';
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
            '<span class="changes-tab-hint">Click Save to persist to PDF</span>' +
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

            if (ch.type !== 'cos' && ch.isLatest) {
                html += '<button class="changes-entry-undo" data-undo-field="' + esc(ch.fieldName) + '" title="Undo this change"><i class="fas fa-undo"></i></button>';
            }

            html += '</div>';
            html += '<div class="changes-entry-details">' + renderChangeDetails(ch) + '</div>';
            html += '</div>';
        }

        html += '</div>';
        $c.html(html);

        // Bind undo buttons
        $c.find('.changes-entry-undo').on('click', function (e) {
            e.stopPropagation();
            var fn = $(this).attr('data-undo-field');
            if (fn && P.EditMode && P.EditMode.popFieldUndo) {
                P.EditMode.popFieldUndo(fn);
            }
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
