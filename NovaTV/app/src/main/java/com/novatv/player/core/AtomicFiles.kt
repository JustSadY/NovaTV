package com.novatv.player.core

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Crash-safe small file persistence (write to temp + rename). */
object AtomicFiles {
    fun readText(file: File): String? {
        val af = AtomicFile(file)
        return try {
            String(af.readFully(), Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val af = AtomicFile(file)
        val out = af.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: IOException) {
            af.failWrite(out)
            throw e
        }
    }
}
