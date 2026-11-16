package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.BrowserSearchEngine
import com.amresalehin.emregallery.data.CookiesTxtManager
import com.amresalehin.emregallery.data.DownloadArchiveStore
import com.amresalehin.emregallery.data.DownloadOptions
import com.amresalehin.emregallery.data.DownloaderDiagnostics
import com.amresalehin.emregallery.data.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.FilenameTemplate
import com.amresalehin.emregallery.data.FilenameTemplateHelper
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.TaskPayloadStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloaderUpgradesTest {

    @Test
    fun filenameTemplateHelperSanitizesTraversalAndFormatsTokens() {
        val rendered = FilenameTemplateHelper.formatFilename(
            template = FilenameTemplate.UPLOADER_TITLE,
            customPattern = "{title}.{ext}",
            title = "../../etc/passwd: Clip?.mp4",
            uploader = "Creator/Name*",
            domain = "example.com",
            dateMillis = 1700000000000L,
            id = "abc123456789",
            ext = "MP4"
        )
        assertFalse(rendered.contains(".."))
        assertFalse(rendered.contains("/"))
        assertFalse(rendered.contains("\\"))
        assertFalse(rendered.contains(":"))
        assertFalse(rendered.contains("?"))
        assertTrue(rendered.endsWith(".mp4"))
        assertTrue(rendered.contains("Creator_Name"))

        val custom = FilenameTemplateHelper.formatFilename(
            template = FilenameTemplate.CUSTOM,
            customPattern = "{domain}_{id}_{title}.{ext}",
            title = "Trailer",
            uploader = null,
            domain = "vimeo.com",
            dateMillis = 1700000000000L,
            id = "998877665544",
            ext = "mkv"
        )
        assertEquals("vimeo.com_998877665544_Trailer.mkv", custom)
    }

    @Test
    fun netscapeCookiesParserHandlesStandardAndHttpOnlyEntries() {
        val sample = """
            # Netscape HTTP Cookie File
            .example.com	TRUE	/	TRUE	1893456000	session_id	abc123xyz
            #HttpOnly_media.example.org	FALSE	/video	TRUE	1893456000	auth_token	tok_987
            invalid_line_without_tabs
        """.trimIndent()

        val cookies = CookiesTxtManager.parseNetscapeCookies(sample)
        assertEquals(2, cookies.size)
        assertEquals("example.com", cookies[0].domain)
        assertEquals("session_id", cookies[0].name)
        assertEquals("abc123xyz", cookies[0].value)
        assertTrue(cookies[0].secure)

        assertEquals("media.example.org", cookies[1].domain)
        assertEquals("/video", cookies[1].path)
        assertEquals("auth_token", cookies[1].name)
        assertEquals("tok_987", cookies[1].value)

        val applied = CookiesTxtManager.applyCookiesToJar(sample)
        assertEquals(2, applied)
        val header = OkHttpDownloader.sharedCookieJar.getCookieString("https://example.com/feed")
        assertTrue(header.contains("session_id=abc123xyz"))
    }

    @Test
    fun downloadArchiveStoreCanonicalizesYouTubeAndTracksCount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DownloadArchiveStore.clear(context)
        try {
            assertEquals(0, DownloadArchiveStore.count(context))
            DownloadArchiveStore.record(
                context = context,
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share",
                title = "Sample Video"
            )
            assertEquals(1, DownloadArchiveStore.count(context))
            assertTrue(DownloadArchiveStore.isArchived(context, "https://youtu.be/dQw4w9WgXcQ"))
            assertTrue(DownloadArchiveStore.isArchived(context, "https://m.youtube.com/shorts/dQw4w9WgXcQ"))
            assertFalse(DownloadArchiveStore.isArchived(context, "https://youtu.be/otherVideo1"))

            DownloadArchiveStore.clear(context)
            assertEquals(0, DownloadArchiveStore.count(context))
        } finally {
            DownloadArchiveStore.clear(context)
        }
    }

    @Test
    fun taskPayloadStoreAndDownloadOptionsRoundtrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val taskId = "test_task_${System.currentTimeMillis()}"
        val options = DownloadOptions(
            formatId = "137+140",
            maxHeight = 1080,
            preferredContainer = "MKV",
            audioOnly = false,
            audioFormat = "OPUS",
            audioBitrateKbps = 256,
            writeSubtitles = true,
            embedSubtitles = true,
            subtitleLangs = "en,es",
            embedMetadata = true,
            embedThumbnail = true,
            sponsorBlock = true,
            targetAlbumId = "album_custom_1",
            addToVault = true
        )
        val headers = mapOf("Referer" to "https://example.com/page", "X-Token" to "secret")
        try {
            TaskPayloadStore.save(context, taskId, headers, options)
            val loaded = TaskPayloadStore.load(context, taskId)
            assertNotNull(loaded)
            assertEquals("https://example.com/page", loaded!!.headers["Referer"])
            assertEquals("secret", loaded.headers["X-Token"])
            assertNotNull(loaded.options)
            assertEquals("137+140", loaded.options!!.formatId)
            assertEquals(1080, loaded.options!!.maxHeight)
            assertEquals("MKV", loaded.options!!.preferredContainer)
            assertEquals("en,es", loaded.options!!.subtitleLangs)
            assertEquals(true, loaded.options!!.sponsorBlock)
            assertEquals("album_custom_1", loaded.options!!.targetAlbumId)
            assertEquals(true, loaded.options!!.addToVault)
        } finally {
            TaskPayloadStore.delete(context, taskId)
            assertNull(TaskPayloadStore.load(context, taskId))
        }
    }

    @Test
    fun okHttpDownloaderNetworkConfigProbeAndThrottledDownloadWorkWithMockWebServer(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        server.start()
        try {
            OkHttpDownloader.configureNetwork(proxyUrl = "", userAgent = "EmreTestAgent/2.0")

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4; codecs=\"avc1\"")
            )
            val probedMime = OkHttpDownloader.probeContentType(server.url("/clip").toString())
            assertEquals("video/mp4", probedMime)
            val headReq = server.takeRequest()
            assertEquals("HEAD", headReq.method)
            assertEquals("EmreTestAgent/2.0", headReq.getHeader("User-Agent"))

            val payload = ByteArray(16384) { (it % 127).toByte() }
            // Valid PNG/binary header bytes or generic MP4 ftyp header so MediaValidator passes
            payload[4] = 'f'.code.toByte()
            payload[5] = 't'.code.toByte()
            payload[6] = 'y'.code.toByte()
            payload[7] = 'p'.code.toByte()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(payload))
            )

            val res = OkHttpDownloader.downloadMediaFileDetailed(
                context = context,
                mediaUrl = server.url("/clip.mp4").toString(),
                fileName = "clip.mp4",
                downloadId = "test_dl_${System.currentTimeMillis()}",
                maxBytesPerSec = 512 * 1024L
            )
            assertTrue("Download should succeed: ${res.errorMessage}", res.isSuccessful)
            assertNotNull(res.file)
            assertEquals(payload.size.toLong(), res.file!!.length())
            res.file?.delete()
        } finally {
            OkHttpDownloader.configureNetwork("", "")
            server.shutdown()
        }
    }

    @Test
    fun mediaInboxRepositoryPreservesDistinctItemsWithSameSourceUrlAndDeletesReplacedFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = MediaInboxRepository.getInstance(context)
        val stackId = "test_stack_${System.currentTimeMillis()}"
        val sharedPageUrl = "https://example.com/album/${System.currentTimeMillis()}"
        val oldFile = File(context.cacheDir, "old_item_${System.currentTimeMillis()}.jpg").apply {
            writeText("old")
        }
        val newFile = File(context.cacheDir, "new_item_${System.currentTimeMillis()}.jpg").apply {
            writeText("new")
        }
        val secondFile = File(context.cacheDir, "second_item_${System.currentTimeMillis()}.jpg").apply {
            writeText("second")
        }

        try {
            repo.addItemToStack(
                stackId = stackId,
                item = MediaInboxItem(
                    id = "item_a",
                    title = "Photo A",
                    localUri = oldFile.absolutePath,
                    sourceUrl = sharedPageUrl,
                    isVideo = false
                ),
                stackTitle = "Album",
                sourceDomain = "example.com",
                sourceUrl = sharedPageUrl
            )
            // Distinct item sharing the exact same sourceUrl must NOT overwrite Photo A
            repo.addItemToStack(
                stackId = stackId,
                item = MediaInboxItem(
                    id = "item_b",
                    title = "Photo B",
                    localUri = secondFile.absolutePath,
                    sourceUrl = sharedPageUrl,
                    isVideo = false
                ),
                stackTitle = "Album",
                sourceDomain = "example.com",
                sourceUrl = sharedPageUrl
            )
            var stack = repo.stacks.value.firstOrNull { it.id == stackId }
            assertNotNull(stack)
            assertEquals(2, stack!!.items.size)

            // Updating item_a with a new file path should delete the stale oldFile
            repo.addItemToStack(
                stackId = stackId,
                item = MediaInboxItem(
                    id = "item_a",
                    title = "Photo A",
                    localUri = newFile.absolutePath,
                    sourceUrl = sharedPageUrl,
                    isVideo = false
                ),
                stackTitle = "Album",
                sourceDomain = "example.com",
                sourceUrl = sharedPageUrl
            )
            stack = repo.stacks.value.firstOrNull { it.id == stackId }
            assertEquals(2, stack!!.items.size)
            assertFalse("Stale file should be deleted when replaced", oldFile.exists())
            assertTrue("New file should remain", newFile.exists())
        } finally {
            repo.deleteStack(stackId)
            oldFile.delete()
            newFile.delete()
            secondFile.delete()
        }
    }

    @Test
    fun parseSrcsetPreservesUrlsContainingTransformCommas() {
        val srcset = "https://res.cloudinary.com/demo/image/upload/w_400,c_scale,q_auto/sample.jpg 400w, " +
            "https://res.cloudinary.com/demo/image/upload/w_1200,c_scale,q_auto/sample.jpg 1200w"
        val entries = MediaClassifier.parseSrcset(srcset, "https://example.com")
        assertEquals(2, entries.size)
        assertEquals(
            "https://res.cloudinary.com/demo/image/upload/w_400,c_scale,q_auto/sample.jpg",
            entries[0].first
        )
        assertEquals("400w", entries[0].second)
        assertEquals(
            "https://res.cloudinary.com/demo/image/upload/w_1200,c_scale,q_auto/sample.jpg",
            entries[1].first
        )
        assertEquals("1200w", entries[1].second)
    }

    @Test
    fun browserSearchEngineFormattingAndDiagnosticsBufferWork() {
        val ddg = MediaClassifier.formatQueryOrUrl("kotlin coroutines", BrowserSearchEngine.DUCKDUCKGO)
        val bing = MediaClassifier.formatQueryOrUrl("kotlin coroutines", BrowserSearchEngine.BING)
        assertTrue(ddg.startsWith("https://duckduckgo.com/?q="))
        assertTrue(bing.startsWith("https://www.bing.com/search?q="))

        DownloaderDiagnostics.clear()
        DownloaderDiagnostics.info("Test", "Initialized engine")
        DownloaderDiagnostics.error("Test", "Failed request")
        assertEquals(2, DownloaderDiagnostics.entries.value.size)
        assertTrue(DownloaderDiagnostics.exportText().contains("Failed request"))
        DownloaderDiagnostics.clear()
    }

    @Test
    fun downloaderPreferencesUpdatePersistsSettings(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DownloaderPreferencesManager.update(context) { cur ->
            cur.copy(
                videoMaxHeight = 1080,
                wifiOnly = true,
                maxConcurrentDownloads = 4,
                browserSearchEngine = BrowserSearchEngine.WIKIPEDIA
            )
        }
        val loaded = DownloaderPreferencesManager.getSettings(context)
        assertEquals(1080, loaded.videoMaxHeight)
        assertTrue(loaded.wifiOnly)
        assertEquals(4, loaded.maxConcurrentDownloads)
        assertEquals(BrowserSearchEngine.WIKIPEDIA, loaded.browserSearchEngine)

        // Restore defaults
        DownloaderPreferencesManager.update(context) {
            it.copy(
                videoMaxHeight = 0,
                wifiOnly = false,
                maxConcurrentDownloads = 3,
                browserSearchEngine = BrowserSearchEngine.DUCKDUCKGO
            )
        }
    }
}
