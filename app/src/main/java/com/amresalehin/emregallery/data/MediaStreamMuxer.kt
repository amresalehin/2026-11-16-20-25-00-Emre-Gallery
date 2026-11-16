package com.amresalehin.emregallery.data

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Audio/video stream probing, lossless muxing, HLS/DASH stream capture,
 * audio extraction/transcoding, and video post-processing via FFmpegKit.
 */
object MediaStreamMuxer {
    private const val TAG = "MediaStreamMuxer"
    private val activeSessions = ConcurrentHashMap<String, Long>()

    data class ProbeResult(
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val streamCount: Int,
        val durationSeconds: Double = 0.0
    )

    fun cancel(jobId: String) {
        if (jobId.isBlank()) return
        val sessionId = activeSessions.remove(jobId)
        if (sessionId != null) {
            runCatching { FFmpegKit.cancel(sessionId) }
        }
    }

    private fun executeFfmpeg(jobId: String, args: Array<String>): Pair<Boolean, String> {
        if (jobId.isBlank()) {
            val session = FFmpegKit.executeWithArguments(args)
            return ReturnCode.isSuccess(session.returnCode) to (session.allLogsAsString ?: "")
        }
        val latch = CountDownLatch(1)
        var succeeded = false
        var logs = ""
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { completed ->
                succeeded = ReturnCode.isSuccess(completed.returnCode)
                logs = completed.allLogsAsString ?: ""
                latch.countDown()
            },
            { },
            { }
        )
        activeSessions[jobId] = session.sessionId
        try {
            latch.await()
        } catch (_: InterruptedException) {
            FFmpegKit.cancel(session.sessionId)
            Thread.currentThread().interrupt()
            return false to "Interrupted"
        } finally {
            activeSessions.remove(jobId)
        }
        return succeeded to logs
    }

    fun probe(file: File): ProbeResult? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val session = FFprobeKit.executeWithArguments(
                arrayOf(
                    "-v", "error",
                    "-show_entries", "stream=codec_type:format=duration",
                    "-of", "json",
                    file.absolutePath
                )
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                Log.w(TAG, "ffprobe failed for ${file.name}: ${session.allLogsAsString}")
                return null
            }
            val root = JSONObject(session.output ?: "{}")
            val streams = root.optJSONArray("streams") ?: return null
            var hasVideo = false
            var hasAudio = false
            for (index in 0 until streams.length()) {
                when (streams.optJSONObject(index)?.optString("codec_type")) {
                    "video" -> hasVideo = true
                    "audio" -> hasAudio = true
                }
            }
            val duration = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: 0.0
            ProbeResult(hasVideo, hasAudio, streams.length(), duration)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to probe ${file.name}", t)
            null
        }
    }

    /**
     * Muxes one video-only file and one audio-only file with stream copy.
     * MP4 is tried first; Matroska is a compatibility fallback for codecs MP4 cannot carry.
     */
    fun mux(video: File, audio: File, target: File, jobId: String = ""): File? {
        if (!video.isFile || video.length() <= 0L || !audio.isFile || audio.length() <= 0L) return null
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }

        val preferred = if (target.extension.equals("mp4", true) || target.extension.equals("mkv", true)) target else File(
            target.parentFile,
            target.nameWithoutExtension + ".mp4"
        )
        val candidates = listOf(
            preferred,
            File(preferred.parentFile, preferred.nameWithoutExtension + ".mkv")
        ).distinctBy { it.absolutePath }

        for (output in candidates) {
            runCatching { if (output.exists()) output.delete() }
            val args = mutableListOf(
                "-nostdin", "-hide_banner", "-y",
                "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c", "copy"
            )
            if (output.extension.equals("mp4", true)) {
                args += listOf("-movflags", "+faststart")
            }
            args += output.absolutePath

            try {
                val (ok, logs) = executeFfmpeg(jobId, args.toTypedArray())
                if (ok && output.isFile && output.length() > 0L) {
                    val result = probe(output)
                    if (result?.hasVideo == true && result.hasAudio) return output
                }
                Log.w(TAG, "Mux failed for ${output.name}: $logs")
            } catch (t: Throwable) {
                Log.e(TAG, "Mux exception for ${output.name}", t)
            }
            runCatching { output.delete() }
        }
        return null
    }

    /**
     * Downloads an HLS (.m3u8) or DASH (.mpd) manifest stream with custom headers via FFmpegKit.
     */
    fun downloadStreamWithFfmpeg(
        url: String,
        headers: Map<String, String> = emptyMap(),
        target: File,
        jobId: String = "",
        onProgress: ((Long) -> Unit)? = null
    ): File? {
        if (url.isBlank()) return null
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: OkHttpDownloader.configuredUserAgent.ifBlank { OkHttpDownloader.DEFAULT_USER_AGENT }
        val headerLines = headers.entries
            .filter { !it.key.equals("User-Agent", true) && it.value.isNotBlank() }
            .joinToString("\r\n") { "${it.key}: ${it.value}" }

        val mp4Target = if (target.extension.equals("mp4", true)) target else File(target.parentFile, target.nameWithoutExtension + ".mp4")
        val mkvTarget = File(mp4Target.parentFile, mp4Target.nameWithoutExtension + ".mkv")

        for (out in listOf(mp4Target, mkvTarget)) {
            runCatching { if (out.exists()) out.delete() }
            val args = mutableListOf("-nostdin", "-hide_banner", "-y")
            if (ua.isNotBlank()) {
                args += listOf("-user_agent", ua)
            }
            if (headerLines.isNotBlank()) {
                args += listOf("-headers", "$headerLines\r\n")
            }
            args += listOf("-i", url, "-c", "copy")
            if (out.extension.equals("mp4", true)) {
                args += listOf("-bsf:a", "aac_adtstoasc", "-movflags", "+faststart")
            }
            args += out.absolutePath

            val (ok, logs) = executeFfmpeg(jobId, args.toTypedArray())
            if (ok && out.isFile && out.length() > 0L) {
                val p = probe(out)
                if (p != null && (p.hasVideo || p.hasAudio)) {
                    onProgress?.invoke(out.length())
                    return out
                }
            }
            Log.w(TAG, "Stream FFmpeg download failed for ${out.name}: $logs")
            runCatching { out.delete() }
        }
        return null
    }

    /**
     * Converts / extracts audio into the requested target format (mp3, m4a, opus, flac) with optional
     * metadata and embedded artwork.
     */
    fun transcodeAudio(
        input: File,
        target: File,
        targetFormat: String,
        bitrateKbps: Int = 192,
        coverArt: File? = null,
        metadata: Map<String, String> = emptyMap(),
        jobId: String = ""
    ): File? {
        if (!input.isFile || input.length() <= 0L) return null
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val fmt = targetFormat.lowercase(Locale.ROOT)
        val br = "${bitrateKbps.coerceIn(64, 320)}k"

        fun runPass(includeCover: Boolean): Boolean {
            runCatching { if (target.exists()) target.delete() }
            val args = mutableListOf("-nostdin", "-hide_banner", "-y", "-i", input.absolutePath)
            val attachCover = includeCover && coverArt != null && coverArt.isFile && coverArt.length() > 0L &&
                fmt in setOf("mp3", "m4a", "flac")
            if (attachCover) {
                args += listOf("-i", coverArt!!.absolutePath, "-map", "0:a:0", "-map", "1:v:0", "-c:v", "copy", "-disposition:v:0", "attached_pic")
                if (fmt == "mp3") args += listOf("-id3v2_version", "3")
            } else {
                args += listOf("-vn", "-map", "0:a:0")
            }
            when (fmt) {
                "mp3" -> args += listOf("-c:a", "libmp3lame", "-b:a", br)
                "m4a", "aac" -> args += listOf("-c:a", "aac", "-b:a", br, "-movflags", "+faststart")
                "opus" -> args += listOf("-c:a", "libopus", "-b:a", br)
                "flac" -> args += listOf("-c:a", "flac")
                else -> args += listOf("-c:a", "copy")
            }
            metadata.forEach { (k, v) ->
                if (v.isNotBlank()) args += listOf("-metadata", "$k=$v")
            }
            args += target.absolutePath
            val (ok, _) = executeFfmpeg(jobId, args.toTypedArray())
            return ok && target.isFile && target.length() > 0L && (probe(target)?.hasAudio == true)
        }

        if (runPass(includeCover = coverArt != null)) return target
        if (coverArt != null && runPass(includeCover = false)) return target
        runCatching { target.delete() }
        return null
    }

    /**
     * Post-processes a downloaded video: embeds soft subtitles, metadata tags, cover thumbnail,
     * and removes SponsorBlock segments when present.
     */
    fun postProcessVideo(
        input: File,
        target: File,
        subtitleFiles: List<File> = emptyList(),
        coverArt: File? = null,
        metadata: Map<String, String> = emptyMap(),
        sponsorSegments: List<SponsorSegment> = emptyList(),
        jobId: String = ""
    ): File? {
        if (!input.isFile || input.length() <= 0L) return null
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }

        var workingInput = input
        val validSegs = sponsorSegments.filter { it.endSeconds > it.startSeconds + 0.25 }.sortedBy { it.startSeconds }
        if (validSegs.isNotEmpty()) {
            val probed = probe(input)
            val totalDur = probed?.durationSeconds ?: 0.0
            if (probed != null && totalDur > 1.0 && probed.hasVideo) {
                val keepRanges = mutableListOf<Pair<Double, Double>>()
                var cursor = 0.0
                for (seg in validSegs) {
                    val s = seg.startSeconds.coerceIn(cursor, totalDur)
                    val e = seg.endSeconds.coerceIn(s, totalDur)
                    if (s - cursor >= 0.4) {
                        keepRanges.add(cursor to s)
                    }
                    cursor = maxOf(cursor, e)
                }
                if (totalDur - cursor >= 0.4) {
                    keepRanges.add(cursor to totalDur)
                }
                if (keepRanges.isNotEmpty() && !(keepRanges.size == 1 && keepRanges[0].first == 0.0 && keepRanges[0].second >= totalDur - 0.1)) {
                    val cutTemp = File(target.parentFile, "sb_cut_${System.currentTimeMillis()}.${input.extension.ifBlank { "mp4" }}")
                    val filterParts = StringBuilder()
                    val concatInputs = StringBuilder()
                    keepRanges.forEachIndexed { idx, (start, end) ->
                        val sStr = String.format(Locale.US, "%.3f", start)
                        val eStr = String.format(Locale.US, "%.3f", end)
                        filterParts.append("[0:v]trim=start=$sStr:end=$eStr,setpts=PTS-STARTPTS[v$idx];")
                        if (probed.hasAudio) {
                            filterParts.append("[0:a]atrim=start=$sStr:end=$eStr,asetpts=PTS-STARTPTS[a$idx];")
                            concatInputs.append("[v$idx][a$idx]")
                        } else {
                            concatInputs.append("[v$idx]")
                        }
                    }
                    val aFlag = if (probed.hasAudio) 1 else 0
                    val outMap = if (probed.hasAudio) "[outv][outa]" else "[outv]"
                    filterParts.append("${concatInputs}concat=n=${keepRanges.size}:v=1:a=$aFlag$outMap")
                    val sbArgs = mutableListOf("-nostdin", "-hide_banner", "-y", "-i", input.absolutePath, "-filter_complex", filterParts.toString(), "-map", "[outv]")
                    if (probed.hasAudio) sbArgs += listOf("-map", "[outa]")
                    sbArgs += cutTemp.absolutePath
                    val (cutOk, _) = executeFfmpeg(jobId, sbArgs.toTypedArray())
                    if (cutOk && cutTemp.isFile && cutTemp.length() > 0L) {
                        workingInput = cutTemp
                    } else {
                        runCatching { cutTemp.delete() }
                    }
                }
            }
        }

        val ext = target.extension.lowercase(Locale.ROOT)
        fun runMux(subs: List<File>, cover: File?): Boolean {
            runCatching { if (target.exists()) target.delete() }
            val args = mutableListOf("-nostdin", "-hide_banner", "-y", "-i", workingInput.absolutePath)
            val usableSubs = subs.filter { it.isFile && it.length() > 0L }
            usableSubs.forEach { args += listOf("-i", it.absolutePath) }
            val usableCover = cover?.takeIf { it.isFile && it.length() > 0L && (ext == "mp4" || ext == "mkv") }
            if (usableCover != null) {
                args += listOf("-i", usableCover.absolutePath)
            }
            args += listOf("-map", "0:v:0", "-map", "0:a?")
            usableSubs.indices.forEach { idx ->
                args += listOf("-map", "${idx + 1}:0")
            }
            if (usableCover != null) {
                val coverIdx = 1 + usableSubs.size
                args += listOf("-map", "$coverIdx:0", "-disposition:v:1", "attached_pic")
            }
            args += listOf("-c", "copy")
            if (usableSubs.isNotEmpty()) {
                val subCodec = if (ext == "mp4" || ext == "mov") "mov_text" else "srt"
                args += listOf("-c:s", subCodec)
            }
            metadata.forEach { (k, v) ->
                if (v.isNotBlank()) args += listOf("-metadata", "$k=$v")
            }
            if (ext == "mp4") {
                args += listOf("-movflags", "+faststart")
            }
            args += target.absolutePath
            val (ok, _) = executeFfmpeg(jobId, args.toTypedArray())
            return ok && target.isFile && target.length() > 0L && (probe(target)?.hasVideo == true)
        }

        val succeeded = runMux(subtitleFiles, coverArt) ||
            (subtitleFiles.isNotEmpty() && runMux(emptyList(), coverArt)) ||
            (coverArt != null && runMux(emptyList(), null))

        if (workingInput != input) {
            runCatching { workingInput.delete() }
        }
        return if (succeeded) target else {
            runCatching { target.delete() }
            null
        }
    }
}
