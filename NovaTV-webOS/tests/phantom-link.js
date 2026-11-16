/*
 * End-to-end test of "Telefondan / Ağdan ekle" in PhantomJS 2.1 (WebKit 538.1).
 * Needs, on 127.0.0.1: the NovaTV proxy on :8787 (serving this app) and a NovaTV Android TV
 * Link server on :8686 (e.g. an emulator with `adb forward tcp:8686 tcp:8686`).
 *
 * Usage: phantomjs tests/phantom-link.js http://127.0.0.1:8787/ <android-pin> <playlist-url> [shot-dir]
 */
var page = require('webpage').create();
var system = require('system');
var url = system.args[1], androidPin = system.args[2], playlistUrl = system.args[3], shots = system.args[4];
var errors = [], results = [];

page.viewportSize = { width: 1280, height: 720 };
page.onError = function (msg, trace) { errors.push(msg + (trace && trace[0] ? ' @ ' + trace[0].file + ':' + trace[0].line : '')); };
page.onConsoleMessage = function (msg) { if (system.env.VERBOSE) console.log('  console: ' + msg); };

function key(code) {
    page.evaluate(function (c) {
        function fire(type) {
            var e = document.createEvent('Events');
            e.initEvent(type, true, true);
            e.keyCode = c; e.which = c;
            (document.activeElement || document).dispatchEvent(e);
        }
        fire('keydown'); fire('keyup');
    }, code);
}
function check(name, ok, detail) { results.push({ name: name, ok: !!ok, detail: detail }); }
function shot(n) { if (shots) page.render(shots + '/' + n + '.png'); }
function waitFor(fn, timeoutMs, then) {
    var start = Date.now();
    (function poll() {
        var v = page.evaluate(fn);
        if (v || Date.now() - start > timeoutMs) { then(v); return; }
        setTimeout(poll, 300);
    }());
}
function finish() {
    results.forEach(function (r) { console.log((r.ok ? 'ok   ' : 'FAIL ') + r.name + (r.ok ? '' : '  -> ' + r.detail)); });
    errors.forEach(function (e) { console.log('JS ERROR ' + e); });
    var failed = results.filter(function (r) { return !r.ok; }).length;
    console.log('\n' + (results.length - failed) + '/' + results.length + ' checks passed, ' + errors.length + ' JavaScript errors');
    phantom.exit(failed || errors.length ? 1 : 0);
}

var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, ESC: 27 };

page.open(url, function (status) {
    if (status !== 'success') { console.log('cannot load ' + url); phantom.exit(2); }
    setTimeout(function () {
        // Home -> IPTV Kaynakları -> "Telefondan / Ağdan" button
        var i;
        for (i = 0; i < 5; i++) key(K.DOWN);
        setTimeout(function () {
            key(K.RIGHT);
            setTimeout(function () {
                key(K.UP); key(K.RIGHT); key(K.OK);
                waitFor(function () { return document.querySelectorAll('.dev-row').length >= 2 && document.querySelector('.link-qr') ? true : false; }, 20000, function () {
                    var devs = page.evaluate(function () {
                        return [].slice.call(document.querySelectorAll('.dev-row')).filter(function (r) { return r.style.display !== 'none'; })
                            .map(function (r) { return r.querySelector('.dev-badge').textContent + ' @ ' + r.querySelector('.dev-addr').textContent; });
                    });
                    check('LAN scan finds the Android TV and the proxy', devs.length >= 2 && devs.join('|').indexOf('Android TV') >= 0 && devs.join('|').indexOf('Proxy') >= 0, JSON.stringify(devs));
                    var pair = page.evaluate(function () {
                        var img = document.querySelector('.link-qr'), pin = document.querySelector('.link-pin');
                        return { qr: img ? img.src.substring(0, 22) : '', pin: pin ? pin.textContent : '', proxy: NV.settings.get('proxyUrl') };
                    });
                    check('proxy auto-configured and QR + PIN shown', pair.qr === 'data:image/gif;base64,' && /^\d{4}$/.test(pair.pin) && pair.proxy === 'http://127.0.0.1:8787', JSON.stringify(pair));
                    shot('link-1-found');
                    var before = page.evaluate(function () { return NV.channels.snapshot().channels.length; });

                    // "Phone": post a playlist to the proxy with the PIN shown on the TV.
                    var phone = page.evaluate(function (pin, list) {
                        var x = new XMLHttpRequest();
                        x.open('POST', '/api/sources', false);
                        x.send(JSON.stringify({ key: pin, name: 'Telefondan (proxy)', url: list }));
                        return x.status + ' ' + x.responseText;
                    }, pair.pin, playlistUrl);
                    check('phone page POST accepted by proxy', phone.indexOf('200') === 0, phone);
                    waitFor(function () { var l = document.querySelector('.link-log'); return l && l.textContent.indexOf('✓') >= 0; }, 15000, function () {
                        var after = page.evaluate(function () { return { n: NV.channels.snapshot().channels.length, log: document.querySelector('.link-log').textContent }; });
                        check('TV imported the phone playlist (+2 channels)', after.n === before + 2, JSON.stringify(after) + ' before=' + before);
                        shot('link-2-phone');

                        // Copy sources from the Android TV: select it, enter PIN, confirm.
                        var idx = page.evaluate(function () {
                            var rows = [].slice.call(document.querySelectorAll('.dev-row')).filter(function (r) { return r.style.display !== 'none'; });
                            var i; for (i = 0; i < rows.length; i++) if (rows[i].querySelector('.dev-badge').textContent === 'Android TV') return i;
                            return -1;
                        });
                        key(K.UP);
                        for (i = 0; i < idx; i++) key(K.DOWN);
                        key(K.OK);
                        setTimeout(function () {
                            page.evaluate(function (pin) { document.querySelector('.dialog .text-input').value = pin; }, androidPin);
                            key(K.OK);
                            waitFor(function () { var t = document.querySelector('.dialog .dialog-title'); return t && /kaynak bulundu/.test(t.textContent); }, 8000, function (ok) {
                                var dlg = page.evaluate(function () { var d = document.querySelector('.dialog'); return d ? d.textContent : ''; });
                                check('Android TV sources listed after PIN', !!ok, dlg);
                                shot('link-3-confirm');
                                key(K.OK);
                                waitFor(function () {
                                    return NV.channels.sources().some(function (s) { return s.name === 'Paylaşım Testi'; });
                                }, 140000, function (ok2) {
                                    var srcs = page.evaluate(function () { return NV.channels.sources().map(function (s) { return s.name; }); });
                                    check('source copied from the Android TV', !!ok2, JSON.stringify(srcs));
                                    shot('link-4-copied');
                                    finish();
                                });
                            });
                        }, 800);
                    });
                });
            }, 500);
        }, 600);
    }, 1500);
});
