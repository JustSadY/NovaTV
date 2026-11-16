#!/usr/bin/env node
/*
 * Verifies that every app script is plain ES5 (webOS TV 1.x = WebKit 537.41):
 *  1. parses with acorn in ecmaVersion 5 (rejects let/const, arrows, classes, template strings ...)
 *  2. rejects ES2015+ library APIs the old engine lacks (Promise, fetch, Object.assign, find ...)
 *  3. rejects CSS features the old engine lacks (flexbox, grid, var(), calc(), object-fit).
 * Usage: node tools/check-es5.js   (needs `npm install acorn` once, see README)
 */
var fs = require('fs');
var path = require('path');
var acorn;
try { acorn = require('acorn'); } catch (e) {
    try { acorn = require(process.env.ACORN_PATH || 'acorn'); } catch (e2) { console.error('acorn is not installed: npm install acorn'); process.exit(2); }
}

var root = path.resolve(__dirname, '..');
var failures = 0;

function walk(dir, ext, out) {
    fs.readdirSync(dir).forEach(function (f) {
        var p = path.join(dir, f);
        if (fs.statSync(p).isDirectory()) walk(p, ext, out);
        else if (path.extname(p) === ext) out.push(p);
    });
    return out;
}

var BANNED_JS = [
    [/\bPromise\b/, 'Promise'], [/\bfetch\s*\(/, 'fetch()'], [/Object\.assign\b/, 'Object.assign'],
    [/\.find\s*\(/, 'Array.prototype.find'], [/\.findIndex\s*\(/, 'findIndex'], [/\.includes\s*\(/, 'includes()'],
    [/(?<!NV)\.startsWith\s*\(/, 'String.startsWith'], [/(?<!NV)\.endsWith\s*\(/, 'String.endsWith'], [/\.repeat\s*\(/, 'String.repeat'],
    [/\.padStart\s*\(|\.padEnd\s*\(/, 'padStart/padEnd'], [/Array\.from\b/, 'Array.from'], [/\bnew (Map|Set|WeakMap)\b/, 'Map/Set'],
    [/\.normalize\s*\(/, 'String.normalize'], [/\bSymbol\b/, 'Symbol'], [/\.closest\s*\(/, 'Element.closest'],
    [/\.remove\s*\(\s*\)/, 'ChildNode.remove()'], [/\bURLSearchParams\b/, 'URLSearchParams'], [/\basync\b|\bawait\b/, 'async/await']
];
var BANNED_CSS = [
    [/display\s*:\s*(-webkit-)?(flex|inline-flex|grid)/, 'flexbox/grid'], [/var\(--/, 'CSS variables'],
    [/\bcalc\(/, 'calc()'], [/object-fit/, 'object-fit'], [/\bgap\s*:/, 'gap']
];

function stripComments(src) {
    return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:\\])\/\/.*$/gm, '$1');
}

walk(path.join(root, 'js'), '.js', []).forEach(function (file) {
    var src = fs.readFileSync(file, 'utf8'), rel = path.relative(root, file), code;
    try {
        acorn.parse(src, { ecmaVersion: 5, sourceType: 'script' });
    } catch (e) {
        failures++;
        console.log('SYNTAX  ' + rel + ': ' + e.message);
    }
    code = stripComments(src).replace(/'(?:\\.|[^'\\])*'/g, "''");
    BANNED_JS.forEach(function (b) {
        if (b[0].test(code)) { failures++; console.log('API     ' + rel + ': uses ' + b[1]); }
    });
});

walk(path.join(root, 'css'), '.css', []).forEach(function (file) {
    var css = stripComments(fs.readFileSync(file, 'utf8')), rel = path.relative(root, file);
    BANNED_CSS.forEach(function (b) {
        if (b[0].test(css)) { failures++; console.log('CSS     ' + rel + ': uses ' + b[1]); }
    });
});

var info = JSON.parse(fs.readFileSync(path.join(root, 'appinfo.json'), 'utf8'));
['id', 'version', 'vendor', 'type', 'main', 'title', 'icon'].forEach(function (k) {
    if (!info[k]) { failures++; console.log('APPINFO missing "' + k + '"'); }
});
['main', 'icon', 'largeIcon', 'splashBackground'].forEach(function (k) {
    if (info[k] && !fs.existsSync(path.join(root, info[k]))) { failures++; console.log('APPINFO file not found: ' + info[k]); }
});
if (!/^\d+\.\d+\.\d+$/.test(info.version)) { failures++; console.log('APPINFO version must be x.y.z'); }

var html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
(html.match(/src="([^"]+)"/g) || []).concat(html.match(/href="([^"]+)"/g) || []).forEach(function (m) {
    var ref = m.replace(/^(src|href)="/, '').replace(/"$/, '');
    if (!/^https?:/.test(ref) && !fs.existsSync(path.join(root, ref))) { failures++; console.log('MISSING ' + ref + ' (referenced by index.html)'); }
});

if (failures) { console.log('\n' + failures + ' problem(s) found.'); process.exit(1); }
console.log('OK: all scripts are ES5, no unsupported APIs/CSS, appinfo.json and index.html references are valid.');
