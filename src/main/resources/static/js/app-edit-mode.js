/**
 * PDFalyzer – PDF form field edit mode (draw new fields on the viewer).
 */
PDFalyzer.EditMode = (function ($, P) {
    'use strict';

    var drawing  = false;
    var startX   = 0, startY = 0;
    var $drawRect = null;
    var targetPage = -1;

    function hasSession() {
        return !!P.state.sessionId;
    }

    function init() {
        $('#editToolbar').addClass('active');
        P.state.editMode = true;
        P.state.selectedFieldNames = [];
        if (!P.state.pendingFieldDeletes) P.state.pendingFieldDeletes = {};

        $('.edit-field-btn').on('click', function () {
            if (!hasSession()) {
                P.Utils.toast('Load a PDF session first', 'warning');
                return;
            }
            $('.edit-field-btn').removeClass('active');
            $(this).addClass('active');
            P.state.editFieldType = $(this).data('type');

            var pageIndex = 0;
            var rect = computeNextLineRect(pageIndex, P.state.editFieldType);
            var fieldName = prompt('Field name:', P.state.editFieldType + '_' + Date.now());
            if (!fieldName) return;

            var options = getDefaultOptions(P.state.editFieldType);
            var optionsJson = prompt('Field options JSON:', JSON.stringify(options));
            if (optionsJson) {
                try {
                    options = JSON.parse(optionsJson);
                } catch (e) {
                    P.Utils.toast('Invalid options JSON, using defaults', 'warning');
                }
            }

            P.state.pendingFormAdds.push({
                fieldType: P.state.editFieldType,
                fieldName: fieldName,
                pageIndex: pageIndex,
                x: rect.x,
                y: rect.y,
                width: rect.width,
                height: rect.height,
                options: options
            });
            updateSaveButton();
            P.Utils.toast('Field queued. Click Save to persist changes.', 'info');
        });

        $('#formSaveBtn').on('click', savePendingChanges);
        $('#formOptionsBtn').on('click', openOptionsPopup);
        $('#applyFieldOptionsBtn').on('click', applyOptionsFromModal);
    }

    function startDraw(e, pageIndex, wrapper) {
        drawing    = true;
        targetPage = pageIndex;
        var rect   = wrapper.getBoundingClientRect();
        startX     = e.clientX - rect.left;
        startY     = e.clientY - rect.top;

        $drawRect = $('<div>', { 'class': 'draw-rect' })
            .css({ left: startX + 'px', top: startY + 'px' })
            .appendTo(wrapper);

        var moveHandler = function (ev) {
            if (!drawing) return;
            var cx = ev.clientX - rect.left, cy = ev.clientY - rect.top;
            $drawRect.css({
                left:   Math.min(startX, cx) + 'px',
                top:    Math.min(startY, cy) + 'px',
                width:  Math.abs(cx - startX) + 'px',
                height: Math.abs(cy - startY) + 'px'
            });
        };

        var upHandler = function (ev) {
            drawing = false;
            $(document).off('mousemove', moveHandler).off('mouseup', upHandler);
            var cx = ev.clientX - rect.left, cy = ev.clientY - rect.top;
            var x = Math.min(startX, cx), y = Math.min(startY, cy);
            var w = Math.abs(cx - startX), h = Math.abs(cy - startY);
            if ($drawRect) $drawRect.remove();
            if (w < 10 || h < 10) return;

            var vp    = P.state.pageViewports[pageIndex];
            var scale = vp.scale;
            var pdfX  = x / scale;
            var pdfY  = (vp.height - y - h) / scale;
            var pdfW  = w / scale;
            var pdfH  = h / scale;

            var fieldName = prompt('Field name:', P.state.editFieldType + '_' + Date.now());
            if (!fieldName) return;

            var options = getDefaultOptions(P.state.editFieldType);
            var optionsJson = prompt('Field options JSON:', JSON.stringify(options));
            if (optionsJson) {
                try {
                    options = JSON.parse(optionsJson);
                } catch (err) {
                    P.Utils.toast('Invalid options JSON, using defaults', 'warning');
                }
            }

            P.state.pendingFormAdds.push({
                fieldType: P.state.editFieldType,
                fieldName: fieldName,
                pageIndex: pageIndex,
                x: pdfX,
                y: pdfY,
                width: pdfW,
                height: pdfH,
                options: options
            });
            updateSaveButton();
            P.Utils.toast('Field queued. Click Save to persist changes.', 'info');
        };

        $(document).on('mousemove', moveHandler).on('mouseup', upHandler);
    }

    function savePendingChanges() {
        if (!hasSession()) return;
        if (!P.state.pendingFormAdds.length && !P.state.pendingFieldRects.length) {
            P.Utils.toast('No pending form changes', 'info');
            return;
        }

        $('#formSaveBtn').prop('disabled', true);

        var saveAdds = $.Deferred().resolve();
        if (P.state.pendingFormAdds.length) {
            saveAdds = P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/add-fields', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(P.state.pendingFormAdds)
            });
        }

        saveAdds
            .done(function (data) {
                if (data && data.tree) {
                    P.state.treeData = data.tree;
                }
                applyPendingRectUpdates();
            })
            .fail(function () {
                P.Utils.toast('Saving new fields failed', 'danger');
                updateSaveButton();
            });
    }

    function applyPendingRectUpdates() {
        if (!P.state.pendingFieldRects.length) {
            finalizeSave();
            return;
        }

        var queue = P.state.pendingFieldRects.slice();
        function next() {
            if (!queue.length) {
                finalizeSave();
                return;
            }
            var item = queue.shift();
            P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                encodeURIComponent(item.fieldName) + '/rect', {
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    x: item.x,
                    y: item.y,
                    width: item.width,
                    height: item.height
                })
            }).done(function (data) {
                if (data && data.tree) P.state.treeData = data.tree;
                next();
            }).fail(function () {
                P.Utils.toast('Saving field geometry failed for ' + item.fieldName, 'danger');
                next();
            });
        }
        next();
    }

    function finalizeSave() {
        P.state.pendingFormAdds = [];
        P.state.pendingFieldRects = [];
        if (P.state.treeData) P.Tree.render(P.state.treeData);
        P.Viewer.loadPdf(P.state.sessionId);
        refreshSelectionButtons();
        updateSaveButton();
        P.Utils.toast('Form changes saved', 'success');
    }

    function resetPending() {
        P.state.pendingFormAdds = [];
        P.state.pendingFieldRects = [];
        P.state.selectedFieldNames = [];
        refreshSelectionButtons();
        updateSaveButton();
    }

    function updateSaveButton() {
        var hasPending = P.state.pendingFormAdds.length > 0 || P.state.pendingFieldRects.length > 0;
        $('#formSaveBtn').prop('disabled', !hasPending || !hasSession());
    }

    function refreshSelectionButtons() {
        var selectedCount = (P.state.selectedFieldNames || []).length;
        $('#formOptionsBtn').prop('disabled', !hasSession() || selectedCount === 0);
    }

    function getDefaultOptions(fieldType) {
        if (fieldType === 'text') return { required: false, readonly: false, multiline: false, defaultValue: '' };
        if (fieldType === 'checkbox') return { required: false, readonly: false, checked: false };
        if (fieldType === 'combo') return { required: false, readonly: false, editable: false, choices: 'A,B,C', defaultValue: '' };
        if (fieldType === 'radio') return { required: false, readonly: false };
        if (fieldType === 'signature') return { required: true, readonly: false };
        return {};
    }

    function computeNextLineRect(pageIndex, fieldType) {
        var baseX = 50;
        var baseY = 700;
        var lineHeight = 34;
        var width = fieldType === 'signature' ? 220 : 180;
        var height = fieldType === 'signature' ? 28 : 22;

        var existingOnPage = collectFieldNodesOnPage(pageIndex).length;
        var pendingOnPage = P.state.pendingFormAdds.filter(function (f) { return f.pageIndex === pageIndex; }).length;
        var line = existingOnPage + pendingOnPage;
        return {
            x: baseX,
            y: Math.max(40, baseY - (line * lineHeight)),
            width: width,
            height: height
        };
    }

    function collectFieldNodesOnPage(pageIndex) {
        var result = [];
        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.pageIndex === pageIndex && node.boundingBox) {
                result.push(node);
            }
            if (node.children) node.children.forEach(walk);
        }
        walk(P.state.treeData);
        return result;
    }

    function renderFieldHandles(pageIndex, wrapperEl) {
        if (!hasSession() || !wrapperEl) return;
        $(wrapperEl).find('.form-field-handle').remove();
        var viewport = P.state.pageViewports[pageIndex];
        if (!viewport) return;

        var fields = collectFieldNodesOnPage(pageIndex);
        fields.forEach(function (fieldNode) {
            var bbox = fieldNode.boundingBox;
            var scale = viewport.scale;
            var left = bbox[0] * scale;
            var top = viewport.height - (bbox[1] + bbox[3]) * scale;
            var width = bbox[2] * scale;
            var height = bbox[3] * scale;

            var fullName = fieldNode.properties && fieldNode.properties.FullName;
            if (!fullName) return;

            var $handle = $('<div>', { 'class': 'form-field-handle' })
                .attr('data-field-name', fullName)
                .css({ left: left + 'px', top: top + 'px', width: width + 'px', height: height + 'px' });
            if ((P.state.selectedFieldNames || []).indexOf(fullName) >= 0) {
                $handle.addClass('selected');
            }
            
            // Add options button to handle
            var $optBtn = $('<button>', { 
                'class': 'field-handle-btn field-handle-options',
                'title': 'Options',
                html: '<i class="fas fa-cog"></i>'
            }).on('click', function(e) {
                e.stopPropagation();
                P.state.selectedFieldNames = [fullName];
                renderFieldHandlesForAllPages();
                openOptionsPopup();
            });
            
            // Add delete button to handle
            var $delBtn = $('<button>', { 
                'class': 'field-handle-btn field-handle-delete',
                'title': 'Delete',
                html: '<i class="fas fa-trash-alt"></i>'
            }).on('click', function(e) {
                e.stopPropagation();
                queueFieldDelete(fullName);
            });
            
            if (isFieldDeletePending(fullName)) {
                var $undoBtn = $('<button>', {
                    'class': 'field-handle-btn field-handle-undo',
                    'title': 'Undo remove',
                    html: '<i class="fas fa-undo"></i>'
                }).on('click', function (e) {
                    e.stopPropagation();
                    undoFieldDelete(fullName);
                });
                $handle.addClass('removed').append($undoBtn).appendTo(wrapperEl);
            } else {
                var $resize = $('<div>', { 'class': 'form-field-resize' });
                $handle.append($optBtn, $delBtn, $resize).appendTo(wrapperEl);
                bindDragResize($handle, $resize, fieldNode, viewport);
            }
        });
        refreshSelectionButtons();
    }

    function bindDragResize($handle, $resize, fieldNode, viewport) {
        var dragging = false;
        var resizing = false;
        var start = {};

        $handle.on('mousedown', function (e) {
            if ($(e.target).closest('.form-field-resize, .field-handle-btn').length) return;
            e.preventDefault();
            e.stopPropagation();

            var name = fieldNode.properties && fieldNode.properties.FullName;
            if (name) {
                if (e.ctrlKey || e.metaKey || e.shiftKey) {
                    toggleFieldSelection(name);
                } else {
                    P.state.selectedFieldNames = [name];
                }
                // Re-render only the handles, not the entire PDF
                renderFieldHandlesForAllPages();
                
                // Highlight the field in the tree
                var fieldNode = findFieldNodeByName(name);
                if (fieldNode) P.Tree.selectNode(fieldNode);
            }

            dragging = true;
            start = {
                x: e.clientX,
                y: e.clientY,
                left: parseFloat($handle.css('left')),
                top: parseFloat($handle.css('top'))
            };
        });

        $resize.on('mousedown', function (e) {
            e.preventDefault();
            e.stopPropagation();
            resizing = true;
            start = {
                x: e.clientX,
                y: e.clientY,
                width: $handle.width(),
                height: $handle.height()
            };
        });

        $(document).on('mousemove.formHandle', function (e) {
            if (dragging) {
                var dx = e.clientX - start.x;
                var dy = e.clientY - start.y;
                $handle.css({ left: (start.left + dx) + 'px', top: (start.top + dy) + 'px' });
            } else if (resizing) {
                var rw = Math.max(20, start.width + (e.clientX - start.x));
                var rh = Math.max(14, start.height + (e.clientY - start.y));
                $handle.css({ width: rw + 'px', height: rh + 'px' });
            }
        }).on('mouseup.formHandle', function () {
            if (!dragging && !resizing) return;
            dragging = false;
            resizing = false;

            var left = parseFloat($handle.css('left'));
            var top = parseFloat($handle.css('top'));
            var width = $handle.width();
            var height = $handle.height();
            var scale = viewport.scale;

            var pdfX = left / scale;
            var pdfY = (viewport.height - top - height) / scale;
            var pdfW = width / scale;
            var pdfH = height / scale;

            queueRectChange(fieldNode.properties.FullName, pdfX, pdfY, pdfW, pdfH);
            updateSaveButton();
        });
    }

    function toggleFieldSelection(fieldName) {
        if (!P.state.selectedFieldNames) P.state.selectedFieldNames = [];
        var idx = P.state.selectedFieldNames.indexOf(fieldName);
        if (idx >= 0) P.state.selectedFieldNames.splice(idx, 1);
        else P.state.selectedFieldNames.push(fieldName);
        refreshSelectionButtons();
    }

    function findFieldNodeByName(fullName) {
        var found = null;
        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName === fullName) {
                found = node;
                return;
            }
            if (node.children) node.children.forEach(walk);
        }
        walk(P.state.treeData);
        return found;
    }

    function renderFieldHandlesForAllPages() {
        if (!P.state.pdfDoc) return;
        for (var i = 0; i < P.state.pdfDoc.numPages; i++) {
            var wrapper = $('[data-page="' + i + '"]')[0];
            if (wrapper) renderFieldHandles(i, wrapper);
        }
    }

    function openOptionsPopup() {
        if (!hasSession()) return;
        var selected = P.state.selectedFieldNames || [];
        if (!selected.length) {
            P.Utils.toast('Select one or more fields first (Ctrl/Shift-click handles)', 'warning');
            return;
        }

        var selectedNodes = getSelectedFieldNodes();
        var single = selectedNodes.length === 1 ? selectedNodes[0] : null;
        $('#fieldOptionsSelectionInfo').text(
            selected.length === 1
                ? ('Editing: ' + selected[0])
                : ('Editing ' + selected.length + ' fields (tri-state controls)')
        );

        $('#optRequired').val(resolveTriState(selectedNodes, 'Required'));
        $('#optReadonly').val(resolveTriState(selectedNodes, 'ReadOnly'));
        $('#optMultiline').val('keep');
        $('#optEditable').val('keep');
        $('#optChecked').val('keep');
        $('#optDefaultValue').val(single && single.properties ? (single.properties.Value || '') : '');
        $('#optChoices').val('');

        $('#optDefaultValue').prop('disabled', !single);
        $('#optChoices').prop('disabled', !single);

        var modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('fieldOptionsModal'));
        modal.show();
    }

    function resolveTriState(nodes, propertyName) {
        if (!nodes.length) return 'keep';
        var first = readBooleanProperty(nodes[0], propertyName);
        for (var i = 1; i < nodes.length; i++) {
            if (readBooleanProperty(nodes[i], propertyName) !== first) return 'keep';
        }
        return first ? 'true' : 'false';
    }

    function readBooleanProperty(node, propertyName) {
        var value = node && node.properties ? node.properties[propertyName] : null;
        return String(value).toLowerCase() === 'true';
    }

    function getSelectedFieldNodes() {
        var names = P.state.selectedFieldNames || [];
        var found = [];
        function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName) {
                if (names.indexOf(node.properties.FullName) >= 0) found.push(node);
            }
            if (node.children) node.children.forEach(walk);
        }
        walk(P.state.treeData);
        return found;
    }

    function parseTriStateValue(value) {
        if (value === 'true') return true;
        if (value === 'false') return false;
        return null;
    }

    function applyOptionsFromModal() {
        if (!hasSession()) return;
        var fieldNames = (P.state.selectedFieldNames || []).slice();
        if (!fieldNames.length) return;

        var options = {
            required: parseTriStateValue($('#optRequired').val()),
            readonly: parseTriStateValue($('#optReadonly').val()),
            multiline: parseTriStateValue($('#optMultiline').val()),
            editable: parseTriStateValue($('#optEditable').val()),
            checked: parseTriStateValue($('#optChecked').val())
        };

        if (!$('#optDefaultValue').prop('disabled')) {
            var defaultValue = $('#optDefaultValue').val();
            if (defaultValue) options.defaultValue = defaultValue;
        }
        if (!$('#optChoices').prop('disabled')) {
            var choicesRaw = $('#optChoices').val();
            if (choicesRaw) {
                options.choices = choicesRaw.split(',').map(function (v) { return v.trim(); })
                    .filter(function (v) { return v.length > 0; });
            }
        }

        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/fields/options', {
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ fieldNames: fieldNames, options: options })
        }).done(function (data) {
            if (data && data.tree) {
                P.state.treeData = data.tree;
                P.Tree.render(P.state.treeData);
                P.Viewer.loadPdf(P.state.sessionId);
            }
            var modalEl = document.getElementById('fieldOptionsModal');
            var modal = bootstrap.Modal.getInstance(modalEl);
            if (modal) modal.hide();
            P.Utils.toast('Field options updated', 'success');
        }).fail(function () {
            P.Utils.toast('Failed to update field options', 'danger');
        });
    }

    function queueRectChange(fieldName, x, y, width, height) {
        var idx = P.state.pendingFieldRects.findIndex(function (r) {
            return r.fieldName === fieldName;
        });
        var payload = { fieldName: fieldName, x: x, y: y, width: width, height: height };
        if (idx >= 0) {
            P.state.pendingFieldRects[idx] = payload;
        } else {
            P.state.pendingFieldRects.push(payload);
        }
    }

    /**
     * Delete a form field by fully-qualified name.
     */
    function deleteField(fieldName) {
        queueFieldDelete(fieldName);
    }

    function isFieldDeletePending(fieldName) {
        return !!(P.state.pendingFieldDeletes && P.state.pendingFieldDeletes[fieldName]);
    }

    function queueFieldDelete(fieldName) {
        if (!P.state.sessionId || !fieldName) return;
        if (!P.state.pendingFieldDeletes) P.state.pendingFieldDeletes = {};
        if (P.state.pendingFieldDeletes[fieldName]) return;

        var timerId = window.setTimeout(function () {
            finalizeFieldDelete(fieldName);
        }, 5000);
        P.state.pendingFieldDeletes[fieldName] = { timerId: timerId };

        if (P.state.selectedFieldNames) {
            P.state.selectedFieldNames = P.state.selectedFieldNames.filter(function (n) {
                return n !== fieldName;
            });
        }
        renderFieldHandlesForAllPages();
        P.Utils.toast('Field "' + fieldName + '" marked for removal (Undo available)', 'warning');
    }

    function undoFieldDelete(fieldName) {
        if (!P.state.pendingFieldDeletes || !P.state.pendingFieldDeletes[fieldName]) return;
        window.clearTimeout(P.state.pendingFieldDeletes[fieldName].timerId);
        delete P.state.pendingFieldDeletes[fieldName];
        renderFieldHandlesForAllPages();
        P.Utils.toast('Removal cancelled for "' + fieldName + '"', 'info');
    }

    function finalizeFieldDelete(fieldName) {
        if (!P.state.sessionId) return;
        if (!P.state.pendingFieldDeletes || !P.state.pendingFieldDeletes[fieldName]) return;

        window.clearTimeout(P.state.pendingFieldDeletes[fieldName].timerId);
        delete P.state.pendingFieldDeletes[fieldName];

        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                          encodeURIComponent(fieldName), { method: 'DELETE' })
            .done(function (data) {
                P.state.treeData = data.tree;
                P.Tree.render(P.state.treeData);
                renderFieldHandlesForAllPages();
                P.Utils.toast('Field "' + fieldName + '" deleted', 'success');
            })
            .fail(function () {
                P.Utils.toast('Delete field failed', 'danger');
                renderFieldHandlesForAllPages();
            });
    }

    /**
     * Update a form field value by fully-qualified name.
     */
    function setFieldValue(fieldName, value) {
        if (!P.state.sessionId) return;
        P.Utils.apiFetch('/api/edit/' + P.state.sessionId + '/field/' +
                          encodeURIComponent(fieldName) + '/value', {
            method: 'POST', contentType: 'application/json',
            data: JSON.stringify({ value: value })
        })
        .done(function (data) {
            P.state.treeData = data.tree;
            P.Tree.render(P.state.treeData);
            P.Utils.toast('Field value updated', 'success');
        })
        .fail(function () { P.Utils.toast('Update field value failed', 'danger'); });
    }

    return {
        init: init,
        startDraw: startDraw,
        deleteField: deleteField,
        setFieldValue: setFieldValue,
        renderFieldHandles: renderFieldHandles,
        resetPending: resetPending,
        savePendingChanges: savePendingChanges,
        updateSaveButton: updateSaveButton
    };
})(jQuery, PDFalyzer);
