/**
 * PDFalyzer Studio – Draggable modal position and width management.
 */
PDFalyzer.Modals = (function ($, P) {
    'use strict';

    function init() {
        var storagePrefix = 'pdfalyzer.modal.position.';
        var widthStoragePrefix = 'pdfalyzer.modal.width.';
        var dragging = null;
        var resizeObserver = null;
        var positionCache = Object.create(null);
        var widthCache = Object.create(null);

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

        function readWidth(modalId) {
            if (!modalId) return null;
            if (typeof widthCache[modalId] === 'number' && isFinite(widthCache[modalId])) {
                return widthCache[modalId];
            }
            try {
                var raw = window.localStorage.getItem(widthStoragePrefix + modalId);
                if (!raw) return null;
                var parsed = Number(raw);
                if (!isFinite(parsed)) return null;
                widthCache[modalId] = parsed;
                return parsed;
            } catch (ignored) {
                return null;
            }
        }

        function writeWidth(modalId, widthPx) {
            if (!modalId) return;
            var safeWidth = Number(widthPx);
            if (!isFinite(safeWidth) || safeWidth <= 120) return;
            widthCache[modalId] = safeWidth;
            try {
                window.localStorage.setItem(widthStoragePrefix + modalId, String(Math.round(safeWidth)));
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

        function captureDialogWidth(dialogEl, modalId) {
            if (!dialogEl) return;
            var measuredWidth = dialogEl.getBoundingClientRect().width;
            if (isFinite(measuredWidth) && measuredWidth > 220) {
                dialogEl.dataset.dragWidth = String(Math.round(measuredWidth));
                writeWidth(modalId, measuredWidth);
            }
        }

        function applyLockedDialogWidth(dialogEl, modalId) {
            if (!dialogEl) return;
            var fixedWidthAttr = Number(dialogEl.getAttribute('data-fixed-dialog-width') || 0);
            if (isFinite(fixedWidthAttr) && fixedWidthAttr > 120) {
                dialogEl.dataset.dragWidth = String(Math.round(fixedWidthAttr));
                dialogEl.style.width = Math.round(fixedWidthAttr) + 'px';
                dialogEl.style.maxWidth = 'none';
                return;
            }
            var storedWidth = Number(dialogEl.dataset.dragWidth || 0);
            if ((!isFinite(storedWidth) || storedWidth <= 120) && modalId) {
                storedWidth = Number(readWidth(modalId) || 0);
                if (isFinite(storedWidth) && storedWidth > 120) {
                    dialogEl.dataset.dragWidth = String(Math.round(storedWidth));
                }
            }
            if (!isFinite(storedWidth) || storedWidth <= 120) {
                var computedMaxWidth = Number.parseFloat(window.getComputedStyle(dialogEl).maxWidth || '');
                if (isFinite(computedMaxWidth) && computedMaxWidth > 120) {
                    storedWidth = computedMaxWidth;
                    dialogEl.dataset.dragWidth = String(Math.round(storedWidth));
                    if (modalId) writeWidth(modalId, storedWidth);
                }
            }
            if (isFinite(storedWidth) && storedWidth > 120) {
                dialogEl.style.width = Math.round(storedWidth) + 'px';
                dialogEl.style.maxWidth = 'none';
            }
        }

        function applyDialogPosition(dialogEl, left, top, modalId) {
            applyLockedDialogWidth(dialogEl, modalId);
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
                    var adjusted = applyDialogPosition(dialogEl, rect.left, rect.top, modalEl && modalEl.id ? modalEl.id : '');
                    if (modalEl && modalEl.id) {
                        writePosition(modalEl.id, adjusted.left, adjusted.top);
                    }
                }
            });
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

            applyDialogPosition(dialogEl, saved.left, saved.top, modalEl.id);
        }

        function prepositionDialogFromSaved(modalEl) {
            if (!modalEl || !modalEl.id) return;
            var dialogEl = getDialog(modalEl);
            if (!dialogEl) return;
            var saved = readPosition(modalEl.id);
            if (!saved) return;

            applyLockedDialogWidth(dialogEl, modalEl.id);

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
            if (P.Utils && P.Utils.prepareModal) {
                P.Utils.prepareModal(modalEl);
            }
            makeHeaderDraggable(modalEl);
            prepositionDialogFromSaved(modalEl);
            observeDialogSize(modalEl);
            restoreDialogPositionDeferred(modalEl, 8);
        });

        document.addEventListener('shown.bs.modal', function (event) {
            var modalEl = event && event.target;
            if (!modalEl || !modalEl.classList || !modalEl.classList.contains('modal')) return;
            var shownDialogEl = getDialog(modalEl);
            if (shownDialogEl && shownDialogEl.style.position !== 'fixed') {
                captureDialogWidth(shownDialogEl, modalEl.id || '');
            }
            restoreDialogPositionDeferred(modalEl, 8);
            var dialogEl = getDialog(modalEl);
            if (!dialogEl || dialogEl.style.position !== 'fixed') return;
            var rect = dialogEl.getBoundingClientRect();
            if (!isRectMeasurable(rect)) return;
            applyDialogPosition(dialogEl, rect.left, rect.top, modalEl.id || '');
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
            captureDialogWidth(dialogEl, modalEl.id || '');
            var rect = dialogEl.getBoundingClientRect();
            var positioned = applyDialogPosition(dialogEl, rect.left, rect.top, modalEl.id || '');
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
            var adjusted = applyDialogPosition(dragging.dialogEl, nextLeft, nextTop, dragging.modalId);
            writePosition(dragging.modalId, adjusted.left, adjusted.top);
        });

        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            var rect = dragging.dialogEl.getBoundingClientRect();
            var finalPosition = applyDialogPosition(dragging.dialogEl, rect.left, rect.top, dragging.modalId);
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
                var adjusted = applyDialogPosition(dialogEl, rect.left, rect.top, modalEl.id || '');
                writePosition(modalEl.id, adjusted.left, adjusted.top);
            }
        });
    }

    return { init: init };
})(jQuery, PDFalyzer);
