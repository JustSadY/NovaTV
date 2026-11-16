/*
 * Start-up test in PhantomJS 2.1 (WebKit 538.1), loading the app from file:// like the TV does.
 * Checks that a clean copy starts without the error screen, and that broken copies (a module that
 * throws during start-up, a missing script, a syntax error) show the error screen instead of an
 * empty page.
 *
 * Usage: phantomjs tests/phantom-boot.js <app-dir> <empty-work-dir>
 */
var fs = require('fs');
var system = require('system');
var appDir = fs.absolute(system.args[1]), work = fs.absolute(system.args[2]);
var results = [];

function check(name, ok, detail) { results.push({ name: name, ok: !!ok, detail: detail }); }

var CASES = [
    { name: 'clean copy starts', expectError: false },
    {
        name: 'module throwing in init shows step, message and file',
        expect: ['Ana ekran: deneme hatası', 'home.js'],
        breakIt: function (dir) {
            fs.write(dir + '/js/ui/home.js', '\nNV.home.init = function () { throw new Error("deneme hatası"); };\n', 'a');
        }
    },
    {
        name: 'missing script file is reported',
        expect: ['Yayın akışı (EPG)'],
        breakIt: function (dir) { fs.remove(dir + '/js/data/epg.js'); }
    },
    {
        name: 'syntax error in a script is reported with its file',
        expect: ['link.js'],
        breakIt: function (dir) { fs.write(dir + '/js/data/link.js', '\nvar broken = {;\n', 'a'); }
    }
];

function runCase(index) {
    var c = CASES[index], dir, page;
    if (!c) {
        results.forEach(function (r) { console.log((r.ok ? 'ok   ' : 'FAIL ') + r.name + (r.ok ? '' : '  -> ' + r.detail)); });
        var failed = results.filter(function (r) { return !r.ok; }).length;
        console.log('\n' + (results.length - failed) + '/' + results.length + ' checks passed');
        phantom.exit(failed ? 1 : 0);
        return;
    }
    dir = work + '/case' + index;
    if (fs.exists(dir)) fs.removeTree(dir);
    fs.copyTree(appDir, dir);
    if (c.breakIt) c.breakIt(dir);

    page = require('webpage').create();
    page.viewportSize = { width: 1280, height: 720 };
    page.onError = function () { /* reported on screen by the guard; that is what is tested */ };
    page.open('file://' + dir + '/index.html', function (status) {
        if (status !== 'success') { check(c.name, false, 'cannot open ' + dir); page.close(); runCase(index + 1); return; }
        setTimeout(function () {
            var st = page.evaluate(function () {
                var box = document.getElementById('boot-error');
                return {
                    shown: !!box && box.style.display !== 'none',
                    text: box ? box.textContent : '',
                    ready: !!(window.NV_BOOT && window.NV_BOOT.isReady()),
                    navItems: document.querySelectorAll('.nav-item').length
                };
            });
            if (c.expectError === false) {
                check(c.name + ' (no error screen, home built)', !st.shown && st.ready && st.navItems === 7, JSON.stringify(st));
            } else {
                var missing = c.expect.filter(function (s) { return st.text.indexOf(s) < 0; });
                check(c.name, st.shown && !missing.length, 'missing ' + JSON.stringify(missing) + ' in: ' + st.text.replace(/\n/g, ' | '));
                // Any key other than OK closes the error screen.
                page.evaluate(function () {
                    var e = document.createEvent('Events');
                    e.initEvent('keydown', true, true);
                    e.keyCode = 39; e.which = 39;
                    document.dispatchEvent(e);
                });
                var closed = page.evaluate(function () { return document.getElementById('boot-error').style.display === 'none'; });
                check(c.name + ' — closes with a key', closed, 'still visible');
            }
            page.close();
            runCase(index + 1);
        }, 2500);
    });
}

runCase(0);
