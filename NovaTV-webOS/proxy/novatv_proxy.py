#!/usr/bin/env python3
"""
NovaTV optional LAN proxy for old LG webOS TVs (webOS 1.x / 2014).

Why: a 2014 TV may fail on servers that block web apps (CORS), on modern certificate chains
(e.g. Let's Encrypt is trusted only from webOS TV 5.0), and it cannot unpack .gz playlists or
filter a 100 MB XMLTV guide. Run this on a PC, Raspberry Pi or NAS in the same network and enter
its address in NovaTV -> Ayarlar -> Proxy adresi.

Endpoints (all GET, all answers carry Access-Control-Allow-Origin: *):
  /ping                          health check
  /fetch?url=U                   body of U (gzip unpacked) - playlists, logos, HLS master lists
  /epg?url=U&ids=a,b&hours=36    XMLTV reduced to the given tvg-ids and time window
  /hls?url=U                     HLS playlist; every URI rewritten to go through this proxy
  /seg?url=U                     streamed bytes of U (HLS segments, keys, MPEG-TS streams)
  /  (anything else)             serves the NovaTV web app itself, for the TV's web browser

NovaTV Link (add from phone; the webOS TV cannot run a server itself, so the proxy relays):
  GET  /api/info                 identifies this proxy to NovaTV apps scanning the LAN
  POST /api/pair                 TV opens a pairing session -> {key} (4-digit, valid 30 min)
  GET  /send?k=KEY               phone page (QR code on the TV points here)
  POST /api/sources              phone sends {key,name,url,epgUrl,m3u}; uploaded files are kept
                                 and served at /files/<random-id>.m3u
  GET  /api/inbox?key=           TV collects what the phone sent
  POST /api/status, GET /api/status   TV reports progress, phone shows it

NovaTV remote management for LG TVs (same panel and operations as the Android TV app):
  GET  /manage?tv=ID             phone panel (manage.html)
  GET  /api/manage/tvs           LG TVs that registered here {tvs: [{id, name, online}]}
  POST /api/manage/pair          {tv, pin, name} -> {token}; PIN from the TV's "Telefondan yönet" screen
  GET  /api/manage/state         ?tv=&token= -> latest state the TV reported
  POST /api/manage/cmd           {tv, token, op, args} -> queued for the TV, answered with its result
  GET  /api/manage/export        ?tv=&token= -> the TV's lists for "Diğer NovaTV'den eşitle"
  POST /api/tv/poll, /api/tv/result   used by the TV: fetch queued commands, report results/state
  Paired phones and TV secrets are kept in <data-dir>/manage.json.

Security:
  * TLS certificates of upstream servers are ALWAYS verified (Python's default context).
  * Only http/https upstream URLs are allowed; private-network use only - do not expose it
    to the internet. Optional --token makes every proxy request require &t=<token>.

Requires Python 3.7+ and only the standard library.
  python3 novatv_proxy.py --port 8787 [--token SECRET] [--host 0.0.0.0]
"""
import argparse
import datetime
import json
import secrets
import threading
import time
import gzip
import io
import mimetypes
import os
import posixpath
import re
import socket
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.1.0"
USER_AGENT = "NovaTV-Proxy/%s (+LAN helper for webOS)" % VERSION
MAX_FETCH = 60 * 1024 * 1024
CHUNK = 64 * 1024
SSL_CONTEXT = ssl.create_default_context()  # verification stays enabled

ARGS = None

# ---------------------------------------------------------------- NovaTV Link state (in memory)
LINK_LOCK = threading.Lock()
PAIRINGS = {}      # key -> {"created": t, "inbox": [items]}
STATUSES = {}      # id -> {"state", "count", "message", "key"}
FAILURES = {"count": 0, "locked_until": 0.0}
PAIR_TTL = 30 * 60
UPLOAD_LIMIT = 8 * 1024 * 1024 + 64 * 1024

# ---------------------------------------------------------------- remote management state
MANAGE = threading.Condition()
TVS = {}           # tv id -> {"name","secret","version","tokens","pin","last_seen","state","queue","results"}
TV_ONLINE_S = 20   # a TV that polled within this many seconds is online
CMD_TIMEOUT_S = 150
TV_ID_RE = re.compile(r"^[0-9a-f]{16}$")
TV_SECRET_RE = re.compile(r"^[0-9a-f]{32}$")
PANEL_FAILURES = {"count": 0, "locked_until": 0.0}


def upload_dir():
    d = ARGS.data_dir or os.path.join(os.path.expanduser("~"), ".cache", "novatv-proxy", "uploads")
    os.makedirs(d, exist_ok=True)
    return d


def data_root():
    return ARGS.data_dir or os.path.join(os.path.expanduser("~"), ".cache", "novatv-proxy")


def manage_file():
    os.makedirs(data_root(), exist_ok=True)
    return os.path.join(data_root(), "manage.json")


def load_manage():
    try:
        with open(manage_file(), encoding="utf-8") as f:
            saved = json.load(f).get("tvs", {})
    except (OSError, ValueError):
        return
    for tv, v in saved.items():
        if TV_ID_RE.match(tv) and TV_SECRET_RE.match(v.get("secret", "")):
            TVS[tv] = new_tv(v.get("name", "LG TV"), v["secret"], v.get("tokens", []))


def save_manage():
    """Must hold MANAGE."""
    data = {"tvs": {tv: {"name": t["name"], "secret": t["secret"], "tokens": sorted(t["tokens"])} for tv, t in TVS.items()}}
    tmp = manage_file() + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, manage_file())


def new_tv(name, secret, tokens=()):
    return {"name": name, "secret": secret, "version": "", "tokens": set(tokens), "pin": "", "last_seen": 0.0,
            "state": None, "queue": [], "results": {}}


def tv_online(t):
    return time.time() - t["last_seen"] < TV_ONLINE_S


def prune_pairings():
    now = time.time()
    for k in [k for k, v in PAIRINGS.items() if now - v["created"] > PAIR_TTL]:
        del PAIRINGS[k]


def parse_xmltv_time(value):
    """'20241115060000 +0300' -> aware datetime (UTC if no offset)."""
    if not value:
        return None
    m = re.match(r"^(\d{14}|\d{12})\s*([+-]\d{4})?", value.strip())
    if not m:
        return None
    digits = m.group(1) if len(m.group(1)) == 14 else m.group(1) + "00"
    dt = datetime.datetime.strptime(digits, "%Y%m%d%H%M%S")
    off = m.group(2) or "+0000"
    sign = -1 if off[0] == "-" else 1
    tz = datetime.timezone(sign * datetime.timedelta(hours=int(off[1:3]), minutes=int(off[3:5])))
    return dt.replace(tzinfo=tz)


def open_upstream(url, timeout=25):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise ValueError("only http/https URLs are allowed")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    return urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT)


def maybe_gunzip(data):
    if data[:2] == b"\x1f\x8b":
        return gzip.decompress(data)
    return data


class Handler(BaseHTTPRequestHandler):
    server_version = "NovaTVProxy/" + VERSION
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------ helpers

    def log_message(self, fmt, *args):
        if ARGS.verbose:
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")

    def reply(self, status, body, ctype="text/plain; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_cors_headers()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def proxy_base(self):
        host = self.headers.get("Host") or "%s:%d" % (socket.gethostname(), ARGS.port)
        return "http://" + host

    def proxied(self, kind, url):
        q = "%s/%s?url=%s" % (self.proxy_base(), kind, urllib.parse.quote(url, safe=""))
        if ARGS.token:
            q += "&t=" + urllib.parse.quote(ARGS.token, safe="")
        return q

    # ------------------------------------------------------------ routing

    def do_HEAD(self):
        self.do_GET()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def reply_json(self, status, obj):
        self.reply(status, json.dumps(obj, ensure_ascii=False), "application/json; charset=utf-8")

    def read_json(self):
        # The body has already been consumed by do_POST (see there).
        return json.loads(self._body.decode("utf-8") or "{}")

    def token_ok(self, query):
        return not ARGS.token or query.get("t", [""])[0] == ARGS.token

    def key_ok(self, key):
        """Pairing-key check with a lockout against guessing."""
        now = time.time()
        with LINK_LOCK:
            prune_pairings()
            if now < FAILURES["locked_until"]:
                return "locked"
            if key in PAIRINGS:
                FAILURES["count"] = 0
                return "ok"
            FAILURES["count"] += 1
            if FAILURES["count"] >= 5:
                FAILURES["count"] = 0
                FAILURES["locked_until"] = now + 30
            return "bad"

    def do_POST(self):
        # Always consume the request body first: on a keep-alive connection unread bytes would be
        # taken as the start of the next request.
        length = int(self.headers.get("Content-Length") or 0)
        if length > UPLOAD_LIMIT:
            self.close_connection = True
            self.reply_json(413, {"ok": False, "error": "Dosya çok büyük (en fazla 8 MB)."})
            return
        self._body = self.rfile.read(length) if length else b""
        parts = urllib.parse.urlsplit(self.path)
        route = parts.path.rstrip("/")
        query = urllib.parse.parse_qs(parts.query)
        try:
            if route == "/api/pair":
                if not self.token_ok(query):
                    self.reply_json(403, {"ok": False, "error": "invalid or missing token"})
                    return
                with LINK_LOCK:
                    prune_pairings()
                    key = "%04d" % secrets.randbelow(10000)
                    while key in PAIRINGS:
                        key = "%04d" % secrets.randbelow(10000)
                    PAIRINGS[key] = {"created": time.time(), "inbox": []}
                self.reply_json(200, {"ok": True, "key": key, "ttl": PAIR_TTL})
            elif route == "/api/sources":
                self.link_receive(self.read_json())
            elif route == "/api/tv/poll":
                self.tv_poll(query, self.read_json())
            elif route == "/api/tv/result":
                self.tv_result(query, self.read_json())
            elif route == "/api/manage/pair":
                self.panel_pair(self.read_json())
            elif route == "/api/manage/cmd":
                body = self.read_json()
                t = self.panel_auth(str(body.get("tv", "")), str(body.get("token", "")))
                if t is not None:
                    self.run_on_tv(t, str(body.get("op", "")), body.get("args") or {})
            elif route == "/api/status":
                body = self.read_json()
                if not self.token_ok(query):
                    self.reply_json(403, {"ok": False, "error": "invalid or missing token"})
                    return
                with LINK_LOCK:
                    st = STATUSES.get(str(body.get("id", "")))
                    if st and st["key"] == str(body.get("key", "")):
                        st.update({"state": str(body.get("state", "")), "count": int(body.get("count") or 0),
                                   "message": str(body.get("message", ""))[:300]})
                self.reply_json(200, {"ok": True})
            else:
                self.reply_json(404, {"ok": False, "error": "not found"})
        except (ValueError, json.JSONDecodeError) as e:
            self.reply_json(400, {"ok": False, "error": str(e)})

    # ------------------------------------------------------------ remote management

    def tv_auth(self, query, body):
        """The TV identifies itself with a random id + secret; the first poll registers it."""
        if not self.token_ok(query):
            self.reply_json(403, {"ok": False, "error": "invalid or missing token"})
            return None
        tv, secret = str(body.get("tv", "")), str(body.get("secret", ""))
        if not TV_ID_RE.match(tv) or not TV_SECRET_RE.match(secret):
            self.reply_json(400, {"ok": False, "error": "invalid tv id"})
            return None
        with MANAGE:
            t = TVS.get(tv)
            if t is None:
                t = TVS[tv] = new_tv(str(body.get("name", "LG TV"))[:60], secret)
                save_manage()
            elif t["secret"] != secret:
                self.reply_json(403, {"ok": False, "error": "tv secret mismatch"})
                return None
        return tv, t

    def tv_poll(self, query, body):
        auth = self.tv_auth(query, body)
        if auth is None:
            return
        tv, t = auth
        pin = str(body.get("pin", ""))
        with MANAGE:
            name = str(body.get("name", ""))[:60] or t["name"]
            if name != t["name"]:
                t["name"] = name
                save_manage()
            t["version"] = str(body.get("version", ""))[:20]
            t["last_seen"] = time.time()
            t["pin"] = pin if re.match(r"^\d{4}$", pin) else ""
            if isinstance(body.get("state"), dict):
                t["state"] = body["state"]
            cmds, t["queue"] = t["queue"], []
            MANAGE.notify_all()
            need = t["state"] is None
        self.reply_json(200, {"ok": True, "cmds": cmds, "needState": need})

    def tv_result(self, query, body):
        auth = self.tv_auth(query, body)
        if auth is None:
            return
        tv, t = auth
        with MANAGE:
            t["last_seen"] = time.time()
            if isinstance(body.get("state"), dict):
                t["state"] = body["state"]
            result = body.get("result")
            t["results"][str(body.get("id", ""))] = result if isinstance(result, dict) else {"ok": False, "error": "geçersiz yanıt"}
            MANAGE.notify_all()
        self.reply_json(200, {"ok": True})

    def panel_auth(self, tv, token):
        with MANAGE:
            t = TVS.get(tv)
            if t is None:
                self.reply_json(404, {"ok": False, "error": "Bu proxy'de böyle bir LG TV yok."})
                return None
            if not token or token not in t["tokens"]:
                self.reply_json(401, {"ok": False, "error": "Bu telefon TV ile eşleşmemiş. PIN ile yeniden eşleşin."})
                return None
        return t

    def panel_pair(self, body):
        tv, pin = str(body.get("tv", "")), str(body.get("pin", ""))
        now = time.time()
        with MANAGE:
            if now < PANEL_FAILURES["locked_until"]:
                self.reply_json(429, {"ok": False, "error": "Çok fazla hatalı PIN. 30 saniye sonra tekrar deneyin."})
                return
            t = TVS.get(tv)
            if t is None:
                self.reply_json(404, {"ok": False, "error": "Bu proxy'de böyle bir LG TV yok."})
                return
            if not t["pin"] or not tv_online(t):
                self.reply_json(409, {"ok": False, "error": "LG TV'de NovaTV → Ayarlar → Telefondan yönet ekranını açın; PIN orada görünür."})
                return
            if pin != t["pin"]:
                PANEL_FAILURES["count"] += 1
                if PANEL_FAILURES["count"] >= 5:
                    PANEL_FAILURES["count"] = 0
                    PANEL_FAILURES["locked_until"] = now + 30
                self.reply_json(403, {"ok": False, "error": "PIN hatalı. TV ekranındaki 4 haneli kodu girin."})
                return
            PANEL_FAILURES["count"] = 0
            token = secrets.token_hex(16)
            t["tokens"].add(token)
            if len(t["tokens"]) > 20:
                t["tokens"].pop()
                t["tokens"].add(token)
            save_manage()
            name = t["name"]
        self.reply_json(200, {"ok": True, "token": token, "name": name})

    def run_on_tv(self, t, op, args):
        """Queues a command for the TV and waits until the TV reports the result."""
        if not isinstance(args, dict):
            self.reply_json(400, {"ok": False, "error": "geçersiz istek"})
            return
        if op == "source.add" and args.get("m3u"):
            m3u = str(args["m3u"])
            if "#EXT" not in m3u and "://" not in m3u:
                self.reply_json(400, {"ok": False, "error": "Dosya bir M3U listesine benzemiyor."})
                return
            item_id = secrets.token_hex(8)
            with open(os.path.join(upload_dir(), item_id + ".m3u"), "w", encoding="utf-8") as f:
                f.write(m3u)
            args = {"name": args.get("name", ""), "url": self.proxy_base() + "/files/" + item_id + ".m3u", "epgUrl": args.get("epgUrl", "")}
        cmd_id = secrets.token_hex(8)
        deadline = time.time() + CMD_TIMEOUT_S
        with MANAGE:
            if not tv_online(t):
                self.reply_json(503, {"ok": False, "error": "LG TV çevrimdışı. TV açık, NovaTV çalışıyor ve Uzaktan yönetim açık mı?"})
                return
            t["queue"].append({"id": cmd_id, "op": op, "args": args})
            while cmd_id not in t["results"] and time.time() < deadline:
                MANAGE.wait(max(0.01, min(5.0, deadline - time.time())))
            result = t["results"].pop(cmd_id, None)
            state = t["state"]
        if result is None:
            self.reply_json(504, {"ok": False, "error": "TV yanıt vermedi. NovaTV açık mı?"})
            return
        out = dict(result)
        if state is not None and op != "config.export":
            out["state"] = state
        self.reply_json(200, out)

    def link_receive(self, body):
        key = str(body.get("key", ""))
        verdict = self.key_ok(key)
        if verdict == "locked":
            self.reply_json(429, {"ok": False, "error": "Çok fazla hatalı PIN. 30 saniye sonra tekrar deneyin."})
            return
        if verdict != "ok":
            self.reply_json(403, {"ok": False, "error": "PIN hatalı. TV ekranındaki 4 haneli kodu girin."})
            return
        name = str(body.get("name", "")).strip()[:120]
        url = str(body.get("url", "")).strip()
        epg = str(body.get("epgUrl", "")).strip()
        m3u = body.get("m3u")
        if not epg.lower().startswith(("http://", "https://")):
            epg = ""
        item_id = secrets.token_hex(8)
        if m3u:
            if "#EXT" not in m3u and "://" not in m3u:
                self.reply_json(400, {"ok": False, "error": "Dosya bir M3U listesine benzemiyor."})
                return
            with open(os.path.join(upload_dir(), item_id + ".m3u"), "w", encoding="utf-8") as f:
                f.write(m3u)
            url = self.proxy_base() + "/files/" + item_id + ".m3u"
        elif not url.lower().startswith(("http://", "https://")):
            self.reply_json(400, {"ok": False, "error": "Geçerli bir http(s) adresi veya M3U dosyası gönderin."})
            return
        with LINK_LOCK:
            PAIRINGS[key]["inbox"].append({"id": item_id, "name": name, "url": url, "epgUrl": epg})
            STATUSES[item_id] = {"state": "waiting", "count": 0, "message": "", "key": key}
        self.reply_json(200, {"ok": True, "id": item_id})

    def do_GET(self):
        parts = urllib.parse.urlsplit(self.path)
        route = parts.path.rstrip("/") or "/"
        query = urllib.parse.parse_qs(parts.query)
        if route == "/api/info":
            self.reply_json(200, {"app": "NovaTV", "link": 1, "manage": 1, "version": VERSION, "platform": "proxy",
                                  "name": socket.gethostname(), "receive": True, "share": False})
            return
        if route in ("/send", "/manage"):
            page = "phone.html" if route == "/send" else "manage.html"
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), page), "rb") as f:
                self.reply(200, f.read(), "text/html; charset=utf-8")
            return
        if route == "/api/manage/tvs":
            with MANAGE:
                tvs = [{"id": tv, "name": t["name"], "online": tv_online(t)} for tv, t in TVS.items()]
            self.reply_json(200, {"ok": True, "tvs": tvs})
            return
        if route == "/api/manage/state":
            t = self.panel_auth(query.get("tv", [""])[0], query.get("token", [""])[0])
            if t is None:
                return
            deadline = time.time() + 10
            with MANAGE:
                while t["state"] is None and tv_online(t) and time.time() < deadline:
                    MANAGE.wait(max(0.01, deadline - time.time()))
                state = t["state"]
            if state is None:
                self.reply_json(503, {"ok": False, "error": "TV henüz bağlanmadı. TV'de NovaTV açık ve Uzaktan yönetim açık mı?"})
            else:
                self.reply_json(200, {"ok": True, "state": state})
            return
        if route == "/api/manage/export":
            t = self.panel_auth(query.get("tv", [""])[0], query.get("token", [""])[0])
            if t is not None:
                self.run_on_tv(t, "config.export", {})
            return
        if route == "/api/inbox":
            if not self.token_ok(query):
                self.reply_json(403, {"ok": False, "error": "invalid or missing token"})
                return
            key = query.get("key", [""])[0]
            with LINK_LOCK:
                prune_pairings()
                pairing = PAIRINGS.get(key)
                items = pairing["inbox"] if pairing else None
                if pairing:
                    pairing["inbox"] = []
            if items is None:
                self.reply_json(404, {"ok": False, "error": "pairing expired"})
            else:
                self.reply_json(200, {"ok": True, "items": items})
            return
        if route == "/api/status":
            key = query.get("key", [""])[0]
            with LINK_LOCK:
                st = STATUSES.get(query.get("id", [""])[0])
            if not st or st["key"] != key:
                self.reply_json(404, {"ok": False, "error": "Bilinmeyen istek."})
            else:
                self.reply_json(200, {k: v for k, v in st.items() if k != "key"})
            return
        if route.startswith("/files/") and re.match(r"^/files/[0-9a-f]{16}\.m3u$", route):
            path = os.path.join(upload_dir(), route[len("/files/"):])
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    self.reply(200, f.read(), "text/plain; charset=utf-8")
            else:
                self.reply(404, "not found")
            return
        if route in ("/ping", "/fetch", "/epg", "/hls", "/seg"):
            if ARGS.token and query.get("t", [""])[0] != ARGS.token:
                self.reply(403, "invalid or missing token")
                return
            url = query.get("url", [""])[0]
            try:
                if route == "/ping":
                    self.reply(200, "NovaTV proxy %s" % VERSION)
                elif route == "/fetch":
                    self.handle_fetch(url)
                elif route == "/epg":
                    self.handle_epg(url, query)
                elif route == "/hls":
                    self.handle_hls(url)
                else:
                    self.handle_seg(url)
            except urllib.error.HTTPError as e:
                self.reply(e.code, "upstream HTTP %d" % e.code)
            except (urllib.error.URLError, socket.timeout, ssl.SSLError, ConnectionError) as e:
                self.reply(502, "upstream error: %s" % e)
            except ValueError as e:
                self.reply(400, str(e))
            except BrokenPipeError:
                pass
            return
        self.handle_static(parts.path)

    # ------------------------------------------------------------ endpoints

    def handle_fetch(self, url):
        with open_upstream(url) as resp:
            data = resp.read(MAX_FETCH + 1)
            if len(data) > MAX_FETCH:
                raise ValueError("response too large")
            ctype = resp.headers.get("Content-Type") or "application/octet-stream"
        data = maybe_gunzip(data)
        if url.lower().split("?")[0].endswith((".m3u", ".m3u8", ".gz", ".xml", ".txt")):
            ctype = "text/plain; charset=utf-8"
        self.reply(200, data, ctype)

    def handle_epg(self, url, query):
        ids = set(i.strip().lower() for i in query.get("ids", [""])[0].split(",") if i.strip())
        hours = float(query.get("hours", ["36"])[0] or 36)
        now = datetime.datetime.now(datetime.timezone.utc)
        start_limit = now - datetime.timedelta(hours=2)
        end_limit = now + datetime.timedelta(hours=hours)
        out = io.StringIO()
        out.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="NovaTV proxy">\n')
        kept_channels = set()
        with open_upstream(url, timeout=60) as resp:
            raw = resp
            head = resp.peek(2) if hasattr(resp, "peek") else b""
            stream = gzip.GzipFile(fileobj=raw) if head[:2] == b"\x1f\x8b" or url.lower().split("?")[0].endswith(".gz") else raw
            for event, elem in ET.iterparse(stream, events=("end",)):
                if elem.tag == "channel":
                    cid = (elem.get("id") or "").lower()
                    if not ids or cid in ids:
                        kept_channels.add(cid)
                        out.write(ET.tostring(elem, encoding="unicode"))
                        out.write("\n")
                    elem.clear()
                elif elem.tag == "programme":
                    cid = (elem.get("channel") or "").lower()
                    if not ids or cid in ids:
                        start = parse_xmltv_time(elem.get("start"))
                        stop = parse_xmltv_time(elem.get("stop")) or start
                        if start and stop and stop >= start_limit and start <= end_limit:
                            for child in list(elem):
                                if child.tag not in ("title", "desc", "sub-title"):
                                    elem.remove(child)
                            out.write(ET.tostring(elem, encoding="unicode"))
                            out.write("\n")
                    elem.clear()
        out.write("</tv>\n")
        self.reply(200, out.getvalue(), "text/xml; charset=utf-8")

    def handle_hls(self, url):
        with open_upstream(url) as resp:
            text = maybe_gunzip(resp.read(4 * 1024 * 1024)).decode("utf-8", "replace")
            base = resp.geturl()
        lines = []
        next_is_playlist = False
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith("#EXT-X-STREAM-INF"):
                next_is_playlist = True
                lines.append(line)
            elif stripped.startswith("#"):
                lines.append(re.sub(r'URI="([^"]+)"', lambda m: 'URI="%s"' % self.rewrite(m.group(1), base, None), line))
            elif stripped:
                lines.append(self.rewrite(stripped, base, next_is_playlist))
                next_is_playlist = False
            else:
                lines.append(line)
        self.reply(200, "\n".join(lines) + "\n", "application/vnd.apple.mpegurl")

    def rewrite(self, uri, base, is_playlist):
        absolute = urllib.parse.urljoin(base, uri)
        path = urllib.parse.urlsplit(absolute).path.lower()
        if is_playlist or path.endswith((".m3u8", ".m3u")):
            return self.proxied("hls", absolute)
        return self.proxied("seg", absolute)

    def handle_seg(self, url):
        resp = open_upstream(url)
        try:
            self.send_response(200)
            self.send_header("Content-Type", resp.headers.get("Content-Type") or "application/octet-stream")
            length = resp.headers.get("Content-Length")
            if length:
                self.send_header("Content-Length", length)
            else:
                self.send_header("Connection", "close")
                self.close_connection = True
            self.send_cors_headers()
            self.end_headers()
            if self.command == "HEAD":
                return
            while True:
                chunk = resp.read(CHUNK)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the TV switched channel
        finally:
            resp.close()

    def handle_static(self, path):
        rel = posixpath.normpath(urllib.parse.unquote(path)).lstrip("/")
        if rel in ("", "."):
            rel = "index.html"
        full = os.path.realpath(os.path.join(ARGS.app_dir, rel))
        if not full.startswith(os.path.realpath(ARGS.app_dir) + os.sep) or not os.path.isfile(full):
            self.reply(404, "not found")
            return
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if ctype.startswith("text/") or full.endswith((".js", ".json")):
            ctype += "; charset=utf-8"
        with open(full, "rb") as f:
            self.reply(200, f.read(), ctype)


def main():
    global ARGS
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description="NovaTV LAN proxy for old webOS TVs")
    p.add_argument("--host", default="0.0.0.0", help="listen address (default 0.0.0.0 = whole LAN)")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--token", default="", help="optional shared secret required as &t=")
    p.add_argument("--app-dir", default=os.path.dirname(here), help="NovaTV web app folder served at /")
    p.add_argument("--data-dir", default="", help="where phone uploads and pairings are kept (default ~/.cache/novatv-proxy)")
    p.add_argument("--verbose", action="store_true")
    ARGS = p.parse_args()
    load_manage()
    mimetypes.add_type("image/svg+xml", ".svg")
    mimetypes.add_type("application/javascript", ".js")
    server = ThreadingHTTPServer((ARGS.host, ARGS.port), Handler)
    server.daemon_threads = True
    print("NovaTV proxy %s listening on http://%s:%d  (app: %s)" % (VERSION, ARGS.host, ARGS.port, ARGS.app_dir))
    print("Enter http://<this-computer-ip>:%d in NovaTV -> Ayarlar -> Proxy adresi." % ARGS.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
