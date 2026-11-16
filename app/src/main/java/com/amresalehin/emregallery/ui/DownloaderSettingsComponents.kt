package com.amresalehin.emregallery.ui

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amresalehin.emregallery.data.BrowserSearchEngine
import com.amresalehin.emregallery.data.CookiesTxtManager
import com.amresalehin.emregallery.data.DownloadArchiveStore
import com.amresalehin.emregallery.data.DownloadOptions
import com.amresalehin.emregallery.data.DownloaderDiagnostics
import com.amresalehin.emregallery.data.DownloaderPreferencesManager
import com.amresalehin.emregallery.data.DownloaderSettings
import com.amresalehin.emregallery.data.EngineUpdateResult
import com.amresalehin.emregallery.data.FilenameTemplate
import com.amresalehin.emregallery.data.FilenameTemplateHelper
import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.GalleryDlStatus
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem
import com.amresalehin.emregallery.data.preferences.MediaCollectionsPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadOptionsBottomSheet(
    selectedItems: List<ScrapedMediaItem>,
    initialOptions: DownloadOptions? = null,
    onDismiss: () -> Unit,
    onConfirm: (DownloadOptions) -> Unit
) {
    val context = LocalContext.current
    val settings by DownloaderPreferencesManager.settingsFlow(context)
        .collectAsState(initial = DownloaderPreferencesManager.getCachedSettings())
    val collectionsManager = remember(context) { MediaCollectionsPreferencesManager(context) }
    val customAlbums by collectionsManager.customAlbums.collectAsState(initial = emptyList())

    var formatId by remember(initialOptions) { mutableStateOf(initialOptions?.formatId) }
    var maxHeight by remember(initialOptions, settings.videoMaxHeight) {
        mutableStateOf(initialOptions?.maxHeight ?: settings.videoMaxHeight)
    }
    var preferredContainer by remember(initialOptions, settings.preferredContainer) {
        mutableStateOf(initialOptions?.preferredContainer ?: settings.preferredContainer)
    }
    var audioOnly by remember(initialOptions, settings.audioOnlyDefault) {
        mutableStateOf(initialOptions?.audioOnly ?: settings.audioOnlyDefault)
    }
    var audioFormat by remember(initialOptions, settings.preferredAudioFormat) {
        mutableStateOf(initialOptions?.audioFormat ?: settings.preferredAudioFormat)
    }
    var audioBitrate by remember(initialOptions, settings.preferredAudioBitrateKbps) {
        mutableIntStateOf(initialOptions?.audioBitrateKbps ?: settings.preferredAudioBitrateKbps)
    }
    var writeSubtitles by remember(initialOptions, settings.downloadSubtitles) {
        mutableStateOf(initialOptions?.writeSubtitles ?: settings.downloadSubtitles)
    }
    var embedSubtitles by remember(initialOptions, settings.embedSubtitles) {
        mutableStateOf(initialOptions?.embedSubtitles ?: settings.embedSubtitles)
    }
    var subtitleLangs by remember(initialOptions, settings.subtitleLangs) {
        mutableStateOf(initialOptions?.subtitleLangs ?: settings.subtitleLangs)
    }
    var embedMetadata by remember(initialOptions, settings.embedMetadata) {
        mutableStateOf(initialOptions?.embedMetadata ?: settings.embedMetadata)
    }
    var embedThumbnail by remember(initialOptions, settings.embedThumbnail) {
        mutableStateOf(initialOptions?.embedThumbnail ?: settings.embedThumbnail)
    }
    var sponsorBlock by remember(initialOptions, settings.sponsorBlockEnabled) {
        mutableStateOf(initialOptions?.sponsorBlock ?: settings.sponsorBlockEnabled)
    }
    var targetAlbumId by remember(initialOptions, settings.defaultAlbumId) {
        mutableStateOf(initialOptions?.targetAlbumId ?: settings.defaultAlbumId)
    }
    var addToVault by remember(initialOptions, settings.defaultAddToVault) {
        mutableStateOf(initialOptions?.addToVault ?: settings.defaultAddToVault)
    }
    var albumMenuExpanded by remember { mutableStateOf(false) }

    val singleItemFormats = remember(selectedItems) {
        if (selectedItems.size == 1) selectedItems.first().availableFormats else emptyList()
    }
    val hasVideoOrStream = remember(selectedItems) {
        selectedItems.any { it.isVideo || it.isStream }
    }

    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .testTag("download_options_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download Options",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${selectedItems.size} item(s) selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close options")
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (singleItemFormats.isNotEmpty()) {
                    SettingsSectionCard(
                        icon = Icons.Default.HighQuality,
                        title = "Stream Format (${singleItemFormats.size})"
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = formatId == null,
                                onClick = { formatId = null },
                                label = { Text("Auto Best") }
                            )
                            singleItemFormats.take(12).forEach { fmt ->
                                val sizeLabel = fmt.fileSizeBytes?.takeIf { it > 0 }?.let {
                                    " (${it / (1024 * 1024)}MB)"
                                }.orEmpty()
                                FilterChip(
                                    selected = formatId == fmt.formatId,
                                    onClick = { formatId = fmt.formatId },
                                    label = { Text("${fmt.label}$sizeLabel") }
                                )
                            }
                        }
                    }
                }

                if (hasVideoOrStream || singleItemFormats.isEmpty()) {
                    SettingsSectionCard(
                        icon = Icons.Default.HighQuality,
                        title = "Video & Container"
                    ) {
                        Text(
                            text = "Max Video Resolution",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val heights = listOf(0 to "Best", 2160 to "2160p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            heights.forEach { (h, label) ->
                                FilterChip(
                                    selected = maxHeight == h,
                                    onClick = { maxHeight = h },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Text(
                            text = "Container",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("AUTO", "MP4", "MKV", "WEBM").forEach { container ->
                                FilterChip(
                                    selected = preferredContainer == container,
                                    onClick = { preferredContainer = container },
                                    label = { Text(container) }
                                )
                            }
                        }
                    }
                }

                SettingsSectionCard(
                    icon = Icons.Default.Audiotrack,
                    title = "Audio Extraction"
                ) {
                    SettingsSwitchRow(
                        label = "Extract audio only",
                        checked = audioOnly,
                        onCheckedChange = { audioOnly = it },
                        testTag = "options_audio_only_switch"
                    )
                    if (audioOnly) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("MP3", "M4A", "OPUS", "FLAC").forEach { fmt ->
                                FilterChip(
                                    selected = audioFormat == fmt,
                                    onClick = { audioFormat = fmt },
                                    label = { Text(fmt) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(128, 192, 256, 320).forEach { kbps ->
                                FilterChip(
                                    selected = audioBitrate == kbps,
                                    onClick = { audioBitrate = kbps },
                                    label = { Text("${kbps}kbps") }
                                )
                            }
                        }
                    }
                }

                SettingsSectionCard(
                    icon = Icons.Default.Subtitles,
                    title = "Subtitles, Metadata & SponsorBlock"
                ) {
                    SettingsSwitchRow(
                        label = "Download subtitles",
                        checked = writeSubtitles,
                        onCheckedChange = { writeSubtitles = it }
                    )
                    if (writeSubtitles) {
                        SettingsSwitchRow(
                            label = "Embed subtitles in video",
                            checked = embedSubtitles,
                            onCheckedChange = { embedSubtitles = it }
                        )
                        OutlinedTextField(
                            value = subtitleLangs,
                            onValueChange = { subtitleLangs = it },
                            label = { Text("Subtitle languages (e.g. en,es)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SettingsSwitchRow(
                        label = "Embed metadata tags",
                        checked = embedMetadata,
                        onCheckedChange = { embedMetadata = it }
                    )
                    SettingsSwitchRow(
                        label = "Embed cover art thumbnail",
                        checked = embedThumbnail,
                        onCheckedChange = { embedThumbnail = it }
                    )
                    SettingsSwitchRow(
                        label = "Remove SponsorBlock segments",
                        checked = sponsorBlock,
                        onCheckedChange = { sponsorBlock = it }
                    )
                }

                SettingsSectionCard(
                    icon = Icons.Default.FolderOpen,
                    title = "Destination & Privacy"
                ) {
                    Box {
                        val selectedAlbumName = customAlbums.firstOrNull { it.id == targetAlbumId }?.fullPath
                            ?: "Inbox Only (Default)"
                        OutlinedButton(
                            onClick = { albumMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Album: $selectedAlbumName",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        DropdownMenu(
                            expanded = albumMenuExpanded,
                            onDismissRequest = { albumMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Inbox Only (Default)") },
                                onClick = {
                                    targetAlbumId = ""
                                    albumMenuExpanded = false
                                }
                            )
                            customAlbums.forEach { album ->
                                DropdownMenuItem(
                                    text = { Text(album.fullPath) },
                                    onClick = {
                                        targetAlbumId = album.id
                                        albumMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    SettingsSwitchRow(
                        label = "Add directly to Locked Vault",
                        checked = addToVault,
                        onCheckedChange = { addToVault = it }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    onConfirm(
                        DownloadOptions(
                            formatId = formatId,
                            maxHeight = maxHeight,
                            preferredContainer = preferredContainer,
                            audioOnly = audioOnly,
                            audioFormat = audioFormat,
                            audioBitrateKbps = audioBitrate,
                            writeSubtitles = writeSubtitles,
                            embedSubtitles = embedSubtitles,
                            subtitleLangs = subtitleLangs.trim().ifBlank { "en" },
                            embedMetadata = embedMetadata,
                            embedThumbnail = embedThumbnail,
                            sponsorBlock = sponsorBlock,
                            targetAlbumId = targetAlbumId.ifBlank { null },
                            addToVault = addToVault
                        )
                    )
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("download_options_confirm_btn")
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Queue Download (${selectedItems.size})",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloaderSettingsBottomSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by DownloaderPreferencesManager.settingsFlow(context)
        .collectAsState(initial = DownloaderPreferencesManager.getCachedSettings())
    val collectionsManager = remember(context) { MediaCollectionsPreferencesManager(context) }
    val customAlbums by collectionsManager.customAlbums.collectAsState(initial = emptyList())

    var proxyDraft by remember(settings.proxyUrl) { mutableStateOf(settings.proxyUrl) }
    var userAgentDraft by remember(settings.customUserAgent) { mutableStateOf(settings.customUserAgent) }
    var subtitleLangsDraft by remember(settings.subtitleLangs) { mutableStateOf(settings.subtitleLangs) }
    var customPatternDraft by remember(settings.customFilenamePattern) { mutableStateOf(settings.customFilenamePattern) }

    var archiveCount by remember { mutableIntStateOf(DownloadArchiveStore.count(context)) }
    var engineStatus by remember { mutableStateOf<GalleryDlStatus?>(null) }
    var isUpdatingYtDlp by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }

    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showCookiesPasteDialog by remember { mutableStateOf(false) }
    var albumMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        engineStatus = withContext(Dispatchers.IO) { GalleryDlEngine.getStatus() }
    }

    fun updateSettings(transform: (DownloaderSettings) -> DownloaderSettings) {
        scope.launch {
            DownloaderPreferencesManager.update(context, transform)
        }
    }

    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            updateSettings { it.copy(safTreeUri = treeUri.toString()) }
        }
    }

    val cookiesFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { fileUri: Uri? ->
        if (fileUri != null) {
            scope.launch(Dispatchers.IO) {
                val content = runCatching {
                    context.contentResolver.openInputStream(fileUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty()
                if (content.isNotBlank()) {
                    DownloaderPreferencesManager.update(context) { it.copy(cookiesTxtContent = content) }
                }
            }
        }
    }

    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .testTag("downloader_settings_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Downloader Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close settings")
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Quality & Audio Defaults
                SettingsSectionCard(
                    icon = Icons.Default.HighQuality,
                    title = "Quality & Audio Defaults"
                ) {
                    Text(
                        text = "Default Max Video Resolution",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0 to "Best", 2160 to "2160p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p").forEach { (h, label) ->
                            FilterChip(
                                selected = settings.videoMaxHeight == h,
                                onClick = { updateSettings { it.copy(videoMaxHeight = h) } },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text(
                        text = "Preferred Video Container",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("AUTO", "MP4", "MKV", "WEBM").forEach { c ->
                            FilterChip(
                                selected = settings.preferredContainer == c,
                                onClick = { updateSettings { it.copy(preferredContainer = c) } },
                                label = { Text(c) }
                            )
                        }
                    }

                    SettingsSwitchRow(
                        label = "Audio-only mode by default",
                        checked = settings.audioOnlyDefault,
                        onCheckedChange = { checked -> updateSettings { it.copy(audioOnlyDefault = checked) } }
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("MP3", "M4A", "OPUS", "FLAC").forEach { fmt ->
                            FilterChip(
                                selected = settings.preferredAudioFormat == fmt,
                                onClick = { updateSettings { it.copy(preferredAudioFormat = fmt) } },
                                label = { Text(fmt) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(128, 192, 256, 320).forEach { kbps ->
                            FilterChip(
                                selected = settings.preferredAudioBitrateKbps == kbps,
                                onClick = { updateSettings { it.copy(preferredAudioBitrateKbps = kbps) } },
                                label = { Text("${kbps}kbps") }
                            )
                        }
                    }
                }

                // 2. Network, Concurrency & Proxy
                SettingsSectionCard(
                    icon = Icons.Default.Speed,
                    title = "Network & Speed"
                ) {
                    SettingsSwitchRow(
                        label = "Download on Wi-Fi (unmetered) only",
                        checked = settings.wifiOnly,
                        onCheckedChange = { checked -> updateSettings { it.copy(wifiOnly = checked) } },
                        testTag = "settings_wifi_only_switch"
                    )

                    Text(
                        text = "Max Concurrent Downloads",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { count ->
                            FilterChip(
                                selected = settings.maxConcurrentDownloads == count,
                                onClick = { updateSettings { it.copy(maxConcurrentDownloads = count) } },
                                label = { Text("$count") }
                            )
                        }
                    }

                    Text(
                        text = "Speed Limit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            0 to "Unlimited",
                            512 to "512 KB/s",
                            1024 to "1 MB/s",
                            2048 to "2 MB/s",
                            5120 to "5 MB/s",
                            10240 to "10 MB/s"
                        ).forEach { (kbps, label) ->
                            FilterChip(
                                selected = settings.speedLimitKbps == kbps,
                                onClick = { updateSettings { it.copy(speedLimitKbps = kbps) } },
                                label = { Text(label) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = proxyDraft,
                        onValueChange = { proxyDraft = it },
                        label = { Text("HTTP / SOCKS Proxy URL") },
                        placeholder = { Text("http://127.0.0.1:8080 or socks5://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = userAgentDraft,
                        onValueChange = { userAgentDraft = it },
                        label = { Text("Custom User-Agent (blank = default)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (proxyDraft != settings.proxyUrl || userAgentDraft != settings.customUserAgent) {
                        Button(
                            onClick = {
                                updateSettings {
                                    it.copy(
                                        proxyUrl = proxyDraft.trim(),
                                        customUserAgent = userAgentDraft.trim()
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Apply Network Settings")
                        }
                    }
                }

                // 3. Post-Processing, SponsorBlock & Browser
                SettingsSectionCard(
                    icon = Icons.Default.Subtitles,
                    title = "Post-Processing & Extraction"
                ) {
                    SettingsSwitchRow(
                        label = "Download subtitles",
                        checked = settings.downloadSubtitles,
                        onCheckedChange = { checked -> updateSettings { it.copy(downloadSubtitles = checked) } }
                    )
                    if (settings.downloadSubtitles) {
                        SettingsSwitchRow(
                            label = "Embed subtitles into video",
                            checked = settings.embedSubtitles,
                            onCheckedChange = { checked -> updateSettings { it.copy(embedSubtitles = checked) } }
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = subtitleLangsDraft,
                                onValueChange = { subtitleLangsDraft = it },
                                label = { Text("Languages (comma separated)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (subtitleLangsDraft != settings.subtitleLangs) {
                                TextButton(
                                    onClick = { updateSettings { it.copy(subtitleLangs = subtitleLangsDraft.trim().ifBlank { "en" }) } },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                    SettingsSwitchRow(
                        label = "Embed metadata tags",
                        checked = settings.embedMetadata,
                        onCheckedChange = { checked -> updateSettings { it.copy(embedMetadata = checked) } }
                    )
                    SettingsSwitchRow(
                        label = "Embed cover thumbnail",
                        checked = settings.embedThumbnail,
                        onCheckedChange = { checked -> updateSettings { it.copy(embedThumbnail = checked) } }
                    )
                    SettingsSwitchRow(
                        label = "SponsorBlock segment removal",
                        checked = settings.sponsorBlockEnabled,
                        onCheckedChange = { checked -> updateSettings { it.copy(sponsorBlockEnabled = checked) } }
                    )
                    if (settings.sponsorBlockEnabled) {
                        val allCats = listOf("sponsor", "selfpromo", "intro", "outro", "interaction", "preview")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            allCats.forEach { cat ->
                                val active = cat in settings.sponsorBlockCategories
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        updateSettings { cur ->
                                            val updated = if (active) cur.sponsorBlockCategories - cat else cur.sponsorBlockCategories + cat
                                            cur.copy(sponsorBlockCategories = updated.ifEmpty { setOf("sponsor") })
                                        }
                                    },
                                    label = { Text(cat) }
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Browser Search Engine",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrowserSearchEngine.entries.forEach { engine ->
                            FilterChip(
                                selected = settings.browserSearchEngine == engine,
                                onClick = { updateSettings { it.copy(browserSearchEngine = engine) } },
                                label = { Text(engine.label) }
                            )
                        }
                    }

                    Text(
                        text = "Playlist Extraction Limit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 50, 100, 250).forEach { limit ->
                            FilterChip(
                                selected = settings.playlistLimit == limit,
                                onClick = { updateSettings { it.copy(playlistLimit = limit) } },
                                label = { Text("$limit") }
                            )
                        }
                    }
                }

                // 4. Storage, SAF Export & Filename Templates
                SettingsSectionCard(
                    icon = Icons.Default.FolderOpen,
                    title = "Storage & Filenames"
                ) {
                    Text(
                        text = if (settings.safTreeUri.isNotBlank()) {
                            "Custom SAF Folder: ${Uri.parse(settings.safTreeUri).lastPathSegment ?: settings.safTreeUri}"
                        } else {
                            "Save Location: App Downloads & Public Downloads/EmreGallery"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { safTreeLauncher.launch(null) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Choose Folder")
                        }
                        if (settings.safTreeUri.isNotBlank()) {
                            OutlinedButton(
                                onClick = { updateSettings { it.copy(safTreeUri = "") } },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Reset")
                            }
                        }
                    }

                    SettingsSwitchRow(
                        label = "Organize files into subfolders by site domain",
                        checked = settings.organizeBySite,
                        onCheckedChange = { checked -> updateSettings { it.copy(organizeBySite = checked) } }
                    )

                    Text(
                        text = "Filename Template",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilenameTemplate.entries.forEach { tpl ->
                            FilterChip(
                                selected = settings.filenameTemplate == tpl,
                                onClick = { updateSettings { it.copy(filenameTemplate = tpl) } },
                                label = { Text(tpl.label) }
                            )
                        }
                    }

                    if (settings.filenameTemplate == FilenameTemplate.CUSTOM) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customPatternDraft,
                                onValueChange = { customPatternDraft = it },
                                label = { Text("Tokens: {title} {uploader} {domain} {date} {id} {ext}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (customPatternDraft != settings.customFilenamePattern) {
                                TextButton(
                                    onClick = {
                                        updateSettings { it.copy(customFilenamePattern = customPatternDraft.ifBlank { "{title}.{ext}" }) }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }

                    val previewName = remember(settings.filenameTemplate, customPatternDraft) {
                        FilenameTemplateHelper.formatFilename(
                            template = settings.filenameTemplate,
                            customPattern = customPatternDraft,
                            title = "Sample Video",
                            uploader = "Creator",
                            domain = "example.com",
                            dateMillis = System.currentTimeMillis(),
                            id = "dQw4w9WgXcQ",
                            ext = "mp4"
                        )
                    }
                    Text(
                        text = "Preview: $previewName",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Box {
                        val defaultAlbumName = customAlbums.firstOrNull { it.id == settings.defaultAlbumId }?.fullPath
                            ?: "Inbox Only"
                        OutlinedButton(
                            onClick = { albumMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Default Album: $defaultAlbumName", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(
                            expanded = albumMenuExpanded,
                            onDismissRequest = { albumMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Inbox Only") },
                                onClick = {
                                    updateSettings { it.copy(defaultAlbumId = "") }
                                    albumMenuExpanded = false
                                }
                            )
                            customAlbums.forEach { album ->
                                DropdownMenuItem(
                                    text = { Text(album.fullPath) },
                                    onClick = {
                                        updateSettings { it.copy(defaultAlbumId = album.id) }
                                        albumMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    SettingsSwitchRow(
                        label = "Route downloads to Locked Vault by default",
                        checked = settings.defaultAddToVault,
                        onCheckedChange = { checked -> updateSettings { it.copy(defaultAddToVault = checked) } }
                    )
                }

                // 5. Download Archive & Cookies
                SettingsSectionCard(
                    icon = Icons.Default.Cookie,
                    title = "Download Archive & Cookies"
                ) {
                    SettingsSwitchRow(
                        label = "Skip already-downloaded URLs (archive)",
                        checked = settings.useDownloadArchive,
                        onCheckedChange = { checked -> updateSettings { it.copy(useDownloadArchive = checked) } }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Archived items: $archiveCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                DownloadArchiveStore.clear(context)
                                archiveCount = 0
                            },
                            enabled = archiveCount > 0,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear Archive")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    val parsedCookiesCount = remember(settings.cookiesTxtContent) {
                        CookiesTxtManager.parseNetscapeCookies(settings.cookiesTxtContent).size
                    }
                    Text(
                        text = if (settings.cookiesTxtContent.isNotBlank()) {
                            "Imported Netscape cookies: $parsedCookiesCount entry(ies)"
                        } else {
                            "No imported cookies.txt (using active WebView session)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { cookiesFileLauncher.launch(arrayOf("text/*", "*/*")) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Text("Import file")
                        }
                        OutlinedButton(
                            onClick = { showCookiesPasteDialog = true },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Text("Paste text")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (settings.cookiesTxtContent.isNotBlank()) {
                            OutlinedButton(
                                onClick = { updateSettings { it.copy(cookiesTxtContent = "") } },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Text("Clear cookies.txt")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                OkHttpDownloader.sharedCookieJar.clearSession()
                                runCatching {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                }
                                updateSettings { it.copy(cookiesTxtContent = "") }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Text("Clear Session")
                        }
                    }
                }

                // 6. Extraction Engines & Diagnostics
                SettingsSectionCard(
                    icon = Icons.Default.BugReport,
                    title = "Engines & Diagnostics"
                ) {
                    val status = engineStatus
                    if (status == null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Checking Python extractors...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            text = "Python ${status.pythonVersion} • yt-dlp ${status.ytDlpVersion} • gallery-dl ${status.galleryDlVersion} (${status.supportedExtractorsCount} sites)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    updateStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isUpdatingYtDlp) return@Button
                                isUpdatingYtDlp = true
                                updateStatusMessage = "Fetching latest pure-Python yt-dlp wheel from PyPI..."
                                scope.launch {
                                    val result: EngineUpdateResult = GalleryDlEngine.updateYtDlpFromPyPi(context)
                                    updateStatusMessage = result.message
                                    engineStatus = withContext(Dispatchers.IO) { GalleryDlEngine.getStatus() }
                                    isUpdatingYtDlp = false
                                }
                            },
                            enabled = !isUpdatingYtDlp,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("settings_update_ytdlp_btn")
                        ) {
                            if (isUpdatingYtDlp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Update yt-dlp")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = GalleryDlEngine.revertYtDlpOverride(context)
                                    updateStatusMessage = result.message
                                    engineStatus = withContext(Dispatchers.IO) { GalleryDlEngine.getStatus() }
                                }
                            },
                            enabled = !isUpdatingYtDlp,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Revert")
                        }
                    }

                    OutlinedButton(
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_view_diagnostics_btn")
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("View Diagnostics Log (${DownloaderDiagnostics.entries.collectAsState().value.size})")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCookiesPasteDialog) {
        var cookiesInput by remember { mutableStateOf(settings.cookiesTxtContent) }
        AlertDialog(
            onDismissRequest = { showCookiesPasteDialog = false },
            title = { Text("Netscape cookies.txt") },
            text = {
                OutlinedTextField(
                    value = cookiesInput,
                    onValueChange = { cookiesInput = it },
                    label = { Text("Paste Netscape cookie file lines") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        updateSettings { it.copy(cookiesTxtContent = cookiesInput.trim()) }
                        showCookiesPasteDialog = false
                    }
                ) {
                    Text("Save Cookies")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCookiesPasteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDiagnosticsDialog) {
        val logEntries by DownloaderDiagnostics.entries.collectAsState()
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Diagnostics Log") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (logEntries.isEmpty()) {
                        Text(
                            text = "No diagnostic events recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(logEntries) { entry ->
                                Text(
                                    text = "[${entry.level}] [${entry.component}] ${entry.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (entry.level) {
                                        DownloaderDiagnostics.Level.ERROR -> MaterialTheme.colorScheme.error
                                        DownloaderDiagnostics.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                        DownloaderDiagnostics.Level.INFO -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(DownloaderDiagnostics.exportText()))
                        },
                        enabled = logEntries.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    TextButton(
                        onClick = { DownloaderDiagnostics.clear() },
                        enabled = logEntries.isNotEmpty()
                    ) {
                        Text("Clear")
                    }
                    TextButton(onClick = { showDiagnosticsDialog = false }) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}
