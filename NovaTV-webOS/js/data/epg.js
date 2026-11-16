/*
 * NovaTV webOS — XMLTV EPG (port of the Android EpgRepository).
 * A full DOM of a big XMLTV file would not fit into a 2014 TV's memory, so the text is scanned
 * with regular expressions in small time slices, keeping only channels from the playlist and a
 * ~1.5 day window. Compressed (.gz) guides and very large files need the optional proxy, which
 * also filters the guide server-side (/epg endpoint).
 */
(function (NV) {
    'use strict';

    var KEEP_PAST = 2 * 3600000, KEEP_FUTURE = 36 * 3600000, MAX_AGE = 12 * 3600000;
    var MAX_DIRECT_CHARS = 12 * 1024 * 1024;

    var data = { ch: {}, names: {}, updatedAt: 0 };
    var resolved = {};
    var status = { state: 'idle', message: '' };
    var running = false;

    function setStatus(state, message) {
        status = { state: state, message: message || '' };
        NV.events.emit('epg-status', status);
    }

    // ---------------------------------------------------------------- time & text

    function daysFromCivil(y, m, d) {
        var yy = m <= 2 ? y - 1 : y, era = Math.floor((yy >= 0 ? yy : yy - 399) / 400), yoe = yy - era * 400;
        var doy = Math.floor((153 * ((m + 9) % 12) + 2) / 5) + d - 1;
        var doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
        return era * 146097 + doe - 719468;
    }

    /** "yyyyMMddHHmmss +hhmm" (seconds/offset optional) -> epoch ms, or -1. */
    function parseTime(s) {
        var t, m, y, mo, d, h, mi, sec, off = 0, tz;
        if (!s) return -1;
        t = String(s).replace(/^\s+|\s+$/g, '');
        m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?/.exec(t);
        if (!m) return -1;
        y = +m[1]; mo = +m[2]; d = +m[3]; h = +m[4]; mi = +m[5]; sec = m[6] ? +m[6] : 0;
        if (mo < 1 || mo > 12 || d < 1 || d > 31) return -1;
        tz = /([+\-])(\d{2})(\d{2})/.exec(t.substring(12));
        if (tz) off = (tz[1] === '-' ? -1 : 1) * (+tz[2] * 60 + +tz[3]);
        return ((daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + sec) - off * 60) * 1000;
    }

    var ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
    function decodeXml(s) {
        return String(s).replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1').replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, function (all, e) {
            if (e.charAt(0) === '#') {
                var code = e.charAt(1).toLowerCase() === 'x' ? parseInt(e.substring(2), 16) : parseInt(e.substring(1), 10);
                return isNaN(code) ? all : String.fromCharCode(code);
            }
            return ENT.hasOwnProperty(e) ? ENT[e] : all;
        }).replace(/^\s+|\s+$/g, '');
    }

    var ATTR_RE = {};
    function attr(attrs, name) {
        var re = ATTR_RE[name], m;
        if (!re) re = ATTR_RE[name] = [new RegExp('\\b' + name + '\\s*=\\s*"([^"]*)"'), new RegExp('\\b' + name + "\\s*=\\s*'([^']*)'")];
        m = re[0].exec(attrs) || re[1].exec(attrs);
        return m ? m[1] : null;
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Incremental XMLTV scan. wanted = { ids: {lowercaseId: 1}, names: {epgKey: 1} }.
     * Calls done(result) with { ch: {id: [[start, stop, title, desc]]}, names: {key: id} }.
     */
    function parseXmltv(text, wanted, done) {
        var now = NV.now(), from = now - KEEP_PAST, to = now + KEEP_FUTURE;
        var keep = {}, sawChannels = false, out = { ch: {}, names: {} };
        var chRe = /<channel\b([^>]*)>([\s\S]*?)<\/channel>/g;
        var prRe = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
        var nameRe = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g;

        (function channelsStep() {
            var m, id, nm, keys, i, ok, budget = 400;
            while (budget-- > 0 && (m = chRe.exec(text)) !== null) {
                sawChannels = true;
                id = (attr(m[1], 'id') || '').toLowerCase();
                if (!id) continue;
                keys = [];
                nameRe.lastIndex = 0;
                while ((nm = nameRe.exec(m[2])) !== null) keys.push(NV.epgKey(decodeXml(nm[1])));
                ok = wanted.ids.hasOwnProperty(id) || wanted.names.hasOwnProperty(NV.epgKey(id));
                for (i = 0; !ok && i < keys.length; i++) if (keys[i] && wanted.names.hasOwnProperty(keys[i])) ok = true;
                if (!ok) continue;
                keep[id] = true;
                for (i = 0; i < keys.length; i++) if (keys[i] && !out.names.hasOwnProperty(keys[i])) out.names[keys[i]] = id;
                if (!out.names.hasOwnProperty(NV.epgKey(id))) out.names[NV.epgKey(id)] = id;
            }
            if (m !== null) { setTimeout(channelsStep, 0); return; }
            programmesStep();
        }());

        function programmesStep() {
            var m, ch, start, stop, title, desc, t, budget = 300, list;
            while (budget-- > 0 && (m = prRe.exec(text)) !== null) {
                ch = (attr(m[1], 'channel') || '').toLowerCase();
                if (!(keep.hasOwnProperty(ch) || (!sawChannels && wanted.ids.hasOwnProperty(ch)))) continue;
                start = parseTime(attr(m[1], 'start'));
                stop = parseTime(attr(m[1], 'stop'));
                if (stop <= 0) stop = start + 3600000;
                if (start <= 0 || stop < from || start > to) continue;
                t = /<title[^>]*>([\s\S]*?)<\/title>/.exec(m[2]);
                title = t ? decodeXml(t[1]) : '';
                t = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]);
                desc = t ? decodeXml(t[1]) : '';
                if (desc.length > 300) desc = desc.substring(0, 300) + '…';
                list = out.ch[ch] || (out.ch[ch] = []);
                list.push([start, stop, title, desc]);
            }
            if (m !== null) { setTimeout(programmesStep, 0); return; }
            done(out);
        }
    }

    // ---------------------------------------------------------------- data management

    function urls() {
        var out = [], seen = {}, list = [], i, s, parts, j, u;
        list.push(NV.settings.get('epgUrl') || '');
        for (i = 0; i < NV.channels.sources().length; i++) {
            s = NV.channels.sources()[i];
            if (s.enabled) list.push(s.epgUrl || s.headerEpg || '');
        }
        for (i = 0; i < list.length; i++) {
            parts = String(list[i]).split(/[,\s]+/);
            for (j = 0; j < parts.length; j++) {
                u = parts[j];
                if (/^https?:\/\//i.test(u) && !seen[u]) { seen[u] = true; out.push(u); }
            }
        }
        return out;
    }

    function wantedFrom(channels) {
        var w = { ids: {}, names: {} }, i, c;
        for (i = 0; i < channels.length; i++) {
            c = channels[i];
            if (c.tvgId) { w.ids[c.tvgId.toLowerCase()] = 1; w.names[NV.epgKey(c.tvgId)] = 1; }
            if (c.tvgName) w.names[NV.epgKey(c.tvgName)] = 1;
            w.names[NV.epgKey(c.name)] = 1;
        }
        return w;
    }

    function signature(channels) {
        var h = 17, i, s;
        for (i = 0; i < channels.length; i++) {
            s = channels[i].tvgId || channels[i].name;
            h = (h * 31 + s.length + s.charCodeAt(0)) | 0;
        }
        return urls().join(',') + '#' + channels.length + '#' + h;
    }

    function publish(newData, persist) {
        var k;
        data = newData;
        resolved = {};
        for (k in data.ch) if (data.ch.hasOwnProperty(k)) data.ch[k].sort(function (a, b) { return a[0] - b[0]; });
        if (persist && !NV.store.set('epg', data)) NV.log('EPG too large to cache; kept in memory only');
        NV.events.emit('epg');
    }

    function resolveId(ch) {
        var id = null, list, i, key;
        if (resolved.hasOwnProperty(ch.key)) return resolved[ch.key];
        if (ch.tvgId && data.ch.hasOwnProperty(ch.tvgId.toLowerCase())) id = ch.tvgId.toLowerCase();
        if (!id) {
            list = [ch.tvgId, ch.tvgName, ch.name];
            for (i = 0; i < list.length && !id; i++) {
                if (!list[i]) continue;
                key = NV.epgKey(list[i]);
                if (data.names.hasOwnProperty(key) && data.ch.hasOwnProperty(data.names[key])) id = data.names[key];
            }
        }
        resolved[ch.key] = id;
        return id;
    }

    function download(url, wanted, cb) {
        var target, ids;
        if (NV.http.hasProxy()) {
            ids = Object.keys(wanted.ids).slice(0, 400).join(',');
            target = NV.http.viaProxy(url, 'epg', 'hours=36&ids=' + encodeURIComponent(ids));
        } else if (/\.gz($|\?)/i.test(url)) {
            cb({ message: 'Sıkıştırılmış (.gz) EPG için Ayarlar\'dan proxy tanımlayın.' });
            return;
        } else {
            target = url;
        }
        NV.http.get(target, { timeout: 120000, maxLength: MAX_DIRECT_CHARS }, function (err, text) {
            if (err) { cb(err); return; }
            parseXmltv(text, wanted, function (res) { cb(null, res); });
        });
    }

    NV.epg = {
        init: function () {
            var cached = NV.store.get('epg', null), now = NV.now(), k, list, kept;
            if (cached && cached.ch) {
                // Drop programmes that already ended.
                for (k in cached.ch) {
                    if (!cached.ch.hasOwnProperty(k)) continue;
                    list = cached.ch[k];
                    kept = list.filter(function (p) { return p[1] > now - KEEP_PAST; });
                    if (kept.length) cached.ch[k] = kept; else delete cached.ch[k];
                }
                publish(cached, false);
            }
        },

        urls: urls,
        status: function () { return status; },
        updatedAt: function () { return data.updatedAt || 0; },
        channelCount: function () { return Object.keys(data.ch).length; },

        refreshIfNeeded: function () {
            var channels = NV.channels.snapshot().channels, age = NV.now() - (NV.store.get('epgMeta', {}).at || 0);
            if (!urls().length || !channels.length) return;
            if (NV.store.get('epgMeta', {}).sig === signature(channels) && age < MAX_AGE && data.updatedAt) return;
            this.refresh(function () {});
        },

        refresh: function (cb) {
            var list = urls(), channels = NV.channels.snapshot().channels, wanted, merged, errors = [], ok = 0;
            if (running) { cb({ message: 'EPG zaten güncelleniyor.' }); return; }
            if (!list.length) { setStatus('idle'); cb(null, 0); return; }
            running = true;
            setStatus('loading');
            wanted = wantedFrom(channels);
            merged = { ch: {}, names: {}, updatedAt: NV.now() };
            (function next(i) {
                var k, n;
                if (i >= list.length) {
                    running = false;
                    NV.store.set('epgMeta', { at: NV.now(), sig: signature(channels) });
                    if (!ok) {
                        setStatus('error', errors.join(' • '));
                        cb({ message: errors.join(' • ') });
                        return;
                    }
                    publish(merged, true);
                    n = Object.keys(merged.ch).length;
                    setStatus(errors.length ? 'error' : 'idle', errors.join(' • '));
                    cb(null, n);
                    return;
                }
                download(list[i], wanted, function (err, res) {
                    if (err) {
                        errors.push(list[i].replace(/^https?:\/\//, '').split('/')[0] + ': ' + err.message);
                    } else {
                        ok++;
                        for (k in res.ch) if (res.ch.hasOwnProperty(k) && !merged.ch[k]) merged.ch[k] = res.ch[k];
                        for (k in res.names) if (res.names.hasOwnProperty(k) && !merged.names[k]) merged.names[k] = res.names[k];
                    }
                    setTimeout(function () { next(i + 1); }, 50);
                });
            }(0));
        },

        clear: function () {
            NV.store.remove('epg');
            NV.store.remove('epgMeta');
            publish({ ch: {}, names: {}, updatedAt: 0 }, false);
        },

        /** { now: [start, stop, title, desc] | null, next: ... } */
        nowNext: function (ch, nowMs) {
            var id = ch ? resolveId(ch) : null, list, lo, hi, mid, idx = -1, now = nowMs || NV.now();
            if (!id) return { now: null, next: null };
            list = data.ch[id];
            lo = 0; hi = list.length - 1;
            while (lo <= hi) {
                mid = (lo + hi) >> 1;
                if (list[mid][0] <= now) { idx = mid; lo = mid + 1; } else { hi = mid - 1; }
            }
            return { now: idx >= 0 && list[idx][1] > now ? list[idx] : null, next: list[idx + 1] || null };
        },

        upcoming: function (ch, max) {
            var id = ch ? resolveId(ch) : null, now = NV.now();
            if (!id) return [];
            return data.ch[id].filter(function (p) { return p[1] > now; }).slice(0, max || 30);
        },

        // exposed for unit tests
        _parseTime: parseTime,
        _parseXmltv: parseXmltv
    };
}(NV));
