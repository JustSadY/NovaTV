/* NovaTV webOS — user settings (mirrors the Android Settings class where the platform allows). */
(function (NV) {
    'use strict';

    var DEFAULTS = {
        autoplayLast: true,      // "Açılışta son kanalı oynat" (skips the start screen)
        invertUpDown: false,     // false: UP = next channel
        bannerSeconds: 5,
        aspectMode: 0,           // 0 fit, 1 fill (stretch), 2 zoom
        quality: 0,              // 0 auto (TV-safe <=1080p, H.264 level <= 4.1), 1 <=720p, 2 <=480p, 3 let the TV decide
        proxyUrl: '',            // optional LAN proxy, e.g. http://192.168.1.10:8787
        proxyToken: '',
        proxyAll: false,         // route every request (playlists, EPG, streams) through the proxy
        autoRefreshHours: 24,    // refresh URL sources on start when older than this (0 = never)
        epgUrl: '',
        builtinSeeded: false,
        manageEnabled: false,    // "Uzaktan yönetim": poll the LAN proxy for commands from a paired phone
        manageTvId: '',          // this TV's id at the proxy (random, created once)
        manageSecret: '',        // shared secret between this TV and the proxy (random, created once)
        deviceName: ''           // name shown in the phone panel (default "LG webOS TV")
    };

    var data = NV.extend(NV.copy(DEFAULTS), NV.store.get('settings', {}) || {});

    NV.settings = {
        get: function (key) {
            return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : DEFAULTS[key];
        },
        set: function (key, value) {
            data[key] = value;
            NV.store.set('settings', data);
            NV.events.emit('settings', key);
        },

        // Last channel is stored separately so it survives a settings reset.
        lastChannel: function () { return NV.store.get('lastChannel', null); },
        setLastChannel: function (ch) {
            if (!ch) { NV.store.remove('lastChannel'); return; }
            NV.store.set('lastChannel', {
                key: ch.key, sourceId: ch.sourceId, name: ch.name, url: ch.url, logo: ch.logo || '',
                group: ch.group || '', tvgId: ch.tvgId || '', tvgName: ch.tvgName || '', number: ch.number || 0,
                sourceName: ch.sourceName || '', proxy: !!ch.proxy
            });
        },
        previousKey: function () { return NV.store.get('previousKey', null); },
        setPreviousKey: function (k) { NV.store.set('previousKey', k); }
    };
}(NV));
