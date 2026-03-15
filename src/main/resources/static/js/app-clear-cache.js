(function () {
    'use strict';

    function clearAllCaches() {
        // Local storage
        try { localStorage.clear(); } catch (e) { /* ignore */ }

        // Session storage
        try { sessionStorage.clear(); } catch (e) { /* ignore */ }

        // Cookies
        try {
            document.cookie.split(';').forEach(function (c) {
                var name = c.split('=')[0].trim();
                document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';
            });
        } catch (e) { /* ignore */ }

        // IndexedDB
        try {
            if (indexedDB && indexedDB.databases) {
                indexedDB.databases().then(function (dbs) {
                    dbs.forEach(function (db) { indexedDB.deleteDatabase(db.name); });
                });
            }
        } catch (e) { /* ignore */ }

        // Cache API (service worker caches)
        try {
            if (caches && caches.keys) {
                caches.keys().then(function (names) {
                    names.forEach(function (name) { caches.delete(name); });
                });
            }
        } catch (e) { /* ignore */ }

        // Unregister service workers
        try {
            if (navigator.serviceWorker) {
                navigator.serviceWorker.getRegistrations().then(function (regs) {
                    regs.forEach(function (r) { r.unregister(); });
                });
            }
        } catch (e) { /* ignore */ }

        // Hard reload after a short delay to let async cleanup finish
        setTimeout(function () {
            window.location.reload(true);
        }, 300);
    }

    function init() {
        var btn = document.getElementById('clearCacheBtn');
        if (btn) {
            btn.addEventListener('click', clearAllCaches);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
