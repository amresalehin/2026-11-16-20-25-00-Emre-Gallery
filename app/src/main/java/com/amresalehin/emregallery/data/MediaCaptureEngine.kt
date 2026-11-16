package com.amresalehin.emregallery.data

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Unified Media Capture Engine.
 * Single source of truth for:
 * 1. Network request interception & stream/media classification.
 * 2. Deep JavaScript DOM, srcset, CSS background, and JSON-LD schema inspection.
 * 3. MutationObserver injection for dynamic DOM change detection.
 * 4. Adaptive scrolling logic.
 * 5. Media normalization, deduplication, and ScrapedMediaItem construction with headers.
 */
object MediaCaptureEngine {

    /**
     * Shared JavaScript DOM scan script that inspects:
     * - <img>, <picture source>, data-src, data-original, srcset, data-lazy-src
     * - <video>, <video source>, poster
     * - <audio>, <audio source>
     * - Schema.org JSON-LD (ImageObject, VideoObject, contentUrl)
     * - Content container CSS background-image
     * - IFrames pointing directly to media
     */
    fun getJsDomScanScript(): String {
        return """
            (function() {
                var list = [];
                function resolveAbs(u) {
                    if (!u) return null;
                    try {
                        return new URL(u, document.baseURI || window.location.href).href;
                    } catch(e) {
                        if (u.indexOf('//') === 0) return 'https:' + u;
                        return u;
                    }
                }

                function isChromeElement(el) {
                    if (!el) return false;
                    var chromeParent = el.closest('header, nav, footer, aside, menu, form, [role="banner"], [role="navigation"], [role="contentinfo"], .header, .site-header, .nav, .navbar, .site-nav, .menu, #header, #nav, #navbar, #footer, #mw-navigation, #p-logo, .footer, .site-footer, .breadcrumb, .breadcrumbs, .pagination, .toolbar, .ad, .ads, .advertisement, .cookie, .cookie-banner, .consent');
                    if (chromeParent) {
                        var contentParent = el.closest('article, main, [role="main"], #content, #main-content, .mw-parser-output, figure, .gallery, .shots-grid, .shots, .dribbble-shot, .entry-content, .post-content');
                        if (!contentParent || chromeParent.contains(contentParent) === false) {
                            return true;
                        }
                    }
                    var idAndClass = ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('alt') || '')).toLowerCase();
                    if (/logo|favicon|avatar_default|badge|donate|donation|fundraising|paypal|magnifying-glass|nav-icon|menu-icon|close-icon|arrow-|chevron|bullet|spinner|1x1/.test(idAndClass)) {
                        return true;
                    }
                    return false;
                }

                // 1. Deep Image Inspection (img, picture, data attributes, srcset)
                var images = document.querySelectorAll('img, picture source');
                for (var i = 0; i < images.length; i++) {
                    var img = images[i];
                    if (isChromeElement(img)) continue;

                    var candidateUrls = [];

                    // Extract srcset highest resolution candidates (comma-safe for Cloudinary transform URLs)
                    var srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset');
                    if (srcset) {
                        var parts = srcset.split(/,\s+(?=https?:\/\/|\/\/|\/|\.\.?\/|[a-zA-Z0-9_-]+\/)/i);
                        for (var p = 0; p < parts.length; p++) {
                            var trimmed = parts[p].trim();
                            if (trimmed) {
                                var splitBits = trimmed.split(/\s+/);
                                var u = splitBits[0];
                                var descriptor = splitBits[1] || '';
                                if (u && u.indexOf('data:') !== 0) {
                                    var absSrcset = resolveAbs(u);
                                    if (absSrcset) {
                                        list.push({u: absSrcset, s: 'SRCSET', r: descriptor});
                                    }
                                }
                            }
                        }
                    }

                    // Direct src & data-lazy attributes
                    var rawSrc = img.src || img.getAttribute('src');
                    var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-original') ||
                                  img.getAttribute('data-lazy-src') || img.getAttribute('data-large-file') ||
                                  img.getAttribute('data-hi-res-src') || img.getAttribute('data-zoom-image');

                    if (dataSrc && dataSrc.indexOf('data:') !== 0) {
                        var absData = resolveAbs(dataSrc);
                        if (absData) candidateUrls.push({u: absData, s: 'DATA_SRC'});
                    }
                    if (rawSrc && rawSrc.indexOf('data:') !== 0) {
                        var absRaw = resolveAbs(rawSrc);
                        if (absRaw) candidateUrls.push({u: absRaw, s: 'DOM'});
                    }

                    for (var c = 0; c < candidateUrls.length; c++) {
                        list.push(candidateUrls[c]);
                    }
                }

                // 2. Video Elements & All Sources
                var videos = document.querySelectorAll('video');
                for (var v = 0; v < videos.length; v++) {
                    var vid = videos[v];
                    var poster = resolveAbs(vid.getAttribute('poster') || vid.poster);
                    var foundAnyVideo = false;
                    if (vid.src && !vid.src.startsWith('blob:') && !vid.src.startsWith('data:')) {
                        var cv = resolveAbs(vid.src);
                        if (cv) {
                            list.push({u: cv, s: 'DOM', p: poster, t: 'video'});
                            foundAnyVideo = true;
                        }
                    }
                    var vsources = vid.querySelectorAll('source');
                    for (var j = 0; j < vsources.length; j++) {
                        var vsRaw = vsources[j].src || vsources[j].getAttribute('data-src');
                        if (vsRaw && !vsRaw.startsWith('blob:') && !vsRaw.startsWith('data:')) {
                            var cv2 = resolveAbs(vsRaw);
                            if (cv2) {
                                list.push({u: cv2, s: 'DOM', p: poster, t: 'video'});
                                foundAnyVideo = true;
                            }
                        }
                    }
                    if (!foundAnyVideo && poster) {
                        list.push({u: poster, s: 'POSTER', r: 'poster'});
                    }
                }

                // 3. Audio Elements & All Sources
                var audios = document.querySelectorAll('audio');
                for (var a = 0; a < audios.length; a++) {
                    var au = audios[a];
                    if (au.src && !au.src.startsWith('blob:') && !au.src.startsWith('data:')) {
                        var ca = resolveAbs(au.src);
                        if (ca) list.push({u: ca, s: 'DOM', t: 'audio'});
                    }
                    var asources = au.querySelectorAll('source');
                    for (var k = 0; k < asources.length; k++) {
                        var asRaw = asources[k].src || asources[k].getAttribute('data-src');
                        if (asRaw && !asRaw.startsWith('blob:') && !asRaw.startsWith('data:')) {
                            var ca2 = resolveAbs(asRaw);
                            if (ca2) list.push({u: ca2, s: 'DOM', t: 'audio'});
                        }
                    }
                }

                // 4. Schema.org JSON-LD (ImageObject, VideoObject, @graph recursion)
                function walkJsonLd(node, depth) {
                    if (!node || depth > 5) return;
                    if (Array.isArray(node)) {
                        for (var x = 0; x < node.length; x++) walkJsonLd(node[x], depth + 1);
                        return;
                    }
                    if (typeof node !== 'object') return;
                    if (node.contentUrl && typeof node.contentUrl === 'string') {
                        var cu = resolveAbs(node.contentUrl);
                        if (cu) list.push({u: cu, s: 'DOM', r: 'json-ld'});
                    }
                    if (node.thumbnailUrl) {
                        var tuArr = Array.isArray(node.thumbnailUrl) ? node.thumbnailUrl : [node.thumbnailUrl];
                        for (var ti = 0; ti < tuArr.length; ti++) {
                            if (typeof tuArr[ti] === 'string') {
                                var tu = resolveAbs(tuArr[ti]);
                                if (tu) list.push({u: tu, s: 'DOM', r: 'json-ld-thumb'});
                            }
                        }
                    }
                    if (node.image) {
                        var imArr = Array.isArray(node.image) ? node.image : [node.image];
                        for (var ii = 0; ii < imArr.length; ii++) {
                            var imVal = imArr[ii];
                            if (typeof imVal === 'string') {
                                var iu = resolveAbs(imVal);
                                if (iu) list.push({u: iu, s: 'DOM', r: 'json-ld-image'});
                            } else if (imVal && typeof imVal === 'object') {
                                walkJsonLd(imVal, depth + 1);
                            }
                        }
                    }
                    if (node['@graph']) walkJsonLd(node['@graph'], depth + 1);
                    if (node.video) walkJsonLd(node.video, depth + 1);
                    if (node.associatedMedia) walkJsonLd(node.associatedMedia, depth + 1);
                }
                var jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (var jl = 0; jl < jsonLdScripts.length; jl++) {
                    try {
                        var rawText = jsonLdScripts[jl].textContent || jsonLdScripts[jl].innerText;
                        if (rawText) {
                            walkJsonLd(JSON.parse(rawText), 0);
                        }
                    } catch(e) {}
                }

                // 5. CSS background-image in content containers
                var bgElements = document.querySelectorAll('main, article, figure, [role="main"], #content, .gallery, .shots-grid, .shots, .photo, .hero');
                var count = Math.min(bgElements.length, 60);
                for (var b = 0; b < count; b++) {
                    var bEl = bgElements[b];
                    if (bEl.clientWidth >= 120 && bEl.clientHeight >= 120 && !isChromeElement(bEl)) {
                        var style = window.getComputedStyle(bEl);
                        var bg = style.backgroundImage;
                        if (bg && bg.indexOf('url(') !== -1) {
                            var match = bg.match(/url\(['"]?(.*?)['"]?\)/);
                            if (match && match[1] && !match[1].startsWith('data:')) {
                                var bgu = resolveAbs(match[1]);
                                if (bgu) list.push({u: bgu, s: 'CSS', r: 'background'});
                            }
                        }
                    }
                }

                // 6. Direct media IFrames
                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                    var ifr = resolveAbs(iframes[f].src);
                    if (ifr && /\.(?:mp4|webm|mkv|mov|mp3|m4a|wav|jpe?g|png|webp|gif|avif)(?:[?#]|$)/i.test(ifr)) {
                        list.push({u: ifr, s: 'IFRAME', r: 'iframe'});
                    }
                }

                return JSON.stringify(list);
            })();
        """.trimIndent()
    }

    /**
     * Hooks fetch() and XMLHttpRequest so streaming manifests (.m3u8/.mpd) or dynamically fetched
     * media URLs in SPAs are reported immediately to EmreBridge without interfering with page execution.
     */
    fun getNetworkHookScript(): String {
        return """
            (function() {
                if (window.__emreNetHookInstalled) return;
                window.__emreNetHookInstalled = true;
                var mediaUrlRe = /https?:\/\/[^\s"'<>\\]+\.(?:m3u8|mpd|mp4|webm|mov|mkv|mp3|m4a|flac|opus|ogg)(?:[?#][^\s"'<>\\]*)?/ig;
                function emit(u) {
                    if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
                    try {
                        var abs = new URL(u, document.baseURI || window.location.href).href;
                        if (window.EmreBridge && window.EmreBridge.reportMedia) {
                            window.EmreBridge.reportMedia(abs);
                        }
                    } catch(e) {}
                }
                function inspectUrl(u) {
                    if (typeof u === 'string' && /\.(?:m3u8|mpd|mp4|webm|mov|mkv|mp3|m4a|flac|opus|ogg)(?:[?#]|$)/i.test(u)) {
                        emit(u);
                    }
                }
                function inspectText(txt) {
                    if (!txt || typeof txt !== 'string' || txt.length > 262144) return;
                    var matches = txt.match(mediaUrlRe);
                    if (matches) {
                        var limit = Math.min(matches.length, 25);
                        for (var i = 0; i < limit; i++) emit(matches[i]);
                    }
                }
                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        try {
                            var reqUrl = typeof input === 'string' ? input : (input && input.url);
                            inspectUrl(reqUrl);
                        } catch(e) {}
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                inspectUrl(resp && resp.url);
                                var ct = (resp && resp.headers && resp.headers.get && resp.headers.get('content-type')) || '';
                                if (/json|mpegurl|dash\+xml/i.test(ct) && resp.clone) {
                                    resp.clone().text().then(inspectText).catch(function(){});
                                }
                            } catch(e) {}
                            return resp;
                        });
                    };
                }
                if (window.XMLHttpRequest) {
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        try { this.__emreUrl = url; inspectUrl(url); } catch(e) {}
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        try {
                            this.addEventListener('load', function() {
                                try {
                                    inspectUrl(this.responseURL || this.__emreUrl);
                                    if (!this.responseType || this.responseType === '' || this.responseType === 'text') {
                                        var ct = (this.getResponseHeader && this.getResponseHeader('content-type')) || '';
                                        if (/json|mpegurl|dash\+xml/i.test(ct)) {
                                            inspectText(this.responseText);
                                        }
                                    }
                                } catch(e) {}
                            });
                        } catch(e) {}
                        return origSend.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()
    }

    /**
     * Shared JavaScript MutationObserver injection for dynamic DOM additions.
     */
    fun getMutationObserverScript(): String {
        return """
            (function() {
                if (window.__emreObserverInstalled) return;
                window.__emreObserverInstalled = true;

                function report(u) {
                    if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
                    try {
                        var abs = new URL(u, document.baseURI || window.location.href).href;
                        if (window.EmreBridge && window.EmreBridge.reportMedia) {
                            window.EmreBridge.reportMedia(abs);
                        }
                    } catch(e) {}
                }

                function checkNode(node) {
                    if (!node || node.nodeType !== 1) return;
                    var tag = node.tagName ? node.tagName.toUpperCase() : '';
                    if (tag === 'IMG') {
                        report(node.src);
                        report(node.getAttribute('data-src'));
                        report(node.getAttribute('data-original'));
                        report(node.getAttribute('data-lazy-src'));
                        report(node.getAttribute('data-large-file'));
                        report(node.getAttribute('data-hi-res-src'));
                        var srcset = node.getAttribute('srcset') || node.getAttribute('data-srcset');
                        if (srcset) {
                            var parts = srcset.split(/,\s+(?=https?:\/\/|\/\/|\/|\.\.?\/|[a-zA-Z0-9_-]+\/)/i);
                            for (var i = 0; i < parts.length; i++) {
                                var bit = parts[i].trim().split(/\s+/)[0];
                                if (bit) report(bit);
                            }
                        }
                    } else if (tag === 'VIDEO') {
                        if (node.src && node.src.indexOf('blob:') !== 0 && node.src.indexOf('data:') !== 0) {
                            report(node.src);
                        }
                        if (node.querySelectorAll) {
                            var vSources = node.querySelectorAll('source');
                            for (var vs = 0; vs < vSources.length; vs++) {
                                var vsSrc = vSources[vs].src;
                                if (vsSrc && vsSrc.indexOf('blob:') !== 0 && vsSrc.indexOf('data:') !== 0) {
                                    report(vsSrc);
                                }
                            }
                        }
                        report(node.getAttribute('poster'));
                    } else if (tag === 'AUDIO') {
                        if (node.src && node.src.indexOf('blob:') !== 0 && node.src.indexOf('data:') !== 0) {
                            report(node.src);
                        }
                        if (node.querySelectorAll) {
                            var aSources = node.querySelectorAll('source');
                            for (var as = 0; as < aSources.length; as++) {
                                var asSrc = aSources[as].src;
                                if (asSrc && asSrc.indexOf('blob:') !== 0 && asSrc.indexOf('data:') !== 0) {
                                    report(asSrc);
                                }
                            }
                        }
                    } else if (tag === 'SOURCE') {
                        var parentTag = node.parentNode ? (node.parentNode.tagName || '').toUpperCase() : '';
                        if (parentTag !== 'VIDEO' && parentTag !== 'AUDIO') {
                            report(node.src);
                            report(node.getAttribute('srcset'));
                        }
                    }

                    if (node.querySelectorAll) {
                        var children = node.querySelectorAll('img, video, audio, source');
                        for (var c = 0; c < children.length; c++) {
                            checkNode(children[c]);
                        }
                    }
                }

                var observer = new MutationObserver(function(mutations) {
                    for (var m = 0; m < mutations.length; m++) {
                        var mut = mutations[m];
                        if (mut.type === 'childList') {
                            for (var i = 0; i < mut.addedNodes.length; i++) {
                                checkNode(mut.addedNodes[i]);
                            }
                        } else if (mut.type === 'attributes') {
                            checkNode(mut.target);
                        }
                    }
                });

                observer.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src', 'data-src', 'data-original', 'data-lazy-src', 'srcset', 'data-srcset', 'poster']
                });
            })();
        """.trimIndent()
    }

    /**
     * Shared JavaScript adaptive scrolling script.
     */
    fun getAdaptiveScrollScript(): String {
        return """
            (function() {
                var doc = document.documentElement || document.body;
                var scrollY = Math.round(window.pageYOffset || doc.scrollTop || 0);
                var innerHeight = window.innerHeight || 0;
                var scrollHeight = doc.scrollHeight || 0;
                var remaining = scrollHeight - (scrollY + innerHeight);

                var scrollDelta = Math.max(innerHeight * 0.85, 400);
                window.scrollBy({ top: scrollDelta, behavior: 'smooth' });
                window.dispatchEvent(new Event('scroll'));

                return JSON.stringify({
                    scrollY: scrollY,
                    scrollHeight: scrollHeight,
                    remaining: remaining
                });
            })();
        """.trimIndent()
    }

    /**
     * Builds and validates a ScrapedMediaItem with provenance, clean titles,
     * download headers (User-Agent, Referer, Cookie), and thumbnail classification.
     */
    fun buildMediaItem(
        rawUrl: String,
        source: ExtractionSource,
        baseUrl: String,
        poster: String? = null,
        resolutionHint: String? = null,
        mimeHint: String? = null,
        indexHint: Int = 0,
        extraHeaders: Map<String, String> = emptyMap()
    ): ScrapedMediaItem? {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("blob:", ignoreCase = true)) return null
        if (trimmed.startsWith("data:", ignoreCase = true) && !MediaClassifier.isSubstantialDataUri(trimmed)) return null
        if (MediaClassifier.isMediaFragment(trimmed)) return null
        if (AdBlockEngine.isAdOrTracker(trimmed)) return null

        val resolved = MediaClassifier.resolveUrl(trimmed, baseUrl) ?: return null
        if (!MediaClassifier.isValidMedia(resolved)) return null
        if (MediaClassifier.isMediaFragment(resolved)) return null

        val resolvedInfo = MediaClassifier.resolveOriginalMedia(resolved)
        val cleanedUrl = MediaClassifier.cleanMediaUrl(resolvedInfo.mediaUrl)
        if (MediaClassifier.isMediaFragment(cleanedUrl)) return null

        val classification = MediaClassifier.classify(cleanedUrl, mimeHint)
        val isVidAudStream = classification.isVideo || classification.isAudio || classification.isStream
        val title = resolvedInfo.cleanTitle ?: MediaClassifier.deriveFileName(cleanedUrl, classification.format, indexHint)

        if (ContentAwareMediaFilter.isUiChromeUrl(
                url = cleanedUrl,
                title = title,
                isVideoOrAudioOrStream = isVidAudStream
            )
        ) {
            return null
        }

        val thumb = resolvedInfo.thumbnailUrl ?: poster ?: if (classification.category == MediaCategory.IMAGES) resolved else poster

        val headers = mutableMapOf<String, String>()
        headers["Referer"] = baseUrl
        headers["User-Agent"] = OkHttpDownloader.configuredUserAgent.ifBlank { OkHttpDownloader.DEFAULT_USER_AGENT }
        for ((hk, hv) in extraHeaders) {
            if (hv.isNotBlank() && hk.lowercase() in setOf("referer", "origin", "user-agent", "authorization")) {
                headers[hk] = hv
            }
        }
        val cookieStr = OkHttpDownloader.sharedCookieJar.getCookieString(cleanedUrl).ifBlank {
            OkHttpDownloader.sharedCookieJar.getCookieString(baseUrl)
        }
        if (cookieStr.isNotBlank()) {
            headers["Cookie"] = cookieStr
        }

        val metadataMap = mutableMapOf<String, String>()
        resolutionHint?.let { metadataMap["resolution"] = it }
        mimeHint?.let { metadataMap["mime_hint"] = it }
        metadataMap["source_url"] = baseUrl

        return ScrapedMediaItem(
            id = "captured_${indexHint}_${System.currentTimeMillis()}_${cleanedUrl.hashCode()}",
            url = cleanedUrl,
            title = title,
            isVideo = classification.isVideo,
            isAudio = classification.isAudio,
            isStream = classification.isStream,
            category = classification.category,
            format = classification.format,
            thumbnailUrl = thumb,
            source = source.label,
            mimeType = classification.mimeType,
            resolutionHint = resolutionHint,
            provenance = MediaProvenance(
                extractorEngine = "MediaCaptureEngine (${source.label})",
                pageUrl = baseUrl,
                domSelector = if (source == ExtractionSource.NETWORK_INTERCEPT) "network_request" else null
            ),
            metadata = metadataMap,
            downloadHeaders = headers
        )
    }

    /**
     * Unified network request interception for both BrowserMediaExtractor and DirectBrowserView.
     */
    fun shouldInterceptRequest(
        request: WebResourceRequest?,
        baseUrl: String,
        onMediaDiscovered: (ScrapedMediaItem) -> Unit
    ): WebResourceResponse? {
        if (request == null) return null
        val reqUrl = request.url.toString()

        // 1. Instant zero-payload response for ad/tracker networks
        if (AdBlockEngine.isAdOrTracker(reqUrl)) {
            return AdBlockEngine.createEmptyResponse()
        }

        // 2. Reject streaming slice fragments (.ts slices, dash segments)
        if (MediaClassifier.isMediaFragment(reqUrl)) {
            return null
        }

        val reqLower = reqUrl.lowercase()
        val cleanReqPath = reqLower.substringBefore("?").substringBefore("#")
        val headers = request.requestHeaders ?: emptyMap()
        val acceptHeader = headers["Accept"]?.lowercase() ?: ""
        val hasDirectMediaExt = MediaClassifier.KNOWN_MEDIA_EXTENSIONS.any { cleanReqPath.endsWith(".$it") }
        val acceptsExplicitMedia = acceptHeader.startsWith("image/") || acceptHeader.startsWith("video/") ||
            acceptHeader.startsWith("audio/") || acceptHeader.contains("mpegurl") || acceptHeader.contains("dash+xml")

        // Reject non-media API, logging, telemetry endpoints (unless explicitly serving direct media)
        val isNonMediaEndpoint = (!hasDirectMediaExt && !acceptsExplicitMedia && (
                cleanReqPath.contains("/api/") || cleanReqPath.contains("/apis/") ||
                cleanReqPath.contains("youtube.com/api/")
            )) ||
            cleanReqPath.contains("/youtubei/") || cleanReqPath.contains("/stats/") ||
            cleanReqPath.contains("/log/") || cleanReqPath.contains("/logging") ||
            cleanReqPath.contains("/ptracking") || cleanReqPath.contains("/beacon") ||
            cleanReqPath.contains("/telemetry") || cleanReqPath.contains("/metrics") ||
            cleanReqPath.contains("generate_204") || cleanReqPath.contains("/recaptcha") ||
            cleanReqPath.contains("/embed/") || cleanReqPath.contains("play.google.com/log") ||
            cleanReqPath.contains("/wiki/") || cleanReqPath.contains("/geohack") ||
            cleanReqPath.contains("centralautologin")

        if (isNonMediaEndpoint) return null

        val isNonMediaPage = cleanReqPath.endsWith(".html") || cleanReqPath.endsWith(".htm") ||
            cleanReqPath.endsWith(".php") || cleanReqPath.endsWith(".js") || cleanReqPath.endsWith(".css") ||
            cleanReqPath.endsWith(".json") || cleanReqPath.endsWith(".xml") || cleanReqPath.endsWith(".txt") ||
            cleanReqPath.endsWith(".do") || cleanReqPath.endsWith(".asp") || cleanReqPath.endsWith(".aspx")

        if (isNonMediaPage) return null

        val isStream = cleanReqPath.endsWith(".m3u8") || cleanReqPath.endsWith(".mpd") ||
            cleanReqPath.contains("chunklist") || acceptHeader.contains("mpegurl") ||
            acceptHeader.contains("dash+xml")

        val isVideo = isStream || cleanReqPath.endsWith(".mp4") || cleanReqPath.endsWith(".webm") ||
            cleanReqPath.endsWith(".mov") || cleanReqPath.endsWith(".mkv") ||
            acceptHeader.contains("video/")

        val isAudio = cleanReqPath.endsWith(".mp3") || cleanReqPath.endsWith(".m4a") ||
            cleanReqPath.endsWith(".aac") || cleanReqPath.endsWith(".wav") ||
            cleanReqPath.endsWith(".ogg") || cleanReqPath.endsWith(".flac") ||
            cleanReqPath.endsWith(".opus") || acceptHeader.contains("audio/")

        val hasImageExt = cleanReqPath.endsWith(".jpg") || cleanReqPath.endsWith(".jpeg") ||
            cleanReqPath.endsWith(".png") || cleanReqPath.endsWith(".webp") ||
            cleanReqPath.endsWith(".gif") || cleanReqPath.endsWith(".avif") ||
            cleanReqPath.endsWith(".svg") || cleanReqPath.endsWith(".bmp") ||
            cleanReqPath.contains("/vi/") || cleanReqPath.contains("/vi_webp/") ||
            Regex("""[?&](?:format|fmt|ext)=(?:jpg|jpeg|png|webp|avif)""").containsMatchIn(reqLower)

        val isImage = hasImageExt && !acceptHeader.contains("text/html")

        if (isVideo || isAudio || isImage) {
            val cleanReqUrl = if (isVideo) MediaClassifier.cleanMediaUrl(reqUrl) else reqUrl
            val requestMimeHint = when {
                acceptHeader.startsWith("audio/") -> acceptHeader.substringBefore(',').substringBefore(';').trim()
                acceptHeader.startsWith("video/") -> acceptHeader.substringBefore(',').substringBefore(';').trim()
                acceptHeader.contains("mpegurl") -> "application/x-mpegURL"
                acceptHeader.contains("dash+xml") -> "application/dash+xml"
                else -> null
            }
            val item = buildMediaItem(
                rawUrl = cleanReqUrl,
                source = ExtractionSource.NETWORK_INTERCEPT,
                baseUrl = baseUrl,
                mimeHint = requestMimeHint,
                extraHeaders = headers
            )
            if (item != null) {
                onMediaDiscovered(item)
            }
        }

        return null
    }

    /**
     * Parses the JSON array returned by getJsDomScanScript().
     */
    fun parseJsDomScanResult(
        resultJson: String?,
        baseUrl: String
    ): List<ScrapedMediaItem> {
        if (resultJson.isNullOrBlank() || resultJson == "null") return emptyList()

        return try {
            val unescaped = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                org.json.JSONTokener(resultJson).nextValue().toString()
            } else {
                resultJson
            }

            val jsonArray = JSONArray(unescaped)
            val results = mutableListOf<ScrapedMediaItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawUrl = obj.optString("u", "")
                if (rawUrl.isNotBlank()) {
                    val sTag = obj.optString("s", "DOM")
                    val source = when (sTag) {
                        "CSS" -> ExtractionSource.CSS_BACKGROUND
                        "SRCSET" -> ExtractionSource.SRCSET
                        "IFRAME" -> ExtractionSource.IFRAME
                        else -> ExtractionSource.DOM_SCRAPE
                    }
                    val poster = obj.optString("p", "").takeIf { it.isNotBlank() }
                    val res = obj.optString("r", "").takeIf { it.isNotBlank() }
                    val semanticType = obj.optString("t", "").takeIf { it.isNotBlank() }
                    val mimeHint = when {
                        semanticType.equals("audio", ignoreCase = true) || res.equals("audio", ignoreCase = true) -> "audio/*"
                        semanticType.equals("video", ignoreCase = true) -> "video/*"
                        else -> null
                    }
                    val item = buildMediaItem(
                        rawUrl = rawUrl,
                        source = source,
                        baseUrl = baseUrl,
                        poster = poster,
                        resolutionHint = res.takeUnless { it.equals("audio", ignoreCase = true) || it.equals("video", ignoreCase = true) },
                        mimeHint = mimeHint,
                        indexHint = i
                    )
                    if (item != null) {
                        results.add(item)
                    }
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}
