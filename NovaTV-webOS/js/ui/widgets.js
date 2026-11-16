/*
 * NovaTV webOS — UI building blocks.
 * Focus is drawn with a CSS class (no DOM focus except text inputs), which is what TV remotes
 * expect and is cheap on a 2014 SoC. Long lists are virtualised: only visible rows exist.
 */
(function (NV) {
    'use strict';

    NV.ui = NV.ui || {};

    // ---------------------------------------------------------------- logos

    /** Shows a channel logo in a plate, falling back to coloured initials (same as Android). */
    NV.ui.bindLogo = function (plate, url, name) {
        var img = plate._img, ini = plate._ini, src;
        if (!img) {
            img = plate._img = NV.el('img', 'logo-img');
            ini = plate._ini = NV.el('span', 'logo-initials');
            img.onerror = function () {
                img.style.display = 'none';
                ini.style.display = '';
            };
            img.onload = function () {
                ini.style.display = 'none';
                img.style.display = '';
            };
            plate.appendChild(img);
            plate.appendChild(ini);
        }
        ini.textContent = NV.initials(name);
        ini.style.backgroundColor = NV.colorFor(name);
        src = url || '';
        // Logos on https hosts may fail on a 2014 TV (certificate store); route them via the proxy if set.
        if (src && NV.startsWith(src, 'https://') && NV.http.hasProxy()) src = NV.http.viaProxy(src, 'fetch');
        if (!src) {
            img.removeAttribute('src');
            img.style.display = 'none';
            ini.style.display = '';
            plate._src = '';
            return;
        }
        if (plate._src !== src) {
            plate._src = src;
            img.style.display = 'none';
            ini.style.display = '';
            img.src = src;
        }
    };

    // ---------------------------------------------------------------- OSD

    var osdTimer = null;
    NV.ui.osd = function (message, ms) {
        var el = NV.$('osd');
        el.textContent = message;
        NV.show(el, true);
        if (osdTimer) clearTimeout(osdTimer);
        osdTimer = setTimeout(function () { NV.show(el, false); }, ms || 2600);
    };

    // ---------------------------------------------------------------- virtual list

    /**
     * opts: { rows: visible row count, rowHeight: px, create(): rowElement,
     *         render(rowEl, item, index), onFocus(item, index), margin: rows kept around focus }
     */
    function VirtualList(container, opts) {
        var i, row;
        this.el = container;
        this.opts = opts;
        this.items = [];
        this.index = 0;
        this.first = 0;
        this.active = false;
        this.rowEls = [];
        container.classList.add('vlist');
        for (i = 0; i < opts.rows; i++) {
            row = opts.create();
            row.style.top = (i * opts.rowHeight) + 'px';
            row.style.display = 'none';
            container.appendChild(row);
            this.rowEls.push(row);
        }
    }

    VirtualList.prototype.setItems = function (items, focusIndex) {
        this.items = items || [];
        this.index = Math.max(0, Math.min(focusIndex === undefined ? this.index : focusIndex, this.items.length - 1));
        this._clampFirst();
        this.draw(true);
    };

    VirtualList.prototype.current = function () { return this.items[this.index] || null; };

    VirtualList.prototype.setActive = function (active) {
        this.active = !!active;
        this.draw(false);
        if (this.active && this.opts.onFocus && this.current()) this.opts.onFocus(this.current(), this.index);
    };

    VirtualList.prototype.move = function (delta) {
        var n = this.items.length, target;
        if (!n) return false;
        target = Math.max(0, Math.min(n - 1, this.index + delta));
        if (target === this.index) return false;
        this.setIndex(target);
        return true;
    };

    VirtualList.prototype.setIndex = function (i) {
        if (!this.items.length) return;
        this.index = Math.max(0, Math.min(this.items.length - 1, i));
        this._clampFirst();
        this.draw(false);
        if (this.opts.onFocus) this.opts.onFocus(this.current(), this.index);
    };

    VirtualList.prototype._clampFirst = function () {
        var vis = this.opts.rows, n = this.items.length, margin = this.opts.margin === undefined ? 1 : this.opts.margin;
        if (vis <= 2) margin = 0;
        if (this.index < this.first + margin) this.first = this.index - margin;
        if (this.index > this.first + vis - 1 - margin) this.first = this.index - vis + 1 + margin;
        this.first = Math.max(0, Math.min(this.first, Math.max(0, n - vis)));
    };

    /** Re-renders visible rows; force=true re-binds content even for unchanged items. */
    VirtualList.prototype.draw = function (force) {
        var r, i, row, item;
        for (r = 0; r < this.rowEls.length; r++) {
            row = this.rowEls[r];
            i = this.first + r;
            item = this.items[i];
            if (item === undefined) {
                row.style.display = 'none';
                row._item = undefined;
                continue;
            }
            row.style.display = '';
            if (force || row._item !== item || row._i !== i) {
                this.opts.render(row, item, i);
                row._item = item;
                row._i = i;
            }
            if (this.active && i === this.index) row.classList.add('focused'); else row.classList.remove('focused');
        }
    };

    VirtualList.prototype.refresh = function () { this.draw(true); };

    VirtualList.prototype.indexWhere = function (fn) {
        var i;
        for (i = 0; i < this.items.length; i++) if (fn(this.items[i])) return i;
        return -1;
    };

    NV.ui.VirtualList = VirtualList;

    // ---------------------------------------------------------------- channel rows

    var ICON = 'assets/icons/';

    NV.ui.createChannelRow = function () {
        var row = NV.el('div', 'row ch-row');
        row._num = row.appendChild(NV.el('span', 'ch-num'));
        row._plate = row.appendChild(NV.el('span', 'logo-plate ch-logo'));
        row._text = row.appendChild(NV.el('div', 'ch-text'));
        row._name = row._text.appendChild(NV.el('div', 'ch-name'));
        row._sub = row._text.appendChild(NV.el('div', 'ch-sub'));
        row._icons = row.appendChild(NV.el('div', 'ch-icons'));
        row._src = row._icons.appendChild(NV.el('span', 'badge ch-src'));
        row._broken = row._icons.appendChild(NV.el('img', 'ch-icon'));
        row._broken.src = ICON + 'warning.svg';
        row._fav = row._icons.appendChild(NV.el('img', 'ch-icon'));
        row._fav.src = ICON + 'star_filled.svg';
        return row;
    };

    /** opts: { numberByPosition, playingKey, movingKey } */
    NV.ui.renderChannelRow = function (row, ch, index, opts) {
        var nn, now;
        opts = opts || {};
        row._num.textContent = String(opts.numberByPosition ? index + 1 : ch.number);
        row._name.textContent = ch.name;
        nn = NV.epg.nowNext(ch);
        now = nn.now;
        row._sub.textContent = now && now[2] ? '▶ ' + now[2] : (ch.group || 'Diğer');
        NV.ui.bindLogo(row._plate, ch.logo, ch.name);
        row._src.textContent = ch.sourceName;
        row._src.style.display = ch.multi ? '' : 'none';
        row._broken.style.display = NV.channels.isBroken(ch.key) ? '' : 'none';
        row._fav.style.display = NV.channels.isFavorite(ch.key) ? '' : 'none';
        if (ch.key === opts.playingKey || ch.key === opts.movingKey) row.classList.add('activated');
        else row.classList.remove('activated');
    };

    // ---------------------------------------------------------------- simple text rows

    NV.ui.createTextRow = function (cls) {
        var row = NV.el('div', 'row text-row ' + (cls || ''));
        row._icon = row.appendChild(NV.el('img', 'row-icon'));
        row._title = row.appendChild(NV.el('div', 'row-title'));
        row._value = row.appendChild(NV.el('div', 'row-value'));
        row._summary = row.appendChild(NV.el('div', 'row-summary'));
        return row;
    };

    NV.ui.renderTextRow = function (row, item) {
        if (item.icon) { row._icon.src = ICON + item.icon + '.svg'; row._icon.style.display = ''; } else { row._icon.style.display = 'none'; }
        row._title.textContent = item.title || '';
        row._value.textContent = item.value || '';
        row._value.style.display = item.value ? '' : 'none';
        row._summary.textContent = item.summary || '';
        row._summary.style.display = item.summary ? '' : 'none';
        if (item.header) row.classList.add('header'); else row.classList.remove('header');
        if (item.error) row.classList.add('error'); else row.classList.remove('error');
        if (item.dim) row.classList.add('dim'); else row.classList.remove('dim');
        if (item.summary) row.classList.remove('no-summary'); else row.classList.add('no-summary');
    };

    // ---------------------------------------------------------------- buttons

    NV.ui.button = function (label, icon, cls) {
        var b = NV.el('div', 'btn ' + (cls || ''));
        if (icon) {
            b._icon = b.appendChild(NV.el('img', 'btn-icon'));
            b._icon.src = ICON + icon + '.svg';
        }
        b._label = b.appendChild(NV.el('span', 'btn-label', label));
        return b;
    };

    NV.ui.setButtonLabel = function (b, label, icon) {
        b._label.textContent = label;
        if (icon && b._icon) b._icon.src = ICON + icon + '.svg';
    };

    /** Horizontal / vertical focus group over plain elements. */
    NV.ui.focusGroup = function (els) {
        return {
            els: els,
            index: 0,
            active: false,
            set: function (i) {
                var k;
                this.index = Math.max(0, Math.min(this.els.length - 1, i));
                for (k = 0; k < this.els.length; k++) {
                    if (this.active && k === this.index) this.els[k].classList.add('focused');
                    else this.els[k].classList.remove('focused');
                }
            },
            setActive: function (a) { this.active = !!a; this.set(this.index); },
            visibleEls: function () { return this.els; },
            move: function (d) {
                var i = this.index + d;
                while (i >= 0 && i < this.els.length && this.els[i].style.display === 'none') i += d;
                if (i < 0 || i >= this.els.length) return false;
                this.set(i);
                return true;
            },
            current: function () { return this.els[this.index]; }
        };
    };
}(NV));
