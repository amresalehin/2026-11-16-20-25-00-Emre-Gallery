package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

enum class FilenameTemplate(val label: String, val pattern: String) {
    TITLE("Title only", "{title}.{ext}"),
    UPLOADER_TITLE("Uploader - Title", "{uploader} - {title}.{ext}"),
    DATE_TITLE("Date - Title", "{date}_{title}.{ext}"),
    ID_TITLE("Title [ID]", "{title} [{id}].{ext}"),
    CUSTOM("Custom pattern", "{custom}")
}

data class DownloaderSettings(
    val videoMaxHeight: Int = 0, // 0 = Best available, else 2160, 1080, 720, 480, 360
    val preferredContainer: String = "AUTO", // AUTO, MP4, WEBM, MKV
    val audioOnlyDefault: Boolean = false,
    val preferredAudioFormat: String = "MP3", // MP3, M4A, OPUS, FLAC
    val preferredAudioBitrateKbps: Int = 192,
    val wifiOnly: Boolean = false,
    val maxConcurrentDownloads: Int = 3,
    val speedLimitKbps: Int = 0, // 0 = Unlimited
    val proxyUrl: String = "",
    val customUserAgent: String = "",
    val downloadSubtitles: Boolean = false,
    val embedSubtitles: Boolean = true,
    val subtitleLangs: String = "en",
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val sponsorBlockEnabled: Boolean = false,
    val sponsorBlockCategories: Set<String> = setOf("sponsor", "selfpromo"),
    val useDownloadArchive: Boolean = true,
    val safTreeUri: String = "",
    val organizeBySite: Boolean = false,
    val filenameTemplate: FilenameTemplate = FilenameTemplate.TITLE,
    val customFilenamePattern: String = "{title}.{ext}",
    val browserSearchEngine: BrowserSearchEngine = BrowserSearchEngine.DUCKDUCKGO,
    val playlistLimit: Int = 50,
    val defaultAlbumId: String = "",
    val defaultAddToVault: Boolean = false,
    val cookiesTxtContent: String = ""
)

data class DownloadOptions(
    val formatId: String? = null,
    val maxHeight: Int? = null,
    val preferredContainer: String? = null,
    val audioOnly: Boolean? = null,
    val audioFormat: String? = null,
    val audioBitrateKbps: Int? = null,
    val writeSubtitles: Boolean? = null,
    val embedSubtitles: Boolean? = null,
    val subtitleLangs: String? = null,
    val embedMetadata: Boolean? = null,
    val embedThumbnail: Boolean? = null,
    val sponsorBlock: Boolean? = null,
    val targetAlbumId: String? = null,
    val addToVault: Boolean? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        formatId?.let { put("formatId", it) }
        maxHeight?.let { put("maxHeight", it) }
        preferredContainer?.let { put("preferredContainer", it) }
        audioOnly?.let { put("audioOnly", it) }
        audioFormat?.let { put("audioFormat", it) }
        audioBitrateKbps?.let { put("audioBitrateKbps", it) }
        writeSubtitles?.let { put("writeSubtitles", it) }
        embedSubtitles?.let { put("embedSubtitles", it) }
        subtitleLangs?.let { put("subtitleLangs", it) }
        embedMetadata?.let { put("embedMetadata", it) }
        embedThumbnail?.let { put("embedThumbnail", it) }
        sponsorBlock?.let { put("sponsorBlock", it) }
        targetAlbumId?.let { put("targetAlbumId", it) }
        addToVault?.let { put("addToVault", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject?): DownloadOptions? {
            if (obj == null) return null
            return DownloadOptions(
                formatId = obj.optString("formatId").takeIf { it.isNotBlank() },
                maxHeight = if (obj.has("maxHeight")) obj.optInt("maxHeight") else null,
                preferredContainer = obj.optString("preferredContainer").takeIf { it.isNotBlank() },
                audioOnly = if (obj.has("audioOnly")) obj.optBoolean("audioOnly") else null,
                audioFormat = obj.optString("audioFormat").takeIf { it.isNotBlank() },
                audioBitrateKbps = if (obj.has("audioBitrateKbps")) obj.optInt("audioBitrateKbps") else null,
                writeSubtitles = if (obj.has("writeSubtitles")) obj.optBoolean("writeSubtitles") else null,
                embedSubtitles = if (obj.has("embedSubtitles")) obj.optBoolean("embedSubtitles") else null,
                subtitleLangs = obj.optString("subtitleLangs").takeIf { it.isNotBlank() },
                embedMetadata = if (obj.has("embedMetadata")) obj.optBoolean("embedMetadata") else null,
                embedThumbnail = if (obj.has("embedThumbnail")) obj.optBoolean("embedThumbnail") else null,
                sponsorBlock = if (obj.has("sponsorBlock")) obj.optBoolean("sponsorBlock") else null,
                targetAlbumId = obj.optString("targetAlbumId").takeIf { it.isNotBlank() },
                addToVault = if (obj.has("addToVault")) obj.optBoolean("addToVault") else null
            )
        }
    }
}

object FilenameTemplateHelper {
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+")

    fun sanitizeComponent(raw: String, fallback: String = "media"): String {
        val cleaned = raw.trim()
            .replace(ILLEGAL_CHARS, "_")
            .replace("..", "_")
            .trim('.', ' ', '_')
        return cleaned.ifBlank { fallback }.take(120)
    }

    fun formatFilename(
        template: FilenameTemplate,
        customPattern: String,
        title: String,
        uploader: String?,
        domain: String?,
        dateMillis: Long,
        id: String?,
        ext: String
    ): String {
        val cleanExt = ext.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").ifBlank { "bin" }
        val cleanTitle = sanitizeComponent(title.substringBeforeLast('.', title), "media")
        val cleanUploader = sanitizeComponent(uploader ?: domain ?: "unknown", "unknown")
        val cleanDomain = sanitizeComponent(domain ?: "web", "web")
        val cleanId = sanitizeComponent(id?.takeLast(12) ?: "item", "item")
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(if (dateMillis > 0) dateMillis else System.currentTimeMillis()))

        val activePattern = if (template == FilenameTemplate.CUSTOM && customPattern.isNotBlank()) {
            customPattern
        } else {
            template.pattern
        }

        var rendered = activePattern
            .replace("{title}", cleanTitle)
            .replace("{uploader}", cleanUploader)
            .replace("{domain}", cleanDomain)
            .replace("{date}", dateStr)
            .replace("{id}", cleanId)
            .replace("{ext}", cleanExt)

        // Strip any path separators inside template filename itself
        rendered = rendered.replace("/", "_").replace("\\", "_")
        val safe = sanitizeComponent(rendered, "${cleanTitle}.$cleanExt")
        return if (safe.endsWith(".$cleanExt", ignoreCase = true)) {
            safe
        } else {
            "${safe.substringBeforeLast('.')}.$cleanExt"
        }
    }
}

object CookiesTxtManager {
    private const val COOKIES_FILE_NAME = "downloader_cookies.txt"

    fun parseNetscapeCookies(raw: String): List<Cookie> {
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<Cookie>()
        val lines = raw.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") && !line.startsWith("#HttpOnly_", ignoreCase = true)) {
                continue
            }
            val cleanLine = if (line.startsWith("#HttpOnly_", ignoreCase = true)) {
                line.substringAfter("#HttpOnly_", "")
            } else {
                line
            }
            val cols = cleanLine.split('\t')
            if (cols.size >= 7) {
                val rawDomain = cols[0].trim().removePrefix(".")
                val path = cols[2].trim().ifBlank { "/" }
                val secure = cols[3].trim().equals("TRUE", ignoreCase = true)
                val expiresSec = cols[4].trim().toLongOrNull() ?: 0L
                val name = cols[5].trim()
                val value = cols[6]
                if (rawDomain.isBlank() || name.isBlank()) continue
                val builder = Cookie.Builder()
                    .domain(rawDomain)
                    .path(path)
                    .name(name)
                    .value(value)
                if (expiresSec > 0L) {
                    builder.expiresAt(expiresSec * 1000L)
                }
                if (secure) builder.secure()
                runCatching { builder.build() }.getOrNull()?.let { parsed.add(it) }
            } else if (cols.size == 1 && line.contains("=")) {
                // Fallback simple "domain|name=value" or "name=value" ignored without domain
            }
        }
        return parsed
    }

    fun applyCookiesToJar(raw: String): Int {
        val cookies = parseNetscapeCookies(raw)
        if (cookies.isEmpty()) return 0
        var count = 0
        for (cookie in cookies) {
            val scheme = if (cookie.secure) "https" else "http"
            val httpUrl = "$scheme://${cookie.domain}${cookie.path}".toHttpUrlOrNull() ?: continue
            OkHttpDownloader.sharedCookieJar.saveFromResponse(httpUrl, listOf(cookie))
            count++
        }
        return count
    }

    fun writeCookiesFile(context: Context, rawNetscapeOrHeader: String, targetUrl: String? = null): File? {
        if (rawNetscapeOrHeader.isBlank()) return null
        val file = File(context.filesDir, COOKIES_FILE_NAME)
        val content = if (rawNetscapeOrHeader.contains('\t')) {
            if (rawNetscapeOrHeader.startsWith("# Netscape HTTP Cookie File")) {
                rawNetscapeOrHeader
            } else {
                "# Netscape HTTP Cookie File\n$rawNetscapeOrHeader"
            }
        } else {
            val host = targetUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: ""
            if (host.isBlank()) return null
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            val pairs = rawNetscapeOrHeader.split(';')
            val expiry = (System.currentTimeMillis() / 1000L) + 86400L * 30L
            for (pair in pairs) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty()) {
                        sb.append(host).append("\tTRUE\t/\tTRUE\t").append(expiry).append('\t')
                            .append(name).append('\t').append(value).append('\n')
                    }
                }
            }
            sb.toString()
        }
        return runCatching {
            file.writeText(content, Charsets.UTF_8)
            file
        }.getOrNull()
    }

    fun clearCookiesFile(context: Context) {
        runCatching { File(context.filesDir, COOKIES_FILE_NAME).delete() }
    }
}

object DownloadArchiveStore {
    private const val ARCHIVE_FILE = "download_archive.json"
    private val lock = Any()

    data class ArchiveEntry(
        val key: String,
        val url: String,
        val title: String,
        val downloadedAtMillis: Long
    )

    private fun archiveFile(context: Context): File = File(context.filesDir, ARCHIVE_FILE)

    private fun canonicalArchiveKey(url: String): String {
        val ytId = MediaClassifier.extractYouTubeVideoId(url)
        if (!ytId.isNullOrBlank()) return "youtube:$ytId"
        return MediaClassifier.deduplicationKey(url)
    }

    fun isArchived(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val key = canonicalArchiveKey(url)
        synchronized(lock) {
            val entries = loadLocked(context)
            return entries.any { it.key == key }
        }
    }

    fun record(context: Context, url: String, title: String) {
        if (url.isBlank()) return
        val key = canonicalArchiveKey(url)
        synchronized(lock) {
            val current = loadLocked(context).toMutableList()
            current.removeAll { it.key == key }
            current.add(0, ArchiveEntry(key, url, title, System.currentTimeMillis()))
            saveLocked(context, current.take(5000))
        }
    }

    fun count(context: Context): Int = synchronized(lock) { loadLocked(context).size }

    fun getAll(context: Context): List<ArchiveEntry> = synchronized(lock) { loadLocked(context) }

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { archiveFile(context).delete() }
        }
    }

    private fun loadLocked(context: Context): List<ArchiveEntry> {
        val file = archiveFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = mutableListOf<ArchiveEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.optString("key")
                if (key.isNotBlank()) {
                    out.add(
                        ArchiveEntry(
                            key = key,
                            url = obj.optString("url"),
                            title = obj.optString("title"),
                            downloadedAtMillis = obj.optLong("downloadedAtMillis", 0L)
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveLocked(context: Context, list: List<ArchiveEntry>) {
        val file = archiveFile(context)
        val tmp = File(context.filesDir, "$ARCHIVE_FILE.tmp")
        val arr = JSONArray()
        for (entry in list) {
            arr.put(JSONObject().apply {
                put("key", entry.key)
                put("url", entry.url)
                put("title", entry.title)
                put("downloadedAtMillis", entry.downloadedAtMillis)
            })
        }
        runCatching {
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}

object TaskPayloadStore {
    data class TaskPayload(
        val headers: Map<String, String> = emptyMap(),
        val options: DownloadOptions? = null
    )

    private fun dir(context: Context): File = File(context.filesDir, "dl_payloads").apply {
        if (!exists()) mkdirs()
    }

    private fun fileFor(context: Context, taskId: String): File {
        val safe = taskId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        return File(dir(context), "$safe.json")
    }

    fun save(context: Context, taskId: String, headers: Map<String, String>, options: DownloadOptions?) {
        if (headers.isEmpty() && options == null) return
        runCatching {
            val obj = JSONObject().apply {
                if (headers.isNotEmpty()) {
                    val hObj = JSONObject()
                    headers.forEach { (k, v) -> hObj.put(k, v) }
                    put("headers", hObj)
                }
                options?.let { put("options", it.toJson()) }
            }
            fileFor(context, taskId).writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    fun load(context: Context, taskId: String): TaskPayload? {
        val f = fileFor(context, taskId)
        if (!f.exists()) return null
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val headersMap = mutableMapOf<String, String>()
            val hObj = obj.optJSONObject("headers")
            if (hObj != null) {
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headersMap[k] = hObj.optString(k, "")
                }
            }
            val opts = DownloadOptions.fromJson(obj.optJSONObject("options"))
            TaskPayload(headers = headersMap, options = opts)
        }.getOrNull()
    }

    fun delete(context: Context, taskId: String) {
        runCatching { fileFor(context, taskId).delete() }
    }
}

object DownloaderDiagnostics {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val timestampMillis: Long,
        val component: String,
        val level: Level,
        val message: String
    )

    private const val MAX_ENTRIES = 300
    private val deque = ConcurrentLinkedDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun info(component: String, message: String) = add(component, Level.INFO, message)
    fun warn(component: String, message: String) = add(component, Level.WARN, message)
    fun error(component: String, message: String) = add(component, Level.ERROR, message)

    private fun add(component: String, level: Level, message: String) {
        deque.addFirst(Entry(System.currentTimeMillis(), component, level, message))
        while (deque.size > MAX_ENTRIES) {
            deque.pollLast()
        }
        _entries.value = deque.toList()
    }

    fun clear() {
        deque.clear()
        _entries.value = emptyList()
    }

    fun exportText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { entry ->
            "[${fmt.format(Date(entry.timestampMillis))}] [${entry.level}] [${entry.component}] ${entry.message}"
        }
    }
}

object DownloaderPreferencesManager {
    private val KEY_VIDEO_MAX_HEIGHT = intPreferencesKey("dl_video_max_height")
    private val KEY_CONTAINER = stringPreferencesKey("dl_preferred_container")
    private val KEY_AUDIO_ONLY = booleanPreferencesKey("dl_audio_only_default")
    private val KEY_AUDIO_FORMAT = stringPreferencesKey("dl_audio_format")
    private val KEY_AUDIO_BITRATE = intPreferencesKey("dl_audio_bitrate_kbps")
    private val KEY_WIFI_ONLY = booleanPreferencesKey("dl_wifi_only")
    private val KEY_MAX_CONCURRENT = intPreferencesKey("dl_max_concurrent")
    private val KEY_SPEED_LIMIT = intPreferencesKey("dl_speed_limit_kbps")
    private val KEY_PROXY_URL = stringPreferencesKey("dl_proxy_url")
    private val KEY_USER_AGENT = stringPreferencesKey("dl_custom_user_agent")
    private val KEY_DOWNLOAD_SUBS = booleanPreferencesKey("dl_download_subtitles")
    private val KEY_EMBED_SUBS = booleanPreferencesKey("dl_embed_subtitles")
    private val KEY_SUB_LANGS = stringPreferencesKey("dl_subtitle_langs")
    private val KEY_EMBED_META = booleanPreferencesKey("dl_embed_metadata")
    private val KEY_EMBED_THUMB = booleanPreferencesKey("dl_embed_thumbnail")
    private val KEY_SPONSORBLOCK = booleanPreferencesKey("dl_sponsorblock_enabled")
    private val KEY_SPONSORBLOCK_CATS = stringSetPreferencesKey("dl_sponsorblock_cats")
    private val KEY_USE_ARCHIVE = booleanPreferencesKey("dl_use_archive")
    private val KEY_SAF_TREE_URI = stringPreferencesKey("dl_saf_tree_uri")
    private val KEY_ORGANIZE_BY_SITE = booleanPreferencesKey("dl_organize_by_site")
    private val KEY_FILENAME_TEMPLATE = stringPreferencesKey("dl_filename_template")
    private val KEY_CUSTOM_PATTERN = stringPreferencesKey("dl_custom_filename_pattern")
    private val KEY_SEARCH_ENGINE = stringPreferencesKey("dl_browser_search_engine")
    private val KEY_PLAYLIST_LIMIT = intPreferencesKey("dl_playlist_limit")
    private val KEY_DEFAULT_ALBUM_ID = stringPreferencesKey("dl_default_album_id")
    private val KEY_DEFAULT_VAULT = booleanPreferencesKey("dl_default_vault")
    private val KEY_COOKIES_TXT = stringPreferencesKey("dl_cookies_txt")

    @Volatile
    private var cachedSettings: DownloaderSettings = DownloaderSettings()

    fun getCachedSettings(): DownloaderSettings = cachedSettings

    fun settingsFlow(context: Context): Flow<DownloaderSettings> {
        return context.applicationContext.galleryDataStore.data.map { prefs ->
            val settings = DownloaderSettings(
                videoMaxHeight = prefs[KEY_VIDEO_MAX_HEIGHT] ?: 0,
                preferredContainer = prefs[KEY_CONTAINER] ?: "AUTO",
                audioOnlyDefault = prefs[KEY_AUDIO_ONLY] ?: false,
                preferredAudioFormat = prefs[KEY_AUDIO_FORMAT] ?: "MP3",
                preferredAudioBitrateKbps = prefs[KEY_AUDIO_BITRATE] ?: 192,
                wifiOnly = prefs[KEY_WIFI_ONLY] ?: false,
                maxConcurrentDownloads = (prefs[KEY_MAX_CONCURRENT] ?: 3).coerceIn(1, 5),
                speedLimitKbps = (prefs[KEY_SPEED_LIMIT] ?: 0).coerceAtLeast(0),
                proxyUrl = prefs[KEY_PROXY_URL] ?: "",
                customUserAgent = prefs[KEY_USER_AGENT] ?: "",
                downloadSubtitles = prefs[KEY_DOWNLOAD_SUBS] ?: false,
                embedSubtitles = prefs[KEY_EMBED_SUBS] ?: true,
                subtitleLangs = prefs[KEY_SUB_LANGS] ?: "en",
                embedMetadata = prefs[KEY_EMBED_META] ?: true,
                embedThumbnail = prefs[KEY_EMBED_THUMB] ?: true,
                sponsorBlockEnabled = prefs[KEY_SPONSORBLOCK] ?: false,
                sponsorBlockCategories = prefs[KEY_SPONSORBLOCK_CATS] ?: setOf("sponsor", "selfpromo"),
                useDownloadArchive = prefs[KEY_USE_ARCHIVE] ?: true,
                safTreeUri = prefs[KEY_SAF_TREE_URI] ?: "",
                organizeBySite = prefs[KEY_ORGANIZE_BY_SITE] ?: false,
                filenameTemplate = prefs[KEY_FILENAME_TEMPLATE]?.let {
                    runCatching { FilenameTemplate.valueOf(it) }.getOrNull()
                } ?: FilenameTemplate.TITLE,
                customFilenamePattern = prefs[KEY_CUSTOM_PATTERN] ?: "{title}.{ext}",
                browserSearchEngine = prefs[KEY_SEARCH_ENGINE]?.let {
                    runCatching { BrowserSearchEngine.valueOf(it) }.getOrNull()
                } ?: BrowserSearchEngine.DUCKDUCKGO,
                playlistLimit = (prefs[KEY_PLAYLIST_LIMIT] ?: 50).coerceIn(5, 500),
                defaultAlbumId = prefs[KEY_DEFAULT_ALBUM_ID] ?: "",
                defaultAddToVault = prefs[KEY_DEFAULT_VAULT] ?: false,
                cookiesTxtContent = prefs[KEY_COOKIES_TXT] ?: ""
            )
            cachedSettings = settings
            settings
        }
    }

    suspend fun getSettings(context: Context): DownloaderSettings = settingsFlow(context).first()

    fun getSettingsBlocking(context: Context): DownloaderSettings = runBlocking {
        getSettings(context)
    }

    suspend fun update(context: Context, transform: (DownloaderSettings) -> DownloaderSettings) {
        val current = getSettings(context)
        val updated = transform(current)
        context.applicationContext.galleryDataStore.edit { prefs ->
            prefs[KEY_VIDEO_MAX_HEIGHT] = updated.videoMaxHeight
            prefs[KEY_CONTAINER] = updated.preferredContainer
            prefs[KEY_AUDIO_ONLY] = updated.audioOnlyDefault
            prefs[KEY_AUDIO_FORMAT] = updated.preferredAudioFormat
            prefs[KEY_AUDIO_BITRATE] = updated.preferredAudioBitrateKbps
            prefs[KEY_WIFI_ONLY] = updated.wifiOnly
            prefs[KEY_MAX_CONCURRENT] = updated.maxConcurrentDownloads.coerceIn(1, 5)
            prefs[KEY_SPEED_LIMIT] = updated.speedLimitKbps.coerceAtLeast(0)
            prefs[KEY_PROXY_URL] = updated.proxyUrl
            prefs[KEY_USER_AGENT] = updated.customUserAgent
            prefs[KEY_DOWNLOAD_SUBS] = updated.downloadSubtitles
            prefs[KEY_EMBED_SUBS] = updated.embedSubtitles
            prefs[KEY_SUB_LANGS] = updated.subtitleLangs
            prefs[KEY_EMBED_META] = updated.embedMetadata
            prefs[KEY_EMBED_THUMB] = updated.embedThumbnail
            prefs[KEY_SPONSORBLOCK] = updated.sponsorBlockEnabled
            prefs[KEY_SPONSORBLOCK_CATS] = updated.sponsorBlockCategories
            prefs[KEY_USE_ARCHIVE] = updated.useDownloadArchive
            prefs[KEY_SAF_TREE_URI] = updated.safTreeUri
            prefs[KEY_ORGANIZE_BY_SITE] = updated.organizeBySite
            prefs[KEY_FILENAME_TEMPLATE] = updated.filenameTemplate.name
            prefs[KEY_CUSTOM_PATTERN] = updated.customFilenamePattern
            prefs[KEY_SEARCH_ENGINE] = updated.browserSearchEngine.name
            prefs[KEY_PLAYLIST_LIMIT] = updated.playlistLimit.coerceIn(5, 500)
            prefs[KEY_DEFAULT_ALBUM_ID] = updated.defaultAlbumId
            prefs[KEY_DEFAULT_VAULT] = updated.defaultAddToVault
            prefs[KEY_COOKIES_TXT] = updated.cookiesTxtContent
        }
        cachedSettings = updated
        OkHttpDownloader.configureNetwork(proxyUrl = updated.proxyUrl, userAgent = updated.customUserAgent)
        if (updated.cookiesTxtContent != current.cookiesTxtContent) {
            if (updated.cookiesTxtContent.isNotBlank()) {
                CookiesTxtManager.applyCookiesToJar(updated.cookiesTxtContent)
                CookiesTxtManager.writeCookiesFile(context, updated.cookiesTxtContent)
            } else {
                CookiesTxtManager.clearCookiesFile(context)
            }
        }
    }
}
