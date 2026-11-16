/* NovaTV webOS — remote-friendly dialogs (port of Android TvDialog): options, confirm, message, input. */
(function (NV) {
    'use strict';

    var K = NV.keys;

    function open(title) {
        var scrim = NV.el('div', 'dialog-scrim'), box = NV.el('div', 'dialog');
        box.appendChild(NV.el('div', 'dialog-title', title));
        scrim.appendChild(box);
        NV.$('layer-root').appendChild(scrim);
        return { scrim: scrim, box: box };
    }

    function close(d, layer) {
        if (d.scrim.parentNode) d.scrim.parentNode.removeChild(d.scrim);
        NV.input.remove(layer);
    }

    function buttonRow(box, labels) {
        var row = NV.el('div', 'dialog-buttons'), btns = [], i;
        for (i = 0; i < labels.length; i++) btns.push(row.appendChild(NV.ui.button(labels[i])));
        box.appendChild(row);
        return NV.ui.focusGroup(btns);
    }

    NV.dialog = {
        /** List of choices; onPick(index). */
        options: function (title, labels, selected, onPick) {
            var d = open(title), host = NV.el('div', 'dialog-list'), rows = Math.min(8, Math.max(1, labels.length)), list, layer;
            host.style.height = (rows * 58) + 'px';
            d.box.appendChild(host);
            list = new NV.ui.VirtualList(host, {
                rows: rows, rowHeight: 58, margin: 1,
                create: function () { return NV.el('div', 'row opt-row'); },
                render: function (row, label, i) {
                    row.textContent = (i === selected ? '✓  ' : '') + label;
                    if (i === selected) row.classList.add('selected'); else row.classList.remove('selected');
                }
            });
            list.setItems(labels, selected >= 0 ? selected : 0);
            list.setActive(true);
            layer = {
                closable: true,
                onBack: function () { close(d, layer); },
                handleKey: function (code) {
                    if (code === K.UP) { list.move(-1); return true; }
                    if (code === K.DOWN) { list.move(1); return true; }
                    if (code === K.CH_UP) { list.move(-6); return true; }
                    if (code === K.CH_DOWN) { list.move(6); return true; }
                    if (code === K.OK) {
                        close(d, layer);
                        onPick(list.index);
                        return true;
                    }
                    return true; // modal
                }
            };
            NV.input.push(layer);
        },

        confirm: function (title, message, yes, no, onYes, onNo) {
            var d = open(title), group, layer;
            d.box.appendChild(NV.el('div', 'dialog-message', message));
            group = buttonRow(d.box, no ? [yes, no] : [yes]);
            group.setActive(true);
            layer = {
                closable: true,
                onBack: function () { close(d, layer); if (onNo) onNo(); },
                handleKey: function (code) {
                    if (code === K.LEFT) { group.move(-1); return true; }
                    if (code === K.RIGHT) { group.move(1); return true; }
                    if (code === K.OK) {
                        close(d, layer);
                        if (group.index === 0) { if (onYes) onYes(); } else if (onNo) onNo();
                        return true;
                    }
                    return true;
                }
            };
            NV.input.push(layer);
        },

        /** Read-only text; UP/DOWN scroll, OK/Back close. */
        message: function (title, text) {
            var d = open(title), body = NV.el('div', 'dialog-message scroll', text), group, layer;
            d.box.appendChild(body);
            group = buttonRow(d.box, ['Tamam']);
            group.setActive(true);
            layer = {
                closable: true,
                onBack: function () { close(d, layer); },
                handleKey: function (code) {
                    if (code === K.UP) { body.scrollTop -= 60; return true; }
                    if (code === K.DOWN) { body.scrollTop += 60; return true; }
                    if (code === K.OK) { close(d, layer); return true; }
                    return true;
                }
            };
            NV.input.push(layer);
        },

        /**
         * Text input using the webOS on-screen keyboard (shown when the <input> gets DOM focus).
         * opts: { hint, numeric }. onDone(value).
         */
        input: function (title, value, opts, onDone) {
            var d = open(title), input = NV.el('input', 'text-input'), group, layer, onInput = true;
            opts = opts || {};
            input.type = opts.numeric ? 'number' : 'text';
            input.value = value || '';
            input.setAttribute('placeholder', opts.hint || '');
            input.setAttribute('autocomplete', 'off');
            d.box.appendChild(input);
            group = buttonRow(d.box, ['Kaydet', 'İptal']);

            function focusInput() {
                onInput = true;
                group.setActive(false);
                input.classList.add('focused');
                input.focus();
            }
            function focusButtons() {
                onInput = false;
                input.classList.remove('focused');
                input.blur();
                group.setActive(true);
            }
            function submit() {
                var v = input.value.replace(/^\s+|\s+$/g, '');
                input.blur();
                close(d, layer);
                onDone(v);
            }
            layer = {
                closable: true,
                onBack: function () { input.blur(); close(d, layer); },
                handleKey: function (code) {
                    if (onInput) {
                        if (code === K.OK) { submit(); return true; }
                        if (code === K.DOWN) { focusButtons(); return true; }
                        return false; // let the input / keyboard handle it
                    }
                    if (code === K.UP) { focusInput(); return true; }
                    if (code === K.LEFT) { group.move(-1); return true; }
                    if (code === K.RIGHT) { group.move(1); return true; }
                    if (code === K.OK) {
                        if (group.index === 0) submit(); else layer.onBack();
                        return true;
                    }
                    return true;
                }
            };
            NV.input.push(layer);
            setTimeout(focusInput, 50);
        }
    };
}(NV));
