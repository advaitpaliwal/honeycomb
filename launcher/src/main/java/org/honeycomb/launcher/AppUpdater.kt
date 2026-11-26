package org.honeycomb.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Metadata for a published GitHub release of Honeycomb. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val apkUrl: String,
    val sha256Url: String?,
    val notes: String = "",
)

/** Current state of the in-app GitHub Releases updater. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
    data class Verifying(val release: ReleaseInfo) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val apkPath: String) : UpdateState
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Zero-dependency in-app updater backed strictly by GitHub Releases.
 *
 * Security invariants:
 * - Only connects over HTTPS to [ALLOWED_HOSTS] (checked on every redirect hop).
 * - Verifies the SHA-256 digest from the matching `.apk.sha256` release asset when present.
 * - Android's own PackageInstaller additionally verifies that the APK's signing certificate
 *   matches the currently installed Honeycomb build before allowing an upgrade.
 */
object AppUpdater {
    const val EXTRA_START_UPDATE = "org.honeycomb.launcher.START_UPDATE"

    internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/advaitpaliwal/honeycomb/releases/latest"
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_TAG = "cached_tag"
    private const val KEY_VERSION = "cached_version"
    private const val KEY_APK_URL = "cached_apk_url"
    private const val KEY_SHA_URL = "cached_sha_url"
    private const val KEY_NOTES = "cached_notes"

    private const val CHECK_INTERVAL_MS = 2L * 60 * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    internal val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Returns the installed Honeycomb versionName (e.g. "0.6.0"). */
    fun installedVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("").ifBlank { "0.0.0" }

    /** Hydrates cached release state on startup and clears stale downloads once upgraded. */
    fun loadCached(ctx: Context) {
        val current = installedVersion(ctx)
        val cached = readCached(ctx)
        if (cached != null && isNewerVersion(current, cached.version)) {
            if (_state.value is UpdateState.Idle) {
                val existing = cachedApkFile(ctx, cached.version)
                _state.value = if (existing.isFile && existing.length() > 0L) {
                    UpdateState.ReadyToInstall(cached, existing.absolutePath)
                } else {
                    UpdateState.Available(cached)
                }
            }
        } else {
            if (cached != null) clearCached(ctx)
            cleanupOldApks(ctx, keepName = null)
            if (_state.value is UpdateState.Available || _state.value is UpdateState.ReadyToInstall) {
                _state.value = UpdateState.UpToDate(current)
            }
        }
    }

    /** Silently checks for updates at most once every 12 hours when Honeycomb resumes. */
    fun checkIfDue(ctx: Context) {
        val app = ctx.applicationContext
        loadCached(app)
        val last = prefs(app).getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                fetchLatest(app, silent = true)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Explicitly checks GitHub Releases now and updates [state]. */
    suspend fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) return
        try {
            _state.value = UpdateState.Checking
            withContext(Dispatchers.IO) { fetchLatest(app, silent = false) }
        } finally {
            busy.set(false)
        }
    }

    /** Downloads [release], verifies its SHA-256 checksum (when published), and opens Android's installer. */
    suspend fun downloadAndInstall(ctx: Context, release: ReleaseInfo): Boolean {
        val app = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) return false
        return try {
            withContext(Dispatchers.IO) {
                if (!isAllowedUrl(release.apkUrl)) {
                    _state.value = UpdateState.Error("Blocked untrusted download host", release)
                    return@withContext false
                }
                _state.value = UpdateState.Downloading(release, 0)
                val expectedSha = release.sha256Url?.let { shaUrl ->
                    if (!isAllowedUrl(shaUrl)) {
                        _state.value = UpdateState.Error("Blocked untrusted checksum host", release)
                        return@withContext false
                    }
                    runCatching { parseSha256Text(fetchText(shaUrl, installedVersion(app))) }.getOrNull()
                }
                val dir = updatesDir(app).apply { mkdirs() }
                val finalFile = cachedApkFile(app, release.version)
                val tmpFile = File(dir, "${finalFile.name}.tmp")
                tmpFile.delete()
                val actualSha = try {
                    downloadFileWithSha256(
                        url = release.apkUrl,
                        userAgent = userAgent(installedVersion(app)),
                        dest = tmpFile,
                    ) { pct ->
                        _state.value = UpdateState.Downloading(release, pct)
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    _state.value = UpdateState.Error("Download failed · Tap to retry", release)
                    return@withContext false
                }
                _state.value = UpdateState.Verifying(release)
                if (expectedSha != null && !expectedSha.equals(actualSha, ignoreCase = true)) {
                    tmpFile.delete()
                    _state.value = UpdateState.Error("Verification failed · Tap to retry", release)
                    return@withContext false
                }
                if (finalFile.exists()) finalFile.delete()
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                cleanupOldApks(app, keepName = finalFile.name)
                _state.value = UpdateState.ReadyToInstall(release, finalFile.absolutePath)
                true
            }
        } finally {
            busy.set(false)
        }.also { ready ->
            if (ready) {
                val cur = _state.value
                if (cur is UpdateState.ReadyToInstall) {
                    launchInstaller(ctx, cur.apkPath)
                }
            }
        }
    }

    /**
     * Launches Android's package installer for [apkPath].
     * Returns `true` if the install sheet was launched, or `false` if we redirected the user to
     * "Allow from this source" first.
     */
    fun launchInstaller(ctx: Context, apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.isFile) {
            val rel = (_state.value as? UpdateState.ReadyToInstall)?.release
            _state.value = if (rel != null) UpdateState.Available(rel) else UpdateState.Idle
            return false
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(allow) }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(install) }.isSuccess
    }

    private fun fetchLatest(ctx: Context, silent: Boolean) {
        val current = installedVersion(ctx)
        val body = try {
            fetchText(LATEST_RELEASE_URL, current)
        } catch (_: Exception) {
            if (!silent) _state.value = UpdateState.Error("Couldn't reach GitHub · Tap to retry")
            return
        }
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val latest = parseReleaseJson(body)
        if (latest == null) {
            if (!silent) _state.value = UpdateState.Error("No release APK found")
            return
        }
        if (isNewerVersion(current, latest.version)) {
            saveCached(ctx, latest)
            val existing = cachedApkFile(ctx, latest.version)
            _state.value = if (existing.isFile && existing.length() > 0L) {
                UpdateState.ReadyToInstall(latest, existing.absolutePath)
            } else {
                UpdateState.Available(latest)
            }
        } else {
            clearCached(ctx)
            cleanupOldApks(ctx, keepName = null)
            _state.value = UpdateState.UpToDate(current)
        }
    }

    private fun userAgent(version: String) = "Honeycomb/$version (Android)"

    private fun openValidated(url: String, userAgent: String, accept: String? = null): HttpURLConnection {
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowedUrl(currentUrl)) throw IOException("Blocked host: $currentUrl")
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                if (accept != null) setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            if (code in 301..308 && code != 304) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("Redirect without location")
                currentUrl = URI(currentUrl).resolve(loc).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
        }
        throw IOException("Too many redirects")
    }

    private fun fetchText(url: String, version: String): String {
        val conn = openValidated(url, userAgent(version), accept = "application/vnd.github+json")
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFileWithSha256(
        url: String,
        userAgent: String,
        dest: File,
        onProgress: (Int) -> Unit,
    ): String {
        val conn = openValidated(url, userAgent)
        return try {
            val total = conn.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("APK exceeds size limit")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        downloaded += n
                        if (downloaded > MAX_APK_BYTES) throw IOException("APK exceeds size limit")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    out.flush()
                }
            }
            if (downloaded <= 0L) throw IOException("Empty download")
            digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates")
    private fun cachedApkFile(ctx: Context, version: String) =
        File(updatesDir(ctx), "honeycomb-${version.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk")

    private fun cleanupOldApks(ctx: Context, keepName: String?) {
        val dir = updatesDir(ctx)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (keepName == null || f.name != keepName) runCatching { f.delete() }
        }
    }

    private fun saveCached(ctx: Context, info: ReleaseInfo) {
        prefs(ctx).edit()
            .putString(KEY_TAG, info.tag)
            .putString(KEY_VERSION, info.version)
            .putString(KEY_APK_URL, info.apkUrl)
            .putString(KEY_SHA_URL, info.sha256Url)
            .putString(KEY_NOTES, info.notes)
            .apply()
    }

    private fun readCached(ctx: Context): ReleaseInfo? {
        val p = prefs(ctx)
        val tag = p.getString(KEY_TAG, null) ?: return null
        val version = p.getString(KEY_VERSION, null) ?: return null
        val apkUrl = p.getString(KEY_APK_URL, null) ?: return null
        if (!isAllowedUrl(apkUrl)) return null
        val shaUrl = p.getString(KEY_SHA_URL, null)?.takeIf { isAllowedUrl(it) }
        return ReleaseInfo(tag, version, apkUrl, shaUrl, p.getString(KEY_NOTES, "").orEmpty())
    }

    private fun clearCached(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_TAG)
            .remove(KEY_VERSION)
            .remove(KEY_APK_URL)
            .remove(KEY_SHA_URL)
            .remove(KEY_NOTES)
            .apply()
    }

    // ---- pure testable helpers ----

    /** Parses a semantic version like "v0.6.0", "0.6.0-beta1", or "0.6" into (major, minor, patch). */
    internal fun parseSemver(raw: String): Triple<Int, Int, Int>? {
        val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        return Triple(nums[0], nums[1], nums.getOrElse(2) { 0 })
    }

    /** True when [candidate] is strictly newer than [current]. */
    internal fun isNewerVersion(current: String, candidate: String): Boolean {
        val c = parseSemver(current) ?: return false
        val n = parseSemver(candidate) ?: return false
        if (n.first != c.first) return n.first > c.first
        if (n.second != c.second) return n.second > c.second
        return n.third > c.third
    }

    /** True only for HTTPS URLs whose host is on [ALLOWED_HOSTS]. */
    internal fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return scheme == "https" && host in ALLOWED_HOSTS
    }

    /** Extracts a 64-character lowercase SHA-256 hex digest from a `sha256sum` file. */
    internal fun parseSha256Text(raw: String): String? {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        return token.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
    }

    /** Parses GitHub's `/releases/latest` JSON payload into a [ReleaseInfo], or null if invalid. */
    @Suppress("UNCHECKED_CAST")
    internal fun parseReleaseJson(json: String): ReleaseInfo? = runCatching {
        val root = JsonReader(json).readValue() as? Map<String, Any?> ?: return@runCatching null
        if (root["draft"] == true || root["prerelease"] == true) return@runCatching null
        val tag = (root["tag_name"] as? String).orEmpty().trim()
        val version = tag.removePrefix("v").removePrefix("V")
        if (parseSemver(version) == null) return@runCatching null
        val assets = root["assets"] as? List<*> ?: return@runCatching null
        var apkName: String? = null
        var apkUrl: String? = null
        val shaByName = mutableMapOf<String, String>()
        for (rawAsset in assets) {
            val a = rawAsset as? Map<String, Any?> ?: continue
            val name = (a["name"] as? String).orEmpty().trim()
            val dl = (a["browser_download_url"] as? String).orEmpty().trim()
            if (!isAllowedUrl(dl)) continue
            if (name.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
                apkName = name
                apkUrl = dl
            } else if (name.endsWith(".apk.sha256", ignoreCase = true)) {
                shaByName[name.lowercase()] = dl
            }
        }
        val finalApkUrl = apkUrl ?: return@runCatching null
        val shaUrl = apkName?.let { shaByName["${it.lowercase()}.sha256"] }
        val notes = (root["body"] as? String).orEmpty().trim()
        ReleaseInfo(
            tag = if (tag.startsWith("v", ignoreCase = true)) tag else "v$version",
            version = version,
            apkUrl = finalApkUrl,
            sha256Url = shaUrl,
            notes = notes,
        )
    }.getOrNull()

    /** Minimal RFC 8259 JSON parser so [parseReleaseJson] runs cleanly on both Android and JVM unit tests. */
    private class JsonReader(private val s: String) {
        private var i = 0

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("EOF")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val k = readString()
                skipWs()
                if (peek() != ':') throw IllegalArgumentException("Expected :")
                i++
                map[k] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("Expected , or }")
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("Expected , or ]")
                }
            }
        }

        private fun readString(): String {
            if (peek() != '"') throw IllegalArgumentException("Expected \"")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("Bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = i
            while (i < s.length && s[i] in "-0123456789.eE+") i++
            val token = s.substring(start, i)
            return token.toLongOrNull() ?: token.toDouble()
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) throw IllegalArgumentException("Expected $lit")
            i += lit.length
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        private fun skipWs() { while (i < s.length && s[i] in " \t\r\n") i++ }
    }
}
