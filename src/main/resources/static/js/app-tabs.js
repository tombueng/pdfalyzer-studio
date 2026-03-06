/**
 * PDFalyzer Studio – shared tab state + core Tabs module.
 * Shared state is stored on PDFalyzer._tabPrivate so sub-modules can access it.
 */
PDFalyzer._tabPrivate = {
    veraPdfExportHtml: '',
    veraPdfExportTitle: 'veraPDF Report',
    fontDiagnosticsState: {
        data: null,
        filterIssuesOnly: false,
        sort: 'issues-desc',
        focusObj: null,
        focusGen: null,
        focusMeta: {},
        detailLoadingKey: null,
        detailLoadedKey: null,
        detailCache: {}
    },
    activeGlyphObserver: null,
    glyphMeasureCanvas: null,
    activeFontDetailState: null,
    activeGlyphCanvasState: null,
    activeFontUsageAreas: [],
    activeGlyphHighlightSelections: {},
    glyphHighlightPalette: ['#ff4d4f', '#fa8c16', '#fadb14', '#52c41a', '#13c2c2', '#1677ff', '#722ed1', '#eb2f96', '#a0d911', '#2f54eb'],
    nextGeneratedGlyphColorIndex: 10 // glyphHighlightPalette.length
};

PDFalyzer.Tabs = (function ($, P) {
    'use strict';

    var _p = P._tabPrivate;

    function init() {
        $('.tab-btn').on('click', function () {
            $('.tab-btn').removeClass('active');
            $(this).addClass('active');
            switchTab($(this).data('tab'));
        });

        $(document).off('pdfviewer:rendered.fontusage').on('pdfviewer:rendered.fontusage', function () {
            if (!_p.activeFontUsageAreas || !_p.activeFontUsageAreas.length) return;
            P.GlyphUI.showFontUsageHighlights(_p.activeFontUsageAreas, { clearExisting: true, persist: false });
        });
    }

    function isTreeTab(tab) {
        return tab === 'structure' || tab === 'forms' || tab === 'bookmarks' ||
            tab === 'rawcos' || tab === 'attachments';
    }

    function captureTreeViewStateForTab(tab) {
        if (!isTreeTab(tab) || !P.Tree || !P.Tree.captureViewState) return;
        var viewState = P.Tree.captureViewState();
        if (!viewState) return;
        if (!P.state.tabTreeViewStates) P.state.tabTreeViewStates = {};
        P.state.tabTreeViewStates[tab] = viewState;
    }

    function getTreeViewStateForTab(tab) {
        if (!P.state.tabTreeViewStates) return null;
        return P.state.tabTreeViewStates[tab] || null;
    }

    function dismissTransientHoverPopups() {
        $('.image-tooltip-preview').remove();
        $('.glyph-tooltip-preview').remove();
        $(document).off('mousemove.imgtooltip');
        $(document).off('mousemove.glyphtooltip');
    }

    function updateStructureSearchControl(tab) {
        var isStructure = tab === 'structure';
        var hasSession = !!P.state.sessionId;
        var $tools = $('.tree-search-tools');
        var $search = $('#searchInput');

        $tools.toggleClass('d-none', !isStructure);
        $search.prop('disabled', !isStructure || !hasSession);

        if (!isStructure && $search.val()) {
            $search.val('');
        }
    }

    function switchTab(tab, onAfterRender) {
        if (!P.state.treeData || !P.state.sessionId) return;

        dismissTransientHoverPopups();

        // Capture viewer scroll position before anything changes
        if (P.ViewerRender && P.ViewerRender.capturePaneViewState) {
            P.state.viewerScrollState = P.ViewerRender.capturePaneViewState();
        }

        var previousTab = P.state.currentTab;
        if (previousTab !== tab) {
            // Leaving a tab — save its current tree state
            captureTreeViewStateForTab(previousTab);
        } else if (isTreeTab(tab)) {
            // Same-tab refresh — capture live DOM state so user-expanded nodes are preserved
            captureTreeViewStateForTab(tab);
        }

        var viewState = getTreeViewStateForTab(tab);
        updateStructureSearchControl(tab);

        $('#treeContent').html(P.Utils.tabSkeleton(tab));

        var doRender = function () {
            switch (tab) {
                case 'structure':   P.Tree.render(P.state.treeData, { viewState: viewState }); break;
                case 'forms':       P.Tree.renderSubtree(P.state.treeData, 'acroform', { viewState: viewState }); break;
                case 'fonts':       P.FontsTab.loadFonts(); break;
                case 'validation':  P.ValidationTab.loadValidation(); break;
                case 'rawcos':      loadRawCos(viewState); break;
                case 'bookmarks':   P.Tree.renderSubtree(P.state.treeData, 'bookmarks', { viewState: viewState }); break;
                case 'attachments': loadAttachments(); break;
                case 'signatures':  P.SignaturesTab.loadSignatures(); break;
                case 'changes':     P.ChangesTab.renderChanges(); break;
            }
            if (typeof onAfterRender === 'function') { onAfterRender(); }
        };

        // Tree tabs render synchronously — defer one frame so skeleton is painted first
        if (isTreeTab(tab)) {
            requestAnimationFrame(doRender);
        } else {
            doRender();
        }

        P.state.currentTab = tab;
        if (P.Storage && P.Storage.saveDraft) {
            P.Storage.saveDraft(P.state);
        }
    }

    // ======================== RAW COS TAB ========================

    function loadRawCos(viewState) {
        if (P.state.rawCosTreeData) {
            P.Tree.render(P.state.rawCosTreeData, { viewState: viewState });
            return;
        }

        P.Utils.apiFetch('/api/tree/' + P.state.sessionId + '/raw-cos')
            .done(function (rawTree) {
                P.state.rawCosTreeData = rawTree;
                P.Tree.render(rawTree, { viewState: viewState });
            })
            .fail(function () { P.Utils.toast('Failed to load raw COS', 'danger'); });
    }

    // ======================== ATTACHMENTS TAB ========================

    function loadAttachments() {
        var attachNodes = P.Tree.findAllByCategory(P.state.treeData, 'attachment');
        if (!attachNodes.length) {
            $('#treeContent').html(
                '<div class="text-muted text-center mt-3">' +
                '<i class="fas fa-paperclip fa-2x mb-2"></i><br>No attachments found</div>');
            return;
        }
        P.Tree.renderSubtree(P.state.treeData, 'attachments', {
            viewState: getTreeViewStateForTab('attachments')
        });
    }

    return { init: init, switchTab: switchTab, isTreeTab: isTreeTab };
})(jQuery, PDFalyzer);
