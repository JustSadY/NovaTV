/*
 * NovaTV webOS — Settings (port of Android SettingsSection). Options the platform cannot
 * offer are shown honestly as information rows instead of fake switches.
 */
(function (NV) {
    'use strict';

    var K = NV.keys;

    NV.sections.settings = function (host) {
        var el = NV.el('div', 'section settings');
        el.appendChild(NV.el('div', 'sec-header')).appendChild(NV.el('div', 'sec-title', 'Ayarlar'));
        var listHost = el.appendChild(NV.el('div', 'list-host'));
        var active = false;
        listHost.style.height = (8 * 66) + 'px';

        var list = new NV.ui.VirtualList(listHost, {
            rows: 8, rowHeight: 66, margin: 2,
            create: function () { return NV.ui.createTextRow('set-row'); },
            render: function (row, item) { NV.ui.renderTextRow(row, item); }
        });

        function onOff(v) { return v ? 'Açık' : 'Kapalı'; }
        function S(k) { return NV.settings.get(k); }

        function choose(title, labels, selected, apply) {
            NV.dialog.options(title, labels, selected, function (i) { apply(i); render(); });
        }

        function epgStatus() {
            var st = NV.epg.status();
            if (st.state === 'loading') return 'EPG güncelleniyor…';
            if (st.state === 'error') return 'EPG hatası: ' + st.message;
            if (!NV.epg.urls().length) return 'EPG adresi yok (kaynaklardaki url-tvg otomatik kullanılır)';
            return NV.epg.channelCount() + ' kanal için program • ' + (NV.epg.updatedAt() ? NV.dateTime(NV.epg.updatedAt()) : 'Hiç');
        }

        function manageStatus() {
            if (!S('manageEnabled')) return 'Kapalı: telefon yalnızca “Telefondan yönet” ekranı açıkken bağlanabilir';
            if (!NV.http.hasProxy()) return 'Proxy adresi gerekli';
            if (NV.manageRelay.isConnected()) return 'Açık: proxy\'ye bağlı; eşleşmiş telefonlar NovaTV açıkken yönetebilir';
            return 'Açık: proxy\'ye ulaşılamıyor (' + (NV.manageRelay.lastError() || 'bekleniyor') + ')';
        }

        function items() {
            var out = [], aspects = ['Sığdır', 'Ekranı doldur', 'Yakınlaştır'];
            var qualities = ['Otomatik (TV uyumlu en iyi)', '720p\'ye kadar', '480p\'ye kadar', 'TV\'ye bırak'];
            var banners = [3, 5, 8, 10, 15], refresh = [0, 6, 12, 24, 48];
            function refreshLabel(h) { return h ? 'Açılışta, ' + h + ' saatte bir' : 'Kapalı'; }

            out.push({ header: true, title: 'AÇILIŞ' });
            out.push({ title: 'Açılışta son kanalı oynat', value: onOff(S('autoplayLast')), summary: 'Başlangıç ekranını atlar ve doğrudan tam ekran canlı yayını açar',
                act: function () { NV.settings.set('autoplayLast', !S('autoplayLast')); render(); } });
            out.push({ title: 'TV açılınca otomatik başlatma', value: 'Desteklenmiyor', summary: 'webOS 1.x\'te üçüncü taraf uygulamalar için resmi bir otomatik başlatma API\'si yok',
                act: function () {
                    NV.dialog.message('TV açılınca otomatik başlatma', 'Android sürümündeki “TV açılınca başlat” özelliği webOS 1.x\'e taşınamaz: LG, üçüncü taraf web uygulamalarının TV açılışında kendiliğinden başlamasını sağlayan resmi bir API sunmuyor. NovaTV bunu çalışıyor gibi göstermez.\n\nYapılabilenler:\n• NovaTV\'yi açtığınızda son kanal doğrudan tam ekran başlar (yukarıdaki ayar).\n• webOS, kapatılmadan önce açık olan uygulamayı bazı durumlarda TV açıldığında geri getirebilir; bu davranış TV yazılımına bağlıdır ve garanti edilemez.\n• Uygulama arka plana alınıp geri dönüldüğünde (webOSRelaunch) yayın kaldığı kanaldan devam eder.');
                } });

            out.push({ header: true, title: 'OYNATMA' });
            out.push({ title: 'Yukarı / Aşağı tuşları', value: S('invertUpDown') ? 'Yukarı = önceki kanal' : 'Yukarı = sonraki kanal',
                act: function () { choose('Yukarı / Aşağı tuşları', ['Yukarı = sonraki kanal', 'Yukarı = önceki kanal'], S('invertUpDown') ? 1 : 0, function (i) { NV.settings.set('invertUpDown', i === 1); }); } });
            out.push({ title: 'Kanal bilgi süresi', value: S('bannerSeconds') + ' saniye',
                act: function () { choose('Kanal bilgi süresi', banners.map(function (b) { return b + ' saniye'; }), banners.indexOf(S('bannerSeconds')), function (i) { NV.settings.set('bannerSeconds', banners[i]); }); } });
            out.push({ title: 'Görüntü oranı', value: aspects[S('aspectMode')] || aspects[0], summary: 'TV\'nin video katmanı dönüşümleri desteklemeyebilir',
                act: function () { choose('Görüntü oranı', aspects, S('aspectMode'), function (i) { NV.settings.set('aspectMode', i); }); } });
            out.push({ title: 'Yayın kalitesi', value: qualities[S('quality')] || qualities[0], summary: 'webOS 1.x en fazla 1080p30 / H.264 Level 4.1 çözer; uygun HLS katmanı otomatik seçilir',
                act: function () { choose('Yayın kalitesi', qualities, S('quality'), function (i) { NV.settings.set('quality', i); }); } });

            out.push({ header: true, title: 'TELEFONDAN YÖNETİM' });
            out.push({ title: 'Telefondan yönet', value: '', summary: 'QR kod ve PIN ile telefonu eşleştirin (yerel ağdaki NovaTV proxy\'si üzerinden)',
                act: function () { NV.manageScreen.open(function () { render(); }); } });
            out.push({ title: 'Uzaktan yönetim', value: onOff(S('manageEnabled')), summary: manageStatus(),
                act: function () {
                    if (!S('manageEnabled') && !NV.http.hasProxy()) { NV.ui.osd('Önce proxy adresini girin (aşağıda).', 5000); return; }
                    NV.settings.set('manageEnabled', !S('manageEnabled'));
                    NV.manageRelay.update();
                    render();
                } });

            out.push({ header: true, title: 'PROXY (İSTEĞE BAĞLI)' });
            out.push({ title: 'Proxy adresi', value: S('proxyUrl') || '—', summary: 'CORS, eski sertifika veya .gz sorunlarında yerel ağdaki proxy (README)',
                act: function () { NV.dialog.input('Proxy adresi', S('proxyUrl') || 'http://', { hint: 'http://192.168.1.10:8787' }, function (v) { NV.settings.set('proxyUrl', v === 'http://' ? '' : v); render(); }); } });
            out.push({ title: 'Proxy anahtarı', value: S('proxyToken') ? '••••' : '—', summary: 'Proxy --token ile başlatıldıysa aynı değeri girin',
                act: function () { NV.dialog.input('Proxy anahtarı', S('proxyToken'), {}, function (v) { NV.settings.set('proxyToken', v); render(); }); } });
            out.push({ title: 'Tüm istekler proxy üzerinden', value: onOff(S('proxyAll')),
                act: function () { NV.settings.set('proxyAll', !S('proxyAll')); render(); } });
            out.push({ title: 'Proxy bağlantısını test et', value: '',
                act: function () {
                    if (!NV.http.hasProxy()) { NV.ui.osd('Önce proxy adresini girin.'); return; }
                    NV.ui.osd('Proxy test ediliyor…');
                    NV.http.get(NV.http.viaProxy('ping', 'ping'), { timeout: 6000 }, function (err, text) {
                        NV.ui.osd(err ? 'Proxy\'ye ulaşılamadı: ' + err.message : 'Proxy çalışıyor: ' + text, 5000);
                    });
                } });

            out.push({ header: true, title: 'KAYNAKLAR VE EPG' });
            out.push({ title: 'Resmi Türkiye kanallarını ekle', value: NV.channels.hasBuiltin() ? 'Ekli' : '', summary: NV.BUILTIN.description,
                act: function () {
                    if (NV.channels.hasBuiltin()) { NV.ui.osd('Ekli'); return; }
                    NV.channels.addBuiltin(function (err, n) { NV.ui.osd(err ? err.message : n + ' resmi kanal eklendi'); render(); });
                } });
            out.push({ title: 'Kaynakları otomatik yenile', value: refreshLabel(S('autoRefreshHours')),
                act: function () { choose('Kaynakları otomatik yenile', refresh.map(refreshLabel), refresh.indexOf(S('autoRefreshHours')), function (i) { NV.settings.set('autoRefreshHours', refresh[i]); }); } });
            out.push({ title: 'Genel EPG (XMLTV) adresi', value: S('epgUrl') || '—', summary: 'Birden fazla adres için virgül kullanın',
                act: function () { NV.dialog.input('Genel EPG (XMLTV) adresi', S('epgUrl'), { hint: 'http://…/epg.xml' }, function (v) { NV.settings.set('epgUrl', v); render(); NV.epg.refresh(function () { render(); }); }); } });
            out.push({ title: 'EPG\'yi şimdi güncelle', value: '', summary: epgStatus(),
                act: function () {
                    NV.ui.osd('EPG güncelleniyor…');
                    NV.epg.refresh(function (err, n) { NV.ui.osd(err ? 'EPG hatası: ' + err.message : n + ' kanal için program yüklendi', 5000); render(); });
                } });

            out.push({ header: true, title: 'BAKIM' });
            out.push({ title: '“Çalışmıyor” işaretlerini temizle', value: String(NV.channels.brokenCount()),
                act: function () { NV.channels.clearAllBroken(); NV.ui.osd('İşaretler temizlendi'); render(); } });
            out.push({ title: 'Gizlenen kanallar', value: String(NV.channels.snapshot().hidden.length), summary: 'Telefondan gizlenen kanalları listeye geri getirir',
                act: function () {
                    var n = NV.channels.snapshot().hidden.length;
                    if (!n) { NV.ui.osd('Gizlenen kanal yok'); return; }
                    NV.dialog.confirm('Gizlenen kanallar', n + ' gizli kanal yeniden listeye eklensin mi?', 'Evet', 'Hayır', function () { NV.channels.restoreAllHidden(); NV.ui.osd('Gizlenen kanallar geri getirildi'); render(); });
                } });
            out.push({ title: 'Kanal sıralamasını sıfırla',
                act: function () { NV.dialog.confirm('Kanal sıralamasını sıfırla', 'Özel kanal sıralaması silinip kaynaktaki sıraya dönülsün mü?', 'Evet', 'Hayır', function () { NV.channels.resetOrder(); NV.ui.osd('Sıralama sıfırlandı'); }); } });
            out.push({ title: 'Son izlenenleri temizle', act: function () { NV.channels.clearRecents(); NV.ui.osd('Son izlenenler temizlendi'); } });
            out.push({ title: 'Kullanılan depolama', value: Math.round(NV.store.usage() / 1024) + ' KB', summary: 'Büyük listeler TV hafızasına sığmazsa açılışta yeniden indirilir' });
            out.push({ title: 'Hakkında', value: '', summary: 'NovaTV ' + NV.VERSION + ' (webOS) • Açık kaynak (MIT) • ' + (NV.isWebOS() ? 'webOS TV' : 'tarayıcı modu') + ' • ' + navigator.userAgent });
            return out;
        }

        function firstRow(all) {
            var i;
            for (i = 0; i < all.length; i++) if (!all[i].header) return i;
            return 0;
        }

        function render() {
            var all = items(), keep = list.index;
            list.setItems(all, list.items.length ? keep : firstRow(all));
            if (all[list.index] && all[list.index].header) list.setIndex(firstRow(all));
            list.setActive(active);
        }

        function move(d) {
            var i = list.index + d;
            while (i >= 0 && i < list.items.length && list.items[i].header) i += d;
            if (i >= 0 && i < list.items.length) list.setIndex(i);
            else if (d < 0 && list.first > 0) { list.first = 0; list.draw(false); }
        }

        NV.events.on('epg-status', function () { if (host.isVisible(section)) render(); });
        NV.events.on('channels', function () { if (host.isVisible(section)) render(); });
        NV.events.on('manage-status', function () { if (host.isVisible(section)) render(); });

        var section = {
            el: el,
            onShow: render,
            focusContent: function () { active = true; render(); return true; },
            blur: function () { active = false; list.setActive(false); },
            handleKey: function (code) {
                var item = list.current();
                if (code === K.UP) { move(-1); return true; }
                if (code === K.DOWN) { move(1); return true; }
                if (code === K.OK) { if (item && item.act) item.act(); return true; }
                if (code === K.RIGHT) return true;
                return code !== K.LEFT;
            }
        };
        return section;
    };
}(NV));
