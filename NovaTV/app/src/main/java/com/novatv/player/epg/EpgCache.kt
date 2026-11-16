package com.novatv.player.epg

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Compact binary cache so the guide is available instantly on the next start. */
class EpgCache(private val file: File) {

    fun write(data: EpgData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(data.updatedAt)
            out.writeInt(data.nameIndex.size)
            for ((k, v) in data.nameIndex) {
                out.writeUTF(k)
                out.writeUTF(v)
            }
            out.writeInt(data.programmes.size)
            for ((id, list) in data.programmes) {
                out.writeUTF(id)
                out.writeInt(list.size)
                for (p in list) {
                    out.writeLong(p.start)
                    out.writeLong(p.stop)
                    out.writeUTF(p.title.take(MAX_TEXT))
                    out.writeUTF((p.description ?: "").take(MAX_TEXT))
                }
            }
        }
        if (file.exists() && !file.delete()) throw IOException("EPG önbelleği güncellenemedi")
        if (!tmp.renameTo(file)) throw IOException("EPG önbelleği yazılamadı")
    }

    /** Reads the cache, dropping programmes that ended before [dropBefore]. */
    fun read(dropBefore: Long): EpgData? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val updatedAt = input.readLong()
                val nameCount = input.readInt()
                val names = HashMap<String, String>(nameCount * 2)
                repeat(nameCount) { names[input.readUTF()] = input.readUTF() }
                val chCount = input.readInt()
                val programmes = HashMap<String, Array<Programme>>(chCount * 2)
                repeat(chCount) {
                    val id = input.readUTF()
                    val n = input.readInt()
                    val list = ArrayList<Programme>(n)
                    repeat(n) {
                        val start = input.readLong()
                        val stop = input.readLong()
                        val title = input.readUTF()
                        val desc = input.readUTF()
                        if (stop >= dropBefore) list.add(Programme(start, stop, title, desc.ifEmpty { null }))
                    }
                    if (list.isNotEmpty()) programmes[id] = list.toTypedArray()
                }
                EpgData(programmes, names, updatedAt)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun delete() {
        file.delete()
    }

    private companion object {
        const val MAGIC = 0x4E545645 // "NTVE"
        const val VERSION = 1
        const val MAX_TEXT = 1000
    }
}
