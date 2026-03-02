/**
 * PDFalyzer Studio – main entry point.
 *
 * All logic is in the /js/ sub-modules.  This file only wires them together
 * and calls init once jQuery's document-ready fires.
 *
 * Module load order (enforced by index.html <script> tags):
 *   js/app-state.js       → PDFalyzer (namespace + shared state)
 *   js/app-utils.js       → PDFalyzer.Utils
 *   js/app-resource.js    → PDFalyzer.Resource
 *   js/app-dict-editor.js → PDFalyzer.DictEditor
 *   js/app-cos-editor.js  → PDFalyzer.CosEditor
 *   js/app-tree.js        → PDFalyzer.Tree
 *   js/app-viewer.js      → PDFalyzer.Viewer
 *   js/app-search.js      → PDFalyzer.Search
 *   js/app-tabs.js        → PDFalyzer.Tabs
 *   js/app-edit-mode.js   → PDFalyzer.EditMode
 *   js/app-upload.js      → PDFalyzer.Upload
 *   js/app-zoom.js        → PDFalyzer.Zoom
 *   js/app-divider.js     → PDFalyzer.Divider
 *   js/app-keyboard.js    → PDFalyzer.Keyboard
 *   js/app-export.js      → PDFalyzer.Export
 *   app.js                ← this file (document-ready init)
 */
(function ($) {
    'use strict';
    var P = PDFalyzer;

    function installClientErrorCapture() {
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
            return text.substring(0, maxFieldLength) + '…(trimmed)';
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

    function initDraggableModals() {
        var storagePrefix = 'pdfalyzer.modal.position.';
        var dragging = null;
        var resizeObserver = null;
        var positionCache = Object.create(null);

        function getDialog(modalEl) {
            return modalEl ? modalEl.querySelector('.modal-dialog') : null;
        }

        function isInteractiveElement(target) {
            if (!target || typeof target.closest !== 'function') return false;
            return !!target.closest('button, a, input, select, textarea, label, [role="button"], .btn-close');
        }

        function readPosition(modalId) {
            if (!modalId) return null;
            if (positionCache[modalId] && typeof positionCache[modalId].left === 'number' && typeof positionCache[modalId].top === 'number') {
                return { left: positionCache[modalId].left, top: positionCache[modalId].top };
            }
            try {
                var raw = window.localStorage.getItem(storagePrefix + modalId);
                if (!raw) return null;
                var parsed = JSON.parse(raw);
                if (!parsed) return null;
                var left = Number(parsed.left);
                var top = Number(parsed.top);
                if (!isFinite(left) || !isFinite(top)) return null;
                positionCache[modalId] = { left: left, top: top };
                return { left: left, top: top };
            } catch (ignored) {
                return null;
            }
        }

        function writePosition(modalId, left, top) {
            if (!modalId) return;
            var safeLeft = Number(left);
            var safeTop = Number(top);
            if (!isFinite(safeLeft) || !isFinite(safeTop)) return;
            positionCache[modalId] = { left: safeLeft, top: safeTop };
            try {
                window.localStorage.setItem(storagePrefix + modalId, JSON.stringify({ left: safeLeft, top: safeTop }));
            } catch (ignored) {
                // ignore storage errors (private mode/quota)
            }
        }

        function clampPosition(dialogEl, left, top) {
            var rect = dialogEl.getBoundingClientRect();
            var keepVisibleX = 80;
            var headerEl = dialogEl.querySelector('.modal-header');
            var headerHeight = headerEl ? headerEl.getBoundingClientRect().height : 40;
            var keepHeaderVisibleY = Math.max(28, headerHeight);
            var maxLeft = window.innerWidth - keepVisibleX;
            var minLeft = keepVisibleX - rect.width;
            var maxTop = window.innerHeight - keepHeaderVisibleY;
            var minTop = 0;
            return {
                left: Math.max(minLeft, Math.min(left, maxLeft)),
                top: Math.max(minTop, Math.min(top, maxTop))
            };
        }

        function captureDialogWidth(dialogEl) {
            if (!dialogEl) return;
            var measuredWidth = dialogEl.getBoundingClientRect().width;
            if (isFinite(measuredWidth) && measuredWidth > 120) {
                dialogEl.dataset.dragWidth = String(Math.round(measuredWidth));
            }
        }

        function applyLockedDialogWidth(dialogEl) {
            if (!dialogEl) return;
            var storedWidth = Number(dialogEl.dataset.dragWidth || 0);
            if (isFinite(storedWidth) && storedWidth > 120) {
                dialogEl.style.width = Math.round(storedWidth) + 'px';
                dialogEl.style.maxWidth = 'none';
            }
        }

        function applyDialogPosition(dialogEl, left, top) {
            applyLockedDialogWidth(dialogEl);
            var clamped = clampPosition(dialogEl, left, top);
            dialogEl.style.position = 'fixed';
            dialogEl.style.margin = '0';
            dialogEl.style.left = clamped.left + 'px';
            dialogEl.style.top = clamped.top + 'px';
            dialogEl.style.transform = 'none';
            dialogEl.classList.add('draggable-modal-positioned');
            if (dialogEl.classList.contains('modal-dialog-centered')) {
                dialogEl.classList.remove('modal-dialog-centered');
            }
            return clamped;
        }

        function isRectMeasurable(rect) {
            return !!rect && rect.width > 40 && rect.height > 20;
        }

        function observeDialogSize(modalEl) {
            if (!resizeObserver || !modalEl) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            resizeObserver.observe(dialogEl);
        }

        function unobserveDialogSize(modalEl) {
            if (!resizeObserver || !modalEl) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            resizeObserver.unobserve(dialogEl);
        }

        if (typeof window.ResizeObserver === 'function') {
            resizeObserver = new window.ResizeObserver(function (entries) {
                for (var i = 0; i < entries.length; i += 1) {
                    var dialogEl = entries[i].target;
                    if (!dialogEl || dialogEl.style.position !== 'fixed') continue;
                    var modalEl = dialogEl.closest('.modal');
                    var rect = dialogEl.getBoundingClientRect();
                    if (!isRectMeasurable(rect)) continue;
                    var adjusted = applyDialogPosition(dialogEl, rect.left, rect.top);
                    if (modalEl && modalEl.id) {
                        writePosition(modalEl.id, adjusted.left, adjusted.top);
                    }
                }
            });
        }

        function restoreDialogPosition(modalEl) {
            if (!modalEl || !modalEl.id) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            var saved = readPosition(modalEl.id);
            if (!saved) return;
            var rect = dialogEl.getBoundingClientRect();
            if (!isRectMeasurable(rect)) return;
            applyDialogPosition(dialogEl, saved.left, saved.top);
        }

        function prepositionDialogFromSaved(modalEl) {
            if (!modalEl || !modalEl.id) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            var saved = readPosition(modalEl.id);
            if (!saved) return;

            captureDialogWidth(dialogEl);
            applyLockedDialogWidth(dialogEl);

            dialogEl.style.position = 'fixed';
            dialogEl.style.margin = '0';
            dialogEl.style.left = Number(saved.left) + 'px';
            dialogEl.style.top = Number(saved.top) + 'px';
            dialogEl.style.transform = 'none';
            dialogEl.classList.add('draggable-modal-positioned');
            if (dialogEl.classList.contains('modal-dialog-centered')) {
                dialogEl.classList.remove('modal-dialog-centered');
            }
        }

        function restoreDialogPositionDeferred(modalEl, attemptsLeft) {
            if (!modalEl || attemptsLeft <= 0) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            var saved = modalEl.id ? readPosition(modalEl.id) : null;
            if (!saved) return;

            var rect = dialogEl.getBoundingClientRect();
            if (!isRectMeasurable(rect)) {
                window.requestAnimationFrame(function () {
                    restoreDialogPositionDeferred(modalEl, attemptsLeft - 1);
                });
                return;
            }

            applyDialogPosition(dialogEl, saved.left, saved.top);
        }

        function makeHeaderDraggable(modalEl) {
            if (!modalEl) return;
            var header = modalEl.querySelector('.modal-header');
            if (header) {
                header.classList.add('draggable-modal-handle');
            }
        }

        document.addEventListener('show.bs.modal', function (event) {
            var modalEl = event && event.target;
            if (!modalEl || !modalEl.classList || !modalEl.classList.contains('modal')) return;
            makeHeaderDraggable(modalEl);
            prepositionDialogFromSaved(modalEl);
            observeDialogSize(modalEl);
            restoreDialogPositionDeferred(modalEl, 8);
        });

        document.addEventListener('shown.bs.modal', function (event) {
            var modalEl = event && event.target;
            if (!modalEl || !modalEl.classList || !modalEl.classList.contains('modal')) return;
            restoreDialogPositionDeferred(modalEl, 8);
            var dialogEl = getDialog(modalEl);
            if (!dialogEl || dialogEl.style.position !== 'fixed') return;
            var rect = dialogEl.getBoundingClientRect();
            if (!isRectMeasurable(rect)) return;
            applyDialogPosition(dialogEl, rect.left, rect.top);
        });

        document.addEventListener('hide.bs.modal', function (event) {
            var modalEl = event && event.target;
            if (!modalEl || !modalEl.classList || !modalEl.classList.contains('modal')) return;
            unobserveDialogSize(modalEl);
        });

        document.addEventListener('hidden.bs.modal', function (event) {
            var modalEl = event && event.target;
            if (!modalEl || !modalEl.classList || !modalEl.classList.contains('modal')) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            dialogEl.style.position = '';
            dialogEl.style.margin = '';
            dialogEl.style.left = '';
            dialogEl.style.top = '';
            dialogEl.style.transform = '';
            dialogEl.style.width = '';
            dialogEl.style.maxWidth = '';
            dialogEl.classList.remove('draggable-modal-positioned');
            delete dialogEl.dataset.dragWidth;
        });

        document.addEventListener('mousedown', function (event) {
            if (event.button !== 0) return;
            var target = event.target;
            if (!target || typeof target.closest !== 'function') return;

            var handle = target.closest('.modal-header.draggable-modal-handle');
            if (!handle || isInteractiveElement(target)) return;

            var modalEl = handle.closest('.modal');
            if (!modalEl || !modalEl.classList.contains('show')) return;

            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;

            event.preventDefault();
            captureDialogWidth(dialogEl);
            var rect = dialogEl.getBoundingClientRect();
            var positioned = applyDialogPosition(dialogEl, rect.left, rect.top);
            dragging = {
                modalId: modalEl.id || '',
                dialogEl: dialogEl,
                startX: event.clientX,
                startY: event.clientY,
                originLeft: positioned.left,
                originTop: positioned.top
            };
            document.body.classList.add('draggable-modal-dragging');
        });

        document.addEventListener('mousemove', function (event) {
            if (!dragging) return;
            var nextLeft = dragging.originLeft + (event.clientX - dragging.startX);
            var nextTop = dragging.originTop + (event.clientY - dragging.startY);
            var adjusted = applyDialogPosition(dragging.dialogEl, nextLeft, nextTop);
            writePosition(dragging.modalId, adjusted.left, adjusted.top);
        });

        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            var rect = dragging.dialogEl.getBoundingClientRect();
            var finalPosition = applyDialogPosition(dragging.dialogEl, rect.left, rect.top);
            writePosition(dragging.modalId, finalPosition.left, finalPosition.top);
            dragging = null;
            document.body.classList.remove('draggable-modal-dragging');
        });

        window.addEventListener('resize', function () {
            var openModals = document.querySelectorAll('.modal.show[id]');
            for (var i = 0; i < openModals.length; i += 1) {
                var modalEl = openModals[i];
                var dialogEl = getDialog(modalEl);
                if (!dialogEl) continue;
                if (dialogEl.style.position !== 'fixed') continue;
                var rect = dialogEl.getBoundingClientRect();
                var adjusted = applyDialogPosition(dialogEl, rect.left, rect.top);
                writePosition(modalEl.id, adjusted.left, adjusted.top);
            }
        });
    }

    $(function () {
        installClientErrorCapture();
        initDraggableModals();
        P.Upload.init();
        P.Search.init();
        P.Tabs.init();
        P.EditMode.init();
        P.Divider.init();
        P.Keyboard.init();
        P.Export.init();
        P.Zoom.init();
        P.Upload.loadSampleOnInit();
    });
})(jQuery);
