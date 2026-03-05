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

    function wireModalFocusSafety(modalEl) {
        if (!modalEl || modalEl.__pdfalyzerFocusSafetyBound) return;

        modalEl.addEventListener('hide.bs.modal', function () {
            var active = document.activeElement;
            if (active && modalEl.contains(active) && typeof active.blur === 'function') {
                active.blur();
            }
        });

        modalEl.addEventListener('hidden.bs.modal', function () {
            var returnFocusEl = modalEl.__pdfalyzerReturnFocusEl;
            modalEl.__pdfalyzerReturnFocusEl = null;
            if (!returnFocusEl || !document.contains(returnFocusEl) || returnFocusEl.disabled) return;
            if (typeof returnFocusEl.focus === 'function') {
                returnFocusEl.focus({ preventScroll: true });
            }
        });

        modalEl.__pdfalyzerFocusSafetyBound = true;
    }

    function prepareModal(modalEl) {
        if (!modalEl || !modalEl.classList || !modalEl.classList.contains('modal')) return;
        modalEl.classList.add('pdfa-modal');

        var dialogEl = modalEl.querySelector('.modal-dialog');
        if (dialogEl) dialogEl.classList.add('pdfa-modal-dialog');

        var contentEl = modalEl.querySelector('.modal-content');
        if (contentEl) contentEl.classList.add('pdfa-modal-content');

        var headerEl = modalEl.querySelector('.modal-header');
        if (headerEl) headerEl.classList.add('pdfa-modal-header');

        var bodyEl = modalEl.querySelector('.modal-body');
        if (bodyEl) bodyEl.classList.add('pdfa-modal-body');

        var footerEl = modalEl.querySelector('.modal-footer');
        if (footerEl) footerEl.classList.add('pdfa-modal-footer');

        wireModalFocusSafety(modalEl);
    }

    function reportClientError(payload) {
        if (!payload) return;
        var body = JSON.stringify(payload);

        try {
            if (navigator && typeof navigator.sendBeacon === 'function') {
                var blob = new Blob([body], { type: 'application/json' });
                var queued = navigator.sendBeacon('/api/client-errors', blob);
                if (queued) return;
            }
        } catch (ignored) {
        }

        try {
            if (typeof fetch === 'function') {
                fetch('/api/client-errors', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: body,
                    keepalive: true
                });
                return;
            }
        } catch (ignoredFetch) {
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

    function isSearchOrFilterInput($input) {
        if (!$input || !$input.length) return false;
        if ($input.is(':hidden')) return false;
        if ($input.is('[data-disable-clear]')) return false;

        var id = String($input.attr('id') || '').toLowerCase();
        var cls = String($input.attr('class') || '').toLowerCase();
        var placeholder = String($input.attr('placeholder') || '').toLowerCase();
        return id.indexOf('search') >= 0 || id.indexOf('filter') >= 0 ||
            cls.indexOf('table-filter-input') >= 0 ||
            placeholder.indexOf('search') >= 0 || placeholder.indexOf('filter') >= 0;
    }

    function toggleInputClearButton($input) {
        if (!$input || !$input.length) return;
        var $btn = $input.siblings('.input-clear-btn').first();
        if (!$btn.length) return;

        var hasValue = String($input.val() || '').length > 0;
        var disabled = $input.prop('disabled') || $input.prop('readonly');
        $btn.toggleClass('d-none', !hasValue || !!disabled);
        $btn.prop('disabled', !!disabled);
    }

    function ensureInputClearButton(inputEl) {
        var $input = $(inputEl);
        if (!isSearchOrFilterInput($input)) return;

        var $wrap = $input.parent('.input-clear-wrap');
        if (!$wrap.length) {
            $input.wrap('<span class="input-clear-wrap"></span>');
            $wrap = $input.parent('.input-clear-wrap');
        }
        if (!$wrap.find('.input-clear-btn').length) {
            var $btn = $('<button type="button" class="input-clear-btn d-none" aria-label="Clear input" title="Clear"><i class="fas fa-times"></i></button>');
            $input.after($btn);
        }
        $input.addClass('has-input-clear');
        toggleInputClearButton($input);
    }

    function initClearableInputs() {
        var selector = 'input[type="text"], input:not([type])';

        $(selector).each(function () {
            ensureInputClearButton(this);
        });

        $(document).off('focusin.inputclear').on('focusin.inputclear', selector, function () {
            ensureInputClearButton(this);
            toggleInputClearButton($(this));
        });

        $(document).off('input.inputclear change.inputclear').on('input.inputclear change.inputclear', selector, function () {
            if (!isSearchOrFilterInput($(this))) return;
            toggleInputClearButton($(this));
        });

        $(document).off('click.inputclear', '.input-clear-btn').on('click.inputclear', '.input-clear-btn', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            var $btn = $(this);
            var $input = $btn.siblings('input').first();
            if (!$input.length) return;
            if ($input.prop('disabled') || $input.prop('readonly')) return;
            if (!$input.val()) return;

            $input.val('');
            $input.trigger('input');
            $input.trigger('change');
            $input.trigger('focus');
            toggleInputClearButton($input);
        });
    }

    function tabSkeleton(tab) {
        if (tab === 'structure' || tab === 'forms' || tab === 'bookmarks' ||
                tab === 'rawcos' || tab === 'attachments') {
            var rows = [
                { w: '68%', ml: 8  }, { w: '52%', ml: 28 }, { w: '78%', ml: 28 },
                { w: '44%', ml: 48 }, { w: '58%', ml: 48 }, { w: '71%', ml: 8  },
                { w: '40%', ml: 28 }, { w: '83%', ml: 8  }, { w: '55%', ml: 28 },
                { w: '47%', ml: 48 }, { w: '62%', ml: 48 }, { w: '76%', ml: 8  }
            ];
            return '<div style="padding:8px 4px">' +
                rows.map(function (r) {
                    return '<div class="skel skel-row" style="width:' + r.w + ';margin-left:' + r.ml + 'px"></div>';
                }).join('') + '</div>';
        }
        if (tab === 'fonts') {
            return '<div class="font-diag-wrap" style="padding:8px">' +
                '<div class="font-diag-stats">' +
                '<div class="skel skel-metric"></div>'.repeat(5) +
                '</div>' +
                '<div style="padding:8px 0">' +
                '<div class="skel skel-row" style="width:100%;height:20px;margin-bottom:12px"></div>' +
                [92, 85, 78, 90, 70, 83, 75].map(function (w) {
                    return '<div class="skel skel-row" style="width:' + w + '%"></div>';
                }).join('') +
                '</div></div>';
        }
        if (tab === 'validation') {
            return '<div style="padding:8px 12px">' +
                [100, 88, 92, 75, 82].map(function (w) {
                    return '<div class="skel skel-issue" style="width:' + w + '%"></div>';
                }).join('') + '</div>';
        }
        return '';
    }

    return { showLoading: showLoading, hideLoading: hideLoading,
             toast: toast, apiFetch: apiFetch, escapeHtml: escapeHtml,
             reportClientError: reportClientError, formatBytes: formatBytes,
             wireModalFocusSafety: wireModalFocusSafety, prepareModal: prepareModal,
             refreshAfterMutation: refreshAfterMutation,
             initClearableInputs: initClearableInputs,
             tabSkeleton: tabSkeleton };
})(jQuery);
