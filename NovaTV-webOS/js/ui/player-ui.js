/*
 * NovaTV webOS — fullscreen live TV (port of Android PlayerActivity), satellite-receiver style:
 *   UP / CH+ next channel, DOWN / CH- previous (if the firmware forwards CH keys), LEFT channel
 *   list, RIGHT quick menu, OK info banner (retry on the error card), 0-9 channel number,
 *   YELLOW favorite, BLUE previous channel, Play/Pause, Back: close overlay -> home.
 */
(function (NV) {
    'use strict';

    var K = NV.keys;
    var ZAP_IMMEDIATE_GAP = 600, ZAP_DEBOUNCE = 380, PANEL_ROWS = 7;

    NV.playerUi = (function () {
        var root, video, player, layer, current = null, zapGroup = 'all', visible = false, readyKey = null;
        var pendingTune = null, tuneTimer = null, lastTuneAt = 0;
        var digits = '', digitTimer = null;
        var bannerTimer = null, bannerChannel = null;
        var panel, panelList, panelGroups = [], panelGroupIndex = 0, panelTimer = null;
        var menuGroup = null, menuActions = [], menuTimer = null;
        var self;

        function $(id) { return NV.$(id); }

        // ------------------------------------------------------------ banner

        function quality() {
            var h = player.info().height;
            if (!readyKey || !current || readyKey !== current.key || !h) return null;
            return h >= 2000 ? '4K' : h >= 1000 ? 'FHD' : h >= 700 ? 'HD' : 'SD';
        }

        function showBanner(ch) {
            var nn = NV.epg.nowNext(ch), q = ch === current ? quality() : null, now = nn.now;
            bannerChannel = ch;
            $('ib-number').textContent = ch.number ? String(ch.number) : '';
            NV.ui.bindLogo($('ib-logo').parentNode, ch.logo, ch.name);
            $('ib-name').textContent = ch.name;
            NV.show($('ib-fav'), NV.channels.isFavorite(ch.key));
            $('ib-source').textContent = ch.sourceName || '';
            NV.show($('ib-source'), !!ch.multi);
            $('ib-quality').textContent = q || '';
            NV.show($('ib-quality'), !!q);
            $('ib-group').textContent = ch.group || 'Diğer';
            $('ib-clock').textContent = NV.hm(NV.now());
            if (now) {
                $('ib-now').textContent = NV.hm(now[0]) + ' – ' + NV.hm(now[1]) + '   ' + now[2];
                $('ib-progress').parentNode.style.display = '';
                $('ib-progress').style.width = Math.max(0, Math.min(100, (NV.now() - now[0]) * 100 / (now[1] - now[0]))) + '%';
            } else {
                $('ib-now').textContent = 'Program bilgisi yok';
                $('ib-progress').parentNode.style.display = 'none';
            }
            $('ib-next').textContent = nn.next ? 'Sonra  ' + NV.hm(nn.next[0]) + '   ' + nn.next[2] : '';
            NV.show($('banner'), true);
            if (bannerTimer) clearTimeout(bannerTimer);
            bannerTimer = setTimeout(hideBanner, Math.max(2, NV.settings.get('bannerSeconds')) * 1000);
        }

        function hideBanner() {
            if (bannerTimer) { clearTimeout(bannerTimer); bannerTimer = null; }
            NV.show($('banner'), false);
        }

        function bannerVisible() { return NV.isShown($('banner')); }

        function refreshBanner() { if (bannerVisible() && bannerChannel) showBanner(bannerChannel); }

        // ------------------------------------------------------------ error / loading

        function showError(ch, message, retryText) {
            $('err-channel').textContent = ch ? (ch.number ? ch.number + '  ' : '') + ch.name : '';
            $('err-message').textContent = message;
            $('err-retry').textContent = retryText || '';
            NV.show($('player-error'), true);
        }

        function hideError() { NV.show($('player-error'), false); }

        // ------------------------------------------------------------ tuning

        function groupChannels(groupId) {
            var snap = NV.channels.snapshot(), out = [], favs, i, c;
            if (groupId === 'fav') {
                favs = NV.channels.favorites();
                for (i = 0; i < favs.length; i++) { c = snap.byKey(favs[i]); if (c) out.push(c); }
                return out;
            }
            if (groupId && groupId.indexOf('g:') === 0) return snap.inGroup(groupId.substring(2));
            return snap.channels;
        }

        function tuneNow(ch, withBanner) {
            if (tuneTimer) { clearTimeout(tuneTimer); tuneTimer = null; }
            pendingTune = null;
            if (current && current.key !== ch.key) NV.settings.setPreviousKey(current.key);
            current = ch;
            readyKey = null;
            lastTuneAt = NV.now();
            hideError();
            if (withBanner !== false) showBanner(ch);
            if (panelList) panelList.refresh();
            player.play(ch);
            NV.settings.setLastChannel(ch);
            NV.channels.addRecent(ch.key);
        }

        function zap(delta) {
            var base = pendingTune || current, all = NV.channels.snapshot().channels, list = groupChannels(zapGroup), i, idx = -1, target, now;
            if (base) for (i = 0; i < list.length; i++) if (list[i].key === base.key) { idx = i; break; }
            if (idx < 0) {
                list = all;
                if (base) for (i = 0; i < list.length; i++) if (list[i].key === base.key) { idx = i; break; }
            }
            if (!list.length) return;
            idx = idx < 0 ? (delta > 0 ? 0 : list.length - 1) : ((idx + delta) % list.length + list.length) % list.length;
            target = list[idx];
            showBanner(target);
            now = NV.now();
            if (!pendingTune && now - lastTuneAt > ZAP_IMMEDIATE_GAP) {
                tuneNow(target, false);
            } else {
                // Rapid presses only move the banner; the stream opens when the user stops.
                pendingTune = target;
                if (tuneTimer) clearTimeout(tuneTimer);
                tuneTimer = setTimeout(function () { tuneTimer = null; if (pendingTune) tuneNow(pendingTune, false); }, ZAP_DEBOUNCE);
            }
        }

        function recall() {
            var prev = NV.channels.snapshot().byKey(NV.settings.previousKey());
            if (prev) tuneNow(prev);
        }

        // ------------------------------------------------------------ number entry

        function onDigit(d) {
            var total = NV.channels.snapshot().channels.length, maxDigits = String(Math.max(1, total)).length;
            if (digits.length >= 4) digits = '';
            digits += String(d);
            $('number-entry').textContent = digits;
            NV.show($('number-entry'), true);
            if (digitTimer) clearTimeout(digitTimer);
            digitTimer = setTimeout(commitNumber, digits.length >= maxDigits ? 500 : 2000);
        }

        function cancelNumber() {
            if (digitTimer) { clearTimeout(digitTimer); digitTimer = null; }
            digits = '';
            NV.show($('number-entry'), false);
        }

        function commitNumber() {
            var n = parseInt(digits, 10), ch;
            cancelNumber();
            if (!n) return;
            ch = NV.channels.snapshot().byNumber(n);
            if (!ch) { NV.ui.osd(n + ' numaralı kanal bulunamadı'); return; }
            closePanel();
            closeMenu();
            zapGroup = 'all';
            tuneNow(ch);
        }

        // ------------------------------------------------------------ channel panel

        function buildGroups() {
            var snap = NV.channels.snapshot(), i;
            panelGroups = [{ id: 'all', title: 'Tüm Kanallar' }, { id: 'fav', title: 'Favoriler' }];
            for (i = 0; i < snap.groups.length; i++) panelGroups.push({ id: 'g:' + snap.groups[i], title: snap.groups[i] || 'Diğer' });
        }

        function showPanelGroup(keepKey) {
            var g = panelGroups[panelGroupIndex], items = groupChannels(g.id), idx = 0, i;
            $('panel-group').textContent = g.title;
            $('panel-count').textContent = items.length + ' kanal';
            for (i = 0; i < items.length; i++) if (items[i].key === keepKey) { idx = i; break; }
            panelList.setItems(items, idx);
            panelList.setActive(true);
            NV.show($('panel-empty'), !items.length);
            if (!items.length) panelEpg(null);
        }

        function panelEpg(ch) {
            var nn, now;
            if (!ch) { $('panel-now').textContent = ''; $('panel-next').textContent = ''; $('panel-progress').parentNode.style.display = 'none'; return; }
            nn = NV.epg.nowNext(ch);
            now = nn.now;
            if (now) {
                $('panel-now').textContent = NV.hm(now[0]) + ' – ' + NV.hm(now[1]) + '  ' + now[2];
                $('panel-progress').parentNode.style.display = '';
                $('panel-progress').style.width = Math.max(0, Math.min(100, (NV.now() - now[0]) * 100 / (now[1] - now[0]))) + '%';
            } else {
                $('panel-now').textContent = ch.name;
                $('panel-progress').parentNode.style.display = 'none';
            }
            $('panel-next').textContent = nn.next ? 'Sonra  ' + NV.hm(nn.next[0]) + '  ' + nn.next[2] : (now ? '' : 'Program bilgisi yok');
        }

        function bumpPanel() {
            if (panelTimer) clearTimeout(panelTimer);
            panelTimer = setTimeout(closePanel, 30000);
        }

        function openPanel(groupId) {
            var i, items;
            if (!NV.channels.snapshot().channels.length) return;
            hideBanner();
            closeMenu();
            buildGroups();
            panelGroupIndex = 0;
            for (i = 0; i < panelGroups.length; i++) if (panelGroups[i].id === groupId) { panelGroupIndex = i; break; }
            items = groupChannels(panelGroups[panelGroupIndex].id);
            if (current && !items.some(function (c) { return c.key === current.key; })) panelGroupIndex = 0;
            NV.show(panel, true);
            showPanelGroup(current ? current.key : null);
            bumpPanel();
        }

        function closePanel() {
            if (panelTimer) { clearTimeout(panelTimer); panelTimer = null; }
            NV.show(panel, false);
        }

        function panelOpen() { return NV.isShown(panel); }

        function panelKey(code) {
            var ch = panelList.current();
            bumpPanel();
            if (code === K.UP) { panelList.move(-1); return true; }
            if (code === K.DOWN) { panelList.move(1); return true; }
            if (code === K.CH_UP) { panelList.move(-8); return true; }
            if (code === K.CH_DOWN) { panelList.move(8); return true; }
            if (code === K.LEFT || code === K.RIGHT) {
                panelGroupIndex = (panelGroupIndex + (code === K.LEFT ? -1 : 1) + panelGroups.length) % panelGroups.length;
                showPanelGroup(current ? current.key : null);
                return true;
            }
            if (code === K.OK && ch) {
                zapGroup = panelGroups[panelGroupIndex].id;
                if (current && ch.key === current.key) closePanel(); else tuneNow(ch);
                return true;
            }
            if ((code === K.YELLOW || code === K.RED) && ch) {
                NV.ui.osd(ch.name + (NV.channels.toggleFavorite(ch.key) ? ' favorilere eklendi' : ' favorilerden çıkarıldı'));
                panelList.refresh();
                return true;
            }
            return true;
        }

        // ------------------------------------------------------------ quick menu

        function openMenu() {
            var ch = current, items = $('qmenu-items'), aspects = ['Sığdır', 'Ekranı doldur', 'Yakınlaştır'], defs, i, row, els = [];
            if (!ch) return;
            hideBanner();
            closePanel();
            defs = [
                ['star', NV.channels.isFavorite(ch.key) ? 'Favorilerden çıkar' : 'Favorilere ekle', function () { toggleFavorite(ch); }],
                ['aspect', 'Görüntü: ' + aspects[NV.settings.get('aspectMode')], function () {
                    NV.dialog.options('Görüntü oranı', aspects, NV.settings.get('aspectMode'), function (k) {
                        NV.settings.set('aspectMode', k);
                        player.setAspect(k);
                    });
                }],
                ['list', 'Kanal listesi', function () { openPanel(zapGroup); }],
                ['guide', 'Program rehberi', function () { showGuide(ch); }],
                ['refresh', 'Yayını yeniden yükle', function () { hideError(); player.retryNow(); }],
                ['info', 'Yayın bilgisi', function () { showStreamInfo(ch); }],
                ['home', 'Ana menü', function () { self.close(); }]
            ];
            NV.clear(items);
            menuActions = [];
            for (i = 0; i < defs.length; i++) {
                row = NV.el('div', 'row menu-row');
                row.appendChild(NV.el('img', 'row-icon')).src = 'assets/icons/' + defs[i][0] + '.svg';
                row.appendChild(NV.el('span', 'row-title', defs[i][1]));
                items.appendChild(row);
                els.push(row);
                menuActions.push(defs[i][2]);
            }
            $('qmenu-channel').textContent = (ch.number ? ch.number + '  ' : '') + ch.name;
            menuGroup = NV.ui.focusGroup(els);
            menuGroup.setActive(true);
            menuGroup.set(0);
            NV.show($('qmenu'), true);
            bumpMenu();
        }

        function bumpMenu() {
            if (menuTimer) clearTimeout(menuTimer);
            menuTimer = setTimeout(closeMenu, 20000);
        }

        function closeMenu() {
            if (menuTimer) { clearTimeout(menuTimer); menuTimer = null; }
            NV.show($('qmenu'), false);
        }

        function menuOpen() { return NV.isShown($('qmenu')); }

        function menuKey(code) {
            bumpMenu();
            if (code === K.UP) { menuGroup.move(-1); return true; }
            if (code === K.DOWN) { menuGroup.move(1); return true; }
            if (code === K.LEFT) { closeMenu(); return true; }
            if (code === K.OK) {
                closeMenu();
                menuActions[menuGroup.index]();
                return true;
            }
            return true;
        }

        // ------------------------------------------------------------ dialogs

        function toggleFavorite(ch) {
            var now = NV.channels.toggleFavorite(ch.key);
            NV.ui.osd(ch.name + (now ? ' favorilere eklendi' : ' favorilerden çıkarıldı'));
            refreshBanner();
        }

        function showGuide(ch) {
            var list = NV.epg.upcoming(ch, 30), now = NV.now(), text;
            if (!list.length) {
                text = 'Bu kanal için EPG verisi yok. Ayarlar\'dan veya kaynak düzenleme ekranından bir XMLTV adresi ekleyebilirsiniz.';
            } else {
                text = list.map(function (p) {
                    return (p[0] <= now && p[1] > now ? '▶ ' : '    ') + NV.weekdayTime(p[0]) + '–' + NV.hm(p[1]) + '   ' + p[2];
                }).join('\n');
            }
            NV.dialog.message(ch.name + ' — program rehberi', text);
        }

        function showStreamInfo(ch) {
            var info = player.info(), v = info.variant, host = String(ch.url).replace(/^[a-z]+:\/\//i, '').split('/')[0].split('@').pop();
            NV.dialog.message('Yayın bilgisi',
                'Kanal: ' + ch.name +
                '\nKaynak: ' + (ch.sourceName || '—') +
                '\nÇözünürlük: ' + (info.width ? info.width + '×' + info.height : 'Bilinmiyor') +
                '\nSeçilen HLS katmanı: ' + (v ? (v.height ? v.height + 'p' : '?') + (v.fps ? ' ' + Math.round(v.fps) + ' fps' : '') + ' • ' + Math.round(v.bandwidth / 1000) + ' kbps' + (v.codecs ? ' • ' + v.codecs : '') : 'TV seçiyor / tek katman') +
                '\nSunucu: ' + host +
                '\nProxy: ' + (player._useProxy() ? 'Evet' : 'Hayır') +
                '\n\nNovaTV ' + NV.VERSION + ' (webOS)');
        }

        // ------------------------------------------------------------ keys

        function handleKey(code) {
            var d = NV.input.digitOf(code);
            if (d >= 0) { onDigit(d); return true; }
            if (menuOpen()) return menuKey(code);
            if (panelOpen()) return panelKey(code);
            switch (code) {
            case K.UP: zap(NV.settings.get('invertUpDown') ? -1 : 1); return true;
            case K.DOWN: zap(NV.settings.get('invertUpDown') ? 1 : -1); return true;
            case K.CH_UP: zap(1); return true;
            case K.CH_DOWN: zap(-1); return true;
            case K.LEFT: openPanel(zapGroup); return true;
            case K.RIGHT: openMenu(); return true;
            case K.OK:
                if (digits) commitNumber();
                else if (NV.isShown($('player-error'))) { hideError(); player.retryNow(); }
                else if (bannerVisible()) hideBanner();
                else if (current) showBanner(current);
                return true;
            case K.INFO: if (bannerVisible()) hideBanner(); else if (current) showBanner(current); return true;
            case K.YELLOW: if (current) toggleFavorite(current); return true;
            case K.BLUE: recall(); return true;
            case K.RED: openMenu(); return true;
            case K.GREEN: openPanel('fav'); return true;
            case K.PLAY: case K.PAUSE: case K.PLAY_PAUSE:
                player.togglePause();
                if (player.isPaused()) NV.ui.osd('Duraklatıldı');
                return true;
            default: return false;
            }
        }

        function onBack() {
            if (digits) cancelNumber();
            else if (menuOpen()) closeMenu();
            else if (panelOpen()) closePanel();
            else if (bannerVisible()) hideBanner();
            else self.close();
        }

        // ------------------------------------------------------------ public

        self = {
            init: function () {
                root = $('player');
                video = $('video');
                panel = $('panel');
                player = new NV.Player(video, {
                    onLoading: function (on) { NV.show($('player-loading'), on); },
                    onStarted: function (ch) {
                        readyKey = ch.key;
                        hideError();
                        NV.channels.clearBroken(ch.key);
                        refreshBanner();
                    },
                    onInfo: function () {
                        player.setAspect(NV.settings.get('aspectMode'));
                        refreshBanner();
                    },
                    onProblem: function (ch, message, attempt, max, fatal, permanent, clientError) {
                        if (!current || ch.key !== current.key) return;
                        if (fatal) NV.channels.markBroken(ch.key);
                        if (permanent) showError(ch, message, '');
                        else if (fatal || attempt >= 2 || clientError) showError(ch, message, fatal ? 'Kanal arka planda yeniden denenecek.' : 'Yeniden bağlanılıyor… (' + attempt + '/' + max + ')');
                    }
                });
                panelList = new NV.ui.VirtualList($('panel-list'), {
                    rows: PANEL_ROWS, rowHeight: 58,
                    create: function () { var r = NV.ui.createChannelRow(); r.classList.add('compact'); return r; },
                    render: function (row, ch, i) { NV.ui.renderChannelRow(row, ch, i, { playingKey: current ? current.key : null }); },
                    onFocus: function (ch) { panelEpg(ch); }
                });
                layer = { closable: true, onBack: onBack, handleKey: handleKey };
                NV.events.on('epg', function () { if (visible) { refreshBanner(); if (panelOpen()) { panelList.refresh(); panelEpg(panelList.current()); } } });
                NV.events.on('lists', function () { if (visible && panelOpen()) panelList.refresh(); });
                NV.events.on('channels', function (snap) {
                    var fresh;
                    if (!visible || !current) return;
                    fresh = snap.byKey(current.key);
                    if (fresh) {
                        // A refreshed list may carry a new tokenised URL for a failing channel.
                        if (fresh.url !== current.url && NV.isShown($('player-error'))) { current = fresh; player.play(fresh); }
                        else current = fresh;
                    }
                });
            },

            /** Opens fullscreen playback of ch. group: 'all' | 'fav' | 'g:<name>'. */
            open: function (ch, group, withList) {
                zapGroup = group || 'all';
                if (!visible) {
                    visible = true;
                    NV.show(root, true);
                    NV.home.setVisible(false); // nothing composited behind the video on a weak GPU
                    NV.input.push(layer);
                }
                tuneNow(ch);
                if (withList) setTimeout(function () { openPanel(zapGroup); }, 300);
            },

            close: function () {
                if (!visible) return;
                visible = false;
                cancelNumber();
                closeMenu();
                closePanel();
                hideBanner();
                hideError();
                if (tuneTimer) { clearTimeout(tuneTimer); tuneTimer = null; }
                player.stop();
                NV.show(root, false);
                NV.input.remove(layer);
                NV.home.setVisible(true);
                NV.home.resume();
            },

            isOpen: function () { return visible; },

            /** App hidden / TV standby: free the decoder. */
            suspend: function () { if (visible) player.suspend(); },

            /** App visible again: continue the same channel. */
            resume: function () { if (visible && current) { hideError(); player.play(current); } }
        };
        return self;
    }());
}(NV));
