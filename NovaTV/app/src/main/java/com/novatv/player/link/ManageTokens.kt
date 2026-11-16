package com.novatv.player.link

import android.content.Context
import com.novatv.player.core.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Phones paired with this TV for remote management. A phone gets a random token once, after
 * typing the PIN shown on the TV, and sends it with every request; the list survives restarts.
 */
class ManageTokens(context: Context) {
    private val file = File(context.filesDir, "manage_devices.json")
    private val random = SecureRandom()

    class Device(val token: String, val name: String, val created: Long)

    @Synchronized
    fun all(): List<Device> {
        val text = AtomicFiles.readText(file) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(o.getString("token"), o.optString("name"), o.optLong("created"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun isValid(token: String?): Boolean = !token.isNullOrEmpty() && all().any { it.token == token }

    /** Registers a phone and returns its new token. Keeps the most recent [MAX_DEVICES]. */
    @Synchronized
    fun add(name: String): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val list = (all() + Device(token, name.take(60), System.currentTimeMillis())).takeLast(MAX_DEVICES)
        save(list)
        return token
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun save(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("token", it.token).put("name", it.name).put("created", it.created)) }
        AtomicFiles.writeText(file, arr.toString())
    }

    private companion object {
        const val MAX_DEVICES = 20
    }
}
