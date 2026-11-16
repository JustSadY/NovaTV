/*
 * NovaTV webOS — HLS helpers (no MSE / hls.js: webOS 1.x plays HLS natively in <video>).
 * Picks a variant the 2014 decoder can handle: LG webOS 1.0 documents 1080p@30 max and
 * H.264 level <= 4.1 for FHD models; many broadcasters offer 1080p50 (level 4.2) or 1440p.
 */
(function (NV) {
    'use strict';

    function isHlsUrl(url) {
        var lower = String(url).toLowerCase(), path = lower.split('?')[0];
        return NV.endsWith(path, '.m3u8') || NV.endsWith(path, '.m3u') || path.indexOf('.m3u8/') >= 0 ||
            lower.indexOf('type=m3u8') >= 0 || lower.indexOf('output=hls') >= 0 || lower.indexOf('format=m3u8') >= 0 ||
            lower.indexOf('extension=m3u8') >= 0;
    }

    function resolve(uri, baseUrl) {
        var m, origin, dir;
        if (/^[a-z][a-z0-9+.\-]*:\/\//i.test(uri)) return uri;
        m = /^([a-z][a-z0-9+.\-]*:\/\/[^\/]+)(\/[^?#]*)?/i.exec(baseUrl);
        if (!m) return uri;
        origin = m[1];
        if (uri.charAt(0) === '/') return origin + uri;
        dir = (m[2] || '/').substring(0, (m[2] || '/').lastIndexOf('/') + 1);
        return origin + dir + uri;
    }

    function attrValue(line, name) {
        var m = new RegExp('[:,]' + name + '=("([^"]*)"|[^,]*)').exec(line);
        return m ? (m[2] !== undefined ? m[2] : m[1]) : null;
    }

    /** Returns [{url, bandwidth, width, height, fps, codecs, level}] or null for a media playlist. */
    function parseMaster(text, baseUrl) {
        var lines = String(text).split(/\r\n|\r|\n/), out = [], i, line, res, next, v;
        if (!/^#EXTM3U/.test(lines[0] || '')) return null;
        for (i = 0; i < lines.length; i++) {
            line = lines[i];
            if (line.indexOf('#EXT-X-STREAM-INF') !== 0) continue;
            next = i + 1;
            while (next < lines.length && (lines[next].replace(/\s+/g, '') === '' || lines[next].charAt(0) === '#')) next++;
            if (next >= lines.length) break;
            res = (attrValue(line, 'RESOLUTION') || '').split('x');
            v = {
                url: resolve(lines[next].replace(/^\s+|\s+$/g, ''), baseUrl),
                bandwidth: parseInt(attrValue(line, 'BANDWIDTH') || '0', 10) || 0,
                width: parseInt(res[0], 10) || 0,
                height: parseInt(res[1], 10) || 0,
                fps: parseFloat(attrValue(line, 'FRAME-RATE') || '0') || 0,
                codecs: attrValue(line, 'CODECS') || ''
            };
            v.level = avcLevel(v.codecs);
            out.push(v);
        }
        return out.length ? out : null;
    }

    /** "avc1.640028" -> 40 (level 4.0); "avc1.77.30" -> 30; unknown -> 0. */
    function avcLevel(codecs) {
        var m = /avc1\.([0-9a-f]{6})/i.exec(codecs), m2;
        if (m) return parseInt(m[1].substring(4, 6), 16);
        m2 = /avc1\.\d+\.(\d+)/i.exec(codecs);
        return m2 ? parseInt(m2[1], 10) : 0;
    }

    function hasUnsupportedCodec(codecs) {
        return /(hvc1|hev1|av01|vp09|ec-3|ac-3)/i.test(codecs);
    }

    /**
     * mode: 0 auto (<=1080p, level <= 4.1, 1080p only up to 30 fps), 1 <=720p, 2 <=480p.
     * Returns the best fitting variant, or the smallest one when nothing fits.
     */
    function pick(variants, mode) {
        var maxH = mode === 2 ? 480 : (mode === 1 ? 720 : 1080), best = null, smallest = null, i, v, ok;
        for (i = 0; i < variants.length; i++) {
            v = variants[i];
            if (!smallest || v.bandwidth < smallest.bandwidth) smallest = v;
            ok = (!v.height || v.height <= maxH) && (!v.level || v.level <= 41) && !hasUnsupportedCodec(v.codecs) &&
                !(v.height > 720 && v.fps > 30.5);
            if (ok && (!best || v.bandwidth > best.bandwidth)) best = v;
        }
        return best || smallest;
    }

    NV.hls = { isHlsUrl: isHlsUrl, parseMaster: parseMaster, pick: pick, avcLevel: avcLevel, resolve: resolve };
}(NV));
