/*
 * NovaTV webOS — "Telefondan yönet" screen: QR code + PIN for pairing a phone with this TV
 * through the NovaTV LAN proxy, the connection state and what the phone changed.
 * While this screen is open the relay runs even if "Uzaktan yönetim" is off (like Android).
 */
(function (NV) {
    'use strict';

    NV.manageScreen = {
        open: function (onClose) {
            var root, left, right, card, body, statusText, log, lines = [], layer, closed = false, pin, url, img, qr;

            if (!NV.http.hasProxy()) {
                NV.dialog.message('Telefondan yönet', 'LG TV\'ler (webOS 1.x) telefondan gelen bağlantıyı doğrudan kabul edemez; yerel ağdaki NovaTV proxy\'si aracılık eder.\n\n1. Bilgisayarda proxy\'yi başlatın (README → Proxy).\n2. Ayarlar → Proxy adresi\'ne adresini girin (ör. http://192.168.1.10:8787).\n3. Bu ekranı yeniden açın.');
                return null;
            }

            root = NV.el('div', 'form-screen link-screen');
            left = root.appendChild(NV.el('div', 'link-left'));
            right = root.appendChild(NV.el('div', 'link-right'));
            left.appendChild(NV.el('div', 'form-title', 'Telefondan yönet'));
            left.appendChild(NV.el('div', 'link-text', '1. Telefon bu TV ile aynı Wi-Fi ağında olsun.\n2. QR kodu telefon kamerasıyla okutun ya da adresi telefonun tarayıcısına yazıp PIN\'i girin.\n3. Telefon bu TV\'yi hatırlar. Panelden kaynak ve kanal ekleyip silebilir, kanalları gizleyip yeniden adlandırabilir, numaraları ve favorileri düzenleyebilirsiniz.'));
            statusText = left.appendChild(NV.el('div', 'link-sub', 'Proxy\'ye bağlanılıyor…'));
            log = left.appendChild(NV.el('div', 'link-log', 'Telefon bekleniyor…'));
            left.appendChild(NV.el('div', 'hint link-hint', NV.settings.get('manageEnabled') ?
                'Uzaktan yönetim açık: eşleşmiş telefonlar NovaTV açıkken ve proxy çalışırken bu TV\'yi yönetebilir. GERİ: kapat' :
                'Uzaktan yönetim kapalı: bu ekran kapanınca telefon bağlantısı da durur (Ayarlar → Uzaktan yönetim). GERİ: kapat'));

            card = right.appendChild(NV.el('div', 'link-card'));
            card.appendChild(NV.el('div', 'link-card-title', 'Telefonla okutun'));
            body = card.appendChild(NV.el('div', 'link-card-body'));

            pin = NV.manageRelay.openPairing();
            url = NV.manageRelay.panelUrl();
            try {
                qr = window.qrcode(0, 'M');
                qr.addData(url + '#p=' + pin);
                qr.make();
                img = body.appendChild(NV.el('img', 'link-qr'));
                img.src = qr.createDataURL(4, 2);
            } catch (e) {
                body.appendChild(NV.el('div', 'link-text', 'QR kod oluşturulamadı.'));
            }
            body.appendChild(NV.el('div', 'link-text small', 'Adres'));
            // Typed by hand: the short address lists this proxy's TVs; the QR code carries the full one.
            body.appendChild(NV.el('div', 'link-url', String(NV.settings.get('proxyUrl')).replace(/\/+$/, '') + '/manage'));
            body.appendChild(NV.el('div', 'link-text small', 'PIN'));
            body.appendChild(NV.el('div', 'link-pin', pin));

            NV.$('layer-root').appendChild(root);

            function paintStatus() {
                if (closed) return;
                if (NV.manageRelay.isConnected()) statusText.textContent = 'Proxy\'ye bağlı • ' + String(NV.settings.get('proxyUrl'));
                else statusText.textContent = 'Proxy\'ye ulaşılamıyor: ' + (NV.manageRelay.lastError() || 'bekleniyor…');
            }

            function onManage(text) {
                lines.push(String(text).replace(/^Telefondan: /, ''));
                log.textContent = lines.slice(-6).reverse().join('\n'); // newest first
            }

            NV.events.on('manage-status', paintStatus);
            NV.events.on('manage', onManage);
            paintStatus();

            function close() {
                closed = true;
                NV.events.off('manage-status', paintStatus);
                NV.events.off('manage', onManage);
                NV.manageRelay.closePairing();
                if (root.parentNode) root.parentNode.removeChild(root);
                NV.input.remove(layer);
                if (onClose) onClose();
            }

            layer = { closable: true, onBack: close, handleKey: function () { return true; } };
            NV.input.push(layer);
            return { close: close };
        }
    };
}(NV));
