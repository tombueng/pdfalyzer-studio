/**
 * PDFalyzer Studio – utility functions (toast, apiFetch, loading indicator).
 */
PDFalyzer.Utils = (function ($) {
    'use strict';

    function showLoading() { $('#statusLoading').show(); }
    function hideLoading() { $('#statusLoading').hide(); }

    function toast(msg, type) {
        var normalizedType = type || 'info';
        var iconMap = {
            success: 'fa-check-circle',
            danger:  'fa-exclamation-circle',
            warning: 'fa-exclamation-triangle',
            info:    'fa-info-circle'
        };
        var durationMap = {
            success: 10000,
            positive: 10000,
            info: 15000,
            informative: 15000,
            warning: 15000,
            danger: 30000,
            error: 30000
        };
        var durationMs = durationMap[normalizedType] || 15000;
        var cssType = normalizedType;

        if (normalizedType === 'positive') cssType = 'success';
        if (normalizedType === 'informative') cssType = 'info';
        if (normalizedType === 'error') cssType = 'danger';

        var iconClass = iconMap[cssType] || 'fa-info-circle';

        var $icon = $('<i>', { 'class': 'fas ' + iconClass + ' toast-icon-' + cssType });
        var $text = $('<span>', { text: msg, 'class': 'toast-text-' + cssType });
        var $countdown = $('<div>', { 'class': 'toast-countdown' });
        var $el   = $('<div>', { 'class': 'toast-msg text-' + cssType });
        var fadeDurationMs = 200;
        var fadeDelayMs = Math.max(durationMs - fadeDurationMs, 0);
        $el.css('--toast-duration-ms', durationMs + 'ms');
        $el.css('--toast-fade-delay-ms', fadeDelayMs + 'ms');
        $el.append($icon).append(' ').append($text).append($countdown);
        $('#toastContainer').append($el);

        function dismissToast() {
            $el.addClass('toast-dismiss');
            setTimeout(function () { $el.remove(); }, 180);
        }

        var dismissed = false;
        var timerId = setTimeout(function () {
            if (dismissed) return;
            dismissed = true;
            dismissToast();
        }, durationMs);

        $el.on('click', function () {
            if (dismissed) return;
            dismissed = true;
            clearTimeout(timerId);
            dismissToast();
        });
    }

    function apiFetch(url, opts) {
        showLoading();
        var skeletonTimer = setTimeout(function () {
            $('#treeContent').addClass('skeleton');
        }, 1000);
        return $.ajax($.extend({
            url: url,
            dataType: (opts && opts.processData === false) ? undefined : 'json'
        }, opts || {}))
            .always(function () {
                clearTimeout(skeletonTimer);
                $('#treeContent').removeClass('skeleton');
                hideLoading();
            });
    }

    function escapeHtml(text) {
        return $('<div>').text(text).html();
    }

    function formatBytes(bytes) {
        var value = Number(bytes);
        if (!isFinite(value) || value < 0) return '0 B';
        if (value < 1024) return Math.round(value) + ' B';
        var units = ['KB', 'MB', 'GB', 'TB'];
        var unitIndex = -1;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value = value / 1024;
            unitIndex += 1;
        }
        var decimals = value >= 10 ? 1 : 2;
        var text = value.toFixed(decimals).replace(/\.0+$/, '').replace(/(\.\d*[1-9])0+$/, '$1');
        return text + ' ' + units[unitIndex];
    }

    function reportClientError(payload) {
        if (!payload) return;
        var body = JSON.stringify(payload);

        try {
            if (navigator && typeof navigator.sendBeacon === 'function') {
                var blob = new Blob([body], { type: 'application/json' });
                navigator.sendBeacon('/api/client-errors', blob);
                return;
            }
        } catch (ignored) {
        }

        try {
            $.ajax({
                url: '/api/client-errors',
                method: 'POST',
                contentType: 'application/json',
                data: body
            });
        } catch (ignored2) {
        }
    }

    function refreshAfterMutation(updatedTree) {
        var P = window.PDFalyzer;
        if (!P || !P.state) return;

        if (updatedTree) {
            P.state.treeData = updatedTree;
            P.state.rawCosTreeData = null;
        }

        if (P.state.treeData) {
            if (P.Tabs && P.Tabs.switchTab && P.state.currentTab) {
                P.Tabs.switchTab(P.state.currentTab);
            } else if (P.Tree && P.Tree.render) {
                P.Tree.render(P.state.treeData);
            }
        }

        if (P.Viewer && P.Viewer.loadPdf && P.state.sessionId) {
            P.Viewer.loadPdf(P.state.sessionId, {
                preserveView: true,
                smoothSwap: true
            });
        }
    }

    return { showLoading: showLoading, hideLoading: hideLoading,
             toast: toast, apiFetch: apiFetch, escapeHtml: escapeHtml,
             reportClientError: reportClientError, formatBytes: formatBytes,
             refreshAfterMutation: refreshAfterMutation };
})(jQuery);
