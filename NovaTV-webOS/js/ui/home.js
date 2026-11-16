/*
 * NovaTV webOS — home screen: navigation rail + sections (port of Android MainActivity).
 * Back behaves like Android: content -> navigation rail -> "Canlı TV" -> leave the app.
 */
(function (NV) {
    'use strict';

    var K = NV.keys;

    var NAV = [
        { id: 'live', title: 'Canlı TV', icon: 'live' },
        { id: 'all', title: 'Tüm Kanallar', icon: 'list' },
        { id: 'categories', title: 'Kategoriler', icon: 'category' },
        { id: 'fav', title: 'Favoriler', icon: 'star' },
        { id: 'recent', title: 'Son İzlenenler', icon: 'history' },
        { id: 'sources', title: 'IPTV Kaynakları', icon: 'source' },
        { id: 'settings', title: 'Ayarlar', icon: 'settings' }
    ];

    NV.home = (function () {
        var navEls = [], navIndex = 0, current = null, sections = {}, area = 'nav', content, root, switchTimer = null;
        var backLayer, homeLayer, self;

        function create(id) {
            switch (id) {
            case 'all': return NV.sections.channels(self, 'all');
            case 'categories': return NV.sections.categories(self);
            case 'fav': return NV.sections.channels(self, 'fav');
            case 'recent': return NV.sections.channels(self, 'recent');
            case 'sources': return NV.sections.sources(self);
            case 'settings': return NV.sections.settings(self);
            default: return NV.sections.live(self);
            }
        }

        function paintNav() {
            var i;
            for (i = 0; i < navEls.length; i++) {
                navEls[i].classList.remove('focused');
                navEls[i].classList.remove('selected');
                if (i === navIndex) navEls[i].classList.add(area === 'nav' ? 'focused' : 'selected');
                else if (current && NAV[i].id === current.id) navEls[i].classList.add('selected');
            }
        }

        function showSection(index) {
            var id = NAV[index].id, sec;
            if (current && current.id === id) return;
            if (current) {
                current.section.blur();
                current.section.el.style.display = 'none';
            }
            sec = sections[id];
            if (!sec) {
                sec = sections[id] = create(id);
                content.appendChild(sec.el);
            }
            sec.el.style.display = '';
            current = { id: id, section: sec };
            sec.onShow();
            updateBackLayer();
        }

        function focusNav() {
            if (current) current.section.blur();
            area = 'nav';
            paintNav();
            updateBackLayer();
        }

        function focusContent() {
            if (!current) return;
            if (switchTimer) { clearTimeout(switchTimer); switchTimer = null; showSection(navIndex); }
            if (current.section.focusContent()) {
                area = 'content';
                paintNav();
                updateBackLayer();
            }
        }

        // Closable layer that exists while Back has something to do inside Home.
        backLayer = {
            closable: true,
            onBack: function () {
                if (area === 'content') {
                    focusNav();
                } else if (navIndex !== 0) {
                    navIndex = 0;
                    showSection(0);
                    paintNav();
                }
                updateBackLayer();
            },
            handleKey: function (code, e) { return homeLayer.handleKey(code, e); },
            handleLongOk: function () { homeLayer.handleLongOk(); }
        };

        function updateBackLayer() {
            var need = area === 'content' || navIndex !== 0;
            var has = NV.input.contains(backLayer);
            if (need && !has && NV.input.top() === homeLayer) NV.input.push(backLayer);
            else if (!need && has) NV.input.remove(backLayer);
        }

        homeLayer = {
            closable: false,
            handleLongOk: function () {
                if (area === 'content' && current.section.handleLongOk) current.section.handleLongOk();
                else homeLayer.handleKey(K.OK);
            },
            handleKey: function (code) {
                if (area === 'nav') {
                    if (code === K.UP || code === K.DOWN) {
                        navIndex = Math.max(0, Math.min(NAV.length - 1, navIndex + (code === K.UP ? -1 : 1)));
                        paintNav();
                        // Small delay so quickly scrolling the rail does not build every section.
                        if (switchTimer) clearTimeout(switchTimer);
                        switchTimer = setTimeout(function () { switchTimer = null; showSection(navIndex); paintNav(); }, 120);
                        updateBackLayer();
                        return true;
                    }
                    if (code === K.RIGHT) { focusContent(); return true; }
                    if (code === K.OK) {
                        if (NAV[navIndex].id === 'live') {
                            if (switchTimer) { clearTimeout(switchTimer); switchTimer = null; showSection(navIndex); }
                            self.openPlayer(false);
                        } else {
                            focusContent();
                        }
                        return true;
                    }
                    return code === K.LEFT;
                }
                if (current.section.handleKey(code)) return true;
                if (code === K.LEFT) { focusNav(); return true; }
                return false;
            }
        };

        self = {
            init: function () {
                var nav = NV.$('nav'), i, item;
                root = NV.$('home');
                content = NV.$('content');
                for (i = 0; i < NAV.length; i++) {
                    item = NV.el('div', 'nav-item');
                    item.appendChild(NV.el('img', 'nav-icon')).src = 'assets/icons/' + NAV[i].icon + '.svg';
                    item.appendChild(NV.el('span', 'nav-label', NAV[i].title));
                    nav.appendChild(item);
                    navEls.push(item);
                }
                NV.input.push(homeLayer);
                showSection(0);
                paintNav();
                NV.events.on('channels', function (snap) {
                    NV.$('sidebar-footer').textContent = 'NovaTV ' + NV.VERSION + '\n' + snap.channels.length + ' kanal';
                });
            },

            isVisible: function (section) {
                return !!current && current.section === section && !root.classList.contains('hidden');
            },

            play: function (ch, group) { NV.playerUi.open(ch, group || 'all', false); },

            openPlayer: function (withList) {
                var snap = NV.channels.snapshot(), last = NV.settings.lastChannel();
                var ch = (last && (snap.byKey(last.key) || last)) || snap.channels[0];
                if (!ch) {
                    if (snap.loaded && !snap.sources.length) self.openSourceEditor(null);
                    else NV.ui.osd('Oynatılacak kanal yok.');
                    return;
                }
                NV.playerUi.open(ch, 'all', withList);
            },

            openLink: function () {
                NV.linkScreen.open(function () { if (current) current.section.onShow(); });
            },

            openSourceEditor: function (source) {
                NV.sourceEditor.open(source, null, function () { if (current) current.section.onShow(); });
            },

            showSourceChannels: function (sourceId) {
                navIndex = 1;
                showSection(1);
                sections.all.showSource(sourceId);
                focusContent();
            },

            /** Called when the player closes and Home is visible again. */
            resume: function () {
                if (current) current.section.onShow();
                paintNav();
                updateBackLayer();
            },

            setVisible: function (visible) { NV.show(root, visible); }
        };
        return self;
    }());
}(NV));
