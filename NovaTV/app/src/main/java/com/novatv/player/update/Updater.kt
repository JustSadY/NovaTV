package com.novatv.player.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.novatv.player.BuildConfig
import com.novatv.player.core.HttpClients
import com.novatv.player.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Pure helpers (unit-tested): version comparison and the repository setting. */
object Versions {
    private val REPO = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")

    /** Numeric parts of "v1.2.3", "1.2", "NovaTV 1.0.4-beta" -> [1,2,3] … */
    fun parts(v: String): List<Int> =
        Regex("\\d+(?:\\.\\d+)*").find(v)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()

    /** Positive when [a] is newer than [b]. */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** "v1.1.0" -> "1.1.0" for display. */
    fun display(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    /** GitHub release notes are Markdown; the TV dialog shows them as plain text. */
    fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
        line.trimEnd()
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace("**", "").replace("__", "").replace("`", "")
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    /** Accepts "owner/name", "github.com/owner/name" or a GitHub URL; returns "owner/name" or null. */
    fun parseRepo(input: String): String? {
        val s = input.trim().removeSuffix("/").removeSuffix(".git")
        val path = s.substringAfter("github.com/", s)
        val repo = path.split('/').filter { it.isNotEmpty() }.take(2).joinToString("/")
        return repo.takeIf { REPO.matches(it) }
    }
}

/**
 * App updates from GitHub Releases. The repository is set once in Settings. NovaTV reads the
 * latest release, compares its version with its own, downloads the APK asset, checks that it is
 * NovaTV, newer and signed with the same key, and hands it to Android's package installer.
 * Android always asks the user to confirm; NovaTV never installs silently.
 */
class Updater(private val context: Context, private val http: HttpClients, private val settings: Settings) {

    class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long)

    sealed class Check {
        class Available(val release: Release) : Check()
        class UpToDate(val latest: String) : Check()
        class Failed(val message: String) : Check()
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): Check = withContext(Dispatchers.IO) {
        val repo = Versions.parseRepo(settings.updateRepo) ?: return@withContext Check.Failed("Önce GitHub deposunu girin (kullanıcı/depo).")
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NovaTV/${BuildConfig.VERSION_NAME} (Android TV)")
            .build()
        val result = runCatching {
            http.download.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> parseRelease(JSONObject(resp.body?.string().orEmpty()))
                    404 -> throw IOException("Depoda yayınlanmış bir sürüm yok ya da depo herkese açık değil ($repo).")
                    403, 429 -> throw IOException("GitHub şu an istek kabul etmiyor (saatlik sınır). Daha sonra tekrar deneyin.")
                    else -> throw IOException("GitHub yanıtı: HTTP ${resp.code}")
                }
            }
        }
        settings.updateLastCheck = System.currentTimeMillis()
        result.fold(
            onSuccess = { r ->
                when {
                    r == null -> Check.Failed("Son sürümde APK dosyası yok.")
                    Versions.compare(r.version, currentVersion) > 0 -> Check.Available(r)
                    else -> Check.UpToDate(r.version)
                }
            },
            onFailure = { Check.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    private fun parseRelease(o: JSONObject): Release? {
        val version = o.optString("tag_name").ifBlank { o.optString("name") }
        val assets = o.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk", true) }
        val apk = apks.firstOrNull { it.optString("name").contains("release", true) } ?: apks.firstOrNull() ?: return null
        return Release(
            version = version,
            notes = Versions.plainNotes(o.optString("body")).take(1500),
            apkUrl = apk.optString("browser_download_url"),
            apkName = apk.optString("name"),
            size = apk.optLong("size"),
        )
    }

    /** Downloads the APK; [onProgress] gets 0–100. */
    suspend fun download(r: Release, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!r.apkUrl.startsWith("https://")) throw IOException("Geçersiz indirme adresi.")
            val dir = updateDir() ?: throw IOException("TV'de indirme için yer bulunamadı.")
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "NovaTV-${Versions.parts(r.version).joinToString(".")}.apk")
            val request = Request.Builder().url(r.apkUrl).header("User-Agent", "NovaTV/${BuildConfig.VERSION_NAME}").build()
            http.download.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("İndirme başarısız: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Boş yanıt")
                val total = body.contentLength().takeIf { it > 0 } ?: r.size
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > MAX_APK) throw IOException("Dosya beklenenden büyük.")
                            out.write(buf, 0, n)
                            if (total > 0) {
                                val p = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (p != last) {
                                    last = p
                                    withContext(Dispatchers.Main) { onProgress(p) }
                                }
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT < 24) file.setReadable(true, false)
            file
        }
    }

    /** Null when the file is a newer NovaTV signed like this app; otherwise the reason. */
    fun verify(file: File): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return "İndirilen dosya geçerli bir Android uygulaması değil."
        if (archive.packageName != context.packageName) return "İndirilen paket NovaTV değil (${archive.packageName})."
        if (PackageInfoCompat.getLongVersionCode(archive) <= BuildConfig.VERSION_CODE.toLong()) {
            return "İndirilen sürüm (${archive.versionName}) bu sürümden yeni değil."
        }
        val mine = signatureDigests(installedInfo(pm))
        val theirs = signatureDigests(archive)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) {
            return "Yeni sürüm farklı bir anahtarla imzalanmış; Android bunu bu uygulamanın üzerine kurmaz. " +
                "Önce NovaTV'yi kaldırıp yeni APK'yı elle kurmanız gerekir (ayarlar ve listeler silinir)."
        }
        return null
    }

    /** True when Android 8+ requires the user to allow installs from NovaTV first. */
    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity): Boolean = try {
        activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Opens Android's installer for [file]; returns an error message or null. */
    fun install(activity: Activity, file: File): String? {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${context.packageName}.updates", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "Bu TV'de paket yükleyici bulunamadı; APK'yı bilgisayardan adb ile kurun."
        }
    }

    /** Android 7+ installs from a FileProvider (internal cache); older versions need a readable external file. */
    private fun updateDir(): File? {
        val base = if (Build.VERSION.SDK_INT >= 24) context.cacheDir else context.externalCacheDir
        return base?.let { File(it, "updates").apply { mkdirs() } }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signatureDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private companion object {
        const val MAX_APK = 150L * 1024 * 1024
    }
}
