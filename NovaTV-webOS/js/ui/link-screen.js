/*
 * NovaTV webOS — "Telefondan / Ağdan ekle" screen.
 * Left: NovaTV devices found on the LAN (Android TV with NovaTV, NovaTV proxy).
 * Right: phone pairing (QR + PIN) when a proxy is available.
 */
(function (NV) {
    'use strict';

    var K = NV.keys;

    NV.linkScreen = {
        open: function (onClose) {
            var root = NV.el('div', 'form-screen link-screen');
            var left = root.appendChild(NV.el('div', 'link-left'));
            var right = root.appendChild(NV.el('div', 'link-right'));
            var layer, scanHandle = null, listener = null, devices = [], area = 'list', closed = false, pairedBase = null;

            left.appendChild(NV.el('div', 'form-title', 'Telefondan / Ağdan ekle'));
            left.appendChild(NV.el('div', 'link-sub', 'Yakındaki NovaTV cihazları'));
            var statusRow = left.appendChild(NV.el('div', 'link-status'));
            var spinner = statusRow.appendChild(NV.el('div', 'spinner small'));
            var statusText = statusRow.appendChild(NV.el('span', 'link-status-text', 'Ağ taranıyor…'));
            var listHost = left.appendChild(NV.el('div', 'link-list'));
            var empty = left.appendChild(NV.el('div', 'link-empty', 'Henüz cihaz bulunamadı. Android TV\'de NovaTV → Ayarlar → “Telefondan yönet” ekranını açın veya bilgisayarda NovaTV proxy\'sini çalıştırın.'));
            var btnRow = left.appendChild(NV.el('div', 'link-buttons'));
            var rescanBtn = btnRow.appendChild(NV.ui.button('Yeniden tara', 'refresh'));
            var ipBtn = btnRow.appendChild(NV.ui.button('IP adresi gir', 'edit'));
            var buttons = NV.ui.focusGroup([rescanBtn, ipBtn]);
            left.appendChild(NV.el('div', 'hint link-hint', 'OK: cihazı seç • Android TV: o TV\'deki kaynakları PIN ile kopyalar • Proxy: telefondan eklemeyi açar • GERİ: kapat'));
            listHost.style.height = (5 * 84) + 'px';

            var card = right.appendChild(NV.el('div', 'link-card'));
            card.appendChild(NV.el('div', 'link-card-title', 'Telefondan ekle'));
            var cardBody = card.appendChild(NV.el('div', 'link-card-body'));

            NV.$('layer-root').appendChild(root);

            var list = new NV.ui.VirtualList(listHost, {
                rows: 5, rowHeight: 84,
                create: function () {
                    var r = NV.el('div', 'row dev-row');
                    r._icon = r.appendChild(NV.el('img', 'dev-icon'));
                    r._name = r.appendChild(NV.el('div', 'dev-name'));
                    r._addr = r.appendChild(NV.el('div', 'dev-addr'));
                    r._badge = r.appendChild(NV.el('div', 'badge dev-badge'));
                    return r;
                },
                render: function (row, d) {
                    row._icon.src = 'assets/icons/' + (d.kind === 'proxy' ? 'link' : 'live') + '.svg';
                    row._name.textContent = d.info.name || d.host;
                    row._addr.textContent = d.host + ':' + d.port + ' • NovaTV ' + (d.info.version || '');
                    row._badge.textContent = d.kind === 'proxy' ? (pairedBase === d.base ? 'Proxy • bağlı' : 'Proxy') : 'Android TV';
                }
            });

            function paint() {
                list.setActive(area === 'list');
                buttons.setActive(area === 'buttons');
                NV.show(empty, !devices.length);
            }

            function setStatus(text, busy) {
                statusText.textContent = text;
                NV.show(spinner, !!busy);
            }

            function addDevice(d) {
                var i;
                for (i = 0; i < devices.length; i++) if (devices[i].base === d.base) return;
                devices.push(d);
                list.setItems(devices.slice(), list.index);
                if (!devices.length || area !== 'buttons') area = 'list';
                paint();
                // A proxy found on the LAN can be used right away for phone pairing.
                if (d.kind === 'proxy' && !pairedBase && !NV.http.hasProxy()) useProxy(d, false);
            }

            function startScan(hosts) {
                if (scanHandle) scanHandle.cancel();
                setStatus('Ağ taranıyor…', true);
                NV.link.subnets(function (subnets, detected) {
                    if (closed) return;
                    // Known own subnet: scan only that one (~20 s); otherwise the two most common home subnets.
                    scanHandle = NV.link.scan(hosts ? { hosts: hosts } : { subnets: subnets.slice(0, detected ? 1 : 2) }, addDevice, function (done, total, subnet) {
                        setStatus((subnet ? subnet + '.x' : 'Adres') + ' taranıyor… %' + Math.round(done * 100 / total), true);
                    }, function (found) {
                        scanHandle = null;
                        setStatus(devices.length ? devices.length + ' cihaz bulundu' : 'Cihaz bulunamadı', false);
                        if (hosts && !found.length) NV.ui.osd('Bu adreste NovaTV bulunamadı (8686 / 8787).', 4000);
                    });
                });
            }

            // ---------------------------------------------------------- phone pairing (proxy)

            function renderPairingHelp(message) {
                NV.clear(cardBody);
                cardBody.appendChild(NV.el('div', 'link-text', message || 'Telefondan eklemek için yerel ağda NovaTV proxy\'si gerekir (bilgisayar, Raspberry Pi veya NAS üzerinde; README\'ye bakın). Proxy bulunduğunda burada bir QR kod belirir.\n\nAndroid TV\'niz varsa: o TV\'de Ayarlar → “Telefondan yönet” ekranını açın, soldaki listeden seçin ve PIN ile kaynaklarını kopyalayın.'));
            }

            function startPairing() {
                if (listener) { listener.stop(); listener = null; }
                NV.clear(cardBody);
                cardBody.appendChild(NV.el('div', 'link-text', 'Proxy ile eşleşiliyor…'));
                NV.link.pair(function (err, p) {
                    var qr, img, log;
                    if (closed) return;
                    if (err) { renderPairingHelp('Proxy ile eşleşilemedi: ' + err.message); return; }
                    pairedBase = String(NV.settings.get('proxyUrl')).replace(/\/+$/, '');
                    list.refresh();
                    NV.clear(cardBody);
                    try {
                        qr = window.qrcode(0, 'M');
                        qr.addData(p.phoneUrl);
                        qr.make();
                        img = cardBody.appendChild(NV.el('img', 'link-qr'));
                        img.src = qr.createDataURL(5, 2);
                    } catch (e) {
                        cardBody.appendChild(NV.el('div', 'link-text', 'QR kod oluşturulamadı.'));
                    }
                    cardBody.appendChild(NV.el('div', 'link-text small', 'Telefon kamerasıyla okutun ya da tarayıcıya yazın:'));
                    cardBody.appendChild(NV.el('div', 'link-url', p.phoneUrl.replace(/\?k=\d+$/, '')));
                    cardBody.appendChild(NV.el('div', 'link-text small', 'PIN'));
                    cardBody.appendChild(NV.el('div', 'link-pin', p.key));
                    log = cardBody.appendChild(NV.el('div', 'link-log', 'Telefondan gönderilecek kaynak bekleniyor…'));
                    var lines = {}, order = [];
                    function line(id, text) {
                        if (!lines.hasOwnProperty(id)) order.push(id);
                        lines[id] = text;
                        log.textContent = order.slice(-4).map(function (k) { return lines[k]; }).join('\n');
                    }
                    listener = NV.link.listen(p.key, function (type, item, extra) {
                        if (type === 'expired') { startPairing(); return; }
                        if (type === 'received') line(item.id, '⏳ ' + (item.name || item.url) + ' — yükleniyor…');
                        else if (type === 'done') { line(item.id, '✓ ' + (item.name || item.url) + ' — ' + extra + ' kanal eklendi'); NV.ui.osd('Telefondan eklendi: ' + (item.name || '') + ' (' + extra + ' kanal)'); }
                        else if (type === 'error') line(item.id, '✗ ' + (item.name || item.url) + ' — ' + extra);
                    });
                });
            }

            function useProxy(d, announce) {
                if (String(NV.settings.get('proxyUrl')).replace(/\/+$/, '') !== d.base) {
                    NV.settings.set('proxyUrl', d.base);
                    if (announce) NV.ui.osd('Proxy ayarlandı: ' + d.base);
                }
                startPairing();
            }

            // ---------------------------------------------------------- copy from another NovaTV

            function copyFrom(d) {
                NV.dialog.input((d.info.name || d.host) + ' ekranındaki PIN', '', { numeric: true, hint: '0000' }, function (pin) {
                    if (!pin) return;
                    setStatus('Kaynaklar alınıyor…', true);
                    NV.link.fetchShared(d, pin, function (err, srcs) {
                        var names;
                        setStatus(devices.length + ' cihaz bulundu', false);
                        if (err) { NV.ui.osd(err.message, 5000); return; }
                        if (!srcs.length) { NV.ui.osd('O cihazda paylaşılacak URL kaynağı yok.', 4000); return; }
                        names = srcs.map(function (s) { return '• ' + s.name; }).join('\n');
                        NV.dialog.confirm(srcs.length + ' kaynak bulundu', names + '\n\nHepsi bu TV\'ye eklensin mi? (Zaten ekli olanlar atlanır.)', 'Ekle', 'İptal', function () {
                            NV.link.importList(srcs, function (item, k, n) {
                                setStatus('Ekleniyor (' + (k + 1) + '/' + n + '): ' + item.name, true);
                            }, function (added, skipped, failed) {
                                setStatus(devices.length + ' cihaz bulundu', false);
                                NV.ui.osd(added + ' kaynak eklendi' + (skipped ? ', ' + skipped + ' zaten vardı' : '') + (failed.length ? '. Hata: ' + failed[0] : ''), 6000);
                            });
                        });
                    });
                });
            }

            function enterIp() {
                NV.dialog.input('Cihazın IP adresi', '192.168.1.', { hint: '192.168.1.40' }, function (v) {
                    var ip = String(v || '').replace(/^https?:\/\//, '').split(':')[0];
                    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) { NV.ui.osd('Geçerli bir IP adresi girin.'); return; }
                    startScan([ip]);
                });
            }

            function close() {
                closed = true;
                if (scanHandle) scanHandle.cancel();
                if (listener) listener.stop();
                if (root.parentNode) root.parentNode.removeChild(root);
                NV.input.remove(layer);
                if (onClose) onClose();
            }

            layer = {
                closable: true,
                onBack: close,
                handleKey: function (code) {
                    var d;
                    if (area === 'list') {
                        if (code === K.UP) { list.move(-1); return true; }
                        if (code === K.DOWN) {
                            if (!devices.length || list.index >= devices.length - 1) { area = 'buttons'; paint(); } else list.move(1);
                            return true;
                        }
                        if (code === K.OK && (d = list.current())) {
                            if (d.kind === 'proxy') useProxy(d, true); else copyFrom(d);
                            return true;
                        }
                        return true;
                    }
                    if (code === K.UP) { if (devices.length) { area = 'list'; paint(); } return true; }
                    if (code === K.LEFT) { buttons.move(-1); return true; }
                    if (code === K.RIGHT) { buttons.move(1); return true; }
                    if (code === K.OK) { if (buttons.index === 0) startScan(); else enterIp(); return true; }
                    return true;
                }
            };
            NV.input.push(layer);
            area = 'buttons';
            paint();

            if (NV.http.hasProxy()) {
                NV.link.info(String(NV.settings.get('proxyUrl')).replace(/\/+$/, ''), 3000, function (err) {
                    if (closed) return;
                    if (err) renderPairingHelp('Ayarlardaki proxy adresine ulaşılamadı. Ağda başka bir proxy aranıyor…');
                    else startPairing();
                });
            } else {
                renderPairingHelp();
            }
            startScan();
            return { close: close };
        }
    };
}(NV));
