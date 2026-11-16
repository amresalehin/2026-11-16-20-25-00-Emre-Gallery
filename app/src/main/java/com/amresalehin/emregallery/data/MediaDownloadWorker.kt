package com.amresalehin.emregallery.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amresalehin.emregallery.data.preferences.MediaCollectionsPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_DOWNLOAD) return
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        val workIdStr = intent.getStringExtra(EXTRA_WORK_ID)
        if (itemId.isNotBlank()) {
            DownloadActivityManager.pauseDownload(context, itemId)
            DownloadQueueManager.cancelByItemId(context, itemId)
        } else if (!workIdStr.isNullOrBlank()) {
            runCatching { DownloadQueueManager.cancelDownload(context, UUID.fromString(workIdStr)) }
        }
    }

    companion object {
        const val ACTION_CANCEL_DOWNLOAD = "com.amresalehin.emregallery.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_WORK_ID = "extra_work_id"
    }
}

/**
 * Persistent background downloader worker powered by Android Jetpack WorkManager.
 * Incorporates byte-level progress, HTTP Range resume, headers/cookies/proxy propagation,
 * yt-dlp + FFmpegKit track muxing & HLS/DASH remuxing, post-processing, and SAF/MediaStore export.
 */
class MediaDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private fun getWorkerNotificationId(key: String): Int {
        return 4001 + (abs(key.hashCode()) % 50000)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: id.toString()
        val title = inputData.getString(KEY_TITLE) ?: inputData.getString(KEY_FILE_NAME) ?: "Media"
        return createSilentForegroundInfo(title, itemId, getWorkerNotificationId(itemId), -1)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "Media Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background media downloads"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun createSilentForegroundInfo(
        title: String,
        itemId: String,
        notificationId: Int,
        progressPct: Int
    ): ForegroundInfo {
        ensureNotificationChannel()

        val cancelIntent = Intent(applicationContext, DownloadActionReceiver::class.java).apply {
            action = DownloadActionReceiver.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadActionReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(DownloadActionReceiver.EXTRA_WORK_ID, id.toString())
        }
        val cancelPending = PendingIntent.getBroadcast(
            applicationContext,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, SILENT_CHANNEL_ID)
            .setContentTitle("Downloading media")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .apply {
                if (progressPct in 0..100) {
                    setProgress(100, progressPct, false)
                } else {
                    setProgress(100, 0, true)
                }
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause / Cancel", cancelPending)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mediaUrl = inputData.getString(KEY_MEDIA_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "media_${System.currentTimeMillis()}"
        val referer = inputData.getString(KEY_REFERER)
        val itemId = inputData.getString(KEY_ITEM_ID) ?: "item_${System.currentTimeMillis()}"
        val title = inputData.getString(KEY_TITLE) ?: fileName
        val isVideo = inputData.getBoolean(KEY_IS_VIDEO, false)
        val isAudio = inputData.getBoolean(KEY_IS_AUDIO, false)
        val isStream = inputData.getBoolean(KEY_IS_STREAM, false)
        val format = inputData.getString(KEY_FORMAT) ?: ""
        val stackId = inputData.getString(KEY_STACK_ID) ?: "stack_${System.currentTimeMillis()}"
        val stackTitle = inputData.getString(KEY_STACK_TITLE) ?: "Discovered Media"
        val sourceDomain = inputData.getString(KEY_SOURCE_DOMAIN) ?: ""
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: mediaUrl
        val headersJson = inputData.getString(KEY_HEADERS_JSON)

        val payload = TaskPayloadStore.load(applicationContext, itemId)
        val settings = DownloaderPreferencesManager.getSettings(applicationContext)
        OkHttpDownloader.configureNetwork(settings.proxyUrl, settings.customUserAgent)

        val parsedFromData: Map<String, String> = runCatching {
            if (!headersJson.isNullOrBlank()) {
                val obj = JSONObject(headersJson)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.getString(k)
                }
                map
            } else emptyMap()
        }.getOrDefault(emptyMap())

        val downloadHeaders: Map<String, String> = (payload?.headers ?: emptyMap()) + parsedFromData
        val downloadOptions: DownloadOptions? = payload?.options

        val cookieString = settings.cookiesTxtContent.takeIf { it.isNotBlank() }
            ?: OkHttpDownloader.sharedCookieJar.getCookieString(mediaUrl).takeIf { it.isNotBlank() }
            ?: OkHttpDownloader.sharedCookieJar.getCookieString(sourceUrl).takeIf { it.isNotBlank() }
            ?: downloadHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value

        val notifId = getWorkerNotificationId(itemId)
        try {
            setForeground(createSilentForegroundInfo(title, itemId, notifId, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground service notification: ${e.message}")
        }

        DownloadQueueManager.acquireConcurrencySlot(applicationContext, itemId)
        setProgress(workDataOf(KEY_PROGRESS to 0.05f, KEY_STATUS to "Initializing download for $title..."))

        try {
            if (isStopped) return@withContext Result.failure()

            var downloadedFile: File? = null
            var errorDiagnostic: String? = null
            var resolvedTitle = title
            var extractorHandled = false
            var expectedAudioFromExtractor = false
            var expectedVideoFromExtractor = false
            val effectiveAudioOnly = downloadOptions?.audioOnly ?: settings.audioOnlyDefault
            var lastProgressUpdate = System.currentTimeMillis()
            var lastBytes = 0L

            fun emitProgress(bytesRead: Long, totalBytes: Long, explicitSpeed: Long = -1L) {
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 300) {
                    val dt = (now - lastProgressUpdate) / 1000.0
                    val db = bytesRead - lastBytes
                    val speed = if (explicitSpeed >= 0L) explicitSpeed else if (dt > 0) (db / dt).toLong() else 0L
                    lastBytes = bytesRead
                    lastProgressUpdate = now
                    val pct = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0.5f
                    DownloadActivityManager.updateProgress(itemId, pct, bytesRead, totalBytes, speed)
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS to pct,
                            KEY_BYTES_READ to bytesRead,
                            KEY_TOTAL_BYTES to totalBytes,
                            KEY_STATUS to "Downloading $resolvedTitle (${bytesRead / 1024} KB)"
                        )
                    )
                    runCatching {
                        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(notifId, createSilentForegroundInfo(resolvedTitle, itemId, notifId, (pct * 100).toInt()).notification)
                    }
                }
            }

            // 1. Try yt-dlp for videos, audio tracks, and adaptive streams when Chaquopy runtime is available
            if ((isVideo || isAudio || isStream) && GalleryDlEngine.isAvailable()) {
                setProgress(workDataOf(KEY_PROGRESS to 0.15f, KEY_STATUS to "Resolving streams & tracks: $title..."))
                val audioAwareResult = GalleryDlEngine.downloadVideoWithAudio(
                    context = applicationContext,
                    sourcePageUrl = sourceUrl,
                    mediaUrl = mediaUrl,
                    fileName = fileName,
                    referer = referer,
                    headers = downloadHeaders,
                    cookies = cookieString,
                    options = downloadOptions,
                    settings = settings,
                    jobId = itemId,
                    onProgress = { b, tot, spd -> emitProgress(b, tot, spd) }
                )
                extractorHandled = audioAwareResult.handled
                expectedAudioFromExtractor = audioAwareResult.expectedAudio
                expectedVideoFromExtractor = audioAwareResult.expectedVideo
                if (!audioAwareResult.title.isNullOrBlank()) resolvedTitle = audioAwareResult.title
                if (audioAwareResult.handled) {
                    downloadedFile = audioAwareResult.file
                    if (downloadedFile == null) {
                        errorDiagnostic = audioAwareResult.errorMessage
                            ?: "The media extractor resolved this source but could not produce a validated file"
                    }
                }
            }

            // 2. If still not downloaded: handle direct HLS (.m3u8) / DASH (.mpd) manifests via FFmpegKit remuxing
            val looksLikeManifest = isStream ||
                mediaUrl.contains(".m3u8", ignoreCase = true) ||
                mediaUrl.contains(".mpd", ignoreCase = true)
            if (!extractorHandled && downloadedFile == null && looksLikeManifest) {
                setProgress(workDataOf(KEY_PROGRESS to 0.25f, KEY_STATUS to "Downloading stream manifest: $title..."))
                val outDir = OkHttpDownloader.getTargetStorageDir(
                    applicationContext,
                    if (settings.organizeBySite) sourceDomain else null
                )
                val formattedName = FilenameTemplateHelper.formatFilename(
                    template = settings.filenameTemplate,
                    customPattern = settings.customFilenamePattern,
                    title = fileName,
                    uploader = null,
                    domain = sourceDomain,
                    dateMillis = System.currentTimeMillis(),
                    id = itemId,
                    ext = "mp4"
                )
                val targetStreamFile = File(outDir, "${System.currentTimeMillis()}_$formattedName")
                downloadedFile = MediaStreamMuxer.downloadStreamWithFfmpeg(
                    url = mediaUrl,
                    headers = downloadHeaders,
                    target = targetStreamFile,
                    jobId = itemId,
                    onProgress = { bytes -> emitProgress(bytes, bytes, 0L) }
                )
                if (downloadedFile == null) {
                    errorDiagnostic = "Unable to capture HLS/DASH stream"
                }
            }

            // 3. Direct HTTP download via OkHttpDownloader with Range resume + speed cap + cancellation
            var okHttpResult: OkHttpDownloader.DownloadResult? = null
            if (!extractorHandled && (downloadedFile == null || !downloadedFile.exists() || downloadedFile.length() == 0L)) {
                val formattedName = FilenameTemplateHelper.formatFilename(
                    template = settings.filenameTemplate,
                    customPattern = settings.customFilenamePattern,
                    title = fileName,
                    uploader = null,
                    domain = sourceDomain,
                    dateMillis = System.currentTimeMillis(),
                    id = itemId,
                    ext = fileName.substringAfterLast('.', "").ifBlank { format.lowercase(Locale.ROOT).ifBlank { "bin" } }
                )
                val result = OkHttpDownloader.downloadMediaFileDetailed(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    fileName = formattedName,
                    referer = referer,
                    downloadId = itemId,
                    headers = downloadHeaders,
                    onProgress = { bytesRead, totalBytes -> emitProgress(bytesRead, totalBytes) },
                    isCancelled = { isStopped },
                    maxBytesPerSec = settings.speedLimitKbps.toLong() * 1024L
                )
                okHttpResult = result

                if (result.isSuccessful && result.file != null && result.file.exists() && result.file.length() > 0L) {
                    downloadedFile = result.file
                } else if (!looksLikeManifest) {
                    errorDiagnostic = result.errorMessage
                    val isRateLimited = result.statusCode == 429
                    val isInvalidContent = result.errorMessage?.let { msg ->
                        msg.contains("webpage", ignoreCase = true) ||
                        msg.contains("HTML", ignoreCase = true) ||
                        msg.contains("JSON error", ignoreCase = true) ||
                        msg.contains("corrupt or invalid raster", ignoreCase = true)
                    } ?: false

                    if (!isStopped && !isRateLimited && !isInvalidContent && GalleryDlEngine.isAvailable()) {
                        setProgress(workDataOf(KEY_PROGRESS to 0.4f, KEY_STATUS to "Attempting engine fallback for $title..."))
                        downloadedFile = GalleryDlEngine.downloadMedia(
                            context = applicationContext,
                            mediaUrl = mediaUrl,
                            fileName = formattedName,
                            referer = referer,
                            headers = downloadHeaders,
                            cookies = cookieString,
                            proxy = settings.proxyUrl,
                            jobId = itemId
                        )
                    }
                }
            }

            if (isStopped) {
                Log.d(TAG, "MediaDownloadWorker was stopped for $mediaUrl")
                return@withContext Result.failure()
            }

            // Optional audio conversion when user selected Audio-Only on a direct video/stream URL
            if (downloadedFile != null && downloadedFile.exists() && !extractorHandled && effectiveAudioOnly) {
                val targetAudioExt = (downloadOptions?.audioFormat ?: settings.preferredAudioFormat).lowercase(Locale.ROOT)
                val targetBitrate = downloadOptions?.audioBitrateKbps ?: settings.preferredAudioBitrateKbps
                if (!downloadedFile.extension.equals(targetAudioExt, ignoreCase = true)) {
                    val audioDest = File(downloadedFile.parentFile, "${downloadedFile.nameWithoutExtension}.$targetAudioExt")
                    val transcoded = MediaStreamMuxer.transcodeAudio(
                        input = downloadedFile,
                        target = audioDest,
                        targetFormat = targetAudioExt,
                        bitrateKbps = targetBitrate,
                        jobId = itemId
                    )
                    if (transcoded != null && transcoded.exists() && transcoded.length() > 0L) {
                        downloadedFile.delete()
                        downloadedFile = transcoded
                    }
                }
            }

            // Stream integrity verification
            val expectVideo = isVideo && !effectiveAudioOnly
            val expectAudio = isAudio || effectiveAudioOnly
            if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0L &&
                (isVideo || isAudio || isStream)) {
                val probe = MediaStreamMuxer.probe(downloadedFile)
                val invalidStreams = probe == null ||
                    (expectVideo && probe?.hasVideo != true) ||
                    (expectAudio && probe?.hasAudio != true) ||
                    (isStream && !effectiveAudioOnly && probe?.hasVideo != true && probe?.hasAudio != true) ||
                    (expectedVideoFromExtractor && probe?.hasVideo != true) ||
                    (expectedAudioFromExtractor && probe?.hasAudio != true)
                if (invalidStreams) {
                    Log.w(TAG, "Rejecting downloaded media without required streams: ${downloadedFile.name}")
                    downloadedFile.delete()
                    errorDiagnostic = "Downloaded file failed audio/video stream verification"
                    downloadedFile = null
                }
            }

            if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0L) {
                setProgress(workDataOf(KEY_PROGRESS to 1.0f, KEY_STATUS to "Saved successfully"))
                DownloadArchiveStore.record(applicationContext, mediaUrl, resolvedTitle)

                val finalMime = okHttpResult?.mimeType ?: MediaClassifier.classify(downloadedFile.name).mimeType
                OkHttpDownloader.exportToPublicMediaStoreOrSaf(
                    context = applicationContext,
                    sourceFile = downloadedFile,
                    mimeType = finalMime,
                    displayName = downloadedFile.name,
                    domain = sourceDomain,
                    settings = settings
                )

                val targetVault = downloadOptions?.addToVault ?: settings.defaultAddToVault
                var routedToVault = false
                if (targetVault) {
                    runCatching {
                        val vaultMgr = LockedVaultManager(applicationContext)
                        val fakeMediaItem = MediaItem(
                            id = abs(itemId.hashCode().toLong()),
                            uri = Uri.fromFile(downloadedFile).toString(),
                            name = downloadedFile.name,
                            mimeType = finalMime,
                            dateAddedSeconds = System.currentTimeMillis() / 1000L,
                            dateTakenMillis = System.currentTimeMillis(),
                            sizeBytes = downloadedFile.length(),
                            width = 0,
                            height = 0,
                            relativePath = "Locked",
                            isVideo = isVideo && !effectiveAudioOnly,
                            durationMillis = 0L
                        )
                        val locked = vaultMgr.lockItems(listOf(fakeMediaItem))
                        if (locked.isNotEmpty()) routedToVault = true
                    }
                }

                if (!routedToVault) {
                    val inboxItem = MediaInboxItem(
                        id = itemId,
                        title = resolvedTitle,
                        localUri = downloadedFile.absolutePath,
                        sourceUrl = mediaUrl,
                        isVideo = isVideo && !effectiveAudioOnly,
                        isAudio = isAudio || effectiveAudioOnly,
                        isStream = isStream,
                        format = downloadedFile.extension.takeIf { it.isNotBlank() }?.uppercase() ?: format,
                        sizeBytes = downloadedFile.length(),
                        dateAddedMillis = System.currentTimeMillis()
                    )

                    MediaInboxRepository.getInstance(applicationContext).addItemToStack(
                        stackId = stackId,
                        item = inboxItem,
                        stackTitle = stackTitle,
                        sourceDomain = sourceDomain,
                        sourceUrl = sourceUrl
                    )

                    val albumId = (downloadOptions?.targetAlbumId ?: settings.defaultAlbumId).takeIf { it.isNotBlank() }
                    if (albumId != null) {
                        runCatching {
                            MediaCollectionsPreferencesManager(applicationContext)
                                .addMediaToCustomAlbum(albumId, downloadedFile.absolutePath)
                        }
                    }
                }

                DownloadActivityManager.markFinished(
                    itemId = itemId,
                    localUri = downloadedFile.absolutePath,
                    sizeBytes = downloadedFile.length()
                )
                DownloaderDiagnostics.info(TAG, "Finished $resolvedTitle -> ${downloadedFile.name}")

                Result.success(
                    workDataOf(
                        KEY_LOCAL_URI to downloadedFile.absolutePath,
                        KEY_SIZE_BYTES to downloadedFile.length(),
                        KEY_ITEM_ID to itemId
                    )
                )
            } else {
                val finalError = errorDiagnostic ?: "Download failed for $mediaUrl"
                val statusCode = okHttpResult?.statusCode ?: -1
                val retryAfter = okHttpResult?.retryAfterSeconds

                val currentItem = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
                val isPausedOrCancelled = isStopped ||
                    currentItem?.status == DownloadActivityStatus.PAUSED ||
                    currentItem?.errorMessage == "Cancelled by user"

                if (!isPausedOrCancelled) {
                    DownloaderDiagnostics.error(TAG, "Failed $resolvedTitle: $finalError")
                    DownloadActivityManager.handleDownloadFailure(
                        context = applicationContext,
                        itemId = itemId,
                        statusCode = statusCode,
                        errorMessage = finalError,
                        retryAfterSeconds = retryAfter
                    )
                }
                Result.failure(workDataOf(KEY_ERROR to finalError))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in MediaDownloadWorker for $mediaUrl", e)
            val err = e.message ?: "Unknown error"
            val currentItem = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
            val isPausedOrCancelled = isStopped ||
                e is kotlinx.coroutines.CancellationException ||
                currentItem?.status == DownloadActivityStatus.PAUSED ||
                currentItem?.errorMessage == "Cancelled by user"

            if (!isPausedOrCancelled) {
                DownloaderDiagnostics.error(TAG, "Exception for $title: $err")
                DownloadActivityManager.handleDownloadFailure(
                    context = applicationContext,
                    itemId = itemId,
                    statusCode = -1,
                    errorMessage = err,
                    retryAfterSeconds = null
                )
            }
            Result.failure(workDataOf(KEY_ERROR to err))
        } finally {
            TaskPayloadStore.delete(applicationContext, itemId)
            DownloadQueueManager.releaseConcurrencySlot(itemId)
            DownloadQueueManager.markDownloadCompleted(itemId)
        }
    }

    companion object {
        const val TAG = "MediaDownloadWorker"
        const val WORK_TAG_DOWNLOAD = "media_download"

        const val KEY_MEDIA_URL = "media_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_REFERER = "referer"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_TITLE = "title"
        const val KEY_IS_VIDEO = "is_video"
        const val KEY_IS_AUDIO = "is_audio"
        const val KEY_IS_STREAM = "is_stream"
        const val KEY_FORMAT = "format"
        const val KEY_STACK_ID = "stack_id"
        const val KEY_STACK_TITLE = "stack_title"
        const val KEY_SOURCE_DOMAIN = "source_domain"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_SOURCE_ENGINE = "source_engine"
        const val KEY_HEADERS_JSON = "headers_json"

        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_READ = "bytes_read"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS = "status"
        const val KEY_LOCAL_URI = "local_uri"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_ERROR = "error"

        const val SILENT_CHANNEL_ID = "media_download_silent_channel"
        const val NOTIFICATION_ID = 4001
    }
}
