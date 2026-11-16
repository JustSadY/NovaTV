/*
 * NovaTV webOS — bootstrap and lifecycle.
 *  - Restores the last channel immediately (stored with its stream URL), before lists load.
 *  - Downloads stale sources and the EPG afterwards, one request at a time.
 *  - Releases the video decoder when the app is hidden and resumes when it comes back
 *    (webOS 1.x: webkitvisibilitychange; relaunch: webOSRelaunch).
 *  - Each start-up step runs on its own; a failing step is shown by js/core/boot-guard.js
 *    instead of leaving an empty screen.
 */
(function (NV) {
    'use strict';

    function tickClock() {
        NV.$('clock').textContent = NV.hm(NV.now());
    }

    function hiddenNow() {
        return document.webkitHidden === true || document.hidden === true;
    }

    function onVisibility() {
        if (hiddenNow()) NV.playerUi.suspend(); else NV.playerUi.resume();
    }

    function autoplay() {
        var last = NV.settings.lastChannel(), snap = NV.channels.snapshot(), ch;
        if (!NV.settings.get('autoplayLast') || !last) return;
        ch = snap.byKey(last.key) || last;
        NV.playerUi.open(ch, 'all', false);
    }

    function step(name, fn) {
        try {
            fn();
        } catch (e) {
            if (window.NV_BOOT) window.NV_BOOT.fail(name, e); else throw e;
        }
    }

    function boot() {
        step('Kanal listesi', function () { NV.channels.init(); });
        step('Yayın akışı (EPG)', function () { NV.epg.init(); });
        step('Ana ekran', function () { NV.home.init(); });
        step('Oynatıcı', function () { NV.playerUi.init(); });
        step('Saat', function () { tickClock(); setInterval(tickClock, 20000); });
        step('Uzaktan yönetim', function () {
            NV.events.on('manage', function (text) { NV.ui.osd(text, 4000); });
            NV.events.on('settings', function (key) { if (key === 'proxyUrl' || key === 'manageEnabled') NV.manageRelay.update(); });
            NV.manageRelay.update();
        });

        document.addEventListener('webkitvisibilitychange', onVisibility, false);
        document.addEventListener('visibilitychange', onVisibility, false);
        document.addEventListener('webOSRelaunch', function () {
            // appinfo.json leaves "handlesRelaunch" at its default (false), so webOS itself brings
            // the app to the front; here playback only continues where it was.
            if (NV.playerUi.isOpen()) NV.playerUi.resume(); else autoplay();
        }, false);

        step('Son kanal', autoplay);
        if (window.NV_BOOT) window.NV_BOOT.done();

        // Controlled background work after the first frame: stale playlists, then the guide.
        setTimeout(function () {
            NV.channels.refreshAll(function (results) {
                var bad = results.filter(function (r) { return r.error; });
                if (bad.length && !NV.playerUi.isOpen()) NV.ui.osd(bad[0].source.name + ': ' + bad[0].error.message, 5000);
                NV.epg.refreshIfNeeded();
            }, true);
        }, 2500);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, false);
    else boot();
}(NV));
