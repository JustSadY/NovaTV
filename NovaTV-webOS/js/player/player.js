/*
 * NovaTV webOS — live player around the native HTML5 <video> element (port of Android LivePlayer).
 *  - HLS (.m3u8) uses the TV's built-in HLS support (no MSE/hls.js; webOS 1.x has no MSE).
 *  - Picks a decodable HLS variant (see NV.hls.pick) when the master playlist is readable.
 *  - Clean source teardown on channel change (pause, remove <source>, load()) to free the decoder.
 *  - Reconnects: 4 fast retries (1-2-4-8 s), then every 15 s; a stall watchdog catches streams
 *    that silently stop. HTTP 4xx is detected with a probe request and reported clearly.
 */
(function (NV) {
    'use strict';

    var FAST_RETRIES = 4, SLOW_RETRY_MS = 15000, STALL_MS = 15000;

    function Player(video, listener) {
        var self = this;
        this.video = video;
        this.listener = listener;
        this.channel = null;
        this.token = 0;
        this.attempts = 0;
        this.retryTimer = null;
        this.started = false;
        this.wantPlaying = false;
        this.paused = false;
        this.lastProgress = 0;
        this.lastTime = -1;
        this.variant = null;
        this.srcUrl = '';
        this.variantCache = {};

        function on(name, fn) { video.addEventListener(name, fn, false); }
        on('playing', function () { self._progress(true); });
        on('timeupdate', function () {
            if (video.currentTime !== self.lastTime) {
                self.lastTime = video.currentTime;
                self._progress(false);
            }
        });
        on('waiting', function () { if (self.wantPlaying) self.listener.onLoading(true); });
        on('stalled', function () { if (self.wantPlaying) self.listener.onLoading(true); });
        on('loadedmetadata', function () { if (self.channel) self.listener.onInfo(self.channel); });
        on('error', function () { self._onError(video.error ? video.error.code : 0); });
        on('ended', function () {
            // A live stream should not end: the server closed the connection.
            if (self.wantPlaying) self._scheduleRetry('Yayın kesildi, yeniden bağlanılıyor.', false);
        });
        this.watchdog = setInterval(function () { self._watch(); }, 2000);
    }

    Player.prototype.play = function (channel) {
        this._clearRetry();
        this.channel = channel;
        this.attempts = 0;
        this._start();
    };

    /** Manual retry (OK on the error card) or resume after the app becomes visible again. */
    Player.prototype.retryNow = function () {
        if (!this.channel) return;
        this._clearRetry();
        this.attempts = 0;
        this._start();
    };

    /** Releases the decoder but remembers the channel (app hidden / TV standby). */
    Player.prototype.suspend = function () {
        this.token++;
        this._clearRetry();
        this.wantPlaying = false;
        this.started = false;
        this._unload();
        this.listener.onLoading(false);
    };

    Player.prototype.stop = function () {
        this.suspend();
        this.channel = null;
    };

    Player.prototype.togglePause = function () {
        if (!this.channel) return;
        if (this.paused) {
            // Live: restart at the live edge instead of resuming an old position.
            this.retryNow();
        } else {
            this.paused = true;
            this.wantPlaying = false;
            try { this.video.pause(); } catch (e) { /* ignore */ }
        }
    };

    Player.prototype.isPaused = function () { return this.paused; };

    Player.prototype.info = function () {
        return {
            width: this.video.videoWidth || 0,
            height: this.video.videoHeight || 0,
            variant: this.variant,
            url: this.srcUrl
        };
    };

    /** 0 fit, 1 fill (stretch), 2 zoom. Uses a CSS transform on the video element. */
    Player.prototype.setAspect = function (mode) {
        var v = this.video, vw = v.videoWidth, vh = v.videoHeight, sw = 1280, sh = 720, fit, sx = 1, sy = 1, t;
        if (vw && vh && mode) {
            fit = Math.min(sw / vw, sh / vh);
            sx = sw / (vw * fit);
            sy = sh / (vh * fit);
            if (mode === 2) { sx = sy = Math.max(sx, sy); }
        }
        t = (sx === 1 && sy === 1) ? '' : 'scale(' + sx.toFixed(3) + ',' + sy.toFixed(3) + ')';
        v.style.webkitTransform = t;
        v.style.transform = t;
    };

    // ---------------------------------------------------------------- internals

    Player.prototype._clearRetry = function () {
        if (this.retryTimer) { clearTimeout(this.retryTimer); this.retryTimer = null; }
    };

    Player.prototype._unload = function () {
        var v = this.video, s;
        try { v.pause(); } catch (e) { /* ignore */ }
        while ((s = v.querySelector('source')) !== null) v.removeChild(s);
        v.removeAttribute('src');
        try { v.load(); } catch (e2) { /* ignore */ }
    };

    Player.prototype._useProxy = function () {
        return NV.http.hasProxy() && (NV.settings.get('proxyAll') || !!(this.channel && this.channel.proxy));
    };

    Player.prototype._start = function () {
        var self = this, ch = this.channel, token = ++this.token, mode = NV.settings.get('quality'), url = ch.url;
        var cacheKey = url + '|' + mode, cached = this.variantCache[cacheKey];
        this.started = false;
        this.wantPlaying = true;
        this.paused = false;
        this.lastProgress = NV.now();
        this.lastTime = -1;
        this.listener.onLoading(true);
        this._unload();

        if (!NV.hls.isHlsUrl(url) || mode === 3) {
            this.variant = null;
            this._load(url, NV.hls.isHlsUrl(url) ? 'hls' : 'progressive');
            return;
        }
        if (cached) {
            this.variant = cached;
            this._load(cached.url, 'hls');
            return;
        }
        // Read the master playlist to choose a variant the TV can decode. If it cannot be read
        // (CORS, TLS) the TV's own player gets the master URL and chooses by itself.
        NV.http.get(this._useProxy() ? NV.http.viaProxy(url, 'fetch') : url, { timeout: 6000, maxLength: 300000 }, function (err, text) {
            var variants, chosen;
            if (token !== self.token) return;
            variants = err ? null : NV.hls.parseMaster(text, url);
            chosen = variants ? NV.hls.pick(variants, mode) : null;
            self.variant = chosen;
            if (chosen) self.variantCache[cacheKey] = chosen;
            self._load(chosen ? chosen.url : url, 'hls');
        });
    };

    Player.prototype._load = function (url, kind) {
        var self = this, token = this.token, v = this.video, src = url, type = null, source;
        if (this._useProxy()) src = NV.http.viaProxy(url, kind === 'hls' ? 'hls' : 'seg');
        if (kind === 'hls') type = 'application/vnd.apple.mpegurl';
        else if (/\.ts($|\?)/i.test(url)) type = 'video/mp2t';
        this.srcUrl = src;
        source = document.createElement('source');
        source.src = src;
        if (type) source.type = type;
        // When every <source> fails, the error fires on the <source> element, not on <video>.
        source.addEventListener('error', function () { if (token === self.token) self._onError(4); }, false);
        v.appendChild(source);
        try { v.load(); } catch (e) { /* ignore */ }
        try { v.play(); } catch (e2) { /* ignore */ }
    };

    Player.prototype._progress = function (fromPlayingEvent) {
        if (!this.wantPlaying || !this.channel) return;
        this.lastProgress = NV.now();
        if (fromPlayingEvent || this.lastTime > 0) {
            this.listener.onLoading(false);
            this.attempts = 0;
            if (!this.started) {
                this.started = true;
                this.listener.onStarted(this.channel);
            }
        }
    };

    Player.prototype._watch = function () {
        if (!this.wantPlaying || this.paused || !this.channel || this.retryTimer) return;
        if (NV.now() - this.lastProgress > STALL_MS) {
            this.lastProgress = NV.now();
            this._scheduleRetry(this.started ? 'Yayın durdu, yeniden bağlanılıyor.' : 'Yayın zamanında başlamadı.', false);
        }
    };

    Player.prototype._onError = function (code) {
        var self = this, token = this.token, url = this.srcUrl;
        if (!this.wantPlaying || !this.channel || this.retryTimer || code === 1) return;
        if (code === 3) {
            this._fatal('Bu TV yayının video/ses biçimini çözemiyor. webOS 1.x: H.264 en fazla 1080p30 (Level 4.1); AAC, MP3, AC3 ses.');
            return;
        }
        // MEDIA_ERR_SRC_NOT_SUPPORTED is also what webOS reports for HTTP errors: probe the URL.
        if (/^https?:/i.test(url)) {
            NV.http.probe(url, function (status) {
                if (token !== self.token) return;
                if (status >= 400 && status < 500 && status !== 408 && status !== 429) {
                    self.attempts = FAST_RETRIES; // straight to slow background retries
                    self._scheduleRetry(NV.http.describe(status), true);
                } else if (status >= 200 && status < 300 && code === 4) {
                    self._fatal('Yayın biçimi bu TV tarafından desteklenmiyor (HLS v3, MPEG-TS/MP4 içinde H.264 gerekir).');
                } else {
                    self._scheduleRetry(code === 2 ? 'Ağ hatası: yayın sunucusuna ulaşılamıyor.' : 'Yayın açılamadı.', false);
                }
            });
        } else {
            this._scheduleRetry('Yayın açılamadı.', false);
        }
    };

    Player.prototype._fatal = function (message) {
        this._clearRetry();
        this.wantPlaying = false;
        this._unload();
        this.listener.onLoading(false);
        this.listener.onProblem(this.channel, message, FAST_RETRIES, FAST_RETRIES, true, true);
    };

    Player.prototype._scheduleRetry = function (message, clientError) {
        var self = this, fatal, delay;
        this.attempts++;
        fatal = this.attempts > FAST_RETRIES;
        delay = fatal ? SLOW_RETRY_MS : Math.min(1000 * Math.pow(2, this.attempts - 1), 8000);
        this._clearRetry();
        this._unload();
        this.listener.onLoading(!fatal);
        this.listener.onProblem(this.channel, message, Math.min(this.attempts, FAST_RETRIES), FAST_RETRIES, fatal, false, clientError);
        this.retryTimer = setTimeout(function () {
            self.retryTimer = null;
            if (self.channel) self._start();
        }, delay);
    };

    NV.Player = Player;
    NV.Player.FAST_RETRIES = FAST_RETRIES;
}(NV));
