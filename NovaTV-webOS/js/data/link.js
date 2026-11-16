/*
 * NovaTV webOS — "NovaTV Link": find NovaTV devices on the local network and add sources
 * without typing on the TV.
 *
 *  - Android TV running NovaTV ("Telefondan ekle" screen open): port 8686, shares its sources
 *    with the 4-digit PIN shown on that TV (GET /api/sources?key=PIN).
 *  - NovaTV LAN proxy (proxy/novatv_proxy.py): port 8787; relays a phone page so a phone can send
 *    a playlist URL or an M3U file to this TV (a webOS 1.x web app cannot run a server itself).
 *
 * Discovery: a web app cannot use mDNS, so the subnet is probed with short /api/info requests.
 * The TV's own address comes from the webOS connection manager when available; otherwise the
 * page origin (TV browser mode) or common home-router subnets are tried.
 */
(function (NV) {
    'use strict';

    var PORTS = [8686, 8787];
    var DEFAULT_SUBNETS = ['192.168.1', '192.168.0', '192.168.2', '10.0.0', '192.168.100', '192.168.43', '172.16.0'];

    function subnetOf(ip) {
        var m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip || '');
        return m ? m[1] + '.' + m[2] + '.' + m[3] : null;
    }

    /** webOS luna call through PalmServiceBridge (webOS 1-3 naming first). cb(response|null). */
    function lunaCall(uri, cb) {
        var bridge, done = false, timer;
        if (typeof window.PalmServiceBridge === 'undefined') { cb(null); return; }
        try {
            bridge = new window.PalmServiceBridge();
            bridge.onservicecallback = function (msg) {
                var r = null;
                if (done) return;
                done = true;
                clearTimeout(timer);
                try { r = JSON.parse(msg); } catch (e) { /* ignore */ }
                cb(r);
            };
            timer = setTimeout(function () { if (!done) { done = true; cb(null); } }, 1500);
            bridge.call(uri, '{}');
        } catch (e) {
            if (!done) { done = true; cb(null); }
        }
    }

    function ipFromStatus(r) {
        var keys = ['wired', 'wifi'], i, s;
        if (!r) return null;
        for (i = 0; i < keys.length; i++) {
            s = r[keys[i]];
            if (s && s.ipAddress && (s.state === 'connected' || s.onInternet === 'yes' || !s.state)) return s.ipAddress;
        }
        return null;
    }

    function info(base, timeout, cb) {
        NV.http.getJson(base + '/api/info', { timeout: timeout || 1500, maxLength: 4096 }, function (err, data) {
            if (err || !data || data.app !== 'NovaTV') { cb(err || { message: 'NovaTV değil' }); return; }
            cb(null, data);
        });
    }

    function proxyApi(path) {
        var base = String(NV.settings.get('proxyUrl') || '').replace(/\/+$/, ''), token = NV.settings.get('proxyToken');
        if (!base) return null;
        return base + path + (path.indexOf('?') >= 0 ? '&' : '?') + (token ? 't=' + encodeURIComponent(token) : 'v=1');
    }

    NV.link = {
        PORTS: PORTS,

        /** cb(subnets[], detected) — the TV's own subnet first (detected=true), then common home subnets. */
        subnets: function (cb) {
            var out = [], add = function (s) { if (s && out.indexOf(s) < 0) out.push(s); };
            add(subnetOf(window.location && window.location.hostname));
            lunaCall('luna://com.palm.connectionmanager/getstatus', function (r1) {
                add(subnetOf(ipFromStatus(r1)));
                lunaCall('luna://com.webos.service.connectionmanager/getStatus', function (r2) {
                    var i, detected;
                    add(subnetOf(ipFromStatus(r2)));
                    detected = out.length > 0;
                    for (i = 0; i < DEFAULT_SUBNETS.length; i++) add(DEFAULT_SUBNETS[i]);
                    cb(out, detected);
                });
            });
        },

        info: info,

        /**
         * Probes subnet hosts on the NovaTV ports. Calls onFound(device) as devices answer,
         * onProgress(done, total, subnet), onDone(devices). Returns { cancel() }.
         * opts: { subnets: [...], ports, concurrency, timeout, hosts: ['a.b.c.d', ...] (optional) }
         */
        scan: function (opts, onFound, onProgress, onDone) {
            var ports = opts.ports || PORTS, queue = [], found = [], seen = {}, active = 0, done = 0, cancelled = false;
            var concurrency = opts.concurrency || 32, timeout = opts.timeout || 1300, i, j, h, total, currentSubnet = '';
            if (opts.hosts) {
                for (i = 0; i < opts.hosts.length; i++) for (j = 0; j < ports.length; j++) queue.push({ host: opts.hosts[i], port: ports[j], subnet: '' });
            } else {
                for (i = 0; i < opts.subnets.length; i++) {
                    for (h = 1; h < 255; h++) for (j = 0; j < ports.length; j++) queue.push({ host: opts.subnets[i] + '.' + h, port: ports[j], subnet: opts.subnets[i] });
                }
            }
            total = queue.length;

            function pump() {
                var job;
                if (cancelled) return;
                if (!queue.length && !active) { if (onDone) onDone(found); return; }
                while (active < concurrency && queue.length) {
                    job = queue.shift();
                    active++;
                    if (job.subnet !== currentSubnet) currentSubnet = job.subnet;
                    probe(job);
                }
            }

            function probe(job) {
                var base = 'http://' + job.host + ':' + job.port;
                info(base, timeout, function (err, data) {
                    var dev;
                    active--;
                    done++;
                    if (cancelled) return;
                    if (!err && !seen[base]) {
                        seen[base] = true;
                        dev = { base: base, host: job.host, port: job.port, info: data, kind: data.platform === 'proxy' ? 'proxy' : 'tv' };
                        found.push(dev);
                        if (onFound) onFound(dev);
                    }
                    if (onProgress && (done % 8 === 0 || done === total)) onProgress(done, total, currentSubnet);
                    pump();
                });
            }

            setTimeout(pump, 0);
            return { cancel: function () { cancelled = true; } };
        },

        /** Reads the sources another NovaTV TV shares (needs the PIN shown on that TV). */
        fetchShared: function (device, pin, cb) {
            NV.http.getJson(device.base + '/api/sources?key=' + encodeURIComponent(pin), { timeout: 6000 }, function (err, data) {
                if (err) {
                    cb({ message: err.status === 403 ? 'PIN hatalı. Diğer TV\'nin ekranındaki 4 haneli kodu girin.' : (err.status === 429 ? 'Çok fazla hatalı PIN. 30 saniye bekleyin.' : err.message) });
                    return;
                }
                cb(null, (data && data.sources) || []);
            });
        },

        /** Imports sources one by one, skipping URLs this TV already has. cb(added, skipped, failed[]). */
        importList: function (list, onEach, cb) {
            var existing = {}, added = 0, skipped = 0, failed = [], i, srcs = NV.channels.sources();
            for (i = 0; i < srcs.length; i++) if (srcs[i].url) existing[srcs[i].url] = true;
            (function next(k) {
                var item;
                if (k >= list.length) { cb(added, skipped, failed); return; }
                item = list[k];
                if (!item.url || existing[item.url]) { skipped++; next(k + 1); return; }
                existing[item.url] = true;
                if (onEach) onEach(item, k, list.length);
                NV.channels.importSource({ name: item.name || 'Ağdan gelen liste', url: item.url, epgUrl: item.epgUrl || '', enabled: true }, function (err) {
                    if (err) failed.push(item.name + ': ' + err.message); else added++;
                    setTimeout(function () { next(k + 1); }, 100);
                });
            }(0));
        },

        // ------------------------------------------------------------ phone pairing via the proxy

        /** Opens a pairing session on the proxy. cb(err, { key, phoneUrl }). */
        pair: function (cb) {
            var url = proxyApi('/api/pair'), base = String(NV.settings.get('proxyUrl') || '').replace(/\/+$/, '');
            if (!url) { cb({ message: 'Proxy tanımlı değil.' }); return; }
            NV.http.postJson(url, {}, { timeout: 6000 }, function (err, data) {
                if (err || !data || !data.key) { cb(err || { message: 'Proxy yanıt vermedi.' }); return; }
                cb(null, { key: data.key, phoneUrl: base + '/send?k=' + data.key });
            });
        },

        /**
         * Polls the proxy inbox every 2 s; each received item is imported and its progress is
         * reported back so the phone page can show it. Returns { stop() }.
         * onEvent(type, item, extra): type = 'received' | 'done' | 'error' | 'expired'.
         */
        listen: function (key, onEvent) {
            var stopped = false, timer = null, busy = false;
            function report(item, state, count, message) {
                var url = proxyApi('/api/status');
                if (url) NV.http.postJson(url, { key: key, id: item.id, state: state, count: count || 0, message: message || '' }, { timeout: 5000 }, function () {});
            }
            function handle(items, k) {
                var item = items[k];
                if (k >= items.length || stopped) { busy = false; return; }
                onEvent('received', item);
                report(item, 'loading');
                NV.channels.importSource({ name: item.name || (item.url.replace(/^https?:\/\//, '').split('/')[0]), url: item.url, epgUrl: item.epgUrl || '', enabled: true }, function (err, count) {
                    if (err) { report(item, 'error', 0, err.message); onEvent('error', item, err.message); }
                    else { report(item, 'done', count); onEvent('done', item, count); }
                    handle(items, k + 1);
                });
            }
            function poll() {
                var url = proxyApi('/api/inbox?key=' + encodeURIComponent(key));
                if (stopped) return;
                if (!busy && url) {
                    NV.http.getJson(url, { timeout: 5000 }, function (err, data) {
                        if (stopped) return;
                        if (err && err.status === 404) { onEvent('expired'); return; }
                        if (!err && data && data.items && data.items.length) { busy = true; handle(data.items, 0); }
                    });
                }
                timer = setTimeout(poll, 2000);
            }
            poll();
            return { stop: function () { stopped = true; if (timer) clearTimeout(timer); } };
        },

        // exposed for tests
        _subnetOf: subnetOf,
        _ipFromStatus: ipFromStatus
    };
}(NV));
