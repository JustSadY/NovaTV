/*
 * NovaTV webOS — XMLHttpRequest helpers.
 * A packaged webOS app is loaded from file://; remote servers may block it (CORS), and a
 * 2014 TV may not trust newer certificate chains (e.g. Let's Encrypt). For those cases the
 * user can run the optional LAN proxy (proxy/novatv_proxy.py) and enable it in Settings.
 * Certificate checks are never disabled anywhere.
 */
(function (NV) {
    'use strict';

    function proxyBase() {
        var p = String(NV.settings.get('proxyUrl') || '').replace(/^\s+|\s+$/g, '').replace(/\/+$/, '');
        return p.length ? p : null;
    }

    NV.http = {
        hasProxy: function () { return proxyBase() !== null; },

        /** Builds a proxy URL. kind: fetch | epg | hls | seg. Returns the original URL when no proxy is set. */
        viaProxy: function (url, kind, extra) {
            var base = proxyBase(), token = NV.settings.get('proxyToken'), q;
            if (!base) return url;
            q = base + '/' + (kind || 'fetch') + '?url=' + encodeURIComponent(url);
            if (token) q += '&t=' + encodeURIComponent(token);
            if (extra) q += '&' + extra;
            return q;
        },

        /** True if requests for this source should use the proxy. */
        useProxyFor: function (source) {
            return this.hasProxy() && (NV.settings.get('proxyAll') || !!(source && source.useProxy));
        },

        /**
         * GET as text. cb(err, text). err = { status, message }.
         * opts: { timeout (ms), maxLength (chars) }
         */
        get: function (url, opts, cb) {
            var xhr, finished = false, timer;
            opts = opts || {};
            function done(err, text) {
                if (finished) return;
                finished = true;
                if (timer) clearTimeout(timer);
                cb(err, text);
            }
            try {
                xhr = new XMLHttpRequest();
                xhr.open('GET', url, true);
                if (xhr.overrideMimeType) xhr.overrideMimeType('text/plain; charset=utf-8');
            } catch (e) {
                done({ status: -1, message: 'Geçersiz adres.' });
                return { abort: function () {} };
            }
            xhr.onreadystatechange = function () {
                if (xhr.readyState === 3 && opts.maxLength && xhr.responseText && xhr.responseText.length > opts.maxLength) {
                    xhr.abort();
                    done({ status: -2, message: 'Dosya çok büyük (' + Math.round(opts.maxLength / 1048576) + ' MB sınırı). Proxy ile filtreleyin.' });
                    return;
                }
                if (xhr.readyState !== 4) return;
                if (xhr.status >= 200 && xhr.status < 300) {
                    done(null, xhr.responseText || '');
                } else {
                    done({ status: xhr.status, message: NV.http.describe(xhr.status) });
                }
            };
            timer = setTimeout(function () {
                try { xhr.abort(); } catch (e) { /* ignore */ }
                done({ status: -3, message: 'Sunucu zamanında yanıt vermedi.' });
            }, opts.timeout || 30000);
            try { xhr.send(null); } catch (e2) { done({ status: 0, message: NV.http.describe(0) }); }
            return { abort: function () { try { xhr.abort(); } catch (e) { /* ignore */ } finished = true; if (timer) clearTimeout(timer); } };
        },

        /** POST a JSON body. cb(err, parsedJson). Used by NovaTV Link (proxy pairing / status). */
        postJson: function (url, body, opts, cb) {
            var xhr, finished = false, timer;
            opts = opts || {};
            function done(err, data) { if (!finished) { finished = true; clearTimeout(timer); cb(err, data); } }
            try {
                xhr = new XMLHttpRequest();
                xhr.open('POST', url, true);
                // text/plain keeps this a "simple" CORS request (no preflight needed).
                xhr.setRequestHeader('Content-Type', 'text/plain; charset=utf-8');
            } catch (e) { done({ status: -1, message: 'Geçersiz adres.' }); return; }
            xhr.onreadystatechange = function () {
                var data = null;
                if (xhr.readyState !== 4) return;
                try { data = JSON.parse(xhr.responseText); } catch (e) { /* ignore */ }
                if (xhr.status >= 200 && xhr.status < 300) done(null, data);
                else done({ status: xhr.status, message: (data && data.error) || NV.http.describe(xhr.status) }, data);
            };
            timer = setTimeout(function () { try { xhr.abort(); } catch (e) { /* ignore */ } done({ status: -3, message: 'Zaman aşımı.' }); }, opts.timeout || 8000);
            try { xhr.send(JSON.stringify(body || {})); } catch (e2) { done({ status: 0, message: NV.http.describe(0) }); }
        },

        /** GET JSON. cb(err, data). */
        getJson: function (url, opts, cb) {
            return NV.http.get(url, opts, function (err, text) {
                var data = null;
                if (err) { cb(err); return; }
                try { data = JSON.parse(text); } catch (e) { cb({ status: -5, message: 'Geçersiz yanıt.' }); return; }
                cb(null, data);
            });
        },

        /** Reads only the HTTP status of a URL (aborts after headers). cb(status) — 0 when unknown. */
        probe: function (url, cb) {
            var xhr, finished = false, timer;
            function done(s) { if (!finished) { finished = true; clearTimeout(timer); try { xhr.abort(); } catch (e) { /* ignore */ } cb(s); } }
            try {
                xhr = new XMLHttpRequest();
                xhr.open('GET', url, true);
            } catch (e) { cb(0); return; }
            xhr.onreadystatechange = function () { if (xhr.readyState >= 2) done(xhr.status || 0); };
            timer = setTimeout(function () { done(0); }, 6000);
            try { xhr.send(null); } catch (e2) { done(0); }
        },

        describe: function (status) {
            if (status === 0) return 'Sunucuya bağlanılamadı. İnternet bağlantısını kontrol edin; sunucu webOS uygulamalarını engelliyor (CORS) veya sertifikası bu TV tarafından tanınmıyor olabilir. Ayarlar\'dan proxy kullanmayı deneyin.';
            if (status === 401 || status === 403) return 'Sunucu erişimi reddetti (HTTP ' + status + '). Abonelik veya bağlantı süresi dolmuş olabilir.';
            if (status === 404 || status === 410) return 'Bulunamadı (HTTP ' + status + ').';
            if (status >= 500) return 'Sunucuda hata oluştu (HTTP ' + status + ').';
            return 'Sunucu beklenmeyen bir yanıt verdi (HTTP ' + status + ').';
        }
    };
}(NV));
