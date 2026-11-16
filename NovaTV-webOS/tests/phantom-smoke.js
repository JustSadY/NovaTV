/*
 * End-to-end smoke test in PhantomJS 2.1 (QtWebKit 538.1, ES5-only JavaScriptCore) — the
 * closest freely available engine to webOS TV 1.x (WebKit 537.41) / 2.x (538.2).
 * It drives the real app with remote-control key codes and fails on any JavaScript error.
 * Video playback cannot be tested here (PhantomJS has no media pipeline).
 *
 * Usage: phantomjs tests/phantom-smoke.js http://127.0.0.1:8787/ [screenshot-dir]
 */
var page = require('webpage').create();
var system = require('system');
var url = system.args[1] || 'http://127.0.0.1:8787/';
var shots = system.args[2] || null;
var errors = [], results = [], step = 0;

page.viewportSize = { width: 1280, height: 720 };
page.onError = function (msg, trace) {
    errors.push(msg + (trace && trace[0] ? ' @ ' + trace[0].file + ':' + trace[0].line : ''));
};
page.onConsoleMessage = function (msg) { if (system.env.VERBOSE) console.log('  console: ' + msg); };

function key(code) {
    page.evaluate(function (c) {
        function fire(type) {
            var e = document.createEvent('Events');
            e.initEvent(type, true, true);
            e.keyCode = c;
            e.which = c;
            (document.activeElement || document).dispatchEvent(e);
        }
        fire('keydown');
        fire('keyup');
    }, code);
}

function check(name, fn) {
    var ok = false, detail = '';
    try {
        detail = page.evaluate(fn);
        ok = detail === true || (typeof detail === 'string' && detail.indexOf('OK') === 0);
    } catch (e) { detail = String(e); }
    results.push({ name: name, ok: ok, detail: detail });
}

function shot(name) {
    if (shots) page.render(shots + '/' + name + '.png');
}

var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, ESC: 27, RED: 403, YELLOW: 405, GREEN: 404, BLUE: 406, ONE: 49 };

var steps = [
    function () {
        check('engine has no ES2015 (Promise undefined)', function () { return typeof Promise === 'undefined'; });
        check('home renders 7 navigation items', function () { return document.querySelectorAll('.nav-item').length === 7 || 'got ' + document.querySelectorAll('.nav-item').length; });
        check('built-in list seeded (13 channels)', function () { return NV.channels.snapshot().channels.length === 13 || 'got ' + NV.channels.snapshot().channels.length; });
        check('live card shows TRT 1', function () { var t = document.querySelector('.card-title').textContent; return t.indexOf('TRT 1') >= 0 || t; });
        shot('01-home');
        key(K.DOWN);
    },
    function () { key(K.RIGHT); },
    function () {
        check('All Channels: first row focused is TRT 1', function () { var r = document.querySelector('.channels.all .row.focused .ch-name'); return (r && r.textContent === 'TRT 1') || (r ? r.textContent : 'no focus'); });
        key(K.DOWN); key(K.DOWN);
    },
    function () {
        check('DOWN x2 focuses TRT Haber', function () { var r = document.querySelector('.channels.all .row.focused .ch-name'); return (r && r.textContent === 'TRT Haber') || (r ? r.textContent : 'no focus'); });
        key(K.YELLOW);
    },
    function () {
        check('YELLOW adds favorite + OSD', function () {
            var fav = NV.channels.favorites().length === 1, osd = document.getElementById('osd').textContent;
            return (fav && osd.indexOf('favorilere eklendi') > 0) || ('fav=' + fav + ' osd=' + osd);
        });
        shot('02-all-channels');
        key(K.RED);
    },
    function () {
        check('RED opens channel options dialog', function () { var d = document.querySelector('.dialog .dialog-title'); return (d && d.textContent.indexOf('TRT Haber') >= 0) || 'no dialog'; });
        shot('03-options');
        key(K.ESC);
    },
    function () {
        check('Back closes the dialog', function () { return !document.querySelector('.dialog') || 'dialog still open'; });
        key(K.OK);
    },
    function () {
        check('OK opens fullscreen player with TRT Haber', function () {
            var p = document.getElementById('player');
            return (!p.classList.contains('hidden') && document.getElementById('ib-name').textContent === 'TRT Haber') || ('player hidden=' + p.classList.contains('hidden') + ' name=' + document.getElementById('ib-name').textContent);
        });
        check('last channel stored', function () { var l = NV.settings.lastChannel(); return (l && l.name === 'TRT Haber') || JSON.stringify(l); });
        check('video element got an HLS <source>', function () { var s = document.querySelector('#video source'); return (s && /m3u8/.test(s.src)) || 'no source'; });
        shot('04-player-banner');
        key(K.UP);
    },
    function () {
        check('UP zaps to next channel (TRT Spor)', function () { return document.getElementById('ib-name').textContent === 'TRT Spor' || document.getElementById('ib-name').textContent; });
        key(K.ONE);
    },
    function () {
        check('number entry shows "1"', function () { var n = document.getElementById('number-entry'); return (!n.classList.contains('hidden') && n.textContent === '1') || n.textContent; });
    },
    function () {
        check('number 1 tunes TRT 1 (after commit delay)', function () { return NV.settings.lastChannel().name === 'TRT 1' || NV.settings.lastChannel().name; });
        key(K.LEFT);
    },
    function () {
        check('LEFT opens channel panel on "Tüm Kanallar"', function () {
            return (!document.getElementById('panel').classList.contains('hidden') && document.getElementById('panel-group').textContent === 'Tüm Kanallar') || document.getElementById('panel-group').textContent;
        });
        shot('05-panel');
        key(K.RIGHT);
    },
    function () {
        check('RIGHT in panel switches to Favoriler (1 channel)', function () {
            return (document.getElementById('panel-group').textContent === 'Favoriler' && document.getElementById('panel-count').textContent === '1 kanal') || document.getElementById('panel-group').textContent + ' / ' + document.getElementById('panel-count').textContent;
        });
        key(K.ESC);
    },
    function () {
        check('Back closes the panel, player stays', function () {
            return (document.getElementById('panel').classList.contains('hidden') && !document.getElementById('player').classList.contains('hidden')) || 'panel/player state wrong';
        });
        key(K.RIGHT);
    },
    function () {
        check('RIGHT opens the quick menu', function () { return !document.getElementById('qmenu').classList.contains('hidden') || 'menu hidden'; });
        shot('06-quick-menu');
        key(K.ESC);
    },
    function () { key(K.ESC); },
    function () { key(K.ESC); },
    function () {
        check('Back from player returns to home', function () {
            return (document.getElementById('player').classList.contains('hidden') && !document.getElementById('home').classList.contains('hidden')) || 'still in player';
        });
        key(K.DOWN); key(K.DOWN); key(K.DOWN); key(K.DOWN);
    },
    function () { key(K.RIGHT); },
    function () {
        check('Sources section lists the built-in list', function () { var r = document.querySelector('.sources .row.focused .src-name'); return (r && r.textContent.indexOf('Türkiye') === 0) || (r ? r.textContent : 'no focus'); });
        shot('07-sources');
        key(K.UP);
    },
    function () { key(K.OK); },
    function () {
        check('"Kaynak ekle" opens the source form', function () { var f = document.querySelector('.form-title'); return (f && f.textContent === 'IPTV kaynağı ekle') || 'no form'; });
        shot('08-source-form');
        key(K.ESC);
    },
    function () {
        check('Back closes the form', function () { return !document.querySelector('.form-screen') || 'form open'; });
        key(K.ESC); // content -> nav
    },
    function () { key(K.DOWN); },
    function () { key(K.RIGHT); },
    function () {
        check('Settings section renders rows', function () { return document.querySelectorAll('.settings .row').length > 5 || 'rows=' + document.querySelectorAll('.settings .row').length; });
        shot('09-settings');
    }
];

page.open(url, function (status) {
    if (status !== 'success') { console.log('could not load ' + url); phantom.exit(2); }
    setTimeout(function next() {
        if (step < steps.length) {
            steps[step++]();
            setTimeout(next, step === 10 ? 2600 : 450);
            return;
        }
        var failed = results.filter(function (r) { return !r.ok; });
        results.forEach(function (r) { console.log((r.ok ? 'ok   ' : 'FAIL ') + r.name + (r.ok ? '' : '  -> ' + r.detail)); });
        errors.forEach(function (e) { console.log('JS ERROR ' + e); });
        console.log('\n' + (results.length - failed.length) + '/' + results.length + ' checks passed, ' + errors.length + ' JavaScript errors');
        phantom.exit(failed.length || errors.length ? 1 : 0);
    }, 1500);
});
