/*
 * NovaTV webOS — channel repository (port of the Android ChannelRepository).
 * Sources, combined numbered channel list, custom order, favorites, recents and broken marks,
 * plus the edits made from the phone panel: hidden and renamed channels and "Kendi kanallarım"
 * (channels added one by one, kept in storage as rows of a custom source).
 * Channel keys do not contain the stream URL, so favorites/order survive token changes.
 * Events: 'channels' (new snapshot), 'lists' (favorites/recents/broken), 'sources' (refresh state).
 */
(function (NV) {
    'use strict';

    var S = NV.store;
    var MAX_RECENTS = 30;
    var MAX_PLAYLIST_CHARS = 25 * 1024 * 1024;

    var sources = [];
    var base = {};            // sourceId -> [channel]
    var order = [];           // custom order (channel keys)
    var favorites = [];
    var recents = [];
    var broken = {};
    var refreshing = {};      // sourceId -> true while loading
    var hidden = {};          // channel key -> true (hidden from the list)
    var renamed = {};         // channel key -> name shown instead of the playlist's
    var CUSTOM_NAME = 'Kendi kanallarım';
    var URL_RE = /^(https?|rtsp|udp):\/\/\S+$/i;
    var snapshot = makeSnapshot([], false);

    function makeSnapshot(channels, loaded, hiddenList) {
        var byKey = {}, groups = [], byGroup = {}, i, c;
        for (i = 0; i < channels.length; i++) {
            c = channels[i];
            byKey[c.key] = i;
            if (!byGroup[c.group]) {
                byGroup[c.group] = [];
                groups.push(c.group);
            }
            byGroup[c.group].push(c);
        }
        return {
            channels: channels,
            loaded: loaded,
            hidden: hiddenList || [],
            groups: groups,
            byGroup: byGroup,
            sources: sources,
            indexOf: function (key) { return key !== null && key !== undefined && byKey.hasOwnProperty(key) ? byKey[key] : -1; },
            byKey: function (key) { var ix = this.indexOf(key); return ix >= 0 ? channels[ix] : null; },
            byNumber: function (n) { return channels[n - 1] || null; },
            inGroup: function (g) { return byGroup[g] || []; },
            source: function (id) { return findSource(id); }
        };
    }

    function findSource(id) {
        var i;
        for (i = 0; i < sources.length; i++) if (sources[i].id === id) return sources[i];
        return null;
    }

    function upsert(src) {
        var i;
        for (i = 0; i < sources.length; i++) {
            if (sources[i].id === src.id) { sources[i] = src; return; }
        }
        sources.push(src);
    }

    function persistSources() { S.set('sources', sources); }

    function persistEdits() {
        if (!Object.keys(hidden).length && !Object.keys(renamed).length) S.remove('edits');
        else S.set('edits', { hidden: Object.keys(hidden), renamed: renamed });
    }

    function customSource() {
        var i;
        for (i = 0; i < sources.length; i++) if (sources[i].custom) return sources[i];
        return null;
    }

    /** "Kendi kanallarım" rows: [name, url, group, logo]. */
    function customRows() { return S.get('custom', []) || []; }

    function customParsed() {
        var rows = customRows(), entries = [], i;
        for (i = 0; i < rows.length; i++) entries.push({ name: rows[i][0], url: rows[i][1], group: rows[i][2] || '', logo: rows[i][3] || null, tvgId: null, tvgName: null });
        return { entries: entries, epgUrl: null, isHlsStream: false, skipped: 0 };
    }

    function attr(s, max) { return String(s || '').replace(/[\r\n"]+/g, ' ').replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '').substring(0, max); }

    /** Same rules as the Android CustomPlaylist.clean: null when unusable. */
    function cleanEntry(e) {
        var name = attr(e.name, 120), url = String(e.url || '').replace(/^\s+|\s+$/g, ''), logo = String(e.logo || '').replace(/^\s+|\s+$/g, '');
        if (!name || !URL_RE.test(url)) return null;
        if (!/^https?:\/\//i.test(logo)) logo = '';
        return [name, url, attr(e.group, 80), attr(logo, 500)];
    }

    /** Writes the custom rows and rebuilds that source's channels. */
    function writeCustom(src, rows) {
        if (!S.set('custom', rows)) return false;
        base[src.id] = toChannels(src, customParsed());
        src.channelCount = rows.length;
        src.lastUpdated = NV.now();
        src.lastError = '';
        upsert(src);
        persistSources();
        rebuild();
        return true;
    }

    function keyIndex(list, key) {
        var i;
        for (i = 0; list && i < list.length; i++) if (list[i].key === key) return i;
        return -1;
    }

    function sourceOfKey(key) {
        var id = String(key).split('|')[0], i;
        for (i = 0; i < sources.length; i++) if (String(sources[i].id) === id) return sources[i];
        return null;
    }

    /** A deleted channel leaves every list. */
    function forgetKey(key) {
        favorites = favorites.filter(function (k) { return k !== key; });
        recents = recents.filter(function (k) { return k !== key; });
        delete broken[key];
        persistLists();
        delete hidden[key];
        delete renamed[key];
        persistEdits();
        if (order.indexOf(key) >= 0) {
            order = order.filter(function (k) { return k !== key; });
            S.set('order', order);
        }
    }

    function rekey(oldKey, newKey) {
        function swap(list) { return list.map(function (k) { return k === oldKey ? newKey : k; }); }
        favorites = swap(favorites);
        recents = swap(recents);
        if (broken[oldKey]) { delete broken[oldKey]; broken[newKey] = true; }
        persistLists();
        if (renamed.hasOwnProperty(oldKey)) { renamed[newKey] = renamed[oldKey]; delete renamed[oldKey]; }
        if (hidden[oldKey]) { delete hidden[oldKey]; hidden[newKey] = true; }
        persistEdits();
        if (order.indexOf(oldKey) >= 0) { order = swap(order); S.set('order', order); }
    }

    function persistLists() {
        S.set('lists', { favorites: favorites, recents: recents, broken: Object.keys(broken) });
        NV.events.emit('lists');
    }

    function newId() {
        var max = 0, i;
        for (i = 0; i < sources.length; i++) if (typeof sources[i].id === 'number' && sources[i].id > max) max = sources[i].id;
        return Math.max(NV.now(), max + 1);
    }

    // ---------------------------------------------------------------- parsing / cache

    function toChannels(source, parsed) {
        var entries = parsed.entries, out = [], counts = {}, i, e, baseKey, n;
        if (parsed.isHlsStream && !source.builtin) {
            // The "playlist" is itself a single HLS stream: expose it as one channel.
            entries = [{ name: source.name, url: source.url, logo: null, group: '', tvgId: null, tvgName: null }];
        }
        for (i = 0; i < entries.length; i++) {
            e = entries[i];
            baseKey = source.id + '|' + NV.identity(e.name) + '|' + NV.identity(e.group);
            n = counts[baseKey] || 0;
            counts[baseKey] = n + 1;
            out.push({
                key: n ? baseKey + '#' + n : baseKey,
                sourceId: source.id,
                name: e.name,
                url: e.url,
                logo: e.logo || '',
                group: e.group || '',
                tvgId: e.tvgId || '',
                tvgName: e.tvgName || '',
                searchKey: NV.searchKey(e.name)
            });
        }
        return out;
    }

    function saveCache(source, parsed) {
        var rows = [], i, e;
        for (i = 0; i < parsed.entries.length; i++) {
            e = parsed.entries[i];
            rows.push([e.name, e.url, e.logo || '', e.group || '', e.tvgId || '', e.tvgName || '']);
        }
        // Large lists may not fit into localStorage (~5 MB); they are then re-downloaded on start.
        if (!S.set('ch.' + source.id, { hls: !!parsed.isHlsStream, e: rows })) S.remove('ch.' + source.id);
    }

    function loadCache(source) {
        var c = S.get('ch.' + source.id, null), entries = [], i, r;
        if (!c || !c.e) return null;
        for (i = 0; i < c.e.length; i++) {
            r = c.e[i];
            entries.push({ name: r[0], url: r[1], logo: r[2] || null, group: r[3], tvgId: r[4] || null, tvgName: r[5] || null });
        }
        return { entries: entries, epgUrl: null, isHlsStream: !!c.hls, skipped: 0 };
    }

    function fetchSource(source, cb) {
        var viaProxy;
        if (source.builtin) {
            cb(null, NV.m3u.parse(NV.BUILTIN.text));
            return;
        }
        if (source.custom) {
            cb(null, customParsed());
            return;
        }
        viaProxy = NV.http.useProxyFor(source) || (source.fetchViaProxy && NV.http.hasProxy());
        if (!viaProxy && /\.gz($|\?)/i.test(source.url)) {
            if (!NV.http.hasProxy()) {
                cb({ status: -4, message: 'Sıkıştırılmış (.gz) listeler için Ayarlar\'dan proxy tanımlayın.' });
                return;
            }
            viaProxy = true;
        }
        download(source, viaProxy, function (err, parsed) {
            // Blocked by CORS / old certificate store (status 0): retry once through the proxy and
            // remember it for this source (only the playlist download; playback stays direct).
            if (err && err.status === 0 && !viaProxy && NV.http.hasProxy()) {
                download(source, true, function (err2, parsed2) {
                    if (!err2) source.fetchViaProxy = true;
                    cb(err2, parsed2);
                });
                return;
            }
            cb(err, parsed);
        });
    }

    function download(source, viaProxy, cb) {
        var url = viaProxy ? NV.http.viaProxy(source.url, 'fetch') : source.url;
        NV.http.get(url, { timeout: 90000, maxLength: MAX_PLAYLIST_CHARS }, function (err, text) {
            if (err) { cb(err); return; }
            // Parsing a big list can take a while on a 2014 TV: yield once so the UI can paint.
            setTimeout(function () {
                var parsed;
                try { parsed = NV.m3u.parse(text); } catch (e) { cb({ status: -5, message: 'Liste okunamadı.' }); return; }
                cb(null, parsed);
            }, 30);
        });
    }

    // ---------------------------------------------------------------- snapshot

    function rebuild() {
        var enabled = [], names = {}, combined = [], hiddenList = [], i, j, s, list, idx, multi = {}, first = {}, numbered = [], c, shown;
        for (i = 0; i < sources.length; i++) {
            s = sources[i];
            if (!s.enabled) continue;
            enabled.push(s);
            names[s.id] = s.name;
            list = base[s.id];
            for (j = 0; list && j < list.length; j++) {
                c = list[j];
                shown = c;
                if (renamed.hasOwnProperty(c.key)) {
                    // A renamed channel keeps its playlist name as tvg-name so the guide still matches.
                    shown = NV.copy(c);
                    shown.name = renamed[c.key];
                    shown.searchKey = NV.searchKey(shown.name);
                    shown.tvgName = c.tvgName || c.name;
                }
                if (hidden[c.key]) hiddenList.push({ key: c.key, name: shown.name, group: c.group, sourceName: s.name });
                else combined.push(shown);
            }
        }
        if (order.length) {
            idx = {};
            for (i = 0; i < order.length; i++) idx[order[i]] = i;
            for (i = 0; i < combined.length; i++) combined[i]._o = i; // explicit tie-break: old engines sort unstably
            combined.sort(function (a, b) {
                var ia = idx.hasOwnProperty(a.key) ? idx[a.key] : 1e9, ib = idx.hasOwnProperty(b.key) ? idx[b.key] : 1e9;
                return ia !== ib ? ia - ib : a._o - b._o;
            });
        }
        if (enabled.length > 1) {
            for (i = 0; i < combined.length; i++) {
                c = combined[i];
                if (first.hasOwnProperty(c.searchKey) && first[c.searchKey] !== c.sourceId) multi[c.searchKey] = true;
                else if (!first.hasOwnProperty(c.searchKey)) first[c.searchKey] = c.sourceId;
            }
        }
        for (i = 0; i < combined.length; i++) {
            c = combined[i];
            numbered.push({
                key: c.key, sourceId: c.sourceId, name: c.name, url: c.url, logo: c.logo, group: c.group,
                tvgId: c.tvgId, tvgName: c.tvgName, searchKey: c.searchKey,
                number: i + 1,
                sourceName: names[c.sourceId] || '',
                multi: !!multi[c.searchKey],
                proxy: !!(findSource(c.sourceId) || {}).useProxy
            });
        }
        snapshot = makeSnapshot(numbered, true, hiddenList);
        NV.events.emit('channels', snapshot);
    }

    function setRefreshing(id, on) {
        if (on) refreshing[id] = true; else delete refreshing[id];
        NV.events.emit('sources');
    }

    function applyParsed(source, parsed) {
        var channels = toChannels(source, parsed);
        base[source.id] = channels;
        if (!source.builtin) saveCache(source, parsed);
        source.lastUpdated = NV.now();
        source.channelCount = channels.length;
        source.lastError = '';
        if (parsed.epgUrl) source.headerEpg = parsed.epgUrl;
        upsert(source);
        return channels.length;
    }

    // ---------------------------------------------------------------- public API

    NV.channels = {
        init: function () {
            var lists = S.get('lists', null) || {}, i, s, parsed;
            favorites = lists.favorites || [];
            recents = lists.recents || [];
            broken = {};
            for (i = 0; lists.broken && i < lists.broken.length; i++) broken[lists.broken[i]] = true;
            order = S.get('order', []) || [];
            sources = S.get('sources', []) || [];
            var edits = S.get('edits', null) || {};
            hidden = {};
            for (i = 0; edits.hidden && i < edits.hidden.length; i++) hidden[edits.hidden[i]] = true;
            renamed = edits.renamed || {};

            if (!sources.length && !NV.settings.get('builtinSeeded')) {
                NV.settings.set('builtinSeeded', true);
                sources.push(this.builtinDraft());
                persistSources();
            }
            for (i = 0; i < sources.length; i++) {
                s = sources[i];
                parsed = s.builtin ? NV.m3u.parse(NV.BUILTIN.text) : (s.custom ? customParsed() : loadCache(s));
                if (parsed) {
                    base[s.id] = toChannels(s, parsed);
                    if (s.builtin) s.channelCount = base[s.id].length;
                }
            }
            rebuild();
        },

        snapshot: function () { return snapshot; },
        sources: function () { return sources; },
        source: function (id) { return findSource(id); },
        isRefreshing: function (id) { return id === undefined ? Object.keys(refreshing).length > 0 : !!refreshing[id]; },
        hasCache: function (id) { return !!base[id]; },

        builtinDraft: function () {
            return {
                id: NV.BUILTIN.id, name: NV.BUILTIN.name, url: '', epgUrl: '', useProxy: false, enabled: true,
                lastUpdated: NV.now(), channelCount: 0, lastError: '', headerEpg: '', builtin: true
            };
        },

        hasBuiltin: function () { return !!findSource(NV.BUILTIN.id); },

        addBuiltin: function (cb) { this.importSource(this.builtinDraft(), cb); },

        /** Downloads and parses a new/edited source; persists it only if it has playable channels. */
        importSource: function (draft, cb) {
            var source = NV.copy(draft);
            if (!source.id) source.id = newId();
            if (source.enabled === undefined) source.enabled = true;
            setRefreshing(source.id, true);
            fetchSource(source, function (err, parsed) {
                var n;
                setRefreshing(source.id, false);
                if (err) { cb(err); return; }
                if (!parsed.entries.length && !parsed.isHlsStream) {
                    cb({ status: -6, message: 'Listede oynatılabilir kanal bulunamadı.' });
                    return;
                }
                n = applyParsed(source, parsed);
                persistSources();
                rebuild();
                cb(null, n);
            });
        },

        /** Saves a source without loading it ("save anyway"). */
        saveSourceOnly: function (draft, error) {
            var source = NV.copy(draft);
            if (!source.id) source.id = newId();
            if (source.enabled === undefined) source.enabled = true;
            source.lastError = error || '';
            upsert(source);
            persistSources();
            rebuild();
        },

        refreshSource: function (id, cb) {
            var source = findSource(id);
            if (!source) { cb({ status: -7, message: 'Kaynak bulunamadı.' }); return; }
            setRefreshing(id, true);
            fetchSource(source, function (err, parsed) {
                var n;
                setRefreshing(id, false);
                if (err) {
                    source.lastError = err.message;
                    persistSources();
                    NV.events.emit('sources');
                    cb(err);
                    return;
                }
                n = applyParsed(source, parsed);
                persistSources();
                rebuild();
                cb(null, n);
            });
        },

        /** Refreshes enabled sources one after another (gentle on a weak TV). */
        refreshAll: function (cb, onlyStale) {
            var list = [], hours = NV.settings.get('autoRefreshHours'), now = NV.now(), i, s, results = [], self = this;
            for (i = 0; i < sources.length; i++) {
                s = sources[i];
                if (!s.enabled || s.builtin || s.custom) continue;
                if (onlyStale && base[s.id] && !(hours > 0 && now - (s.lastUpdated || 0) > hours * 3600000)) continue;
                list.push(s);
            }
            (function next(k) {
                if (k >= list.length) { if (cb) cb(results); return; }
                self.refreshSource(list[k].id, function (err, n) {
                    results.push({ source: list[k], error: err, count: n });
                    setTimeout(function () { next(k + 1); }, 200);
                });
            }(0));
        },

        deleteSource: function (id) {
            var prefix = id + "|", k, src = findSource(id);
            sources = sources.filter(function (s) { return s.id !== id; });
            delete base[id];
            S.remove('ch.' + id);
            favorites = favorites.filter(function (key) { return !NV.startsWith(key, prefix); });
            recents = recents.filter(function (key) { return !NV.startsWith(key, prefix); });
            for (k in broken) if (broken.hasOwnProperty(k) && NV.startsWith(k, prefix)) delete broken[k];
            persistLists();
            for (k in hidden) if (hidden.hasOwnProperty(k) && NV.startsWith(k, prefix)) delete hidden[k];
            for (k in renamed) if (renamed.hasOwnProperty(k) && NV.startsWith(k, prefix)) delete renamed[k];
            persistEdits();
            if (src && src.custom) S.remove('custom');
            persistSources();
            rebuild();
        },

        setEnabled: function (id, enabled) {
            var s = findSource(id);
            if (!s) return;
            s.enabled = enabled;
            persistSources();
            rebuild();
        },

        moveSource: function (id, delta) {
            var i, j, item;
            for (i = 0; i < sources.length; i++) if (sources[i].id === id) break;
            j = i + delta;
            if (i >= sources.length || j < 0 || j >= sources.length) return;
            item = sources.splice(i, 1)[0];
            sources.splice(j, 0, item);
            persistSources();
            rebuild();
        },

        // ------------------------------------------------------------ user lists

        favorites: function () { return favorites; },
        isFavorite: function (key) { return favorites.indexOf(key) >= 0; },
        toggleFavorite: function (key) {
            var i = favorites.indexOf(key);
            if (i >= 0) favorites.splice(i, 1); else favorites.push(key);
            persistLists();
            return i < 0;
        },
        moveFavorite: function (key, delta) {
            var i = favorites.indexOf(key), j = i + delta;
            if (i < 0 || j < 0 || j >= favorites.length) return;
            favorites.splice(j, 0, favorites.splice(i, 1)[0]);
            persistLists();
        },

        recents: function () { return recents; },
        addRecent: function (key) {
            recents = [key].concat(recents.filter(function (k) { return k !== key; })).slice(0, MAX_RECENTS);
            persistLists();
        },
        removeRecent: function (key) {
            recents = recents.filter(function (k) { return k !== key; });
            persistLists();
        },
        clearRecents: function () { recents = []; persistLists(); },

        isBroken: function (key) { return broken.hasOwnProperty(key); },
        brokenCount: function () { return Object.keys(broken).length; },
        markBroken: function (key) { if (!broken[key]) { broken[key] = true; persistLists(); } },
        clearBroken: function (key) { if (broken[key]) { delete broken[key]; persistLists(); } },
        clearAllBroken: function () { broken = {}; persistLists(); },

        // ------------------------------------------------------------ ordering

        saveOrder: function (keys) {
            order = keys.slice();
            if (!order.length) S.remove('order');
            else if (!S.set('order', order)) NV.log('order could not be stored');
            rebuild();
        },
        moveToNumber: function (key, number) {
            var keys = snapshot.channels.map(function (c) { return c.key; }), i = keys.indexOf(key);
            if (i < 0) return;
            keys.splice(i, 1);
            keys.splice(Math.max(0, Math.min(number - 1, keys.length)), 0, key);
            this.saveOrder(keys);
        },
        resetOrder: function () { this.saveOrder([]); },
        orderKeys: function () { return order; },

        // ------------------------------------------------------------ edits (phone panel)

        hiddenKeys: function () { return Object.keys(hidden); },
        renamedChannels: function () { return renamed; },
        isCustomKey: function (key) { var s = sourceOfKey(key); return !!(s && s.custom); },
        customSource: function () { return customSource(); },

        /** Entries of "Kendi kanallarım" with their original names: [{name, url, group, logo}]. */
        customEntries: function () {
            return customRows().map(function (r) { return { name: r[0], url: r[1], group: r[2] || '', logo: r[3] || '' }; });
        },

        /** Adds a channel to "Kendi kanallarım" (created on first use). cb(err, key). */
        addCustom: function (entry, cb) {
            var row = cleanEntry(entry), src = customSource(), rows;
            if (!row) { cb({ message: 'Kanal adı ve geçerli bir yayın adresi (http, https, rtsp veya udp) gerekli.' }); return; }
            if (!src) {
                src = { id: newId(), name: CUSTOM_NAME, url: '', epgUrl: '', useProxy: false, enabled: true, lastUpdated: NV.now(), channelCount: 0, lastError: '', headerEpg: '', custom: true };
            }
            rows = customRows();
            rows.push(row);
            if (!writeCustom(src, rows)) { cb({ message: 'TV hafızası dolu; kanal kaydedilemedi.' }); return; }
            cb(null, base[src.id][base[src.id].length - 1].key);
        },

        /** Edits a channel of "Kendi kanallarım"; favorites/order follow it if its key changes. cb(err, newKey). */
        updateCustom: function (key, entry, cb) {
            var src = sourceOfKey(key), row = cleanEntry(entry), i, rows, newKey;
            if (!src || !src.custom || (i = keyIndex(base[src.id], key)) < 0) { cb({ message: 'Kanal bulunamadı.' }); return; }
            if (!row) { cb({ message: 'Kanal adı ve geçerli bir yayın adresi (http, https, rtsp veya udp) gerekli.' }); return; }
            rows = customRows();
            rows[i] = row;
            if (!writeCustom(src, rows)) { cb({ message: 'TV hafızası dolu; kanal kaydedilemedi.' }); return; }
            newKey = base[src.id][i].key;
            if (newKey !== key) { rekey(key, newKey); rebuild(); }
            cb(null, newKey);
        },

        /** Own channels are deleted, playlist channels hidden. Returns true when deleted, false when hidden, null if unknown. */
        deleteChannel: function (key) {
            var src = sourceOfKey(key), i, rows;
            if (!src) return null;
            if (src.custom) {
                i = keyIndex(base[src.id], key);
                if (i < 0) return null;
                rows = customRows();
                rows.splice(i, 1);
                forgetKey(key);
                writeCustom(src, rows);
                return true;
            }
            hidden[key] = true;
            persistEdits();
            rebuild();
            return false;
        },

        hideChannels: function (keys) {
            var i;
            for (i = 0; i < keys.length; i++) hidden[keys[i]] = true;
            persistEdits();
            rebuild();
        },

        restoreChannel: function (key) { delete hidden[key]; persistEdits(); rebuild(); },
        restoreAllHidden: function () { hidden = {}; persistEdits(); rebuild(); },

        /** names: {key: name}; an empty name restores the playlist's name. */
        renameChannels: function (names, overwrite) {
            var k, n;
            for (k in names) {
                if (!names.hasOwnProperty(k)) continue;
                if (overwrite === false && renamed.hasOwnProperty(k)) continue;
                n = attr(names[k], 120);
                if (n) renamed[k] = n; else delete renamed[k];
            }
            persistEdits();
            rebuild();
        },

        setFavorite: function (key, on) { if (this.isFavorite(key) !== !!on) this.toggleFavorite(key); }
    };
}(NV));
