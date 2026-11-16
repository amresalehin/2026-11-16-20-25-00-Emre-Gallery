package com.amresalehin.emregallery.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class GalleryDlStatus(
    val available: Boolean,
    val version: String,
    val engineName: String,
    val supportedExtractorsCount: Int,
    val popularSites: List<GalleryDlSite>,
    val ytDlpAvailable: Boolean = available,
    val ytDlpVersion: String = "N/A",
    val galleryDlAvailable: Boolean = available,
    val galleryDlVersion: String = version,
    val pythonVersion: String = "3.10"
)

data class GalleryDlSite(
    val name: String,
    val domains: List<String>,
    val icon: String
)

data class GalleryDlSupportInfo(
    val isSupported: Boolean,
    val category: String,
    val subcategory: String,
    val extractorName: String,
    val displayName: String,
    val engine: String = "gallery-dl"
)

data class GalleryDlOptions(
    val userAgent: String? = null,
    val cookies: String? = null,
    val timeoutSeconds: Int = 30,
    val proxy: String? = null,
    val playlistStart: Int = 1,
    val playlistEnd: Int = 50
)

data class SponsorSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val category: String
)

data class AudioAwareDownloadResult(
    val handled: Boolean,
    val file: File? = null,
    val expectedAudio: Boolean = false,
    val expectedVideo: Boolean = false,
    val errorMessage: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val uploadDate: String? = null,
    val subtitleFiles: List<File> = emptyList(),
    val sponsorSegments: List<SponsorSegment> = emptyList()
)

data class GalleryDlExtractResult(
    val success: Boolean,
    val extractorName: String = "",
    val category: String = "",
    val pageTitle: String = "",
    val items: List<ScrapedMediaItem> = emptyList(),
    val errorMessage: String? = null
)

data class EngineUpdateResult(
    val success: Boolean,
    val version: String? = null,
    val message: String
)

/**
 * Gallery-dl & yt-dlp integration service powered by Chaquopy Python SDK on Android.
 */
object GalleryDlEngine {
    private const val TAG = "GalleryDlEngine"
    private var isInitialized = false

    fun interface ProgressBridge {
        fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long)
    }

    fun getOverrideDir(context: Context): File = File(context.filesDir, "py_overrides")

    fun init(context: Context) {
        if (isInitialized) return
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            val overrideDir = getOverrideDir(context.applicationContext)
            if (overrideDir.exists() && overrideDir.isDirectory) {
                runCatching {
                    val py = Python.getInstance()
                    py.getModule("gallery_dl_runner").callAttr("register_override_path", overrideDir.absolutePath)
                }
            }
            isInitialized = true
            Log.d(TAG, "Chaquopy Python SDK and gallery-dl runtime initialized successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Python runtime initialization deferred or failed: ${t.message}")
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Python.isStarted()
        } catch (_: Throwable) {
            false
        }
    }

    fun cancelJob(jobId: String) {
        if (jobId.isBlank() || !isAvailable()) return
        runCatching {
            Python.getInstance().getModule("gallery_dl_runner").callAttr("cancel_job", jobId)
        }
    }

    /**
     * Retrieves runtime status, version, and supported popular sites.
     */
    fun getStatus(): GalleryDlStatus {
        if (!isAvailable()) {
            return GalleryDlStatus(
                available = false,
                version = "Unavailable",
                engineName = "gallery-dl (Chaquopy Python required)",
                supportedExtractorsCount = 0,
                popularSites = defaultPopularSites(),
                ytDlpAvailable = false,
                galleryDlAvailable = false
            )
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val statusJson = runner.callAttr("get_engine_status").toString()
            val json = JSONObject(statusJson)

            val sitesList = mutableListOf<GalleryDlSite>()
            val popularArr = json.optJSONArray("popular_extractors") ?: JSONArray()
            for (i in 0 until popularArr.length()) {
                val obj = popularArr.getJSONObject(i)
                val doms = mutableListOf<String>()
                val dArr = obj.optJSONArray("domains") ?: JSONArray()
                for (j in 0 until dArr.length()) {
                    doms.add(dArr.getString(j))
                }
                sitesList.add(
                    GalleryDlSite(
                        name = obj.optString("name", "Unknown"),
                        domains = doms,
                        icon = obj.optString("icon", "public")
                    )
                )
            }

            GalleryDlStatus(
                available = json.optBoolean("available", true),
                version = json.optString("version", "1.32.19"),
                engineName = json.optString("engine", "gallery-dl (Chaquopy)"),
                supportedExtractorsCount = json.optInt("supported_categories_count", 287),
                popularSites = if (sitesList.isNotEmpty()) sitesList else defaultPopularSites(),
                ytDlpAvailable = json.optBoolean("yt_dlp_available", true),
                ytDlpVersion = json.optString("yt_dlp_version", "N/A"),
                galleryDlAvailable = json.optBoolean("gallery_dl_available", true),
                galleryDlVersion = json.optString("gallery_dl_version", "N/A"),
                pythonVersion = json.optString("python_version", "3.10")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get gallery-dl status", e)
            GalleryDlStatus(
                available = true,
                version = "1.32.19",
                engineName = "gallery-dl 1.32.19",
                supportedExtractorsCount = 287,
                popularSites = defaultPopularSites()
            )
        }
    }

    /**
     * Checks whether the target URL matches a site-specific gallery-dl or yt-dlp extractor.
     */
    fun checkSupport(url: String): GalleryDlSupportInfo {
        if (url.isBlank()) {
            return GalleryDlSupportInfo(false, "", "", "", "")
        }

        if (!isAvailable()) {
            val lower = url.lowercase()
            for (site in defaultPopularSites()) {
                if (site.domains.any { lower.contains(it) }) {
                    return GalleryDlSupportInfo(
                        isSupported = true,
                        category = site.name.lowercase(),
                        subcategory = "",
                        extractorName = "${site.name}Extractor",
                        displayName = site.name
                    )
                }
            }
            return GalleryDlSupportInfo(false, "", "", "", "")
        }

        return try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")
            val checkJson = runner.callAttr("check_support", url.trim()).toString()
            val json = JSONObject(checkJson)
            GalleryDlSupportInfo(
                isSupported = json.optBoolean("supported", false),
                category = json.optString("category", ""),
                subcategory = json.optString("subcategory", ""),
                extractorName = json.optString("extractor", ""),
                displayName = json.optString("display_name", json.optString("category", "")),
                engine = json.optString("engine", "gallery-dl")
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkSupport error", e)
            GalleryDlSupportInfo(false, "", "", "", "")
        }
    }

    fun checkSupportYtDlp(url: String): Boolean {
        if (url.isBlank()) return false
        if (!isAvailable()) {
            val lower = url.lowercase()
            val hints = listOf(
                "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com",
                "twitch.tv", "soundcloud.com", "bandcamp.com", "facebook.com", "instagram.com",
                "bilibili.com", "twitter.com", "x.com", "reddit.com", "v.redd.it"
            )
            return hints.any { lower.contains(it) } || lower.contains(".m3u8") || lower.contains(".mpd")
        }
        return runCatching {
            val runner = Python.getInstance().getModule("gallery_dl_runner")
            val res = JSONObject(runner.callAttr("check_support_ytdlp", url.trim()).toString())
            res.optBoolean("supported", false)
        }.getOrDefault(false)
    }

    private fun parseFormats(arr: JSONArray?): List<ScrapedFormatOption> {
        if (arr == null) return emptyList()
        val list = mutableListOf<ScrapedFormatOption>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val fid = f.optString("formatId")
            if (fid.isBlank()) continue
            list.add(
                ScrapedFormatOption(
                    formatId = fid,
                    label = f.optString("label", fid),
                    ext = f.optString("ext", "mp4"),
                    height = if (f.has("height") && !f.isNull("height")) f.optInt("height") else null,
                    width = if (f.has("width") && !f.isNull("width")) f.optInt("width") else null,
                    fps = if (f.has("fps") && !f.isNull("fps")) f.optInt("fps") else null,
                    abrKbps = if (f.has("abrKbps") && !f.isNull("abrKbps")) f.optInt("abrKbps") else null,
                    vcodec = f.optString("vcodec").takeIf { it.isNotBlank() && it != "null" },
                    acodec = f.optString("acodec").takeIf { it.isNotBlank() && it != "null" },
                    fileSizeBytes = if (f.has("fileSizeBytes") && !f.isNull("fileSizeBytes")) f.optLong("fileSizeBytes") else null,
                    hasVideo = f.optBoolean("hasVideo", true),
                    hasAudio = f.optBoolean("hasAudio", true)
                )
            )
        }
        return list
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) list.add(v)
        }
        return list
    }

    private fun parseExtractedItems(root: JSONObject, pageUrl: String): Pair<String, List<ScrapedMediaItem>> {
        val pageTitle = root.optString("pageTitle", "")
        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val itemsList = mutableListOf<ScrapedMediaItem>()

        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            val catStr = obj.optString("category", "IMAGES")
            val mediaCategory = when (catStr.uppercase()) {
                "VIDEOS" -> MediaCategory.VIDEOS
                "AUDIO" -> MediaCategory.AUDIO
                "STREAMS" -> MediaCategory.STREAMS
                else -> MediaCategory.IMAGES
            }

            val isVideo = obj.optBoolean("isVideo", false)
            val isAudio = obj.optBoolean("isAudio", false)
            val isStream = obj.optBoolean("isStream", false)

            val uploader = obj.optString("uploader").takeIf { it.isNotBlank() && it != "null" }
            val durationSeconds = if (obj.has("durationSeconds") && !obj.isNull("durationSeconds")) obj.optLong("durationSeconds") else null
            val playlistIndex = if (obj.has("playlistIndex") && !obj.isNull("playlistIndex")) obj.optInt("playlistIndex") else null
            val playlistTitle = obj.optString("playlistTitle").takeIf { it.isNotBlank() && it != "null" }
            val playlistTotal = if (obj.has("playlistTotal") && !obj.isNull("playlistTotal")) obj.optInt("playlistTotal") else null
            val formats = parseFormats(obj.optJSONArray("availableFormats"))
            val subtitleLangs = parseStringList(obj.optJSONArray("subtitleLangs"))

            val item = ScrapedMediaItem(
                id = obj.optString("id", "gdl_${System.currentTimeMillis()}_$i"),
                url = obj.getString("url"),
                title = obj.optString("title", "media_${i + 1}"),
                isVideo = isVideo,
                isAudio = isAudio,
                isStream = isStream,
                category = mediaCategory,
                format = obj.optString("format", "IMAGE"),
                mimeType = obj.optString("mimeType", if (isVideo) "video/mp4" else "image/jpeg"),
                thumbnailUrl = obj.optString("thumbnailUrl").takeIf { !it.isNullOrBlank() && it != "null" },
                source = obj.optString("source", "gallery-dl"),
                resolutionHint = obj.optString("resolutionHint").takeIf { !it.isNullOrBlank() && it != "null" },
                sizeBytes = if (obj.has("sizeBytes") && !obj.isNull("sizeBytes")) obj.getLong("sizeBytes") else null,
                provenance = MediaProvenance(
                    extractorEngine = obj.optString("source", "gallery-dl"),
                    pageUrl = pageUrl
                ),
                metadata = buildMap {
                    put("source_url", pageUrl)
                    uploader?.let { put("uploader", it) }
                    playlistTitle?.let { put("playlist_title", it) }
                },
                downloadHeaders = mapOf(
                    "User-Agent" to OkHttpDownloader.DEFAULT_USER_AGENT,
                    "Referer" to pageUrl
                ),
                durationSeconds = durationSeconds,
                uploader = uploader,
                availableFormats = formats,
                subtitleLangs = subtitleLangs,
                playlistIndex = playlistIndex,
                playlistTitle = playlistTitle,
                playlistTotal = playlistTotal
            )
            itemsList.add(item)
        }
        return Pair(pageTitle, itemsList)
    }

    private suspend fun executePythonExtractor(
        methodName: String,
        url: String,
        options: GalleryDlOptions
    ): GalleryDlExtractResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext GalleryDlExtractResult(
                success = false,
                errorMessage = "Chaquopy Python runtime is not active."
            )
        }

        try {
            val py = Python.getInstance()
            val runner = py.getModule("gallery_dl_runner")

            val optsJson = JSONObject().apply {
                options.userAgent?.takeIf { it.isNotBlank() }?.let { put("user_agent", it) }
                options.cookies?.takeIf { it.isNotBlank() }?.let { put("cookies", it) }
                options.proxy?.takeIf { it.isNotBlank() }?.let { put("proxy", it) }
                put("playlist_start", options.playlistStart.coerceAtLeast(1))
                put("playlist_end", options.playlistEnd.coerceAtLeast(1))
                put("timeout", options.timeoutSeconds)
            }.toString()

            val rawResult = runner.callAttr(methodName, url.trim(), optsJson).toString()
            val root = JSONObject(rawResult)

            val success = root.optBoolean("success", false)
            if (!success) {
                val err = root.optString("error", "Extraction failed or produced no items")
                return@withContext GalleryDlExtractResult(
                    success = false,
                    errorMessage = err
                )
            }

            val extractorName = root.optString("extractor", root.optString("engine", methodName))
            val (pageTitle, itemsList) = parseExtractedItems(root, url.trim())

            GalleryDlExtractResult(
                success = true,
                extractorName = extractorName,
                pageTitle = pageTitle,
                items = itemsList
            )
        } catch (e: Exception) {
            Log.e(TAG, "$methodName execution failed", e)
            GalleryDlExtractResult(
                success = false,
                errorMessage = e.localizedMessage ?: "$methodName execution failed"
            )
        }
    }

    suspend fun extractGalleryDl(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_gallery_dl", url, options)

    suspend fun extractYtDlp(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_yt_dlp", url, options)

    suspend fun extractMedia(url: String, options: GalleryDlOptions = GalleryDlOptions()): GalleryDlExtractResult =
        executePythonExtractor("extract_media", url, options)

    /**
     * Resolves the original webpage with yt-dlp, downloads separate tracks when offered,
     * applies post-processing (muxing, audio conversion, subtitles, metadata/artwork, SponsorBlock),
     * and returns only a locally validated media file.
     */
    suspend fun downloadVideoWithAudio(
        context: Context,
        sourcePageUrl: String,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        cookies: String? = null,
        options: DownloadOptions? = null,
        settings: DownloaderSettings? = null,
        jobId: String = "",
        onProgress: ((bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit)? = null
    ): AudioAwareDownloadResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AudioAwareDownloadResult(handled = false)

        val effectiveSettings = settings ?: DownloaderPreferencesManager.getCachedSettings()
        val sourceUrl = mediaUrl.takeIf {
            sourcePageUrl == mediaUrl || checkSupportYtDlp(mediaUrl)
        } ?: sourcePageUrl.takeIf { it.isNotBlank() }
            ?: referer?.takeIf { it.isNotBlank() }
            ?: mediaUrl

        val workDir = File(context.cacheDir, "audio-video-${UUID.randomUUID()}")
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            return@withContext AudioAwareDownloadResult(handled = false, errorMessage = "Unable to create temporary download directory")
        }

        var extractorHandled = false
        try {
            val effectiveHeaders = LinkedHashMap<String, String>()
            headers?.forEach { (key, value) -> effectiveHeaders[key] = value }
            if (!effectiveHeaders.keys.any { it.equals("User-Agent", true) }) {
                effectiveHeaders["User-Agent"] = effectiveSettings.customUserAgent.ifBlank { OkHttpDownloader.DEFAULT_USER_AGENT }
            }
            if (!referer.isNullOrBlank() && !effectiveHeaders.keys.any { it.equals("Referer", true) }) {
                effectiveHeaders["Referer"] = referer
            }
            val effectiveCookies = effectiveSettings.cookiesTxtContent.takeIf { it.isNotBlank() }
                ?: cookies
                ?: effectiveHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
            if (!effectiveCookies.isNullOrBlank()) {
                val existingCookieKeys = effectiveHeaders.keys.filter { it.equals("Cookie", true) }
                existingCookieKeys.forEach { effectiveHeaders.remove(it) }
                effectiveHeaders["Cookie"] = effectiveCookies
            }
            val headersJson = JSONObject().apply {
                effectiveHeaders.forEach { (key, value) -> put(key, value) }
            }.toString()

            val effectiveMaxHeight = options?.maxHeight ?: effectiveSettings.videoMaxHeight
            val effectiveContainer = options?.preferredContainer ?: effectiveSettings.preferredContainer
            val effectiveAudioOnly = options?.audioOnly ?: effectiveSettings.audioOnlyDefault
            val effectiveAudioFmt = (options?.audioFormat ?: effectiveSettings.preferredAudioFormat).uppercase()
            val effectiveAudioBitrate = options?.audioBitrateKbps ?: effectiveSettings.preferredAudioBitrateKbps
            val effectiveWriteSubs = options?.writeSubtitles ?: effectiveSettings.downloadSubtitles
            val effectiveEmbedSubs = options?.embedSubtitles ?: effectiveSettings.embedSubtitles
            val effectiveSubLangs = options?.subtitleLangs ?: effectiveSettings.subtitleLangs
            val effectiveSponsorBlock = options?.sponsorBlock ?: effectiveSettings.sponsorBlockEnabled

            val dlOptsJson = JSONObject().apply {
                options?.formatId?.let { put("format_id", it) }
                if (effectiveMaxHeight > 0) put("max_height", effectiveMaxHeight)
                if (!effectiveContainer.equals("AUTO", true)) put("preferred_ext", effectiveContainer.lowercase())
                put("audio_only", effectiveAudioOnly)
                put("write_subtitles", effectiveWriteSubs)
                put("subtitle_langs", effectiveSubLangs)
                if (effectiveSettings.proxyUrl.isNotBlank()) put("proxy", effectiveSettings.proxyUrl)
                if (effectiveSettings.customUserAgent.isNotBlank()) put("user_agent", effectiveSettings.customUserAgent)
                if (effectiveSettings.speedLimitKbps > 0) put("ratelimit_bps", effectiveSettings.speedLimitKbps * 1024L)
                if (effectiveSponsorBlock && effectiveSettings.sponsorBlockCategories.isNotEmpty()) {
                    put("sponsorblock", true)
                    put("sponsorblock_categories", JSONArray(effectiveSettings.sponsorBlockCategories.toList()))
                }
            }.toString()

            val pyCallback: Any? = if (onProgress != null) {
                PyObject.fromJava(ProgressBridge { dl, tot, spd -> onProgress(dl, tot, spd) })
            } else null

            val runner = Python.getInstance().getModule("gallery_dl_runner")
            val raw = runner.callAttr(
                "download_yt_dlp_tracks",
                sourceUrl,
                workDir.absolutePath,
                headersJson,
                dlOptsJson,
                jobId,
                pyCallback
            ).toString()
            val result = JSONObject(raw)
            val handled = result.optBoolean("handled", false)
            extractorHandled = handled
            if (!handled) {
                return@withContext AudioAwareDownloadResult(
                    handled = false,
                    errorMessage = result.optString("error").takeIf { it.isNotBlank() }
                )
            }

            val expectedAudio = result.optBoolean("hasAudio", false)
            val expectedVideo = result.optBoolean("hasVideo", false) && !effectiveAudioOnly
            if (!result.optBoolean("success", false)) {
                return@withContext AudioAwareDownloadResult(
                    handled = true,
                    expectedAudio = expectedAudio,
                    expectedVideo = expectedVideo,
                    errorMessage = result.optString("error", "yt-dlp track download failed")
                )
            }

            fun privateFile(key: String): File? {
                val rawPath = result.optString(key).takeIf { it.isNotBlank() } ?: return null
                val candidate = File(rawPath).canonicalFile
                val root = workDir.canonicalFile
                if (!candidate.path.startsWith(root.path + File.separator)) return null
                return candidate.takeIf { it.isFile && it.length() > 0L }
            }

            val subPathsList = mutableListOf<File>()
            val subArr = result.optJSONArray("subtitlePaths")
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val p = subArr.optString(i)
                    if (p.isNotBlank()) {
                        val f = File(p).canonicalFile
                        if (f.path.startsWith(workDir.canonicalFile.path + File.separator) && f.isFile && f.length() > 0L) {
                            subPathsList.add(f)
                        }
                    }
                }
            }

            val sponsorSegs = mutableListOf<SponsorSegment>()
            val segArr = result.optJSONArray("sponsorSegments")
            if (segArr != null) {
                for (i in 0 until segArr.length()) {
                    val sObj = segArr.optJSONObject(i) ?: continue
                    sponsorSegs.add(
                        SponsorSegment(
                            startSeconds = sObj.optDouble("start", 0.0),
                            endSeconds = sObj.optDouble("end", 0.0),
                            category = sObj.optString("category", "sponsor")
                        )
                    )
                }
            }

            val resolvedTitle = result.optString("title").takeIf { it.isNotBlank() } ?: fileName
            val resolvedUploader = result.optString("uploader").takeIf { it.isNotBlank() }
            val resolvedDate = result.optString("uploadDate").takeIf { it.isNotBlank() }
            val resolvedThumbUrl = result.optString("thumbnailUrl").takeIf { it.isNotBlank() }

            val outputDir = OkHttpDownloader.getTargetStorageDir(context)
            val domain = runCatching { java.net.URI(sourceUrl).host?.removePrefix("www.") }.getOrNull()
            val formattedStem = FilenameTemplateHelper.formatFilename(
                template = effectiveSettings.filenameTemplate,
                customPattern = effectiveSettings.customFilenamePattern,
                title = resolvedTitle,
                uploader = resolvedUploader,
                domain = domain,
                dateMillis = System.currentTimeMillis(),
                id = jobId.ifBlank { null },
                ext = "tmp"
            ).substringBeforeLast('.')

            var workingFile: File? = null
            when (result.optString("kind")) {
                "split" -> {
                    val videoFile = privateFile("videoPath")
                    val audioFile = privateFile("audioPath")
                    if (videoFile == null || audioFile == null) {
                        return@withContext AudioAwareDownloadResult(
                            handled = true, expectedAudio = true, expectedVideo = true,
                            errorMessage = "yt-dlp did not produce both non-empty audio and video tracks"
                        )
                    }
                    val targetExt = if (effectiveContainer.equals("MKV", true)) "mkv" else "mp4"
                    val muxTarget = File(workDir, "muxed_stage.$targetExt")
                    workingFile = MediaStreamMuxer.mux(videoFile, audioFile, muxTarget, jobId = jobId)
                    if (workingFile == null) {
                        return@withContext AudioAwareDownloadResult(
                            handled = true, expectedAudio = true, expectedVideo = true,
                            errorMessage = "FFmpeg could not mux the selected audio and video tracks"
                        )
                    }
                }
                "single", "video_only", "audio_only" -> {
                    val sourceFile = privateFile("mediaPath")
                    if (sourceFile == null) {
                        return@withContext AudioAwareDownloadResult(
                            handled = true, expectedAudio = expectedAudio, expectedVideo = expectedVideo,
                            errorMessage = "yt-dlp completed without a non-empty media file"
                        )
                    }
                    workingFile = sourceFile
                }
                else -> {
                    return@withContext AudioAwareDownloadResult(
                        handled = true, expectedAudio = expectedAudio, expectedVideo = expectedVideo,
                        errorMessage = "yt-dlp returned an unsupported track selection"
                    )
                }
            }

            // Optional cover art download for thumbnail embedding
            val embedThumb = options?.embedThumbnail ?: effectiveSettings.embedThumbnail
            val embedMeta = options?.embedMetadata ?: effectiveSettings.embedMetadata
            var coverFile: File? = null
            if (embedThumb && !resolvedThumbUrl.isNullOrBlank()) {
                val thumbTemp = File(workDir, "cover.jpg")
                runCatching {
                    val req = Request.Builder().url(resolvedThumbUrl).build()
                    OkHttpDownloader.client.newCall(req).execute().use { r ->
                        if (r.isSuccessful) {
                            r.body?.bytes()?.let { thumbTemp.writeBytes(it) }
                        }
                    }
                }
                if (thumbTemp.isFile && thumbTemp.length() > 0L) coverFile = thumbTemp
            }

            val metaMap = buildMap {
                put("title", resolvedTitle)
                resolvedUploader?.let { put("artist", it) }
                resolvedDate?.let { put("date", it) }
                put("comment", sourceUrl)
            }

            // Post-process Audio-only extraction/transcoding vs Video post-processing
            val finalFile: File? = if (effectiveAudioOnly) {
                val targetAudExt = when (effectiveAudioFmt) {
                    "M4A" -> "m4a"
                    "OPUS" -> "opus"
                    "FLAC" -> "flac"
                    else -> "mp3"
                }
                val finalAudioFile = File(outputDir, "${System.currentTimeMillis()}_${formattedStem}.$targetAudExt")
                MediaStreamMuxer.transcodeAudio(
                    input = workingFile,
                    target = finalAudioFile,
                    targetFormat = targetAudExt,
                    bitrateKbps = effectiveAudioBitrate,
                    coverArt = coverFile,
                    metadata = if (embedMeta) metaMap else emptyMap(),
                    jobId = jobId
                ) ?: run {
                    // Fallback copy if codec already matches and transcode wasn't strictly needed
                    val ext = workingFile.extension.ifBlank { "m4a" }
                    val fallback = File(outputDir, "${System.currentTimeMillis()}_${formattedStem}.$ext")
                    workingFile.copyTo(fallback, overwrite = true)
                    fallback
                }
            } else {
                val outExt = when (effectiveContainer.uppercase()) {
                    "MKV" -> "mkv"
                    "WEBM" -> if (workingFile.extension.equals("webm", true)) "webm" else workingFile.extension.ifBlank { "mp4" }
                    "MP4" -> "mp4"
                    else -> workingFile.extension.ifBlank { "mp4" }
                }
                val targetVideoFile = File(outputDir, "${System.currentTimeMillis()}_${formattedStem}.$outExt")
                val needsPostProcess = (effectiveEmbedSubs && subPathsList.isNotEmpty()) ||
                    (embedMeta && metaMap.isNotEmpty()) ||
                    sponsorSegs.isNotEmpty() ||
                    coverFile != null

                if (needsPostProcess) {
                    MediaStreamMuxer.postProcessVideo(
                        input = workingFile,
                        target = targetVideoFile,
                        subtitleFiles = if (effectiveEmbedSubs) subPathsList else emptyList(),
                        coverArt = coverFile,
                        metadata = if (embedMeta) metaMap else emptyMap(),
                        sponsorSegments = sponsorSegs,
                        jobId = jobId
                    ) ?: run {
                        workingFile.copyTo(targetVideoFile, overwrite = true)
                        targetVideoFile
                    }
                } else {
                    workingFile.copyTo(targetVideoFile, overwrite = true)
                    targetVideoFile
                }
            }

            // Also copy non-embedded subtitle files (.srt/.vtt) next to target if requested
            val savedSubs = mutableListOf<File>()
            if (effectiveWriteSubs && !effectiveEmbedSubs && finalFile != null) {
                for ((idx, sub) in subPathsList.withIndex()) {
                    val destSub = File(outputDir, "${finalFile.nameWithoutExtension}_${idx}.${sub.extension}")
                    runCatching {
                        sub.copyTo(destSub, overwrite = true)
                        savedSubs.add(destSub)
                    }
                }
            }

            val probe = finalFile?.let { MediaStreamMuxer.probe(it) }
            if (finalFile == null || probe == null ||
                (expectedVideo && !probe.hasVideo) || ((expectedAudio || effectiveAudioOnly) && !probe.hasAudio)) {
                finalFile?.delete()
                return@withContext AudioAwareDownloadResult(
                    handled = true, expectedAudio = expectedAudio || effectiveAudioOnly, expectedVideo = expectedVideo,
                    errorMessage = "Downloaded file failed audio/video stream verification"
                )
            }

            android.media.MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
            DownloaderDiagnostics.info(TAG, "Saved ${finalFile.name} (${finalFile.length()} bytes)")
            AudioAwareDownloadResult(
                handled = true,
                file = finalFile,
                expectedAudio = expectedAudio || effectiveAudioOnly,
                expectedVideo = expectedVideo,
                title = resolvedTitle,
                uploader = resolvedUploader,
                thumbnailUrl = resolvedThumbUrl,
                uploadDate = resolvedDate,
                subtitleFiles = savedSubs,
                sponsorSegments = sponsorSegs
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Audio-aware yt-dlp download failed for $sourceUrl", t)
            DownloaderDiagnostics.error(TAG, "yt-dlp failed for $sourceUrl: ${t.message}")
            AudioAwareDownloadResult(
                handled = extractorHandled,
                errorMessage = t.localizedMessage ?: "Audio-aware extraction or muxing failed"
            )
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * Downloads the latest pure-Python yt-dlp wheel from PyPI (no native/dex/jar allowed) into private filesDir override.
     */
    suspend fun updateYtDlpFromPyPi(context: Context): EngineUpdateResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext EngineUpdateResult(false, message = "Python runtime is not initialized")
        }
        val tmpWheel = File(context.cacheDir, "yt_dlp_update.whl")
        try {
            val req = Request.Builder()
                .url("https://pypi.org/pypi/yt-dlp/json")
                .header("Accept", "application/json")
                .build()
            val bodyStr = OkHttpDownloader.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext EngineUpdateResult(false, message = "PyPI check failed (HTTP ${resp.code})")
                }
                resp.body?.string() ?: ""
            }
            val root = JSONObject(bodyStr)
            val version = root.getJSONObject("info").getString("version")
            val urls = root.getJSONArray("urls")
            var wheelUrl: String? = null
            for (i in 0 until urls.length()) {
                val u = urls.getJSONObject(i)
                val fn = u.optString("filename")
                if (u.optString("packagetype") == "bdist_wheel" && fn.endsWith("py3-none-any.whl")) {
                    wheelUrl = u.optString("url")
                    break
                }
            }
            if (wheelUrl.isNullOrBlank()) {
                return@withContext EngineUpdateResult(false, message = "No pure-Python universal wheel found for $version")
            }

            OkHttpDownloader.client.newCall(Request.Builder().url(wheelUrl).build()).execute().use { wResp ->
                if (!wResp.isSuccessful) {
                    return@withContext EngineUpdateResult(false, message = "Failed to download wheel (HTTP ${wResp.code})")
                }
                wResp.body?.byteStream()?.use { input ->
                    FileOutputStream(tmpWheel).use { output -> input.copyTo(output) }
                }
            }

            val overrideDir = getOverrideDir(context)
            val runner = Python.getInstance().getModule("gallery_dl_runner")
            val installResStr = runner.callAttr(
                "install_pure_python_override",
                tmpWheel.absolutePath,
                overrideDir.absolutePath,
                "yt_dlp"
            ).toString()
            val installRes = JSONObject(installResStr)
            if (installRes.optBoolean("success", false)) {
                val newVer = installRes.optString("version", version)
                DownloaderDiagnostics.info(TAG, "Updated yt-dlp pure-Python engine to $newVer")
                EngineUpdateResult(true, version = newVer, message = "Updated yt-dlp to $newVer")
            } else {
                val err = installRes.optString("error", "Install failed")
                EngineUpdateResult(false, message = err)
            }
        } catch (e: Exception) {
            DownloaderDiagnostics.error(TAG, "yt-dlp update error: ${e.message}")
            EngineUpdateResult(false, message = e.localizedMessage ?: "Update failed")
        } finally {
            runCatching { tmpWheel.delete() }
        }
    }

    suspend fun revertYtDlpOverride(context: Context): EngineUpdateResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext EngineUpdateResult(false, message = "Python unavailable")
        runCatching {
            val runner = Python.getInstance().getModule("gallery_dl_runner")
            runner.callAttr("clear_pure_python_override", getOverrideDir(context).absolutePath, "yt_dlp")
            EngineUpdateResult(true, message = "Cleared override (takes full effect on restart)")
        }.getOrElse {
            EngineUpdateResult(false, message = it.localizedMessage ?: "Revert failed")
        }
    }

    /**
     * Downloads a media file with proper referer header via Python requests or fallback.
     */
    suspend fun downloadMedia(
        context: Context,
        mediaUrl: String,
        fileName: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        cookies: String? = null,
        proxy: String? = null,
        jobId: String = ""
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = OkHttpDownloader.getTargetStorageDir(context)
            val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(dir, "${System.currentTimeMillis()}_$sanitizedName")

            val settings = DownloaderPreferencesManager.getCachedSettings()
            val userAgent = headers?.get("User-Agent") ?: settings.customUserAgent.ifBlank { OkHttpDownloader.DEFAULT_USER_AGENT }
            val effectiveCookies = cookies ?: settings.cookiesTxtContent.takeIf { it.isNotBlank() } ?: headers?.get("Cookie")
            val effectiveProxy = proxy ?: settings.proxyUrl.takeIf { it.isNotBlank() }
            val headersJson = if (!headers.isNullOrEmpty()) {
                val json = JSONObject()
                headers.forEach { (k, v) -> json.put(k, v) }
                json.toString()
            } else ""

            if (isAvailable()) {
                val py = Python.getInstance()
                val runner = py.getModule("gallery_dl_runner")
                val success = runner.callAttr(
                    "download_media_file",
                    mediaUrl,
                    targetFile.absolutePath,
                    referer ?: "",
                    userAgent,
                    effectiveCookies ?: "",
                    headersJson,
                    effectiveProxy ?: "",
                    jobId
                ).toBoolean()

                if (success && targetFile.exists() && targetFile.length() > 0L) {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        null,
                        null
                    )
                    return@withContext targetFile
                }
            }

            OkHttpDownloader.downloadMediaFile(
                context = context,
                mediaUrl = mediaUrl,
                fileName = fileName,
                referer = referer,
                headers = headers
            )
        } catch (e: Exception) {
            Log.e(TAG, "downloadMedia failed", e)
            null
        }
    }

    fun defaultPopularSites(): List<GalleryDlSite> = listOf(
        GalleryDlSite("Reddit", listOf("reddit.com", "redd.it", "i.redd.it", "v.redd.it"), "reddit"),
        GalleryDlSite("Imgur", listOf("imgur.com", "i.imgur.com"), "image"),
        GalleryDlSite("Twitter / X", listOf("twitter.com", "x.com", "pbs.twimg.com"), "tag"),
        GalleryDlSite("Pinterest", listOf("pinterest.com", "i.pinimg.com"), "pin"),
        GalleryDlSite("ArtStation", listOf("artstation.com"), "palette"),
        GalleryDlSite("DeviantArt", listOf("deviantart.com"), "brush"),
        GalleryDlSite("Danbooru", listOf("donmai.us", "gelbooru.com", "safebooru.org", "yande.re"), "collections"),
        GalleryDlSite("Flickr", listOf("flickr.com"), "photo_camera"),
        GalleryDlSite("Tumblr", listOf("tumblr.com"), "feed"),
        GalleryDlSite("Wikimedia", listOf("wikimedia.org", "wikipedia.org"), "public"),
        GalleryDlSite("Bluesky", listOf("bsky.app"), "cloud"),
        GalleryDlSite("500px", listOf("500px.com"), "camera_alt"),
        GalleryDlSite("Behance", listOf("behance.net"), "design_services"),
        GalleryDlSite("Bilibili", listOf("bilibili.com"), "smart_display"),
        GalleryDlSite("4chan", listOf("4chan.org", "4channel.org", "i.4cdn.org"), "forum")
    )
}
