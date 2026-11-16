package com.novatv.player.data

import android.content.Context
import com.novatv.player.core.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Favorites, recents, broken-channel marks and custom channel order (all keyed by Channel.key). */
class UserListsStore(context: Context) {
    private val listsFile = File(context.filesDir, "lists.json")
    private val orderFile = File(context.filesDir, "order.json")
    private val editsFile = File(context.filesDir, "edits.json")

    class Lists(val favorites: List<String>, val recents: List<String>, val broken: Set<String>)

    /** Channel edits made by the user: hidden channels and renamed channels (key -> name). */
    class Edits(val hidden: Set<String>, val renamed: Map<String, String>)

    @Synchronized
    fun loadLists(): Lists {
        val text = AtomicFiles.readText(listsFile) ?: return Lists(emptyList(), emptyList(), emptySet())
        return runCatching {
            val o = JSONObject(text)
            Lists(
                favorites = o.optJSONArray("favorites").toStringList(),
                recents = o.optJSONArray("recents").toStringList(),
                broken = o.optJSONArray("broken").toStringList().toSet(),
            )
        }.getOrDefault(Lists(emptyList(), emptyList(), emptySet()))
    }

    @Synchronized
    fun saveLists(favorites: List<String>, recents: List<String>, broken: Set<String>) {
        val o = JSONObject()
            .put("favorites", JSONArray(favorites))
            .put("recents", JSONArray(recents))
            .put("broken", JSONArray(broken.toList()))
        AtomicFiles.writeText(listsFile, o.toString())
    }

    @Synchronized
    fun loadOrder(): List<String> {
        val text = AtomicFiles.readText(orderFile) ?: return emptyList()
        return runCatching { JSONArray(text).toStringList() }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveOrder(keys: List<String>) {
        if (keys.isEmpty()) {
            orderFile.delete()
        } else {
            AtomicFiles.writeText(orderFile, JSONArray(keys).toString())
        }
    }

    @Synchronized
    fun loadEdits(): Edits {
        val text = AtomicFiles.readText(editsFile) ?: return Edits(emptySet(), emptyMap())
        return runCatching {
            val o = JSONObject(text)
            val names = HashMap<String, String>()
            o.optJSONObject("renamed")?.let { r -> r.keys().forEach { k -> r.optString(k).takeIf { it.isNotBlank() }?.let { names[k] = it } } }
            Edits(o.optJSONArray("hidden").toStringList().toSet(), names)
        }.getOrDefault(Edits(emptySet(), emptyMap()))
    }

    @Synchronized
    fun saveEdits(hidden: Set<String>, renamed: Map<String, String>) {
        if (hidden.isEmpty() && renamed.isEmpty()) {
            editsFile.delete()
            return
        }
        val r = JSONObject()
        renamed.forEach { (k, v) -> r.put(k, v) }
        AtomicFiles.writeText(editsFile, JSONObject().put("hidden", JSONArray(hidden.toList())).put("renamed", r).toString())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }
}
