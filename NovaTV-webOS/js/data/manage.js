/*
 * NovaTV webOS — remote management from a phone (same operations and JSON as the Android TV app,
 * so the one panel proxy/manage.html manages both).
 *
 * A webOS 1.x web app cannot accept connections, so the NovaTV LAN proxy relays: when
 * "Uzaktan yönetim" is on, the TV asks the proxy every few seconds for queued commands
 * (POST /api/tv/poll), runs them and posts the result and its new state (POST /api/tv/result).
 * The phone pairs through the proxy with the PIN shown on the TV's "Telefondan yönet" screen.
 */
(function (NV) {
    'use strict';

    var FORMAT = 'novatv-config';
    var IDLE_POLL_MS = 3000, ERROR_POLL_MS = 15000;

    function isHttp(s) { return /^https?:\/\//i.test(String(s || '')); }
    function trim(s) { return String(s === undefined || s === null ? '' : s).replace(/^\s+|\s+$/g, ''); }

    function kind(s) { return s.builtin ? 'builtin' : (s.custom ? 'custom' : (s.url ? 'url' : 'file')); }

    function findSource(id) {
        var list = NV.channels.sources(), i;
        for (i = 0; i < list.length; i++) if (String(list[i].id) === String(id)) return list[i];
        return null;
    }

    function channelName(key) {
        var snap = NV.channels.snapshot(), c = snap.byKey(key), i;
        if (c) return c.name;
        for (i = 0; i < snap.hidden.length; i++) if (snap.hidden[i].key === key) return snap.hidden[i].name;
        return String(key).split('|')[1] || key;
    }

    /** Portable source reference (see the Android ConfigSync): "u:<url>", "b" or "c"; null for other sources. */
    function sourceRef(s) {
        if (s.builtin) return 'b';
        if (s.custom) return 'c';
        return s.url ? 'u:' + s.url : null;
    }

    function refMaps() {
        var list = NV.channels.sources(), byId = {}, byRef = {}, i, r;
        for (i = 0; i < list.length; i++) {
            r = sourceRef(list[i]);
            if (r === null) continue;
            byId[String(list[i].id)] = r;
            if (!byRef.hasOwnProperty(r)) byRef[r] = list[i].id;
        }
        return { byId: byId, byRef: byRef };
    }

    function toRef(key, byId) {
        var bar = String(key).indexOf('|'), id;
        if (bar <= 0) return null;
        id = key.substring(0, bar);
        return byId.hasOwnProperty(id) ? [byId[id], key.substring(bar)] : null;
    }

    function toKey(ref, byRef) {
        return ref && byRef.hasOwnProperty(ref[0]) ? byRef[ref[0]] + ref[1] : null;
    }

    function ok(message, extra) {
        var r = NV.extend({ ok: true, message: message }, extra || {});
        NV.events.emit('manage', 'Telefondan: ' + message);
        return r;
    }

    function fail(message) { return { ok: false, error: message }; }

    NV.manage = {
        deviceName: function () { return NV.settings.get('deviceName') || 'LG webOS TV'; },

        state: function () {
            var snap = NV.channels.snapshot(), sources = [], channels = [], hidden = [], favs = {}, renamed = NV.channels.renamedChannels(), i, s, c, o, src;
            var list = NV.channels.sources(), fl = NV.channels.favorites();
            for (i = 0; i < fl.length; i++) favs[fl[i]] = true;
            for (i = 0; i < list.length; i++) {
                s = list[i];
                sources.push({
                    id: String(s.id), name: s.name, kind: kind(s), url: s.url || '', epgUrl: s.epgUrl || '', enabled: !!s.enabled,
                    channelCount: s.channelCount || 0, lastUpdated: s.lastUpdated || 0, error: s.lastError || '', refreshing: NV.channels.isRefreshing(s.id)
                });
            }
            for (i = 0; i < snap.channels.length; i++) {
                c = snap.channels[i];
                o = { key: c.key, number: c.number, name: c.name, group: c.group, sourceId: String(c.sourceId), sourceName: c.sourceName, favorite: !!favs[c.key] };
                if (renamed.hasOwnProperty(c.key)) o.renamed = true;
                src = findSource(c.sourceId);
                if (src && src.custom) { o.custom = true; o.url = c.url; o.logo = c.logo || ''; }
                channels.push(o);
            }
            for (i = 0; i < snap.hidden.length; i++) hidden.push(snap.hidden[i]);
            return {
                device: { name: this.deviceName(), platform: 'webos', version: NV.VERSION },
                sources: sources, channels: channels, hidden: hidden
            };
        },

        /** Runs one panel operation; cb(result) with {ok, message | error, ...}. */
        exec: function (op, a, cb) {
            var self = this, s, key, n, r;
            a = a || {};
            try {
                switch (op) {
                case 'source.add':
                    if (a.m3u) { cb(fail('Dosyalar LG TV\'ye proxy üzerinden gönderilir; paneli proxy adresinden açın.')); return; }
                    if (!isHttp(a.url)) { cb(fail('Geçerli bir http(s) adresi gönderin.')); return; }
                    if (NV.channels.sources().some(function (x) { return x.url === trim(a.url); })) { cb(fail('Bu liste zaten ekli.')); return; }
                    n = trim(a.name) || trim(a.url).replace(/^https?:\/\//i, '').split('/')[0];
                    NV.channels.importSource({ name: n.substring(0, 120), url: trim(a.url), epgUrl: isHttp(a.epgUrl) ? trim(a.epgUrl) : '', enabled: true }, function (err, count) {
                        cb(err ? fail(err.message) : ok('Kaynak eklendi: ' + n + ' (' + count + ' kanal)', { count: count }));
                    });
                    return;
                case 'source.update':
                    s = findSource(a.id);
                    if (!s) { cb(fail('Kaynak bulunamadı.')); return; }
                    if (s.builtin || s.custom) { cb(fail('Bu kaynak düzenlenemez; açıp kapatabilir veya silebilirsiniz.')); return; }
                    r = NV.copy(s);
                    r.name = (trim(a.name) || s.name).substring(0, 120);
                    if (a.epgUrl !== undefined) {
                        if (trim(a.epgUrl) && !isHttp(a.epgUrl)) { cb(fail('EPG adresi http(s) ile başlamalı.')); return; }
                        r.epgUrl = trim(a.epgUrl);
                    }
                    if (a.url !== undefined && trim(a.url) !== s.url) {
                        if (!isHttp(a.url)) { cb(fail('Geçerli bir http(s) adresi girin.')); return; }
                        r.url = trim(a.url);
                        r.fetchViaProxy = false;
                        NV.channels.importSource(r, function (err, count) { cb(err ? fail(err.message) : ok('Kaynak güncellendi: ' + r.name + ' (' + count + ' kanal)')); });
                        return;
                    }
                    NV.channels.saveSourceOnly(r, s.lastError);
                    cb(ok('Kaynak güncellendi: ' + r.name));
                    return;
                case 'source.delete':
                    s = findSource(a.id);
                    if (!s) { cb(fail('Kaynak bulunamadı.')); return; }
                    NV.channels.deleteSource(s.id);
                    cb(ok('Kaynak silindi: ' + s.name));
                    return;
                case 'source.refresh':
                    s = findSource(a.id);
                    if (!s) { cb(fail('Kaynak bulunamadı.')); return; }
                    NV.channels.refreshSource(s.id, function (err, count) { cb(err ? fail(err.message) : ok(s.name + ' yenilendi (' + count + ' kanal)')); });
                    return;
                case 'source.enable':
                    s = findSource(a.id);
                    if (!s) { cb(fail('Kaynak bulunamadı.')); return; }
                    NV.channels.setEnabled(s.id, a.enabled !== false);
                    cb(ok(s.name + (a.enabled !== false ? ' açıldı' : ' kapatıldı')));
                    return;
                case 'channel.add':
                    NV.channels.addCustom(a, function (err, k) { cb(err ? fail(err.message) : ok('Kanal eklendi: ' + trim(a.name), { key: k })); });
                    return;
                case 'channel.update':
                    key = String(a.key || '');
                    if (NV.channels.isCustomKey(key) && a.url !== undefined) {
                        NV.channels.updateCustom(key, a, function (err, k) { cb(err ? fail(err.message) : ok('Kanal güncellendi: ' + trim(a.name), { key: k })); });
                        return;
                    }
                    r = {};
                    r[key] = trim(a.name);
                    NV.channels.renameChannels(r, true);
                    cb(ok(trim(a.name) ? 'Kanal adı değişti: ' + trim(a.name) : 'Kanal adı sıfırlandı'));
                    return;
                case 'channel.delete':
                    key = String(a.key || '');
                    n = channelName(key);
                    r = NV.channels.deleteChannel(key);
                    cb(r === null ? fail('Kanal bulunamadı.') : ok((r ? 'Kanal silindi: ' : 'Kanal gizlendi: ') + n));
                    return;
                case 'channel.restore':
                    key = String(a.key || '');
                    NV.channels.restoreChannel(key);
                    cb(ok('Kanal geri getirildi: ' + channelName(key)));
                    return;
                case 'channel.favorite':
                    key = String(a.key || '');
                    NV.channels.setFavorite(key, a.on !== false);
                    cb(ok((a.on !== false ? 'Favorilere eklendi: ' : 'Favorilerden çıkarıldı: ') + channelName(key)));
                    return;
                case 'channel.number':
                    key = String(a.key || '');
                    n = parseInt(a.number, 10);
                    if (!(n >= 1)) { cb(fail('Geçerli bir kanal numarası girin.')); return; }
                    NV.channels.moveToNumber(key, n);
                    cb(ok(channelName(key) + ' → ' + n + '. kanal'));
                    return;
                case 'order.reset':
                    NV.channels.resetOrder();
                    cb(ok('Kanal sırası sıfırlandı'));
                    return;
                case 'config.export':
                    cb({ ok: true, config: self.exportConfig() });
                    return;
                case 'config.import':
                    self.importConfig(a.config, cb);
                    return;
                default:
                    cb(fail('Bilinmeyen işlem: ' + op));
                }
            } catch (e) {
                cb(fail(e && e.message ? e.message : String(e)));
            }
        },

        /** This TV's lists in the device-independent format shared with Android TV. */
        exportConfig: function () {
            var maps = refMaps(), list = NV.channels.sources(), sources = [], custom = NV.channels.customEntries(), renamed = [], names = NV.channels.renamedChannels(), i, r, k;
            function refs(keys) {
                var out = [], j, x;
                for (j = 0; j < keys.length; j++) { x = toRef(keys[j], maps.byId); if (x) out.push(x); }
                return out;
            }
            for (i = 0; i < list.length; i++) {
                r = sourceRef(list[i]);
                if (r === null || r === 'c') continue;
                sources.push({ ref: r, name: list[i].name, url: list[i].url || '', epgUrl: list[i].epgUrl || '', enabled: !!list[i].enabled });
            }
            for (k in names) {
                if (!names.hasOwnProperty(k)) continue;
                r = toRef(k, maps.byId);
                if (r) renamed.push([r[0], r[1], names[k]]);
            }
            return {
                format: FORMAT, version: 1, device: this.deviceName(), sources: sources, custom: custom,
                favorites: refs(NV.channels.favorites()), hidden: refs(NV.channels.hiddenKeys()), renamed: renamed, order: refs(NV.channels.orderKeys())
            };
        },

        /**
         * Adds another TV's lists to this one (nothing here is deleted). Sources are downloaded
         * one after another; favorites, hidden channels and names are merged; the order is taken
         * over only when this TV has no custom order.
         */
        importConfig: function (c, cb) {
            var srcs, added = 0, failed = [];
            if (!c || c.format !== FORMAT) { cb(fail('Bu bir NovaTV listesi değil.')); return; }
            srcs = c.sources || [];
            (function next(i) {
                var o, url;
                if (i >= srcs.length) { finish(); return; }
                o = srcs[i];
                if (o.ref === 'b') {
                    if (NV.channels.hasBuiltin()) { next(i + 1); return; }
                    NV.channels.addBuiltin(function (err) { if (err) failed.push(o.name); else added++; next(i + 1); });
                    return;
                }
                url = String(o.ref || '').indexOf('u:') === 0 ? o.ref.substring(2) : '';
                if (!isHttp(url) || NV.channels.sources().some(function (x) { return x.url === url; })) { next(i + 1); return; }
                NV.channels.importSource({ name: o.name || url, url: url, epgUrl: o.epgUrl || '', enabled: true }, function (err) {
                    if (err) failed.push((o.name || url) + ': ' + err.message); else added++;
                    setTimeout(function () { next(i + 1); }, 50);
                });
            }(0));

            function finish() {
                var custom = c.custom || [], existing = {}, have = NV.channels.customEntries(), customAdded = 0, maps, favs = [], hide = [], names = {}, nNames = 0, order = [], orderTaken, i, k, parts;
                for (i = 0; i < have.length; i++) existing[have[i].url + '\n' + have[i].name] = true;
                (function addCustom(j) {
                    if (j >= custom.length) { afterCustom(); return; }
                    if (existing[trim(custom[j].url) + '\n' + trim(custom[j].name)]) { addCustom(j + 1); return; }
                    NV.channels.addCustom(custom[j], function (err) { if (!err) customAdded++; addCustom(j + 1); });
                }(0));

                function afterCustom() {
                    maps = refMaps();
                    for (i = 0; c.favorites && i < c.favorites.length; i++) {
                        k = toKey(c.favorites[i], maps.byRef);
                        if (k && !NV.channels.isFavorite(k)) { NV.channels.setFavorite(k, true); favs.push(k); }
                    }
                    for (i = 0; c.hidden && i < c.hidden.length; i++) {
                        k = toKey(c.hidden[i], maps.byRef);
                        if (k && NV.channels.hiddenKeys().indexOf(k) < 0) hide.push(k);
                    }
                    if (hide.length) NV.channels.hideChannels(hide);
                    for (i = 0; c.renamed && i < c.renamed.length; i++) {
                        k = toKey(c.renamed[i], maps.byRef);
                        if (k && !NV.channels.renamedChannels().hasOwnProperty(k)) { names[k] = c.renamed[i][2]; nNames++; }
                    }
                    if (nNames) NV.channels.renameChannels(names, false);
                    for (i = 0; c.order && i < c.order.length; i++) { k = toKey(c.order[i], maps.byRef); if (k) order.push(k); }
                    orderTaken = order.length > 0 && !NV.channels.orderKeys().length;
                    if (orderTaken) NV.channels.saveOrder(order);
                    parts = [added + ' kaynak'];
                    if (customAdded) parts.push(customAdded + ' kanal');
                    if (favs.length) parts.push(favs.length + ' favori');
                    if (hide.length) parts.push(hide.length + ' gizleme');
                    if (nNames) parts.push(nNames + ' kanal adı');
                    if (orderTaken) parts.push('kanal sırası');
                    cb(ok('Eşitlendi: ' + parts.join(', ') + ' alındı' + (failed.length ? '. Eklenemeyen: ' + failed.join('; ') : ''), { failed: failed }));
                }
            }
        }
    };

    // ---------------------------------------------------------------- proxy relay (TV side)

    function proxyBase() { return String(NV.settings.get('proxyUrl') || '').replace(/\/+$/, ''); }

    function proxyApi(path) {
        var base = proxyBase(), token = NV.settings.get('proxyToken');
        if (!base) return null;
        return base + path + (token ? '?t=' + encodeURIComponent(token) : '');
    }

    function randomHex(n) {
        var out = '', bytes, i;
        try {
            bytes = new Uint8Array(n);
            window.crypto.getRandomValues(bytes);
            for (i = 0; i < n; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
            return out;
        } catch (e) {
            for (i = 0; i < n * 2; i++) out += Math.floor(Math.random() * 16).toString(16);
            return out;
        }
    }

    NV.manageRelay = (function () {
        var running = false, held = false, timer = null, busy = false, dirty = true, pin = '', lastError = '', connected = false;

        function identity() {
            var id = NV.settings.get('manageTvId'), secret = NV.settings.get('manageSecret');
            if (!id) { id = randomHex(8); NV.settings.set('manageTvId', id); }
            if (!secret) { secret = randomHex(16); NV.settings.set('manageSecret', secret); }
            return { tv: id, secret: secret };
        }

        function schedule(ms) {
            if (timer) clearTimeout(timer);
            timer = running ? setTimeout(poll, ms) : null;
        }

        function setConnected(on, err) {
            var changed = on !== connected || (err || '') !== lastError;
            connected = on;
            lastError = err || '';
            if (changed) NV.events.emit('manage-status');
        }

        function poll() {
            var url = proxyApi('/api/tv/poll'), id, body;
            timer = null;
            if (!running || busy) return;
            if (!url) { setConnected(false, 'Proxy adresi yok'); schedule(ERROR_POLL_MS); return; }
            id = identity();
            body = { tv: id.tv, secret: id.secret, name: NV.manage.deviceName(), version: NV.VERSION, pin: pin };
            if (dirty) { body.state = NV.manage.state(); dirty = false; }
            busy = true;
            NV.http.postJson(url, body, { timeout: 10000 }, function (err, data) {
                busy = false;
                if (!running) return;
                if (err || !data || !data.ok) {
                    dirty = dirty || !!body.state;
                    setConnected(false, err ? err.message : (data && data.error) || 'Proxy yanıt vermedi');
                    schedule(ERROR_POLL_MS);
                    return;
                }
                setConnected(true);
                if (data.needState) dirty = true;
                if (data.cmds && data.cmds.length) run(data.cmds, 0, id);
                else schedule(dirty ? 300 : IDLE_POLL_MS);
            });
        }

        function run(cmds, k, id) {
            var cmd = cmds[k];
            if (!running) return;
            if (k >= cmds.length) { schedule(300); return; }
            busy = true;
            NV.manage.exec(cmd.op, cmd.args, function (result) {
                var url = proxyApi('/api/tv/result');
                dirty = false;
                NV.http.postJson(url, { tv: id.tv, secret: id.secret, id: cmd.id, result: result, state: NV.manage.state() }, { timeout: 15000 }, function () {
                    busy = false;
                    run(cmds, k + 1, id);
                });
            });
        }

        NV.events.on('channels', function () { dirty = true; });
        NV.events.on('lists', function () { dirty = true; });
        NV.events.on('sources', function () { dirty = true; });

        return {
            /** Follows the setting; called on start and whenever it changes. */
            update: function () {
                var want = (!!NV.settings.get('manageEnabled') || held) && !!proxyBase();
                if (want && !running) { running = true; dirty = true; schedule(500); }
                else if (!want && running) { running = false; if (timer) clearTimeout(timer); timer = null; setConnected(false); }
                return running;
            },
            isRunning: function () { return running; },
            isConnected: function () { return connected; },
            lastError: function () { return lastError; },

            /** Shows a 4-digit PIN on the TV; the proxy accepts it for pairing a phone while it is set. */
            openPairing: function () {
                var b = '', i, bytes;
                for (i = 0; i < 4; i++) b += Math.floor(Math.random() * 10);
                try {
                    bytes = new Uint8Array(2);
                    window.crypto.getRandomValues(bytes);
                    b = String(10000 + ((bytes[0] << 8) | bytes[1]) % 10000).substring(1);
                } catch (e) { /* Math.random fallback above */ }
                pin = b;
                dirty = true;
                held = true;
                this.update();
                if (running) schedule(0);
                return pin;
            },
            /** Clears the PIN; the relay keeps running only if "Uzaktan yönetim" is on. */
            closePairing: function () {
                pin = '';
                held = false;
                if (running) schedule(0);
                this.update();
            },
            panelUrl: function () { return proxyBase() + '/manage?tv=' + identity().tv; },
            identity: identity
        };
    }());
}(NV));
