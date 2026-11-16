/*
 * NovaTV webOS — start-up guard. Loaded before every other script.
 *
 * A TV has no console: if a script fails to load or throws while the app starts, the user would
 * only see an empty dark screen ("the app does not open"). This guard turns such failures into a
 * readable error screen with the message, file and line, plus the engine details, so the problem
 * can be photographed and reported. OK reloads the app; any other key closes the screen.
 *
 * app.js calls NV_BOOT.fail(step, error) for each start-up step that throws and NV_BOOT.done()
 * at the end. If done() is not reached within 10 s, the screen lists the missing modules.
 */
(function () {
    'use strict';

    var MODULES = ['store', 'settings', 'http', 'BUILTIN', 'channels', 'epg', 'link', 'manage', 'manageRelay', 'hls', 'Player',
        'input', 'ui', 'dialog', 'sourceEditor', 'linkScreen', 'manageScreen', 'sections', 'home', 'playerUi'];
    var errors = [], ready = false, box = null, reportedAfterBoot = {}, afterBootCount = 0;

    function where(file, line) {
        var name = String(file || '').replace(/^.*\//, '').replace(/\?.*$/, '');
        return name ? ' (' + name + (line ? ':' + line : '') + ')' : '';
    }

    function environment() {
        var nv = window.NV;
        return 'NovaTV ' + (nv && nv.VERSION ? nv.VERSION : '?') +
            ' • ekran ' + window.innerWidth + 'x' + window.innerHeight +
            ' • webOS köprüsü: ' + (typeof window.PalmSystem !== 'undefined' ? 'var' : 'yok') +
            '\n' + navigator.userAgent;
    }

    function hide() {
        if (box) box.style.display = 'none';
    }

    function render() {
        var title;
        if (!document.body) {
            document.addEventListener('DOMContentLoaded', render, false);
            return;
        }
        if (!box) {
            box = document.createElement('div');
            box.id = 'boot-error';
            box.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:99999;' +
                '-webkit-box-sizing:border-box;box-sizing:border-box;padding:56px 72px;overflow:hidden;' +
                'background:#0A0C11;color:#E8ECF3;font:22px/1.45 sans-serif;white-space:pre-wrap;';
            document.body.appendChild(box);
        }
        title = ready ? 'NovaTV çalışırken bir hata oluştu' : 'NovaTV açılırken bir hata oluştu';
        box.textContent = title + '\n\n' + errors.slice(-8).join('\n') +
            '\n\nBu ekranın fotoğrafını hata bildirimiyle birlikte gönderin.\n\n' + environment() +
            '\n\nOK: yeniden dene   •   başka bir tuş: kapat';
        box.style.display = 'block';
    }

    function record(text) {
        errors.push('• ' + text);
        if (!ready) { render(); return; }
        // After a successful start, errors are shown briefly instead of covering the app.
        if (reportedAfterBoot[text] || afterBootCount >= 3) return;
        reportedAfterBoot[text] = true;
        afterBootCount++;
        if (window.NV && window.NV.ui && typeof window.NV.ui.osd === 'function') window.NV.ui.osd('Hata: ' + text, 6000);
    }

    window.onerror = function (message, file, line) {
        record(String(message) + where(file, line));
        return false;
    };

    // Capture phase on window: runs before the app's own key handlers.
    window.addEventListener('keydown', function (e) {
        if (!box || box.style.display === 'none') return;
        e.preventDefault();
        e.stopPropagation();
        if (e.keyCode === 13) window.location.reload(); else hide();
    }, true);

    setTimeout(function () {
        var missing = [], i, nv = window.NV;
        if (ready || errors.length) return;
        for (i = 0; i < MODULES.length; i++) if (!nv || !nv[MODULES[i]]) missing.push(MODULES[i]);
        record('Başlatma 10 saniyede tamamlanmadı.' + (missing.length ? ' Yüklenmeyen bileşenler: ' + missing.join(', ') : ''));
    }, 10000);

    window.NV_BOOT = {
        /** A start-up step threw; e may carry JavaScriptCore's sourceURL/line. */
        fail: function (step, e) {
            var msg = e && e.message ? e.message : String(e);
            record(step + ': ' + msg + where(e && (e.sourceURL || e.fileName), e && (e.line || e.lineNumber)));
        },
        /** Start-up finished. Earlier errors keep the error screen; otherwise the app is ready. */
        done: function () {
            if (errors.length) render();
            ready = true;
        },
        isReady: function () { return ready; },
        errors: function () { return errors.slice(); }
    };
}());
