package com.novatv.player.link

import android.content.Context
import com.novatv.player.BuildConfig
import com.novatv.player.data.PlaylistSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * "NovaTV Link" — a tiny HTTP server for the local network (see [LinkHost] for when it runs).
 *
 * The phone panel (assets/link/manage.html) is served at "/". A phone pairs once with the
 * 4-digit PIN shown on the TV's "Telefondan yönet" screen and receives a token; all management
 * calls need that token. After 5 wrong PINs, pairing is refused for 30 s.
 *
 * Protocol (JSON, Access-Control-Allow-Origin: * so another NovaTV panel can read it for sync):
 *   GET  /api/info                               -> {app, link, manage, version, platform, name, share}
 *   POST /api/manage/pair   {pin, name}          -> {ok, token}       (only while the PIN screen is open)
 *   GET  /api/manage/state?token=                -> {ok, state}
 *   POST /api/manage/cmd    {token, op, args}    -> {ok, message | error, state}
 *   GET  /api/manage/export?token=               -> {ok, config}      (for "Diğer NovaTV'den eşitle")
 *   GET  /api/sources?key=PIN                    -> {sources: [{name, url, epgUrl}]}  (LG app "PIN ile kopyala")
 */
class LinkServer(
    private val context: Context,
    private val deviceName: () -> String,
    private val manage: ManageApi,
    private val tokens: ManageTokens,
    private val pinProvider: () -> String?,
    private val exportSources: () -> List<PlaylistSource>,
) {
    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    @Volatile private var failures = 0
    @Volatile private var lockedUntil = 0L

    /** Binds to 8686 (or the next free port up to 8695). Returns false if no port is available. */
    fun start(scope: CoroutineScope): Boolean {
        val socket = (PREFERRED_PORT..PREFERRED_PORT + 9).firstNotNullOfOrNull { p ->
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (_: IOException) {
                null
            }
        } ?: return false
        serverSocket = socket
        port = socket.localPort
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                launch(Dispatchers.IO) { runCatching { handle(client) } }
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(java.util.Locale.ROOT)
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase(java.util.Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) {
                respondJson(s, 413, error("Dosya çok büyük (en fazla 8 MB)."))
                return
            }
            val body = if (length > 0) readExactly(input, length) else ByteArray(0)
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            when {
                method == "OPTIONS" -> respond(s, 204, "text/plain", ByteArray(0))
                method == "GET" && path == "/api/info" -> respondJson(s, 200, info())
                method == "GET" && (path == "/" || path == "/index.html" || path == "/manage" || path == "/send") -> {
                    val html = context.assets.open("link/manage.html").use { it.readBytes() }
                    respond(s, 200, "text/html; charset=utf-8", html)
                }
                method == "POST" && path == "/api/manage/pair" -> pair(s, body)
                method == "GET" && path == "/api/manage/state" -> {
                    if (!tokenOk(s, query["token"])) return
                    respondJson(s, 200, JSONObject().put("ok", true).put("state", manage.state()))
                }
                method == "GET" && path == "/api/manage/export" -> {
                    if (!tokenOk(s, query["token"])) return
                    respondJson(s, 200, JSONObject().put("ok", true).put("config", manage.exportConfig()))
                }
                method == "POST" && path == "/api/manage/cmd" -> {
                    val json = parseJson(body) ?: return respondJson(s, 400, error("Geçersiz istek."))
                    if (!tokenOk(s, json.optString("token"))) return
                    val result = manage.exec(json.optString("op"), json.optJSONObject("args") ?: JSONObject())
                    respondJson(s, 200, result.put("state", manage.state()))
                }
                method == "GET" && path == "/api/sources" -> {
                    if (!pinOk(s, query["key"])) return
                    val arr = JSONArray()
                    for (src in exportSources()) {
                        if (!src.isUrl || src.isBuiltIn) continue
                        arr.put(JSONObject().put("name", src.name).put("url", src.location).put("epgUrl", src.epgUrl))
                    }
                    respondJson(s, 200, JSONObject().put("sources", arr))
                }
                else -> respondJson(s, 404, error("Bulunamadı."))
            }
        }
    }

    private fun pair(s: Socket, body: ByteArray) {
        val json = parseJson(body) ?: return respondJson(s, 400, error("Geçersiz istek."))
        if (pinProvider() == null) {
            respondJson(s, 409, error("TV'de Ayarlar → Telefondan yönet ekranını açın; PIN orada görünür."))
            return
        }
        if (!pinOk(s, json.optString("pin"))) return
        val token = tokens.add(json.optString("name").ifBlank { "Telefon" })
        respondJson(s, 200, JSONObject().put("ok", true).put("token", token).put("name", deviceName()))
    }

    private fun tokenOk(s: Socket, token: String?): Boolean {
        if (tokens.isValid(token)) return true
        respondJson(s, 401, error("Bu telefon TV ile eşleşmemiş veya eşleşme sıfırlanmış. PIN ile yeniden eşleşin."))
        return false
    }

    /** PIN check with a simple lockout against guessing. */
    private fun pinOk(s: Socket, key: String?): Boolean {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) {
            respondJson(s, 429, error("Çok fazla hatalı PIN. 30 saniye sonra tekrar deneyin."))
            return false
        }
        val pin = pinProvider()
        if (pin != null && key == pin) {
            failures = 0
            return true
        }
        failures++
        if (failures >= MAX_FAILURES) {
            failures = 0
            lockedUntil = now + LOCK_MS
        }
        respondJson(s, 403, error("PIN hatalı. TV ekranındaki 4 haneli kodu girin."))
        return false
    }

    private fun info() = JSONObject()
        .put("app", "NovaTV")
        .put("link", 1)
        .put("manage", 1)
        .put("version", BuildConfig.VERSION_NAME)
        .put("platform", "android-tv")
        .put("name", deviceName())
        .put("receive", true)
        .put("share", true)

    private fun parseJson(body: ByteArray): JSONObject? = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    private fun respondJson(s: Socket, status: Int, json: JSONObject) =
        respond(s, status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private fun respond(s: Socket, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            409 -> "Conflict"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE) return null
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var off = 0
        while (off < length) {
            val r = input.read(out, off, length - off)
            if (r < 0) break
            off += r
        }
        return if (off == length) out else out.copyOf(off)
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in q.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            out[URLDecoder.decode(part.substring(0, eq), "UTF-8")] = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
        }
        return out
    }

    companion object {
        const val PREFERRED_PORT = 8686
        const val SERVICE_TYPE = "_novatv._tcp"
        private const val MAX_BODY = 8 * 1024 * 1024 + 64 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_FAILURES = 5
        private const val LOCK_MS = 30_000L
    }
}
