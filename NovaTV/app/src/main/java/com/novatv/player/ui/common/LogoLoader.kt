package com.novatv.player.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.novatv.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal channel-logo loader (no extra image library):
 * memory LRU of downsampled bitmaps + OkHttp disk cache, max 4 parallel downloads,
 * request cancellation when RecyclerView rows are recycled, and letter placeholders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object LogoLoader {
    private const val MAX_DIMENSION = 220
    private const val MAX_BYTES = 1_500_000

    private lateinit var clientProvider: () -> OkHttpClient
    private val client: OkHttpClient by lazy { clientProvider() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Dispatchers.IO.limitedParallelism(4)

    private val memory: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt().coerceAtMost(16 * 1024)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024 + 1
        }
    }
    /** URLs that failed recently (url -> time) to avoid retry storms while scrolling. */
    private val failed = LruCache<String, Long>(512)

    fun init(context: Context, provider: () -> OkHttpClient) {
        clientProvider = provider
    }

    fun load(view: ImageView, url: String?, fallbackText: String) {
        cancel(view)
        val clean = url?.trim()
        view.setTag(R.id.tag_logo_url, clean)
        if (clean.isNullOrEmpty() || !(clean.startsWith("http://") || clean.startsWith("https://"))) {
            view.setImageDrawable(InitialsDrawable(fallbackText))
            return
        }
        memory.get(clean)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(InitialsDrawable(fallbackText))
        val failedAt = failed.get(clean)
        if (failedAt != null && System.currentTimeMillis() - failedAt < 10 * 60_000L) return

        val job = scope.launch {
            val bitmap = withContext(io) { fetch(clean) }
            if (bitmap != null) {
                memory.put(clean, bitmap)
                if (view.getTag(R.id.tag_logo_url) == clean) view.setImageBitmap(bitmap)
            } else {
                failed.put(clean, System.currentTimeMillis())
            }
        }
        view.setTag(R.id.tag_logo_job, job)
    }

    /** Shows a local resource, cancelling any pending remote load for this view. */
    fun showResource(view: ImageView, resId: Int) {
        cancel(view)
        view.setTag(R.id.tag_logo_url, null)
        view.setImageResource(resId)
    }

    fun cancel(view: ImageView) {
        (view.getTag(R.id.tag_logo_job) as? Job)?.cancel()
        view.setTag(R.id.tag_logo_job, null)
    }

    fun clearMemory() {
        memory.evictAll()
        failed.evictAll()
    }

    private fun fetch(url: String): Bitmap? = try {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) return null
            val length = body.contentLength()
            if (length > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) null else decode(bytes)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIMENSION || bounds.outHeight / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val max = maxOf(decoded.width, decoded.height)
        if (max <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / max
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }
}
