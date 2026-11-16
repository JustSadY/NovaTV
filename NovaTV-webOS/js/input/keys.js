/*
 * NovaTV webOS — LG remote input and Back handling.
 *
 * Layers: every screen/overlay that takes keys is a layer { handleKey(code, event) -> bool,
 * onBack() , closable }. Keys go to the top layer.
 *
 * Back key: appinfo.json keeps "disableBackHistoryAPI": false, the webOS default. The platform
 * then turns the Back key into history.back(). While any closable layer is open, one history
 * entry is kept "armed"; its popstate closes the top layer. With no layer open there is no entry,
 * so Back leaves the app (webOS 5.0 and older open the Home launcher). If a firmware sends the raw
 * Back keyCode (461) instead, a fallback timer performs the same action.
 */
(function (NV) {
    'use strict';

    var K = {
        LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13,
        BACK: 461, ESC: 27, BACKSPACE: 8,
        RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406,
        PLAY: 415, PAUSE: 19, PLAY_PAUSE: 10252, STOP: 413, FF: 417, RW: 412,
        INFO: 457,
        // Channel keys: on webOS TV these are normally reserved by the system. If a firmware
        // forwards them (as PageUp/PageDown), they are used; nothing depends on it.
        CH_UP: 33, CH_DOWN: 34
    };

    var layers = [];
    var armed = false;
    var suppressPop = 0;
    var backFallback = null;

    function closableCount() {
        var n = 0, i;
        for (i = 0; i < layers.length; i++) if (layers[i].closable) n++;
        return n;
    }

    function topClosable() {
        var i;
        for (i = layers.length - 1; i >= 0; i--) if (layers[i].closable) return layers[i];
        return null;
    }

    function arm() {
        if (armed) return;
        try {
            window.history.pushState({ novatv: true }, '', window.location.href);
            armed = true;
        } catch (e) {
            NV.log('pushState failed: ' + e);
        }
    }

    var syncTimer = null;

    /** Deferred so that "close dialog, open next dialog" in one tick causes no history churn. */
    function syncHistory() {
        if (syncTimer) return;
        syncTimer = setTimeout(function () {
            syncTimer = null;
            if (closableCount() > 0) {
                arm();
            } else if (armed) {
                armed = false;
                suppressPop++;
                try { window.history.back(); } catch (e) { suppressPop--; }
            }
        }, 0);
    }

    function performBack() {
        var top = topClosable();
        if (top) {
            top.onBack();
            syncHistory();
        } else if (window.PalmSystem && typeof window.PalmSystem.platformBack === 'function') {
            window.PalmSystem.platformBack();
        }
    }

    window.addEventListener('popstate', function () {
        if (backFallback) { clearTimeout(backFallback); backFallback = null; }
        if (suppressPop > 0) { suppressPop--; return; }
        if (!armed) return; // old WebKit fires popstate on page load
        armed = false;
        performBack();
    }, false);

    function digitOf(code) {
        if (code >= 48 && code <= 57) return code - 48;
        if (code >= 96 && code <= 105) return code - 96;
        return -1;
    }

    function isTextInput(target) {
        return target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA');
    }

    document.addEventListener('keydown', function (e) {
        var code = e.keyCode, top = layers[layers.length - 1], handled = false;

        if (code === K.BACK) {
            // Normally the platform calls history.back(); act only if no popstate follows.
            if (backFallback) clearTimeout(backFallback);
            backFallback = setTimeout(function () { backFallback = null; performBack(); }, 250);
            e.preventDefault();
            return;
        }
        // Desktop / TV-browser testing: Esc (and Backspace outside text fields) act as Back.
        if (code === K.ESC || (code === K.BACKSPACE && !isTextInput(e.target))) {
            if (armed) window.history.back(); else performBack();
            e.preventDefault();
            return;
        }
        if (isTextInput(e.target) && code !== K.OK && code !== K.UP && code !== K.DOWN) return;

        // Long-press OK (like Android's long-press / MENU) for layers that support it.
        // Only when the platform is known to deliver keyup events (otherwise OK would never fire).
        if (code === K.OK && keyupSeen && top && top.handleLongOk && !isTextInput(e.target)) {
            e.preventDefault();
            if (okDownAt) return; // auto-repeat while held
            okDownAt = NV.now();
            okLayer = top;
            okTimer = setTimeout(function () {
                okTimer = null;
                if (okLayer === NV.input.top()) okLayer.handleLongOk();
                okLayer = null;
            }, 650);
            return;
        }
        if (top && top.handleKey) handled = top.handleKey(code, e) === true;
        if (handled) e.preventDefault();
    }, false);

    var okDownAt = 0, okTimer = null, okLayer = null, keyupSeen = false;

    document.addEventListener('keyup', function (e) {
        keyupSeen = true;
        if (e.keyCode !== K.OK) return;
        okDownAt = 0;
        if (okTimer) {
            // Released before the long-press delay: a normal OK press.
            clearTimeout(okTimer);
            okTimer = null;
            if (okLayer && okLayer === NV.input.top() && okLayer.handleKey) okLayer.handleKey(K.OK, e);
            okLayer = null;
            e.preventDefault();
        }
    }, false);

    NV.keys = K;

    NV.input = {
        digitOf: digitOf,
        push: function (layer) {
            layers.push(layer);
            syncHistory();
        },
        remove: function (layer) {
            var i = layers.indexOf(layer);
            if (i >= 0) layers.splice(i, 1);
            syncHistory();
        },
        top: function () { return layers[layers.length - 1] || null; },
        contains: function (layer) { return layers.indexOf(layer) >= 0; },
        /** Programmatic Back (e.g. a "Geri" button). */
        back: function () { performBack(); }
    };
}(NV));
