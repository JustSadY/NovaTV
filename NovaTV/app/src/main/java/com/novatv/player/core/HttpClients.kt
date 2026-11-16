package com.novatv.player.core

import android.content.Context
import android.os.Build
import com.novatv.player.BuildConfig
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class HttpClients(context: Context, private val settings: Settings) {

    /** Shared base: one connection pool + dispatcher for everything. */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Playlist / EPG downloads (may be large and slow). */
    val download: OkHttpClient = base.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Live streams: short read timeout so a dead stream is detected quickly. */
    val stream: OkHttpClient = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Channel logos with a 30 MB disk cache. Logo servers often send no-cache headers, so cache anyway. */
    val images: OkHttpClient = base.newBuilder()
        .cache(Cache(File(context.cacheDir, "logos"), 30L * 1024 * 1024))
        .readTimeout(15, TimeUnit.SECONDS)
        // Some logo hosts (e.g. Wikimedia) reject OkHttp's generic default User-Agent.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", DEFAULT_USER_AGENT).build())
        }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.isSuccessful) {
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=1209600")
                    .removeHeader("Pragma")
                    .build()
            } else {
                response
            }
        }
        .build()

    fun userAgent(override: String? = null): String {
        if (!override.isNullOrBlank()) return override
        val custom = settings.userAgent
        return custom.ifBlank { DEFAULT_USER_AGENT }
    }

    fun clearImageCache() {
        runCatching { images.cache?.evictAll() }
    }

    companion object {
        val DEFAULT_USER_AGENT =
            "NovaTV/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
        const val VLC_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
