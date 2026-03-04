/**
 * PDFalyzer Studio – Client-side error capture and reporting.
 */
PDFalyzer.ErrorCapture = (function ($, P) {
    'use strict';

    function install() {
        if (window.__pdfalyzerErrorCaptureInstalled) return;
        window.__pdfalyzerErrorCaptureInstalled = true;
        window.__pdfalyzerJsErrors = [];
        window.__pdfalyzerErrorSeq = 0;

        var lastFingerprint = null;
        var lastTimestamp = 0;
        var maxFieldLength = 4000;

        function trimValue(value) {
            var text = normalizeValue(value);
            if (text.length <= maxFieldLength) return text;
            return text.substring(0, maxFieldLength) + '\u2026(trimmed)';
        }

        function normalizeValue(value) {
            if (value === null || value === undefined) return '';
            return String(value);
        }

        function safeSerialize(value) {
            if (value === null || value === undefined) return '';
            if (typeof value === 'string') return value;
            if (typeof value === 'number' || typeof value === 'boolean') return String(value);
            if (value instanceof Error) {
                return (value.name || 'Error') + ': ' + (value.message || '') + (value.stack ? ('\n' + value.stack) : '');
            }
            try {
                return JSON.stringify(value);
            } catch (ignored) {
                return Object.prototype.toString.call(value);
            }
        }

        function normalizeSource(source) {
            var text = normalizeValue(source);
            if (!text) return '';
            try {
                var parsed = new URL(text, window.location.href);
                if (parsed.origin === window.location.origin) {
                    return parsed.pathname + parsed.search + parsed.hash;
                }
                return parsed.href;
            } catch (ignored) {
                return text;
            }
        }

        function currentPagePath() {
            var loc = window.location;
            return (loc && (loc.pathname + loc.search + loc.hash)) || '';
        }

        function pushClientError(kind, message, source, line, column, stack) {
            var payload = {
                timestamp: new Date().toISOString(),
                sequence: ++window.__pdfalyzerErrorSeq,
                kind: trimValue(kind || 'error'),
                message: trimValue(message || '(unknown error)'),
                source: trimValue(normalizeSource(source || '')),
                line: trimValue(line || ''),
                column: trimValue(column || ''),
                stack: trimValue(stack || ''),
                page: trimValue(currentPagePath()),
                userAgent: trimValue((navigator && navigator.userAgent) || '')
            };

            var fingerprint = [payload.kind, payload.message, payload.source, payload.line, payload.column].join('|');
            var now = Date.now();
            if (fingerprint === lastFingerprint && (now - lastTimestamp) < 250) return;
            lastFingerprint = fingerprint;
            lastTimestamp = now;

            window.__pdfalyzerJsErrors.push(payload);
            if (window.__pdfalyzerJsErrors.length > 200) {
                window.__pdfalyzerJsErrors.shift();
            }

            if (P.Utils && P.Utils.reportClientError) {
                P.Utils.reportClientError(payload);
            }
        }

        window.addEventListener('error', function (event) {
            var target = event && event.target;
            var isResourceError = target && target !== window;
            if (isResourceError) {
                var tag = target.tagName ? String(target.tagName).toLowerCase() : 'resource';
                var targetSource = target.src || target.href || '';
                pushClientError(
                    'resource-error',
                    'Failed to load ' + tag,
                    targetSource,
                    '',
                    '',
                    ''
                );
                return;
            }

            pushClientError(
                'error',
                event && event.message,
                event && event.filename,
                event && event.lineno,
                event && event.colno,
                event && event.error && event.error.stack
            );
        }, true);

        window.addEventListener('unhandledrejection', function (event) {
            var reason = event ? event.reason : null;
            var message = reason && reason.message ? reason.message : safeSerialize(reason);
            var stack = reason && reason.stack ? reason.stack : safeSerialize(reason);
            pushClientError('unhandledrejection', message, '', '', '', stack);
        });

        if (window.console && typeof window.console.error === 'function' && !window.__pdfalyzerConsoleErrorWrapped) {
            window.__pdfalyzerConsoleErrorWrapped = true;
            var originalConsoleError = window.console.error;
            window.console.error = function () {
                var args = Array.prototype.slice.call(arguments || []);
                var rendered = args.map(safeSerialize).join(' | ');
                pushClientError('console-error', rendered, '', '', '', '');
                return originalConsoleError.apply(window.console, args);
            };
        }
    }

    return { install: install };
})(jQuery, PDFalyzer);
