/*
 * NovaTV webOS — home sections (ports of LiveSection, ChannelsSection, CategoriesSection,
 * SourcesSection). Each section: { el, onShow(), focusContent(), blur(), handleKey(code),
 * handleLongOk(), wantsBack(), onBack() }. handleKey returns false to let Home move focus
 * back to the navigation rail (LEFT at the left edge).
 */
(function (NV) {
    'use strict';

    var K = NV.keys;
    NV.sections = {};

    function header(title) {
        var h = NV.el('div', 'sec-header');
        h._title = h.appendChild(NV.el('div', 'sec-title', title));
        h._count = h.appendChild(NV.el('div', 'sec-count'));
        return h;
    }

    function channelOptions(host, ch, group, extras) {
        var labels = [], actions = [], fav = NV.channels.isFavorite(ch.key), i;
        labels.push('İzle'); actions.push(function () { host.play(ch, group); });
        labels.push(fav ? 'Favorilerden çıkar' : 'Favorilere ekle');
        actions.push(function () {
            var now = NV.channels.toggleFavorite(ch.key);
            NV.ui.osd(ch.name + (now ? ' favorilere eklendi' : ' favorilerden çıkarıldı'));
        });
        for (i = 0; extras && i < extras.length; i++) { labels.push(extras[i][0]); actions.push(extras[i][1]); }
        labels.push('Numaraya taşı…');
        actions.push(function () {
            var total = NV.channels.snapshot().channels.length;
            NV.dialog.input('Yeni kanal numarası (1–' + total + ')', String(ch.number), { numeric: true }, function (v) {
                var n = parseInt(v, 10);
                if (!n) return;
                NV.channels.moveToNumber(ch.key, Math.max(1, Math.min(total, n)));
                NV.ui.osd('Kanal sırası kaydedildi');
            });
        });
        if (NV.channels.isBroken(ch.key)) {
            labels.push('“Çalışmıyor” işaretini kaldır');
            actions.push(function () { NV.channels.clearBroken(ch.key); });
        }
        labels.push('Kanal bilgisi');
        actions.push(function () {
            NV.dialog.message(ch.name, 'Numara: ' + ch.number + '\nKaynak: ' + (ch.sourceName || '—') + '\nKategori: ' +
                (ch.group || 'Diğer') + '\nEPG kimliği (tvg-id): ' + (ch.tvgId || '—') + '\nDurum: ' +
                (NV.channels.isBroken(ch.key) ? 'Son denemede açılamadı' : 'Normal'));
        });
        NV.dialog.options(ch.number + '  ' + ch.name, labels, -1, function (i) { actions[i](); });
    }
    NV.sections.channelOptions = channelOptions;

    // =================================================================== Canlı TV

    NV.sections.live = function (host) {
        var el = NV.el('div', 'section live');
        var hdr = el.appendChild(header('Canlı TV'));
        var card = el.appendChild(NV.el('div', 'card'));
        var plate = card.appendChild(NV.el('div', 'logo-plate card-logo'));
        var info = card.appendChild(NV.el('div', 'card-info'));
        var caption = info.appendChild(NV.el('div', 'card-caption'));
        var title = info.appendChild(NV.el('div', 'card-title'));
        var body = info.appendChild(NV.el('div', 'card-body'));
        var prog = info.appendChild(NV.el('div', 'progress'));
        var progBar = prog.appendChild(NV.el('div', 'progress-bar'));
        var btnRow = info.appendChild(NV.el('div', 'card-buttons'));
        var primary = btnRow.appendChild(NV.ui.button('Canlı TV\'yi aç', 'play'));
        var secondary = btnRow.appendChild(NV.ui.button('Kanal listesi', 'list'));
        var recentTitle = el.appendChild(NV.el('div', 'sub-title', 'Son İzlenenler'));
        var recentHost = el.appendChild(NV.el('div', 'cards-row'));
        el.appendChild(NV.el('div', 'hint', 'Kumanda: ▲▼ kanal değiştir • ◀ kanal listesi • ▶ hızlı menü • OK bilgi • 0–9 kanal numarası • GERİ menü'));

        var buttons = NV.ui.focusGroup([primary, secondary]);
        var recentCards = [], recentItems = [], recentIndex = 0, area = 'buttons', active = false, mode = 'channel', shown = null;
        var i, c;
        for (i = 0; i < 6; i++) {
            c = NV.el('div', 'mini-card');
            c._plate = c.appendChild(NV.el('div', 'logo-plate'));
            c._name = c.appendChild(NV.el('div', 'mini-name'));
            recentHost.appendChild(c);
            recentCards.push(c);
        }

        function currentChannel() {
            var snap = NV.channels.snapshot(), last = NV.settings.lastChannel();
            return (last && (snap.byKey(last.key) || last)) || snap.channels[0] || null;
        }

        function render() {
            var snap = NV.channels.snapshot(), ch, nn, lines = [], keys = NV.channels.recents(), k, j;
            hdr._count.textContent = snap.channels.length + ' kanal • ' + snap.sources.length + ' kaynak • ' + snap.groups.length + ' kategori';
            if (snap.loaded && !snap.sources.length) {
                mode = 'welcome';
                NV.ui.bindLogo(plate, 'icon.png', 'NovaTV');
                caption.textContent = 'NOVATV';
                title.textContent = 'NovaTV\'ye hoş geldiniz';
                body.textContent = 'Henüz bir IPTV kaynağı yok. Kendi M3U / M3U8 bağlantınızı ekleyin; resmi TRT ve TBMM TV yayınlarını Ayarlar → “Resmi Türkiye kanallarını ekle” ile geri getirebilirsiniz.';
                prog.style.display = 'none';
                NV.ui.setButtonLabel(primary, 'Kaynak ekle', 'add');
                NV.ui.setButtonLabel(secondary, 'Telefondan / Ağdan', 'phone');
                secondary.style.display = '';
            } else {
                mode = 'channel';
                ch = currentChannel();
                shown = ch;
                secondary.style.display = '';
                NV.ui.setButtonLabel(secondary, 'Kanal listesi', 'list');
                if (!ch) {
                    caption.textContent = 'NOVATV';
                    title.textContent = 'Kanallar yükleniyor…';
                    body.textContent = '';
                    prog.style.display = 'none';
                } else {
                    NV.ui.bindLogo(plate, ch.logo, ch.name);
                    caption.textContent = NV.settings.lastChannel() ? 'SON İZLENEN KANAL' : 'CANLI TV';
                    title.textContent = (ch.number ? ch.number + '  ' : '') + ch.name;
                    nn = NV.epg.nowNext(ch);
                    if (nn.now) {
                        lines.push(NV.hm(nn.now[0]) + ' – ' + NV.hm(nn.now[1]) + '   ' + nn.now[2]);
                        prog.style.display = '';
                        progBar.style.width = Math.max(0, Math.min(100, (NV.now() - nn.now[0]) * 100 / (nn.now[1] - nn.now[0]))) + '%';
                    } else {
                        lines.push(ch.group || 'Diğer');
                        prog.style.display = 'none';
                    }
                    if (nn.next) lines.push('Sonra  ' + NV.hm(nn.next[0]) + '   ' + nn.next[2]);
                    body.textContent = lines.join('\n');
                }
                NV.ui.setButtonLabel(primary, NV.settings.lastChannel() ? 'İzlemeye devam et' : 'Canlı TV\'yi aç', 'play');
            }
            recentItems = [];
            for (k = 0; k < keys.length && recentItems.length < recentCards.length; k++) {
                c = snap.byKey(keys[k]);
                if (c) recentItems.push(c);
            }
            for (j = 0; j < recentCards.length; j++) {
                c = recentCards[j];
                if (j < recentItems.length) {
                    c.style.display = '';
                    NV.ui.bindLogo(c._plate, recentItems[j].logo, recentItems[j].name);
                    c._name.textContent = recentItems[j].name;
                } else {
                    c.style.display = 'none';
                }
            }
            recentTitle.style.display = recentItems.length ? '' : 'none';
            if (area === 'recents' && !recentItems.length) area = 'buttons';
            paint();
        }

        function paint() {
            var j;
            buttons.setActive(active && area === 'buttons');
            if (recentIndex >= recentItems.length) recentIndex = Math.max(0, recentItems.length - 1);
            for (j = 0; j < recentCards.length; j++) {
                if (active && area === 'recents' && j === recentIndex) recentCards[j].classList.add('focused');
                else recentCards[j].classList.remove('focused');
            }
        }

        NV.events.on('channels', function () { if (host.isVisible(section)) render(); });
        NV.events.on('lists', function () { if (host.isVisible(section)) render(); });
        NV.events.on('epg', function () { if (host.isVisible(section)) render(); });

        var section = {
            el: el,
            onShow: render,
            focusContent: function () { active = true; area = 'buttons'; buttons.set(0); paint(); return true; },
            blur: function () { active = false; paint(); },
            handleKey: function (code) {
                if (area === 'buttons') {
                    if (code === K.LEFT) return buttons.move(-1);
                    if (code === K.RIGHT) { buttons.move(1); return true; }
                    if (code === K.DOWN && recentItems.length) { area = 'recents'; paint(); return true; }
                    if (code === K.OK) {
                        if (mode === 'welcome') { if (buttons.index === 0) host.openSourceEditor(null); else host.openLink(); }
                        else if (buttons.index === 0) { if (shown) host.play(shown); }
                        else host.openPlayer(true);
                        return true;
                    }
                    return code === K.UP || code === K.DOWN;
                }
                if (code === K.LEFT) { if (recentIndex === 0) return false; recentIndex--; paint(); return true; }
                if (code === K.RIGHT) { if (recentIndex < recentItems.length - 1) recentIndex++; paint(); return true; }
                if (code === K.UP) { area = 'buttons'; paint(); return true; }
                if (code === K.OK) { host.play(recentItems[recentIndex]); return true; }
                return code === K.DOWN;
            },
            wantsBack: function () { return false; }
        };
        return section;
    };

    // =================================================================== Kanal listeleri

    /** mode: 'all' | 'fav' | 'recent' */
    NV.sections.channels = function (host, mode) {
        var el = NV.el('div', 'section channels ' + mode), titles = { all: 'Tüm Kanallar', fav: 'Favoriler', recent: 'Son İzlenenler' };
        var hdr = el.appendChild(header(titles[mode]));
        var action = hdr.appendChild(NV.ui.button('Listeyi temizle', null, 'small'));
        var filters = el.appendChild(NV.el('div', 'filters'));
        var search = filters.appendChild(NV.el('input', 'text-input search'));
        var sourceBtn = filters.appendChild(NV.ui.button('Kaynak: Tümü', null, 'filter-btn'));
        var listHost = el.appendChild(NV.el('div', 'list-host'));
        var empty = el.appendChild(NV.el('div', 'empty hidden'));
        var hint = el.appendChild(NV.el('div', 'hint', 'OK: izle • KIRMIZI veya OK basılı tut: seçenekler • SARI: favori'));
        var rows = mode === 'all' ? 6 : 7, area = 'list', active = false, query = '', sourceId = null;
        var moving = null, beforeMove = null, moveLayer = null, lastKey = null;

        search.type = 'text';
        search.setAttribute('placeholder', 'Kanal ara…');
        search.setAttribute('autocomplete', 'off');
        if (mode !== 'all') filters.style.display = 'none';
        if (mode !== 'recent') action.style.display = 'none';
        listHost.style.height = (rows * 76) + 'px';

        var list = new NV.ui.VirtualList(listHost, {
            rows: rows, rowHeight: 76,
            create: NV.ui.createChannelRow,
            render: function (row, ch, i) {
                NV.ui.renderChannelRow(row, ch, i, { numberByPosition: !!moving, movingKey: moving });
            },
            onFocus: function (ch) { lastKey = ch.key; }
        });

        function compute() {
            var snap = NV.channels.snapshot(), out = [], keys, i, c, nq, num, exact;
            if (mode === 'fav' || mode === 'recent') {
                keys = mode === 'fav' ? NV.channels.favorites() : NV.channels.recents();
                for (i = 0; i < keys.length; i++) { c = snap.byKey(keys[i]); if (c) out.push(c); }
                return out;
            }
            nq = NV.searchKey(query);
            num = /^\d+$/.test(query) ? parseInt(query, 10) : 0;
            exact = num ? snap.byNumber(num) : null;
            if (exact && sourceId !== null && exact.sourceId !== sourceId) exact = null;
            if (exact) out.push(exact);
            for (i = 0; i < snap.channels.length; i++) {
                c = snap.channels[i];
                if (sourceId !== null && c.sourceId !== sourceId) continue;
                if (nq && c.searchKey.indexOf(nq) < 0 && NV.searchKey(c.tvgName).indexOf(nq) < 0) continue;
                if (exact && c.key === exact.key) continue;
                out.push(c);
            }
            return out;
        }

        function render() {
            var items = compute(), snap = NV.channels.snapshot(), idx;
            hdr._count.textContent = items.length + ' kanal';
            list.setItems(items, 0);
            idx = lastKey ? list.indexWhere(function (c) { return c.key === lastKey; }) : -1;
            if (idx >= 0) list.setIndex(idx); else list.draw(true);
            empty.textContent = snap.loaded && !snap.sources.length ? 'Kanal yok. Önce “IPTV Kaynakları” bölümünden bir kaynak ekleyin.' :
                (mode === 'fav' ? 'Henüz favori kanal yok.\nBir kanalda SARI tuşa basın veya seçeneklerden “Favorilere ekle”yi seçin.' :
                    (mode === 'recent' ? 'Henüz kanal izlenmedi.' : 'Kanal bulunamadı.'));
            NV.show(empty, !items.length);
            if (!items.length && area === 'list' && active) area = mode === 'all' ? 'search' : (mode === 'recent' ? 'action' : 'list');
            sourceBtn._label.textContent = sourceId === null ? 'Kaynak: Tümü' : 'Kaynak: ' + ((snap.source(sourceId) || {}).name || '?');
            paint();
        }

        function paint() {
            list.setActive(active && area === 'list');
            if (active && area === 'search') search.classList.add('focused'); else search.classList.remove('focused');
            if (active && area === 'source') sourceBtn.classList.add('focused'); else sourceBtn.classList.remove('focused');
            if (active && area === 'action') action.classList.add('focused'); else action.classList.remove('focused');
        }

        var applySearch = NV.debounce(function () {
            var q = search.value.replace(/^\s+|\s+$/g, '');
            if (q === query) return;
            query = q;
            lastKey = null;
            render();
        }, 400);
        search.addEventListener('input', applySearch, false);
        search.addEventListener('keyup', applySearch, false);

        function pickSource() {
            var srcs = NV.channels.sources().filter(function (s) { return s.enabled; }), labels = ['Tüm kaynaklar'], sel = 0, i;
            for (i = 0; i < srcs.length; i++) {
                labels.push(srcs[i].name + '  (' + (srcs[i].channelCount || 0) + ')');
                if (srcs[i].id === sourceId) sel = i + 1;
            }
            NV.dialog.options('IPTV Kaynakları', labels, sel, function (i) {
                sourceId = i === 0 ? null : srcs[i - 1].id;
                lastKey = null;
                render();
            });
        }

        function playGroup() { return mode === 'fav' ? 'fav' : 'all'; }

        function options() {
            var ch = list.current(), extras = [];
            if (!ch) return;
            if (mode === 'all') extras.push(['Taşı (yeniden sırala)', function () { startMove(); }]);
            if (mode === 'fav') {
                extras.push(['Yukarı taşı', function () { NV.channels.moveFavorite(ch.key, -1); lastKey = ch.key; }]);
                extras.push(['Aşağı taşı', function () { NV.channels.moveFavorite(ch.key, 1); lastKey = ch.key; }]);
            }
            if (mode === 'recent') extras.push(['Listeden kaldır', function () { NV.channels.removeRecent(ch.key); }]);
            channelOptions(host, ch, playGroup(), extras);
        }

        function startMove() {
            var ch = list.current();
            if (!ch) return;
            if (query || sourceId !== null) { NV.ui.osd('Sıralamak için arama ve kaynak filtresini kaldırın.'); return; }
            moving = ch.key;
            beforeMove = list.items.slice();
            hint.textContent = 'Taşıma modu: ▲▼ ile konumu değiştirin, OK ile kaydedin, GERİ ile iptal edin';
            hint.classList.add('accent');
            list.refresh();
            // Back must cancel the move instead of leaving the screen.
            moveLayer = {
                closable: true,
                onBack: function () { list.setItems(beforeMove, list.indexWhere(function (c) { return c.key === moving; })); endMove(); },
                handleKey: function (code) {
                    var from = list.index, to, items;
                    if (code === K.UP || code === K.DOWN || code === K.CH_UP || code === K.CH_DOWN) {
                        to = from + (code === K.UP ? -1 : code === K.DOWN ? 1 : code === K.CH_UP ? -10 : 10);
                        to = Math.max(0, Math.min(list.items.length - 1, to));
                        if (to !== from) {
                            items = list.items.slice();
                            items.splice(to, 0, items.splice(from, 1)[0]);
                            list.items = items;
                            list.setIndex(to);
                            list.refresh();
                        }
                        return true;
                    }
                    if (code === K.OK) {
                        NV.channels.saveOrder(list.items.map(function (c) { return c.key; }));
                        lastKey = moving;
                        endMove();
                        NV.ui.osd('Kanal sırası kaydedildi');
                        return true;
                    }
                    return true;
                }
            };
            NV.input.push(moveLayer);
        }

        function endMove() {
            moving = null;
            beforeMove = null;
            hint.textContent = 'OK: izle • KIRMIZI veya OK basılı tut: seçenekler • SARI: favori';
            hint.classList.remove('accent');
            if (moveLayer) NV.input.remove(moveLayer);
            moveLayer = null;
            render();
        }

        NV.events.on('channels', function () { if (host.isVisible(section) && !moving) render(); });
        NV.events.on('lists', function () {
            if (!host.isVisible(section) || moving) return;
            if (mode === 'all') list.refresh(); else render();
        });
        NV.events.on('epg', function () { if (host.isVisible(section)) list.refresh(); });

        var section = {
            el: el,
            onShow: render,
            focusContent: function () {
                active = true;
                area = list.items.length ? 'list' : (mode === 'all' ? 'search' : (mode === 'recent' ? 'action' : 'list'));
                if (area === 'action' && action.style.display === 'none') area = 'list';
                paint();
                return area !== 'list' || list.items.length > 0 || mode === 'all';
            },
            blur: function () { active = false; search.blur(); paint(); },
            showSource: function (id) { sourceId = id; query = ''; search.value = ''; lastKey = null; render(); },
            handleLongOk: function () { if (area === 'list') options(); else section.handleKey(K.OK); },
            handleKey: function (code) {
                var ch;
                if (area === 'search') {
                    if (code === K.OK) {
                        if (document.activeElement === search) {
                            search.blur();
                            query = search.value.replace(/^\s+|\s+$/g, '');
                            lastKey = null;
                            render();
                            if (list.items.length) { area = 'list'; paint(); }
                        } else {
                            search.focus(); // opens the on-screen keyboard
                        }
                        return true;
                    }
                    if (code === K.RIGHT) { search.blur(); area = 'source'; paint(); return true; }
                    if (code === K.DOWN) { search.blur(); if (list.items.length) { area = 'list'; paint(); } return true; }
                    if (code === K.LEFT) { search.blur(); return false; }
                    return code === K.UP;
                }
                if (area === 'source') {
                    if (code === K.OK) { pickSource(); return true; }
                    if (code === K.LEFT) { area = 'search'; paint(); return true; }
                    if (code === K.DOWN) { if (list.items.length) { area = 'list'; paint(); } return true; }
                    return true;
                }
                if (area === 'action') {
                    if (code === K.OK) { NV.channels.clearRecents(); NV.ui.osd('Son izlenenler temizlendi'); return true; }
                    if (code === K.DOWN && list.items.length) { area = 'list'; paint(); return true; }
                    return code !== K.LEFT;
                }
                // list
                ch = list.current();
                if (code === K.UP) {
                    if (list.index === 0) {
                        if (mode === 'all') { area = 'search'; paint(); }
                        else if (mode === 'recent') { area = 'action'; paint(); }
                        return true;
                    }
                    list.move(-1); return true;
                }
                if (code === K.DOWN) { list.move(1); return true; }
                if (code === K.CH_UP) { list.move(-rows); return true; }
                if (code === K.CH_DOWN) { list.move(rows); return true; }
                if (code === K.RIGHT) return true;
                if (code === K.LEFT) return false;
                if (!ch) return false;
                if (code === K.OK) { host.play(ch, playGroup()); return true; }
                if (code === K.RED) { options(); return true; }
                if (code === K.YELLOW) {
                    NV.ui.osd(ch.name + (NV.channels.toggleFavorite(ch.key) ? ' favorilere eklendi' : ' favorilerden çıkarıldı'));
                    return true;
                }
                return false;
            }
        };
        return section;
    };

    // =================================================================== Kategoriler

    NV.sections.categories = function (host) {
        var el = NV.el('div', 'section categories');
        var hdr = el.appendChild(header('Kategoriler'));
        var cols = el.appendChild(NV.el('div', 'cat-cols'));
        var groupHost = cols.appendChild(NV.el('div', 'cat-groups'));
        cols.appendChild(NV.el('div', 'cat-divider'));
        var chHost = cols.appendChild(NV.el('div', 'cat-channels'));
        var empty = chHost.appendChild(NV.el('div', 'empty hidden', 'Bir kategori seçin'));
        var area = 'groups', active = false, selected = null;

        var groups = new NV.ui.VirtualList(groupHost, {
            rows: 8, rowHeight: 66,
            create: function () {
                var r = NV.el('div', 'row grp-row');
                r._name = r.appendChild(NV.el('div', 'grp-name'));
                r._count = r.appendChild(NV.el('div', 'grp-count'));
                return r;
            },
            render: function (row, g) {
                row._name.textContent = g.name || 'Diğer';
                row._count.textContent = String(g.count);
                if (g.name === selected) row.classList.add('activated'); else row.classList.remove('activated');
            },
            onFocus: NV.debounce(function (g) {
                if (g && g.name !== selected) { selected = g.name; showChannels(); }
            }, 150)
        });
        var channels = new NV.ui.VirtualList(chHost, {
            rows: 7, rowHeight: 76,
            create: NV.ui.createChannelRow,
            render: function (row, ch, i) { NV.ui.renderChannelRow(row, ch, i); }
        });

        function render() {
            var snap = NV.channels.snapshot(), items = [], i;
            for (i = 0; i < snap.groups.length; i++) items.push({ name: snap.groups[i], count: snap.byGroup[snap.groups[i]].length });
            hdr._count.textContent = items.length + ' kategori';
            if (selected === null || !snap.byGroup[selected]) selected = items.length ? items[0].name : null;
            groups.setItems(items, Math.max(0, groups.indexWhere(function (g) { return g.name === selected; })));
            i = 0;
            while (i < items.length && items[i].name !== selected) i++;
            if (i < items.length) groups.setIndex(i);
            showChannels();
        }

        function showChannels() {
            var snap = NV.channels.snapshot(), items = selected === null ? [] : snap.inGroup(selected);
            channels.setItems(items, 0);
            groups.refresh();
            empty.textContent = snap.loaded && !snap.sources.length ? 'Kanal yok. Önce bir kaynak ekleyin.' : 'Bir kategori seçin';
            NV.show(empty, !items.length);
        }

        function paint() {
            groups.setActive(active && area === 'groups');
            channels.setActive(active && area === 'channels');
        }

        NV.events.on('channels', function () { if (host.isVisible(section)) render(); });
        NV.events.on('lists', function () { if (host.isVisible(section)) channels.refresh(); });
        NV.events.on('epg', function () { if (host.isVisible(section)) channels.refresh(); });

        var section = {
            el: el,
            onShow: render,
            focusContent: function () { active = true; area = 'groups'; paint(); return groups.items.length > 0; },
            blur: function () { active = false; paint(); },
            handleLongOk: function () {
                var ch = channels.current();
                if (area === 'channels' && ch) channelOptions(host, ch, 'g:' + ch.group); else section.handleKey(K.OK);
            },
            handleKey: function (code) {
                var ch;
                if (area === 'groups') {
                    if (code === K.UP) { groups.move(-1); return true; }
                    if (code === K.DOWN) { groups.move(1); return true; }
                    if ((code === K.RIGHT || code === K.OK) && channels.items.length) {
                        if (groups.current() && groups.current().name !== selected) { selected = groups.current().name; showChannels(); }
                        area = 'channels'; paint(); return true;
                    }
                    return code !== K.LEFT;
                }
                ch = channels.current();
                if (code === K.UP) { channels.move(-1); return true; }
                if (code === K.DOWN) { channels.move(1); return true; }
                if (code === K.CH_UP) { channels.move(-7); return true; }
                if (code === K.CH_DOWN) { channels.move(7); return true; }
                if (code === K.LEFT) { area = 'groups'; paint(); return true; }
                if (code === K.RIGHT) return true;
                if (!ch) return false;
                if (code === K.OK) { host.play(ch, 'g:' + ch.group); return true; }
                if (code === K.RED) { channelOptions(host, ch, 'g:' + ch.group); return true; }
                if (code === K.YELLOW) {
                    NV.ui.osd(ch.name + (NV.channels.toggleFavorite(ch.key) ? ' favorilere eklendi' : ' favorilerden çıkarıldı'));
                    return true;
                }
                return false;
            }
        };
        return section;
    };

    // =================================================================== IPTV Kaynakları

    NV.sections.sources = function (host) {
        var el = NV.el('div', 'section sources');
        el.appendChild(header('IPTV Kaynakları'));
        var btnRow = el.appendChild(NV.el('div', 'btn-row'));
        var addBtn = btnRow.appendChild(NV.ui.button('Kaynak ekle', 'add'));
        var phoneBtn = btnRow.appendChild(NV.ui.button('Telefondan / Ağdan', 'phone'));
        var refreshBtn = btnRow.appendChild(NV.ui.button('Tümünü yenile', 'refresh'));
        var spinner = btnRow.appendChild(NV.el('div', 'spinner small hidden'));
        var listHost = el.appendChild(NV.el('div', 'list-host'));
        var empty = el.appendChild(NV.el('div', 'empty hidden', 'Henüz kaynak yok. “Kaynak ekle” ile M3U bağlantınızı girin.'));
        el.appendChild(NV.el('div', 'hint', 'NovaTV yalnızca kamu yayıncılarının (TRT, TBMM TV) resmi ve ücretsiz yayınlarını örnek olarak içerir; ücretli veya izinsiz yayın içermez.'));
        var buttons = NV.ui.focusGroup([addBtn, phoneBtn, refreshBtn]), area = 'buttons', active = false;
        listHost.style.height = (5 * 92) + 'px';

        function mask(url) {
            return String(url).replace(/(username|password|user|pass)=([^&]+)/gi, '$1=•••').replace(/:\/\/([^\/@:]+):([^\/@]+)@/, '://•••@');
        }

        var list = new NV.ui.VirtualList(listHost, {
            rows: 5, rowHeight: 92,
            create: function () {
                var r = NV.el('div', 'row src-row');
                r._icon = r.appendChild(NV.el('img', 'src-icon'));
                r._text = r.appendChild(NV.el('div', 'src-text'));
                r._name = r._text.appendChild(NV.el('div', 'src-name'));
                r._loc = r._text.appendChild(NV.el('div', 'src-loc'));
                r._status = r._text.appendChild(NV.el('div', 'src-status'));
                r._badge = r.appendChild(NV.el('div', 'badge src-badge'));
                return r;
            },
            render: function (row, s) {
                var st;
                row._icon.src = 'assets/icons/' + (s.builtin ? 'live' : 'link') + '.svg';
                row._name.textContent = s.name;
                row._loc.textContent = s.builtin ? NV.BUILTIN.description : mask(s.url);
                st = (s.channelCount || 0) + ' kanal • Güncelleme: ' + (s.lastUpdated ? NV.dateTime(s.lastUpdated) : 'Hiç');
                if (s.lastError) st += '\nHata: ' + s.lastError;
                row._status.textContent = st;
                if (s.lastError) row._status.classList.add('error'); else row._status.classList.remove('error');
                row._badge.textContent = NV.channels.isRefreshing(s.id) ? 'Yükleniyor…' : (!s.enabled ? 'Devre dışı' : (s.builtin ? 'Dahili' : (s.useProxy ? 'URL • Proxy' : 'M3U URL')));
                row.style.opacity = s.enabled ? '1' : '0.6';
            }
        });

        function render() {
            var srcs = NV.channels.sources();
            list.setItems(srcs.slice(), list.index);
            NV.show(empty, !srcs.length);
            NV.show(spinner, NV.channels.isRefreshing());
            if (area === 'list' && !srcs.length) area = 'buttons';
            paint();
        }

        function paint() {
            buttons.setActive(active && area === 'buttons');
            list.setActive(active && area === 'list');
        }

        function options(s) {
            var labels = [], actions = [];
            if (!s.builtin) { labels.push('Düzenle'); actions.push(function () { host.openSourceEditor(s); }); }
            if (!s.builtin) {
                labels.push('Şimdi yenile');
                actions.push(function () {
                    NV.ui.osd(s.name + ' yenileniyor…');
                    NV.channels.refreshSource(s.id, function (err, n) {
                        NV.ui.osd(err ? s.name + ' yenilenemedi: ' + err.message : s.name + ': ' + n + ' kanal yüklendi', err ? 5000 : 2600);
                    });
                });
            }
            labels.push('Bu kaynağın kanallarını göster');
            actions.push(function () { host.showSourceChannels(s.id); });
            labels.push(s.enabled ? 'Devre dışı bırak' : 'Etkinleştir');
            actions.push(function () { NV.channels.setEnabled(s.id, !s.enabled); });
            labels.push('Yukarı taşı'); actions.push(function () { NV.channels.moveSource(s.id, -1); });
            labels.push('Aşağı taşı'); actions.push(function () { NV.channels.moveSource(s.id, 1); });
            labels.push('Kaynağı sil');
            actions.push(function () {
                NV.dialog.confirm('Kaynağı sil', '“' + s.name + '” kaynağı ve kanalları silinsin mi?', 'Sil', 'İptal', function () {
                    NV.channels.deleteSource(s.id);
                });
            });
            NV.dialog.options(s.name, labels, -1, function (i) { actions[i](); });
        }

        NV.events.on('channels', function () { if (host.isVisible(section)) render(); });
        NV.events.on('sources', function () { if (host.isVisible(section)) render(); });

        var section = {
            el: el,
            onShow: render,
            focusContent: function () { active = true; area = list.items.length ? 'list' : 'buttons'; paint(); return true; },
            blur: function () { active = false; paint(); },
            handleKey: function (code) {
                if (area === 'buttons') {
                    if (code === K.LEFT) return buttons.move(-1);
                    if (code === K.RIGHT) { buttons.move(1); return true; }
                    if (code === K.DOWN && list.items.length) { area = 'list'; paint(); return true; }
                    if (code === K.OK) {
                        if (buttons.index === 0) {
                            host.openSourceEditor(null);
                        } else if (buttons.index === 1) {
                            host.openLink();
                        } else {
                            NV.ui.osd('Tüm kaynaklar yenileniyor…');
                            NV.channels.refreshAll(function (results) {
                                var bad = results.filter(function (r) { return r.error; });
                                NV.ui.osd(bad.length ? bad[0].source.name + ' yenilenemedi: ' + bad[0].error.message : 'Kaynaklar yenilendi', bad.length ? 5000 : 2600);
                            });
                        }
                        return true;
                    }
                    return true;
                }
                if (code === K.UP) { if (list.index === 0) { area = 'buttons'; paint(); } else list.move(-1); return true; }
                if (code === K.DOWN) { list.move(1); return true; }
                if (code === K.OK || code === K.RED) { if (list.current()) options(list.current()); return true; }
                if (code === K.RIGHT) return true;
                return code !== K.LEFT;
            }
        };
        return section;
    };
}(NV));
