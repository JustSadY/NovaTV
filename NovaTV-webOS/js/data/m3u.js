/*
 * NovaTV webOS — M3U / M3U8 (extended M3U) parser. Port of the Android M3uParser:
 * #EXTM3U url-tvg/x-tvg-url/tvg-url, #EXTINF attributes (tvg-id, tvg-name, tvg-logo, logo,
 * group-title) with quoted values containing commas, #EXTGRP, #EXTVLCOPT user-agent/referrer,
 * Kodi "url|User-Agent=..", bare URL lines, BOM and CRLF.
 */
(function (NV) {
    'use strict';

    var SUPPORTED = { http: 1, https: 1, rtsp: 1, udp: 1 };

    /** Parses key=value pairs starting at index i. Returns the index of the stop comma or the end. */
    function parseAttributes(line, start, out, stopAtComma) {
        var i = start, n = line.length, c, keyStart, key, quote, valueStart, end;
        while (i < n) {
            c = line.charAt(i);
            if (c === ',' && stopAtComma) return i;
            if (c === ' ' || c === '\t' || c === ',') { i++; continue; }
            keyStart = i;
            while (i < n && line.charAt(i) !== '=' && line.charAt(i) !== ' ' && line.charAt(i) !== '\t' &&
                !(stopAtComma && line.charAt(i) === ',')) i++;
            key = line.substring(keyStart, i).replace(/^\s+|\s+$/g, '').toLowerCase();
            if (i < n && line.charAt(i) === '=') {
                i++;
                if (i < n && (line.charAt(i) === '"' || line.charAt(i) === "'")) {
                    quote = line.charAt(i);
                    valueStart = i + 1;
                    end = line.indexOf(quote, valueStart);
                    if (end < 0) end = n;
                    if (key) out[key] = line.substring(valueStart, end).replace(/^\s+|\s+$/g, '');
                    i = end < n ? end + 1 : n;
                } else {
                    valueStart = i;
                    while (i < n && line.charAt(i) !== ' ' && line.charAt(i) !== '\t' && !(stopAtComma && line.charAt(i) === ',')) i++;
                    if (key) out[key] = line.substring(valueStart, i).replace(/^\s+|\s+$/g, '');
                }
            }
        }
        return n;
    }

    function parseExtInf(line, start, out) {
        var i = start, n = line.length;
        while (i < n && line.charAt(i) !== ' ' && line.charAt(i) !== '\t' && line.charAt(i) !== ',') i++;
        i = parseAttributes(line, i, out, true);
        return (i < n && line.charAt(i) === ',') ? i + 1 : i;
    }

    function isSupported(url) {
        var p = url.indexOf('://');
        return p > 0 && SUPPORTED[url.substring(0, p).toLowerCase()] === 1;
    }

    function nameFromUrl(url) {
        var path = url.split('?')[0].replace(/\/+$/, ''), last = path.substring(path.lastIndexOf('/') + 1), dot = last.lastIndexOf('.');
        var base = dot > 0 ? last.substring(0, dot) : last;
        return base || url.replace(/^[a-z]+:\/\//i, '').split('/')[0];
    }

    function decode(v) {
        try { return decodeURIComponent(v.replace(/\+/g, ' ')); } catch (e) { return v; }
    }

    function blank(v) { return v === undefined || v === null || String(v).replace(/\s+/g, '') === ''; }

    /**
     * Parses the playlist text.
     * Returns { entries: [{name,url,logo,group,tvgId,tvgName,userAgent,referrer}], epgUrl, isHlsStream, skipped }
     */
    function parse(text) {
        var lines = String(text || '').split(/\r\n|\r|\n/), entries = [], epgUrl = null, hlsTags = 0, skipped = 0;
        var pendingAttrs = null, pendingName = null, extGroup = null, vlcUa = null, vlcRef = null;
        var i, line, attrs, colon, nameStart, opt, eq, key, value, pipe, url, pipeUa, pipeRef, parts, j, k, v, tvgName, header;

        for (i = 0; i < lines.length; i++) {
            line = lines[i];
            if (i === 0 && line.charCodeAt(0) === 0xFEFF) line = line.substring(1);
            line = line.replace(/^\s+|\s+$/g, '');
            if (!line) continue;

            if (line.charAt(0) === '#') {
                if (/^#EXTM3U/i.test(line)) {
                    attrs = {};
                    parseAttributes(line, 7, attrs, false);
                    header = attrs['url-tvg'] || attrs['x-tvg-url'] || attrs['tvg-url'];
                    if (!blank(header)) epgUrl = header.replace(/^\s+|\s+$/g, '');
                } else if (/^#EXTINF/i.test(line)) {
                    attrs = {};
                    colon = line.indexOf(':');
                    nameStart = colon < 0 ? line.length : parseExtInf(line, colon + 1, attrs);
                    pendingAttrs = attrs;
                    pendingName = line.substring(nameStart).replace(/^\s+|\s+$/g, '');
                } else if (/^#EXTGRP:/i.test(line)) {
                    extGroup = line.substring(8).replace(/^\s+|\s+$/g, '');
                } else if (/^#EXTVLCOPT:/i.test(line)) {
                    opt = line.substring(11).replace(/^\s+|\s+$/g, '');
                    eq = opt.indexOf('=');
                    if (eq > 0) {
                        key = opt.substring(0, eq).toLowerCase();
                        value = opt.substring(eq + 1);
                        if (key === 'http-user-agent') vlcUa = value;
                        else if (key === 'http-referrer' || key === 'http-referer') vlcRef = value;
                    }
                } else if (/^#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE)/i.test(line)) {
                    hlsTags++;
                }
                continue;
            }

            pipe = line.indexOf('|');
            url = (pipe >= 0 ? line.substring(0, pipe) : line).replace(/^\s+|\s+$/g, '');
            pipeUa = null; pipeRef = null;
            if (pipe >= 0) {
                parts = line.substring(pipe + 1).split('&');
                for (j = 0; j < parts.length; j++) {
                    k = parts[j].indexOf('=');
                    if (k <= 0) continue;
                    v = decode(parts[j].substring(k + 1));
                    key = parts[j].substring(0, k).toLowerCase();
                    if (key === 'user-agent') pipeUa = v;
                    else if (key === 'referer' || key === 'referrer') pipeRef = v;
                }
            }
            attrs = pendingAttrs || {};
            if (isSupported(url)) {
                tvgName = blank(attrs['tvg-name']) ? null : attrs['tvg-name'];
                entries.push({
                    name: !blank(pendingName) ? pendingName : (tvgName || nameFromUrl(url)),
                    url: url,
                    logo: !blank(attrs['tvg-logo']) ? attrs['tvg-logo'] : (!blank(attrs.logo) ? attrs.logo : null),
                    group: (!blank(attrs['group-title']) ? attrs['group-title'] : (extGroup || '')).replace(/^\s+|\s+$/g, ''),
                    tvgId: blank(attrs['tvg-id']) ? null : attrs['tvg-id'],
                    tvgName: tvgName,
                    userAgent: pipeUa || vlcUa || (blank(attrs['user-agent']) ? null : attrs['user-agent']),
                    referrer: pipeRef || vlcRef || (blank(attrs['http-referrer']) ? null : attrs['http-referrer'])
                });
            } else {
                skipped++;
            }
            pendingAttrs = null; pendingName = null; extGroup = null; vlcUa = null; vlcRef = null;
        }

        var hasMeta = false;
        for (i = 0; i < entries.length; i++) if (entries[i].tvgId || entries[i].logo) { hasMeta = true; break; }
        return { entries: entries, epgUrl: epgUrl, isHlsStream: hlsTags > 0 && !hasMeta, skipped: skipped };
    }

    NV.m3u = { parse: parse, isSupported: isSupported };
}(NV));
