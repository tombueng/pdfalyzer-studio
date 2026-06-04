/* PDFalyzer Studio — desktop (Chromium --app) integration.
 *
 * When launched by the Windows installer's PdfalyzerStudio.exe, the bundled Chromium opens the
 * UI with ?desktop=1. In that mode the app should feel native rather than like a web page —
 * starting with no browser right-click context menu. Normal browser usage is unaffected. */
(function () {
    'use strict';

    function flagSet(key) {
        try { return window.sessionStorage.getItem(key) === '1'; } catch (e) { return false; }
    }

    // Persist the desktop marker for the session so it survives in-app navigation/reloads.
    try {
        if (new URLSearchParams(window.location.search).get('desktop') === '1') {
            window.sessionStorage.setItem('pdfalyzerDesktop', '1');
        }
    } catch (e) { /* sessionStorage may be unavailable */ }

    var isDesktop = flagSet('pdfalyzerDesktop');
    if (!isDesktop && window.matchMedia) {
        isDesktop = window.matchMedia('(display-mode: standalone)').matches
            || window.matchMedia('(display-mode: minimal-ui)').matches;
    }

    if (isDesktop) {
        document.documentElement.classList.add('pdfalyzer-desktop');
        // Suppress the browser context menu so right-click behaves like a native desktop app.
        window.addEventListener('contextmenu', function (event) {
            event.preventDefault();
        }, { capture: true });
    }
})();
