#!/usr/bin/env node
/*
 * Unit tests for the platform-independent modules (parser, EPG, HLS variant choice, repository).
 * They load the real ES5 files into a tiny browser-like sandbox. Run: node tests/run-tests.js
 */
var fs = require('fs');
var path = require('path');
var vm = require('vm');
var assert = require('assert');

var root = path.resolve(__dirname, '..');
var storage = {};
var sandbox = {
    console: console,
    setTimeout: setTimeout, clearTimeout: clearTimeout, setInterval: setInterval, clearInterval: clearInterval,
    localStorage: {
        getItem: function (k) { return Object.prototype.hasOwnProperty.call(storage, k) ? storage[k] : null; },
        setItem: function (k, v) { storage[k] = String(v); },
        removeItem: function (k) { delete storage[k]; },
        key: function (i) { return Object.keys(storage)[i]; },
        get length() { return Object.keys(storage).length; }
    },
    navigator: { userAgent: 'node' },
    document: { addEventListener: function () {} }
};
sandbox.window = sandbox;
vm.createContext(sandbox);
['js/core/base.js', 'js/core/storage.js', 'js/core/settings.js', 'js/core/http.js', 'js/data/m3u.js', 'js/data/builtin.js',
    'js/data/channels.js', 'js/data/epg.js', 'js/data/link.js', 'js/data/manage.js', 'js/player/hls.js'].forEach(function (f) {
    vm.runInContext(fs.readFileSync(path.join(root, f), 'utf8'), sandbox, { filename: f });
});
var NV = sandbox.NV;

var tests = [], passed = 0;
function test(name, fn) { tests.push({ name: name, fn: fn }); }

// ------------------------------------------------------------------ M3U (same cases as Android)

test('m3u: extended attributes and header', function () {
    var r = NV.m3u.parse('﻿#EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="ignored"\r\n' +
        '#EXTINF:-1 tvg-id="trt1.tr" tvg-name="TRT 1" tvg-logo="http://logo/trt1.png" group-title="Ulusal, HD",TRT 1 HD\r\n' +
        'http://stream.example/live/trt1.m3u8\r\n');
    assert.strictEqual(r.epgUrl, 'http://epg.example/guide.xml.gz');
    assert.strictEqual(r.entries.length, 1);
    var e = r.entries[0];
    assert.strictEqual(e.name, 'TRT 1 HD');
    assert.strictEqual(e.tvgId, 'trt1.tr');
    assert.strictEqual(e.tvgName, 'TRT 1');
    assert.strictEqual(e.logo, 'http://logo/trt1.png');
    assert.strictEqual(e.group, 'Ulusal, HD');
    assert.strictEqual(r.isHlsStream, false);
});

test('m3u: names with commas, EXTGRP, VLC options, Kodi pipe headers', function () {
    var r = NV.m3u.parse('#EXTM3U\n#EXTINF:0,First, Extra\n#EXTGRP:Sports\n#EXTVLCOPT:http-user-agent=MyAgent/1.0\nhttp://a/1.ts\n' +
        '#EXTINF:-1,Second\nhttp://a/2.m3u8|User-Agent=Kodi%2F20&Referer=http://r2/\n');
    assert.strictEqual(r.entries[0].name, 'First, Extra');
    assert.strictEqual(r.entries[0].group, 'Sports');
    assert.strictEqual(r.entries[0].userAgent, 'MyAgent/1.0');
    assert.strictEqual(r.entries[1].url, 'http://a/2.m3u8');
    assert.strictEqual(r.entries[1].userAgent, 'Kodi/20');
    assert.strictEqual(r.entries[1].group, '');
});

test('m3u: bare URLs, unsupported schemes, quotes', function () {
    var r = NV.m3u.parse("http://host/path/channel_one.ts\nrtmp://host/live\n#EXTINF:-1 tvg-id=abc tvg-logo='http://l/x.png',Udp\nudp://@239.0.0.1:1234\n");
    assert.strictEqual(r.entries.length, 2);
    assert.strictEqual(r.entries[0].name, 'channel_one');
    assert.strictEqual(r.entries[1].tvgId, 'abc');
    assert.strictEqual(r.entries[1].logo, 'http://l/x.png');
    assert.strictEqual(r.skipped, 1);
});

test('m3u: detects a single HLS media playlist', function () {
    assert.strictEqual(NV.m3u.parse('#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nhttp://s/seg1.ts\n').isHlsStream, true);
});

test('builtin list: 13 official channels, local logos, public-broadcaster hosts', function () {
    var r = NV.m3u.parse(NV.BUILTIN.text), i, host;
    assert.strictEqual(r.entries.length, 13);
    for (i = 0; i < r.entries.length; i++) {
        assert.ok(/^assets\/logos\/[a-z0-9]+\.png$/.test(r.entries[i].logo), r.entries[i].logo);
        assert.ok(fs.existsSync(path.join(root, r.entries[i].logo)), 'missing ' + r.entries[i].logo);
        host = r.entries[i].url.replace(/^https?:\/\//, '').split('/')[0];
        assert.ok(/\.medya\.trt\.com\.tr$|^meclistv-live\.ercdn\.net$/.test(host), host);
    }
});

// ------------------------------------------------------------------ text keys

test('text: Turkish-aware search and EPG keys', function () {
    assert.strictEqual(NV.searchKey('İSTANBUL Şehir Çocuk'), 'istanbul sehir cocuk');
    assert.strictEqual(NV.epgKey('TRT 1 HD'), 'trt1');
    assert.strictEqual(NV.epgKey('TR: TRT 1'), 'trt1');
    assert.strictEqual(NV.epgKey('TR | TRT-1 FHD'), 'trt1');
    assert.strictEqual(NV.epgKey('CNN Türk'), 'cnnturk');
    assert.strictEqual(NV.initials('TRT Haber'), 'TH');
});

// ------------------------------------------------------------------ EPG

function utc(y, mo, d, h, mi) { return Date.UTC(y, mo - 1, d, h, mi, 0); }

test('epg: XMLTV time parsing', function () {
    assert.strictEqual(NV.epg._parseTime('20241115060000 +0300'), utc(2024, 11, 15, 3, 0));
    assert.strictEqual(NV.epg._parseTime('20240229203000 -0500'), utc(2024, 3, 1, 1, 30));
    assert.strictEqual(NV.epg._parseTime('202501010000'), utc(2025, 1, 1, 0, 0));
    assert.strictEqual(NV.epg._parseTime('garbage'), -1);
    assert.strictEqual(NV.epg._parseTime('20241345000000'), -1);
});

test('epg: incremental XMLTV scan keeps only wanted channels and window', function (done) {
    var now = Date.now(), h = 3600000, xml = ['<tv>', '<channel id="TRT1.tr"><display-name>TRT 1</display-name></channel>',
        '<channel id="x.tr"><display-name>Other</display-name></channel>'], i, fmt;
    fmt = function (ms) {
        var d = new Date(ms), p = function (n) { return (n < 10 ? '0' : '') + n; };
        return d.getUTCFullYear() + p(d.getUTCMonth() + 1) + p(d.getUTCDate()) + p(d.getUTCHours()) + p(d.getUTCMinutes()) + '00 +0000';
    };
    for (i = -5; i < 60; i++) {
        xml.push('<programme start="' + fmt(now + i * h) + '" stop="' + fmt(now + (i + 1) * h) + '" channel="TRT1.tr"><title>P' + i + ' &amp; Co</title></programme>');
        xml.push('<programme start="' + fmt(now + i * h) + '" stop="' + fmt(now + (i + 1) * h) + '" channel="x.tr"><title>X</title></programme>');
    }
    xml.push('</tv>');
    NV.epg._parseXmltv(xml.join('\n'), { ids: { 'trt1.tr': 1 }, names: {} }, function (res) {
        assert.deepStrictEqual(Object.keys(res.ch), ['trt1.tr']);
        assert.ok(res.ch['trt1.tr'].length >= 36 && res.ch['trt1.tr'].length <= 40, 'window size ' + res.ch['trt1.tr'].length);
        assert.strictEqual(res.ch['trt1.tr'][2][2].indexOf('&amp;'), -1);
        assert.strictEqual(res.names.trt1, 'trt1.tr');
        done();
    });
});

// ------------------------------------------------------------------ HLS variant choice

var TRT1_MASTER = '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n' +
    '#EXT-X-STREAM-INF:BANDWIDTH=563200,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=640x360,FRAME-RATE=50.000\nmaster_360.m3u8\n' +
    '#EXT-X-STREAM-INF:BANDWIDTH=1284800,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=854x480,FRAME-RATE=50.000\nmaster_480.m3u8\n' +
    '#EXT-X-STREAM-INF:BANDWIDTH=4716800,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=50.000\nmaster_720.m3u8\n' +
    '#EXT-X-STREAM-INF:BANDWIDTH=8113600,CODECS="avc1.64002a,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=50.000\nmaster_1080.m3u8\n' +
    '#EXT-X-STREAM-INF:BANDWIDTH=11545600,CODECS="avc1.640033,mp4a.40.2",RESOLUTION=2560x1440,FRAME-RATE=50.000\nmaster_1440.m3u8\n';

test('hls: picks a variant a webOS 1.x decoder can play', function () {
    var v = NV.hls.parseMaster(TRT1_MASTER, 'http://tv-trt1.medya.trt.com.tr/master.m3u8');
    assert.strictEqual(v.length, 5);
    assert.strictEqual(v[3].level, 42);
    // 1080p50 (level 4.2) and 1440p exceed webOS 1.0 limits -> 720p50 (level 4.0)
    assert.strictEqual(NV.hls.pick(v, 0).url, 'http://tv-trt1.medya.trt.com.tr/master_720.m3u8');
    assert.strictEqual(NV.hls.pick(v, 1).height, 720);
    assert.strictEqual(NV.hls.pick(v, 2).height, 480);
    assert.strictEqual(NV.hls.parseMaster('#EXTM3U\n#EXTINF:6,\nseg.ts\n', 'http://x/'), null);
    assert.strictEqual(NV.hls.resolve('/a/b.m3u8', 'http://h:8080/x/y.m3u8'), 'http://h:8080/a/b.m3u8');
    assert.strictEqual(NV.hls.resolve('c.m3u8', 'http://h/x/y.m3u8?tok=1'), 'http://h/x/c.m3u8');
    assert.ok(NV.hls.isHlsUrl('http://h/live/1.m3u8?t=1') && !NV.hls.isHlsUrl('http://h/live/1.ts'));
});

// ------------------------------------------------------------------ NovaTV Link

test('link: subnet and webOS connection-manager parsing', function () {
    assert.strictEqual(NV.link._subnetOf('192.168.1.42'), '192.168.1');
    assert.strictEqual(NV.link._subnetOf('tv.local'), null);
    assert.strictEqual(NV.link._ipFromStatus({ wired: { state: 'disconnected' }, wifi: { state: 'connected', ipAddress: '10.0.0.7' } }), '10.0.0.7');
    assert.strictEqual(NV.link._ipFromStatus({ wired: { state: 'connected', ipAddress: '192.168.0.20' } }), '192.168.0.20');
    assert.strictEqual(NV.link._ipFromStatus(null), null);
    assert.strictEqual(JSON.stringify(NV.link.PORTS), '[8686,8787]');
});

// ------------------------------------------------------------------ repository

test('repository: seeds the built-in list once, numbering, favorites, order, delete', function () {
    var snap, keys, k1;
    NV.channels.init();
    snap = NV.channels.snapshot();
    assert.strictEqual(snap.channels.length, 13);
    assert.strictEqual(snap.channels[0].name, 'TRT 1');
    assert.strictEqual(snap.channels[0].number, 1);
    assert.strictEqual(snap.byNumber(3).name, 'TRT Haber');
    assert.ok(snap.groups.indexOf('Haber') >= 0);
    k1 = snap.channels[0].key;
    assert.strictEqual(NV.channels.toggleFavorite(k1), true);
    assert.ok(NV.channels.isFavorite(k1));
    keys = snap.channels.map(function (c) { return c.key; });
    NV.channels.moveToNumber(k1, 5);
    assert.strictEqual(NV.channels.snapshot().byNumber(5).key, k1);
    NV.channels.resetOrder();
    assert.strictEqual(NV.channels.snapshot().byNumber(1).key, k1);
    // deleting the built-in list is respected (not re-seeded on next start)
    NV.channels.deleteSource(NV.BUILTIN.id);
    assert.strictEqual(NV.channels.snapshot().channels.length, 0);
    assert.strictEqual(NV.channels.isFavorite(k1), false);
    NV.channels.init();
    assert.strictEqual(NV.channels.snapshot().channels.length, 0);
    assert.strictEqual(keys.length, 13);
});

// ------------------------------------------------------------------ remote management (phone panel)

var exported = null;

function exec(op, args) {
    var out = null;
    NV.manage.exec(op, args, function (r) { out = r; });
    assert.ok(out, op + ' answered synchronously in this sandbox');
    return out;
}

test('manage: own channels, hide, rename, number, favorites and persistence', function () {
    var r, st, own, trtHaber, trt1, key2, snap;
    NV.channels.addBuiltin(function (err) { assert.ifError(err); });
    trt1 = NV.channels.snapshot().byNumber(1);
    trtHaber = NV.channels.snapshot().byNumber(3);

    r = exec('channel.add', { name: 'Benim  "Kanal"', url: 'http://ornek.tv/a/index.m3u8', group: 'Yerel', logo: 'file:///x.png' });
    assert.ok(r.ok, r.error);
    assert.strictEqual(exec('channel.add', { name: 'Adsız', url: 'javascript:alert(1)' }).ok, false);
    st = NV.manage.state();
    own = st.channels.filter(function (c) { return c.custom; });
    assert.strictEqual(own.length, 1);
    assert.strictEqual(own[0].name, 'Benim Kanal');
    assert.strictEqual(own[0].logo, '');
    assert.ok(st.sources.some(function (s) { return s.kind === 'custom' && s.channelCount === 1; }));

    assert.ok(exec('channel.favorite', { key: own[0].key, on: true }).ok);
    r = exec('channel.update', { key: own[0].key, name: 'Benim Kanal 2', url: 'http://ornek.tv/b.m3u8', group: 'Yerel' });
    assert.ok(r.ok, r.error);
    key2 = r.key;
    assert.notStrictEqual(key2, own[0].key);
    assert.ok(NV.channels.isFavorite(key2), 'favorite follows the renamed own channel');
    assert.ok(!NV.channels.isFavorite(own[0].key));

    assert.ok(exec('channel.delete', { key: trt1.key }).message.indexOf('gizlendi') >= 0);
    assert.ok(exec('channel.update', { key: trtHaber.key, name: 'Haber Kanalı' }).ok);
    assert.ok(exec('channel.number', { key: key2, number: 1 }).ok);
    snap = NV.channels.snapshot();
    assert.strictEqual(snap.byNumber(1).key, key2);
    assert.strictEqual(snap.byKey(trt1.key), null);
    assert.strictEqual(snap.hidden.length, 1);
    assert.strictEqual(snap.byKey(trtHaber.key).name, 'Haber Kanalı');
    assert.strictEqual(snap.byKey(trtHaber.key).tvgName, trtHaber.tvgName || 'TRT Haber');
    assert.strictEqual(snap.channels.length, 13);

    exported = NV.manage.exportConfig();
    assert.strictEqual(exported.format, 'novatv-config');
    assert.strictEqual(JSON.stringify(exported.sources.map(function (x) { return x.ref; })), '["b"]');
    assert.strictEqual(exported.custom.length, 1);
    assert.strictEqual(exported.favorites[0][0], 'c');
    assert.strictEqual(exported.hidden[0][0], 'b');
    assert.strictEqual(exported.renamed[0][2], 'Haber Kanalı');
    assert.strictEqual(exported.order[0][0], 'c');

    // everything survives a restart
    NV.channels.init();
    snap = NV.channels.snapshot();
    assert.strictEqual(snap.byNumber(1).name, 'Benim Kanal 2');
    assert.strictEqual(snap.hidden.length, 1);
    assert.strictEqual(snap.byKey(trtHaber.key).name, 'Haber Kanalı');

    // restore + reset name, then delete the own channel
    assert.ok(exec('channel.restore', { key: trt1.key }).ok);
    assert.ok(exec('channel.update', { key: trtHaber.key, name: '' }).ok);
    assert.strictEqual(NV.channels.snapshot().byKey(trtHaber.key).name, 'TRT Haber');
    assert.ok(exec('channel.delete', { key: key2 }).message.indexOf('silindi') >= 0);
    assert.ok(!NV.channels.isFavorite(key2));
    assert.strictEqual(NV.channels.snapshot().channels.length, 13);
    assert.strictEqual(exec('nope', {}).ok, false);
});

test('manage: another TV imports the exported lists (sync)', function () {
    var r, snap;
    Object.keys(storage).forEach(function (k) { delete storage[k]; });
    NV.channels.init(); // built-in list not re-seeded: the setting is remembered
    assert.strictEqual(NV.channels.snapshot().channels.length, 0);
    r = exec('config.import', { config: exported });
    assert.ok(r.ok, r.error);
    assert.ok(/1 kaynak, 1 kanal, 1 favori, 1 gizleme, 1 kanal adı, kanal sırası/.test(r.message), r.message);
    snap = NV.channels.snapshot();
    assert.strictEqual(snap.byNumber(1).name, 'Benim Kanal 2');
    assert.ok(NV.channels.isFavorite(snap.byNumber(1).key));
    assert.strictEqual(snap.hidden.length, 1);
    assert.ok(snap.channels.some(function (c) { return c.name === 'Haber Kanalı'; }));
    // importing again adds nothing
    r = exec('config.import', { config: exported });
    assert.ok(/^Eşitlendi: 0 kaynak alındı$/.test(r.message), r.message);
    assert.strictEqual(exec('config.import', { config: { format: 'x' } }).ok, false);
});

test('manage: the phone panel is the same file in the proxy and the Android app', function () {
    var android = path.join(root, '..', 'NovaTV', 'app', 'src', 'main', 'assets', 'link', 'manage.html');
    if (!fs.existsSync(android)) return; // only checked when both projects are side by side
    assert.ok(fs.readFileSync(android).equals(fs.readFileSync(path.join(root, 'proxy', 'manage.html'))), 'proxy/manage.html differs from the Android copy');
});

// ------------------------------------------------------------------ runner

(function run(i) {
    var t, finished = false, timer;
    if (i >= tests.length) {
        console.log('\n' + passed + '/' + tests.length + ' tests passed');
        process.exit(passed === tests.length ? 0 : 1);
    }
    t = tests[i];
    function next(err) {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (err) console.log('FAIL ' + t.name + '\n     ' + (err.stack || err).toString().split('\n').slice(0, 3).join('\n     '));
        else { passed++; console.log('ok   ' + t.name); }
        run(i + 1);
    }
    timer = setTimeout(function () { next(new Error('timeout')); }, 5000);
    try {
        if (t.fn.length) t.fn(function (e) { next(e); });
        else { t.fn(); next(); }
    } catch (e) { next(e); }
}(0));
