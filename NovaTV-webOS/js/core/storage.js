/* NovaTV webOS — localStorage wrapper (quota-safe, JSON). */
(function (NV) {
    'use strict';

    var PREFIX = 'nv.';

    function ls() {
        try { return window.localStorage || null; } catch (e) { return null; }
    }

    NV.store = {
        get: function (key, def) {
            var s = ls(), v;
            if (!s) return def;
            try {
                v = s.getItem(PREFIX + key);
                return v === null || v === undefined ? def : JSON.parse(v);
            } catch (e) {
                return def;
            }
        },

        /** Returns false when the value could not be stored (e.g. quota exceeded). */
        set: function (key, value) {
            var s = ls();
            if (!s) return false;
            try {
                s.setItem(PREFIX + key, JSON.stringify(value));
                return true;
            } catch (e) {
                NV.log('storage: could not save ' + key + ' (' + e + ')');
                return false;
            }
        },

        remove: function (key) {
            var s = ls();
            if (!s) return;
            try { s.removeItem(PREFIX + key); } catch (e) { /* ignore */ }
        },

        /** Approximate number of characters used by NovaTV keys. */
        usage: function () {
            var s = ls(), total = 0, i, k;
            if (!s) return 0;
            try {
                for (i = 0; i < s.length; i++) {
                    k = s.key(i);
                    if (k && NV.startsWith(k, PREFIX)) total += k.length + (s.getItem(k) || '').length;
                }
            } catch (e) { /* ignore */ }
            return total;
        }
    };
}(NV));
