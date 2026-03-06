/**
 * PDFalyzer Studio – IndexedDB storage for signing profiles.
 * Stores visual configurations and certificate public info (never private keys or passwords).
 */
PDFalyzer.SigningStore = (function (P) {
    'use strict';

    var DB_NAME = 'pdfalyzer-signing';
    var DB_VERSION = 1;
    var STORE_NAME = 'profiles';
    var _db = null;

    function open() {
        return new Promise(function (resolve, reject) {
            if (_db) { resolve(_db); return; }
            var req = indexedDB.open(DB_NAME, DB_VERSION);
            req.onupgradeneeded = function (e) {
                var db = e.target.result;
                if (!db.objectStoreNames.contains(STORE_NAME)) {
                    db.createObjectStore(STORE_NAME, { keyPath: 'id', autoIncrement: true });
                }
            };
            req.onsuccess = function (e) { _db = e.target.result; resolve(_db); };
            req.onerror = function (e) { reject(e.target.error); };
        });
    }

    function save(profile) {
        return open().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE_NAME, 'readwrite');
                var store = tx.objectStore(STORE_NAME);
                profile.updatedAt = Date.now();
                if (!profile.createdAt) profile.createdAt = profile.updatedAt;
                if (!profile.useCount) profile.useCount = 0;
                var req = profile.id ? store.put(profile) : store.add(profile);
                req.onsuccess = function () { profile.id = req.result; resolve(profile); };
                req.onerror = function () { reject(req.error); };
            });
        });
    }

    function recordUsage(id) {
        return get(id).then(function (profile) {
            if (!profile) return null;
            profile.lastUsedAt = Date.now();
            profile.useCount = (profile.useCount || 0) + 1;
            return save(profile);
        });
    }

    function list() {
        return open().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE_NAME, 'readonly');
                var store = tx.objectStore(STORE_NAME);
                var req = store.getAll();
                req.onsuccess = function () { resolve(req.result || []); };
                req.onerror = function () { reject(req.error); };
            });
        });
    }

    function get(id) {
        return open().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE_NAME, 'readonly');
                var store = tx.objectStore(STORE_NAME);
                var req = store.get(id);
                req.onsuccess = function () { resolve(req.result || null); };
                req.onerror = function () { reject(req.error); };
            });
        });
    }

    function remove(id) {
        return open().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE_NAME, 'readwrite');
                var store = tx.objectStore(STORE_NAME);
                var req = store.delete(id);
                req.onsuccess = function () { resolve(); };
                req.onerror = function () { reject(req.error); };
            });
        });
    }

    function clear() {
        return open().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE_NAME, 'readwrite');
                var store = tx.objectStore(STORE_NAME);
                var req = store.clear();
                req.onsuccess = function () { resolve(); };
                req.onerror = function () { reject(req.error); };
            });
        });
    }

    return {
        open: open,
        save: save,
        list: list,
        get: get,
        remove: remove,
        clear: clear,
        recordUsage: recordUsage
    };
})(PDFalyzer);
