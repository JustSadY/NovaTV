/*
 * NovaTV webOS — base helpers.
 * Target engine: webOS TV 1.x (WebKit 537.41). ES5 only: no let/const, arrow functions,
 * template strings, Promise, fetch, Map/Set, Array.prototype.find, Object.assign ...
 */
var NV = window.NV || {};
window.NV = NV;

(function (NV) {
    'use strict';

    NV.VERSION = '1.1.0';

    var rafImpl = window.requestAnimationFrame || window.webkitRequestAnimationFrame ||
        function (cb) { return setTimeout(cb, 16); };

    NV.raf = function (cb) { return rafImpl.call(window, cb); };

    NV.$ = function (id) { return document.getElementById(id); };

    NV.el = function (tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined && text !== null) e.textContent = String(text);
        return e;
    };

    NV.clear = function (node) {
        while (node && node.firstChild) node.removeChild(node.firstChild);
    };

    NV.show = function (node, visible) {
        if (!node) return;
        if (visible) node.classList.remove('hidden'); else node.classList.add('hidden');
    };

    NV.isShown = function (node) {
        return !!node && !node.classList.contains('hidden');
    };

    NV.startsWith = function (s, p) { return s.lastIndexOf(p, 0) === 0; };

    NV.endsWith = function (s, p) { return s.length >= p.length && s.indexOf(p, s.length - p.length) !== -1; };

    NV.pad2 = function (n) { return n < 10 ? '0' + n : String(n); };

    NV.now = function () { return new Date().getTime(); };

    var MONTHS = ['Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz', 'Tem', 'Ağu', 'Eyl', 'Eki', 'Kas', 'Ara'];
    var DAYS = ['Paz', 'Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt'];

    NV.hm = function (ms) {
        var d = new Date(ms);
        return NV.pad2(d.getHours()) + ':' + NV.pad2(d.getMinutes());
    };

    NV.dateTime = function (ms) {
        var d = new Date(ms);
        return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + NV.hm(ms);
    };

    NV.weekdayTime = function (ms) {
        return DAYS[new Date(ms).getDay()] + ' ' + NV.hm(ms);
    };

    NV.debounce = function (fn, ms) {
        var t = null;
        return function () {
            var self = this, args = arguments;
            if (t) clearTimeout(t);
            t = setTimeout(function () { t = null; fn.apply(self, args); }, ms);
        };
    };

    NV.copy = function (obj) {
        var out = {}, k;
        for (k in obj) if (Object.prototype.hasOwnProperty.call(obj, k)) out[k] = obj[k];
        return out;
    };

    NV.extend = function (target, src) {
        var k;
        for (k in src) if (Object.prototype.hasOwnProperty.call(src, k)) target[k] = src[k];
        return target;
    };

    // ---------------------------------------------------------------- text normalisation
    // String.prototype.normalize does not exist on WebKit 537, so accents are folded manually.
    var FOLD = {
        'ç': 'c', 'ğ': 'g', 'ı': 'i', 'ö': 'o', 'ş': 's', 'ü': 'u', 'â': 'a', 'î': 'i', 'û': 'u',
        'é': 'e', 'è': 'e', 'ê': 'e', 'ë': 'e', 'á': 'a', 'à': 'a', 'ä': 'a', 'å': 'a', 'ã': 'a',
        'ó': 'o', 'ò': 'o', 'ô': 'o', 'õ': 'o', 'ú': 'u', 'ù': 'u', 'ñ': 'n', 'í': 'i', 'ì': 'i', 'ï': 'i'
    };
    var FOLD_RE = /[çğıöşüâîûéèêëáàäåãóòôõúùñíìï]/g;

    /** Lowercase, Turkish/accent-insensitive key used for search. */
    NV.searchKey = function (s) {
        return String(s || '').toLowerCase().replace(/̇/g, '').replace(FOLD_RE, function (c) { return FOLD[c]; });
    };

    NV.identity = function (s) {
        return NV.searchKey(s).replace(/^\s+|\s+$/g, '').replace(/\s+/g, ' ');
    };

    var COUNTRY_PREFIX = /^(tr|us|uk|de|fr|nl|it|es)\s*[:|\-]+/;
    var QUALITY_SUFFIX = /(fhd|uhd|hd|sd|4k|8k|hevc|h265|1080p|720p|backup|yedek)$/;

    /** Compact key used to match playlist names with XMLTV display names (same rules as Android). */
    NV.epgKey = function (s) {
        var k = NV.searchKey(s).replace(/^\s+|\s+$/g, '').replace(COUNTRY_PREFIX, '').replace(/[^a-z0-9]+/g, '');
        var prev;
        do {
            prev = k;
            k = k.replace(QUALITY_SUFFIX, '');
        } while (k !== prev && k.length > 0);
        return k.length ? k : prev;
    };

    NV.initials = function (name) {
        var words = String(name || '').replace(/^\s+|\s+$/g, '').split(/[\s_\-|:]+/), out = [], i;
        for (i = 0; i < words.length && out.length < 2; i++) {
            if (words[i] && /[0-9A-Za-zÇĞİÖŞÜçğıöşü]/.test(words[i].charAt(0))) out.push(words[i]);
        }
        if (!out.length) return 'TV';
        if (out.length === 1) return out[0].substring(0, 2).toUpperCase();
        return (out[0].charAt(0) + out[1].charAt(0)).toUpperCase();
    };

    var PALETTE = ['#2F5FAF', '#2E7D6B', '#7A4FB5', '#B5574F', '#4F7FB5', '#8A6D2F', '#3F8F9F', '#9F3F6F'];

    NV.colorFor = function (name) {
        var h = 0, s = String(name || ''), i;
        for (i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
        return PALETTE[Math.abs(h) % PALETTE.length];
    };

    // ---------------------------------------------------------------- events

    var listeners = {};

    NV.events = {
        on: function (name, fn) {
            (listeners[name] = listeners[name] || []).push(fn);
        },
        off: function (name, fn) {
            var l = listeners[name], i;
            if (!l) return;
            for (i = l.length - 1; i >= 0; i--) if (l[i] === fn) l.splice(i, 1);
        },
        emit: function (name, data) {
            var l = (listeners[name] || []).slice(), i;
            for (i = 0; i < l.length; i++) {
                try { l[i](data); } catch (e) { NV.log('event ' + name + ' failed: ' + e); }
            }
        }
    };

    NV.log = function (msg) {
        if (window.console && console.log) console.log('[NovaTV] ' + msg);
    };

    /** True when running inside the webOS web app manager (PalmSystem bridge present). */
    NV.isWebOS = function () { return typeof window.PalmSystem !== 'undefined'; };
}(NV));
