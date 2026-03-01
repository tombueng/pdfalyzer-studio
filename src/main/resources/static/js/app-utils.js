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

    return { showLoading: showLoading, hideLoading: hideLoading,
             toast: toast, apiFetch: apiFetch, escapeHtml: escapeHtml,
             reportClientError: reportClientError };
})(jQuery);
