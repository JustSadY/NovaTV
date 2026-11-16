/*
 * NovaTV webOS — add / edit an IPTV source (port of Android SourceEditActivity).
 * Local M3U files are not offered: a webOS 1.x web app has no file picker or USB access;
 * the optional proxy can serve a file from a PC instead (see README).
 */
(function (NV) {
    'use strict';

    var K = NV.keys;

    NV.sourceEditor = {
        /** source: existing source object or null for a new one. onClose() after closing. */
        open: function (source, prefill, onClose) {
            var editing = source && !source.builtin ? source : null;
            var builtin = source && source.builtin;
            var scrim = NV.el('div', 'form-screen'), form = NV.el('div', 'form');
            var fields = [], focus = 0, busy = false, layer, status, spinner;

            form.appendChild(NV.el('div', 'form-title', builtin ? 'Dahili liste' : (editing ? 'Kaynağı düzenle' : 'IPTV kaynağı ekle')));

            function addInput(label, value, hint) {
                var wrap = NV.el('div', 'field'), input = NV.el('input', 'text-input');
                wrap.appendChild(NV.el('div', 'field-label', label));
                input.type = 'text';
                input.value = value || '';
                input.setAttribute('placeholder', hint || '');
                input.setAttribute('autocomplete', 'off');
                wrap.appendChild(input);
                form.appendChild(wrap);
                fields.push({ kind: 'input', el: input });
                return input;
            }

            var nameIn = addInput('Kaynak adı', editing ? editing.name : (prefill && prefill.name) || '', 'Örn. Ev IPTV');
            var urlIn = addInput('M3U / M3U8 adresi', editing ? editing.url : (prefill && prefill.url) || '', 'http://sunucu-adresi/playlist.m3u');
            var epgIn = addInput('EPG (XMLTV) adresi — isteğe bağlı', editing ? editing.epgUrl : '', 'http://sunucu-adresi/epg.xml');

            var proxyRow = NV.el('div', 'field toggle-row');
            var proxyOn = editing ? !!editing.useProxy : false;
            proxyRow.appendChild(NV.el('div', 'field-label', 'Bu kaynağı proxy üzerinden yükle'));
            var proxyVal = proxyRow.appendChild(NV.el('div', 'toggle-value'));
            form.appendChild(proxyRow);
            fields.push({ kind: 'toggle', el: proxyRow });
            function renderProxy() {
                proxyVal.textContent = proxyOn ? 'Açık' : 'Kapalı';
                if (!NV.http.hasProxy()) proxyVal.textContent += ' (Ayarlar\'da proxy adresi tanımlı değil)';
            }
            renderProxy();

            var buttons = NV.el('div', 'form-buttons');
            var saveBtn = buttons.appendChild(NV.ui.button('Kaydet ve yükle', 'refresh'));
            var cancelBtn = buttons.appendChild(NV.ui.button('İptal'));
            var deleteBtn = buttons.appendChild(NV.ui.button('Sil', 'delete'));
            if (!editing) deleteBtn.style.display = 'none';
            spinner = buttons.appendChild(NV.el('div', 'spinner small hidden'));
            status = buttons.appendChild(NV.el('div', 'form-status'));
            form.appendChild(buttons);
            var btnGroup = NV.ui.focusGroup([saveBtn, cancelBtn, deleteBtn]);
            fields.push({ kind: 'buttons', group: btnGroup });

            form.appendChild(NV.el('div', 'legal', 'NovaTV yalnızca kamu yayıncılarının (TRT, TBMM TV) resmi ve ücretsiz yayınlarını örnek olarak içerir; ücretli veya izinsiz yayın içermez. Yalnızca kullanım hakkına sahip olduğunuz bağlantıları ekleyin.'));
            scrim.appendChild(form);
            NV.$('layer-root').appendChild(scrim);

            function render() {
                var i, f;
                for (i = 0; i < fields.length; i++) {
                    f = fields[i];
                    if (f.kind === 'buttons') f.group.setActive(i === focus);
                    else if (i === focus) f.el.classList.add('focused');
                    else f.el.classList.remove('focused');
                }
            }

            function closeForm() {
                var i;
                for (i = 0; i < fields.length; i++) if (fields[i].kind === 'input') fields[i].el.blur();
                if (scrim.parentNode) scrim.parentNode.removeChild(scrim);
                NV.input.remove(layer);
                if (onClose) onClose();
            }

            function setBusy(b, message) {
                busy = b;
                NV.show(spinner, b);
                status.textContent = message || '';
                saveBtn.classList[b ? 'add' : 'remove']('disabled');
            }

            function save() {
                var url = urlIn.value.replace(/^\s+|\s+$/g, ''), name = nameIn.value.replace(/^\s+|\s+$/g, ''), draft;
                if (busy) return;
                if (!/^https?:\/\/.{4,}/i.test(url)) {
                    status.textContent = 'Lütfen geçerli bir http(s) adresi girin.';
                    focus = 1;
                    render();
                    return;
                }
                if (!name) name = url.replace(/^https?:\/\//i, '').split('/')[0] || 'IPTV Listesi';
                draft = editing ? NV.copy(editing) : { enabled: true };
                draft.name = name;
                draft.url = url;
                draft.epgUrl = epgIn.value.replace(/^\s+|\s+$/g, '');
                draft.useProxy = proxyOn;
                setBusy(true, 'Liste indiriliyor ve işleniyor…');
                NV.channels.importSource(draft, function (err, count) {
                    setBusy(false, '');
                    if (!err) {
                        NV.ui.osd(count + ' kanal yüklendi');
                        closeForm();
                        return;
                    }
                    NV.dialog.confirm('Liste yüklenemedi', err.message + '\n\nKaynak yine de kaydedilsin mi? Daha sonra yenileyebilirsiniz.',
                        'Yine de kaydet', 'İptal', function () {
                            NV.channels.saveSourceOnly(draft, err.message);
                            closeForm();
                        });
                });
            }

            function activate() {
                var f = fields[focus];
                if (f.kind === 'input') {
                    f.el.focus(); // opens the webOS on-screen keyboard
                } else if (f.kind === 'toggle') {
                    proxyOn = !proxyOn;
                    renderProxy();
                } else if (btnGroup.index === 0) {
                    save();
                } else if (btnGroup.index === 1) {
                    closeForm();
                } else if (editing) {
                    NV.dialog.confirm('Kaynağı sil', '“' + editing.name + '” kaynağı ve kanalları silinsin mi?', 'Sil', 'İptal', function () {
                        NV.channels.deleteSource(editing.id);
                        closeForm();
                    });
                }
            }

            layer = {
                closable: true,
                onBack: closeForm,
                handleKey: function (code) {
                    var f = fields[focus];
                    if (code === K.UP) {
                        if (f.kind === 'input') f.el.blur();
                        if (focus > 0) { focus--; render(); }
                        return true;
                    }
                    if (code === K.DOWN) {
                        if (f.kind === 'input') f.el.blur();
                        if (focus < fields.length - 1) { focus++; render(); }
                        return true;
                    }
                    if (f.kind === 'buttons' && (code === K.LEFT || code === K.RIGHT)) {
                        btnGroup.move(code === K.LEFT ? -1 : 1);
                        return true;
                    }
                    if (code === K.OK) {
                        if (f.kind === 'input' && document.activeElement === f.el) {
                            f.el.blur(); // keyboard "Enter": go to the next field
                            if (focus < fields.length - 1) { focus++; render(); }
                        } else {
                            activate();
                        }
                        return true;
                    }
                    return f.kind !== 'input';
                }
            };
            NV.input.push(layer);
            focus = (editing || (prefill && prefill.url)) ? fields.length - 1 : 0;
            render();
        }
    };
}(NV));
