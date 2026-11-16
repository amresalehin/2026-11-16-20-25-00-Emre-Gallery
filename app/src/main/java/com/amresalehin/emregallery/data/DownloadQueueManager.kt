package com.amresalehin.emregallery.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages persistent media downloads through Android Jetpack WorkManager.
 * Schedules, tracks, limits concurrency, and cancels background downloads with user-configured constraints.
 */
object DownloadQueueManager {

    private val _inMemoryDownloading = kotlinx.coroutines.flow.MutableStateFlow(false)
    val inMemoryDownloading: kotlinx.coroutines.flow.StateFlow<Boolean> = _inMemoryDownloading

    private val activeDownloadCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeItemIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val concurrencyMutex = Mutex()
    private val runningSlotIds = mutableSetOf<String>()

    private fun buildConstraints(wifiOnly: Boolean): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    private fun sha256Short(input: String): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            digest.take(12).joinToString("") { "%02x".format(it) }
        }.getOrDefault(input.hashCode().toString())
    }

    suspend fun acquireConcurrencySlot(context: Context, itemId: String) {
        val maxConcurrent = DownloaderPreferencesManager.getSettings(context).maxConcurrentDownloads.coerceIn(1, 5)
        while (true) {
            val acquired = concurrencyMutex.withLock {
                if (runningSlotIds.contains(itemId) || runningSlotIds.size < maxConcurrent) {
                    runningSlotIds.add(itemId)
                    true
                } else {
                    false
                }
            }
            if (acquired) return
            delay(250L)
        }
    }

    suspend fun releaseConcurrencySlot(itemId: String) {
        concurrencyMutex.withLock {
            runningSlotIds.remove(itemId)
        }
    }

    fun isDownloading(itemId: String): Boolean {
        if (activeItemIds.contains(itemId)) return true
        val activity = DownloadActivityManager.activities.value.firstOrNull { it.id == itemId }
        return activity?.status == DownloadActivityStatus.DOWNLOADING || activity?.status == DownloadActivityStatus.RETRYING
    }

    fun markDownloadStarted(count: Int = 1, itemId: String? = null) {
        if (itemId != null) activeItemIds.add(itemId)
        activeDownloadCount.addAndGet(count)
        _inMemoryDownloading.value = true
    }

    fun markDownloadCompleted(itemId: String? = null) {
        if (itemId != null) activeItemIds.remove(itemId)
        val remaining = activeDownloadCount.decrementAndGet()
        if (remaining <= 0) {
            activeDownloadCount.set(0)
            _inMemoryDownloading.value = false
        }
    }

    private fun serializeHeaders(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return runCatching {
            val json = JSONObject()
            headers.forEach { (k, v) ->
                // Guard against exceeding the 10KB WorkManager Data limit by capping individual header values
                if (v.length <= 2048) {
                    json.put(k, v)
                }
            }
            json.toString().takeIf { it.length <= 4096 }
        }.getOrNull()
    }

    fun enqueueDownload(
        context: Context,
        item: ScrapedMediaItem,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null,
        isRetry: Boolean = false,
        options: DownloadOptions? = null
    ): UUID {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = item.provenance.pageUrl.takeIf { it.isNotBlank() } ?: sourceUrl ?: referer ?: item.url
        val headersJson = serializeHeaders(item.downloadHeaders)
        val settings = DownloaderPreferencesManager.getCachedSettings()

        TaskPayloadStore.save(context, item.id, item.downloadHeaders, options)

        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.url)
            .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title)
            .putString(MediaDownloadWorker.KEY_REFERER, referer ?: url)
            .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
            .putString(MediaDownloadWorker.KEY_TITLE, item.title)
            .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
            .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
            .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
            .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
            .putString(MediaDownloadWorker.KEY_STACK_ID, sId)
            .putString(MediaDownloadWorker.KEY_STACK_TITLE, sTitle)
            .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, domain)
            .putString(MediaDownloadWorker.KEY_SOURCE_URL, url)
            .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine)
            .apply {
                if (!headersJson.isNullOrBlank()) {
                    putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                }
            }
            .build()

        val dedupKey = MediaClassifier.deduplicationKey(item.url)
        val uniqueWorkName = "download_${sha256Short(dedupKey)}"

        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(buildConstraints(settings.wifiOnly))
            .setInputData(inputData)
            .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
            .addTag("item_${item.id}")
            .addTag(uniqueWorkName)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            if (isRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        markDownloadStarted(count = 1, itemId = item.id)

        DownloadActivityManager.initialize(context)
        DownloadActivityManager.recordQueued(
            id = item.id,
            title = item.title,
            mediaUrl = item.url,
            thumbnailUrl = item.thumbnailUrl ?: item.url,
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            isStream = item.isStream,
            format = item.format,
            workId = request.id.toString(),
            isRetry = isRetry,
            stackId = sId,
            stackTitle = sTitle,
            sourceDomain = domain,
            sourceUrl = url
        )

        return request.id
    }

    fun enqueueBatch(
        context: Context,
        items: List<ScrapedMediaItem>,
        referer: String? = null,
        stackId: String? = null,
        stackTitle: String? = null,
        sourceDomain: String? = null,
        sourceUrl: String? = null,
        options: DownloadOptions? = null
    ): List<UUID> {
        val sId = stackId ?: "stack_${System.currentTimeMillis()}"
        val sTitle = stackTitle ?: sourceDomain ?: "Discovered Media"
        val domain = sourceDomain ?: ""
        val url = sourceUrl ?: referer ?: ""
        val settings = DownloaderPreferencesManager.getCachedSettings()

        val workManager = WorkManager.getInstance(context)
        val filteredByArchive = if (settings.useDownloadArchive) {
            items.filter { item -> !DownloadArchiveStore.isArchived(context, item.url) }
        } else {
            items
        }
        val distinctItems = MediaClassifier.deduplicateScrapedItems(filteredByArchive)

        val workIds = distinctItems.map { item ->
            val itemSourceUrl = item.provenance.pageUrl.takeIf { it.isNotBlank() } ?: url
            val headersJson = serializeHeaders(item.downloadHeaders)
            TaskPayloadStore.save(context, item.id, item.downloadHeaders, options)

            val inputData = Data.Builder()
                .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.url)
                .putString(MediaDownloadWorker.KEY_FILE_NAME, item.title)
                .putString(MediaDownloadWorker.KEY_REFERER, referer ?: itemSourceUrl)
                .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
                .putString(MediaDownloadWorker.KEY_TITLE, item.title)
                .putBoolean(MediaDownloadWorker.KEY_IS_VIDEO, item.isVideo)
                .putBoolean(MediaDownloadWorker.KEY_IS_AUDIO, item.isAudio)
                .putBoolean(MediaDownloadWorker.KEY_IS_STREAM, item.isStream)
                .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
                .putString(MediaDownloadWorker.KEY_STACK_ID, sId)
                .putString(MediaDownloadWorker.KEY_STACK_TITLE, sTitle)
                .putString(MediaDownloadWorker.KEY_SOURCE_DOMAIN, domain)
                .putString(MediaDownloadWorker.KEY_SOURCE_URL, itemSourceUrl)
                .putString(MediaDownloadWorker.KEY_SOURCE_ENGINE, item.provenance.extractorEngine)
                .apply {
                    if (!headersJson.isNullOrBlank()) {
                        putString(MediaDownloadWorker.KEY_HEADERS_JSON, headersJson)
                    }
                }
                .build()

            val dedupKey = MediaClassifier.deduplicationKey(item.url)
            val uniqueWorkName = "download_${sha256Short(dedupKey)}"

            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setConstraints(buildConstraints(settings.wifiOnly))
                .setInputData(inputData)
                .addTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
                .addTag("item_${item.id}")
                .addTag(uniqueWorkName)
                .build()

            workManager.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.KEEP,
                request
            )
            markDownloadStarted(count = 1, itemId = item.id)
            request.id
        }

        if (distinctItems.isNotEmpty()) {
            DownloadActivityManager.initialize(context)
            DownloadActivityManager.recordBatchQueued(
                items = distinctItems,
                workIds = workIds,
                stackId = sId,
                stackTitle = sTitle,
                sourceDomain = domain,
                sourceUrl = url
            )
        }

        return workIds
    }

    fun getActiveDownloadsFlow(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
    }

    fun cancelDownload(context: Context, workId: UUID) {
        runCatching {
            WorkManager.getInstance(context).cancelWorkById(workId)
        }
        markDownloadCompleted()
    }

    fun cancelByItemId(context: Context, itemId: String) {
        GalleryDlEngine.cancelJob(itemId)
        MediaStreamMuxer.cancel(itemId)
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag("item_$itemId")
        }
        markDownloadCompleted(itemId)
    }

    fun cancelAll(context: Context) {
        for (id in activeItemIds.toList()) {
            GalleryDlEngine.cancelJob(id)
            MediaStreamMuxer.cancel(id)
        }
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(MediaDownloadWorker.WORK_TAG_DOWNLOAD)
        }
        activeItemIds.clear()
        activeDownloadCount.set(0)
        _inMemoryDownloading.value = false
    }
}
