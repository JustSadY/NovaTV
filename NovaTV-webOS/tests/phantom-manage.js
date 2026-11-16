/*
 * End-to-end test of remote management for the LG app in PhantomJS 2.1 (WebKit 538.1):
 * the NovaTV web app (the "TV") polls the LAN proxy; this script plays the phone panel by calling
 * the proxy's panel API from the page (asynchronous XHR, so the TV keeps running meanwhile).
 *
 * Needs the NovaTV proxy serving this app, e.g.:
 *   python3 proxy/novatv_proxy.py --host 127.0.0.1 --port 8788 --data-dir /tmp/novatv-test
 * Usage: phantomjs tests/phantom-manage.js http://127.0.0.1:8788/ [shot-dir]
 */
var page = require('webpage').create();
var system = require('system');
var base = system.args[1].replace(/\/+$/, ''), shots = system.args[2];
var errors = [], results = [];

page.viewportSize = { width: 1280, height: 720 };
page.onError = function (msg, trace) { errors.push(msg + (trace && trace[0] ? ' @ ' + trace[0].file + ':' + trace[0].line : '')); };
page.onConsoleMessage = function (msg) { if (system.env.VERBOSE) console.log('  console: ' + msg); };

function check(name, ok, detail) { results.push({ name: name, ok: !!ok, detail: detail }); }
function shot(n) { if (shots) page.render(shots + '/' + n + '.png'); }
function finish() {
    results.forEach(function (r) { console.log((r.ok ? 'ok   ' : 'FAIL ') + r.name + (r.ok ? '' : '  -> ' + r.detail)); });
    errors.forEach(function (e) { console.log('JS ERROR ' + e); });
    var failed = results.filter(function (r) { return !r.ok; }).length;
    console.log('\n' + (results.length - failed) + '/' + results.length + ' checks passed, ' + errors.length + ' JavaScript errors');
    phantom.exit(failed || errors.length ? 1 : 0);
}
function waitFor(fn, timeoutMs, then) {
    var start = Date.now();
    (function poll() {
        var v = page.evaluate(fn);
        if (v || Date.now() - start > timeoutMs) { then(v); return; }
        setTimeout(poll, 250);
    }());
}

/** Asynchronous request from the page (the "phone"); then(status, json). */
function api(method, path, body, then) {
    page.evaluate(function (m, p, b) {
        window.__api = null;
        var x = new XMLHttpRequest();
        x.open(m, p, true);
        x.onreadystatechange = function () { if (x.readyState === 4) window.__api = { status: x.status, text: x.responseText }; };
        if (b) { x.setRequestHeader('Content-Type', 'application/json'); x.send(b); } else x.send();
    }, method, path, body ? JSON.stringify(body) : null);
    waitFor(function () { return window.__api; }, 160000, function (r) {
        var json = null;
        try { json = JSON.parse(r.text); } catch (e) { /* not json */ }
        then(r ? r.status : 0, json);
    });
}

var tv, token, pin;

page.open(base + '/', function (status) {
    if (status !== 'success') { console.log('cannot load ' + base); phantom.exit(2); }
    setTimeout(function () {
        // TV side: proxy address + "Uzaktan yönetim", then open the pairing screen.
        pin = page.evaluate(function (b) {
            NV.settings.set('proxyUrl', b);
            NV.settings.set('manageEnabled', true);
            NV.manageScreen.open();
            return document.querySelector('.link-pin').textContent;
        }, base);
        check('pairing screen shows a 4-digit PIN and a QR code', /^\d{4}$/.test(pin) && page.evaluate(function () { return !!document.querySelector('.link-qr'); }), pin);
        waitFor(function () { return NV.manageRelay.isConnected(); }, 10000, function (connected) {
            check('TV relay connects to the proxy', connected, page.evaluate(function () { return NV.manageRelay.lastError(); }));
            shot('manage-1-pairing');
            api('GET', '/api/manage/tvs', null, function (st, j) {
                // The proxy remembers TVs from earlier runs (manage.json); this one is the online TV.
                var online = j && j.tvs ? j.tvs.filter(function (t) { return t.online; }) : [];
                tv = online.length === 1 ? online[0].id : null;
                check('proxy lists the TV as online', st === 200 && tv, JSON.stringify(j));
                api('POST', '/api/manage/pair', { tv: tv, pin: '0000' === pin ? '1111' : '0000', name: 'test' }, function (st1) {
                    check('wrong PIN is refused', st1 === 403, st1);
                    api('POST', '/api/manage/pair', { tv: tv, pin: pin, name: 'test' }, function (st2, j2) {
                        token = j2 && j2.token;
                        check('pairing with the TV PIN gives a token', st2 === 200 && /^[0-9a-f]{32}$/.test(token || ''), JSON.stringify(j2));
                        afterPair();
                    });
                });
            });
        });
    }, 1500);
});

function afterPair() {
    api('GET', '/api/manage/state?tv=' + tv + '&token=' + token, null, function (st, j) {
        check('panel reads the TV state (13 built-in channels)', st === 200 && j.state.channels.length === 13 && j.state.device.platform === 'webos', st + ' ' + (j && j.state ? j.state.channels.length : JSON.stringify(j)));
        api('GET', '/api/manage/state?tv=' + tv + '&token=bad', null, function (st2) {
            check('unknown token is refused (401)', st2 === 401, st2);
            api('POST', '/api/manage/cmd', { tv: tv, token: token, op: 'channel.add', args: { name: 'Telefon Kanalı', url: 'http://ornek.tv/x/index.m3u8', group: 'Yerel' } }, function (st3, j3) {
                var onTv = page.evaluate(function () { return NV.channels.snapshot().channels.filter(function (c) { return c.name === 'Telefon Kanalı'; }).length; });
                check('channel.add reaches the TV and returns its new state', st3 === 200 && j3.ok && onTv === 1 && j3.state.channels.length === 14, st3 + ' ' + JSON.stringify(j3 && j3.message) + ' onTv=' + onTv);
                var trt1 = page.evaluate(function () { return NV.channels.snapshot().byNumber(1).key; });
                api('POST', '/api/manage/cmd', { tv: tv, token: token, op: 'channel.delete', args: { key: trt1 } }, function (st4, j4) {
                    var hidden = page.evaluate(function () { return NV.channels.snapshot().hidden.length; });
                    check('channel.delete hides a playlist channel on the TV', st4 === 200 && j4.ok && hidden === 1 && j4.state.hidden.length === 1, JSON.stringify(j4 && (j4.message || j4.error)));
                    var m3u = '#EXTM3U\n#EXTINF:-1 group-title="Test",Dosya Kanal 1\nhttp://ornek.tv/1.m3u8\n#EXTINF:-1 group-title="Test",Dosya Kanal 2\nhttp://ornek.tv/2.m3u8\n';
                    api('POST', '/api/manage/cmd', { tv: tv, token: token, op: 'source.add', args: { name: 'Telefondaki dosya', m3u: m3u } }, function (st5, j5) {
                        var n = page.evaluate(function () { return NV.channels.snapshot().channels.length; });
                        check('M3U file from the phone: proxy stores it, TV imports it (+2)', st5 === 200 && j5.ok && n === 15, JSON.stringify(j5 && (j5.message || j5.error)) + ' n=' + n);
                        api('GET', '/api/manage/export?tv=' + tv + '&token=' + token, null, function (st6, j6) {
                            var c = j6 && j6.config;
                            check('export for sync: own channel, hidden, sources', st6 === 200 && c && c.format === 'novatv-config' && c.custom.length === 1 && c.hidden.length === 1 && c.sources.length === 2,
                                JSON.stringify(c && { s: c.sources.length, cu: c.custom.length, h: c.hidden.length }));
                            shot('manage-2-after');
                            page.evaluate(function () { NV.input.top().onBack(); });
                            setTimeout(function () {
                                check('closing the screen clears the PIN (pairing refused)', true, '');
                                api('POST', '/api/manage/pair', { tv: tv, pin: pin, name: 'late' }, function (st7) {
                                    results[results.length - 1].ok = st7 === 409;
                                    results[results.length - 1].detail = 'status ' + st7;
                                    api('GET', '/api/manage/state?tv=' + tv + '&token=' + token, null, function (st8) {
                                        check('paired phone still works with "Uzaktan yönetim" on', st8 === 200, st8);
                                        finish();
                                    });
                                });
                            }, 4000);
                        });
                    });
                });
            });
        });
    });
}
