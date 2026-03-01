/**
 * PDFalyzer – utility functions (toast, apiFetch, loading indicator).
 */
PDFalyzer.Utils = (function ($) {
    'use strict';

    function showLoading() { $('#statusLoading').show(); }
    function hideLoading() { $('#statusLoading').hide(); }

    function toast(msg, type) {
        var iconMap = {
            success: 'fa-check-circle',
            danger:  'fa-exclamation-circle',
            warning: 'fa-exclamation-triangle',
            info:    'fa-info-circle'
        };
        var iconClass = iconMap[type] || 'fa-info-circle';
        var $icon = $('<i>', { 'class': 'fas ' + iconClass + ' toast-icon-' + type });
        var $text = $('<span>', { text: msg, 'class': 'toast-text-' + type });
        var $el   = $('<div>', { 'class': 'toast-msg' + (type ? ' text-' + type : '') });
        $el.append($icon).append(' ').append($text);
        $('#toastContainer').append($el);
        $el.on('click', function () { $el.remove(); });
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

    return { showLoading: showLoading, hideLoading: hideLoading,
             toast: toast, apiFetch: apiFetch, escapeHtml: escapeHtml };
})(jQuery);
