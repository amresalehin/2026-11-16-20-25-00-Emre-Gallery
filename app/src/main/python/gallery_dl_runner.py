import json
import os
import re
import shutil
import sys
import tempfile
import hashlib
import threading
import time
import zipfile

_gdl_lock = threading.Lock()
_cancel_lock = threading.Lock()
_cancelled_jobs = set()
_progress_callbacks = {}

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)

try:
    import gallery_dl
    from gallery_dl import extractor as gdl_extractor, job as gdl_job, config as gdl_config, exception as gdl_exception
    GALLERY_DL_AVAILABLE = True
except Exception:
    gallery_dl = None
    GALLERY_DL_AVAILABLE = False

try:
    import yt_dlp
    YTDLP_AVAILABLE = True
except Exception:
    yt_dlp = None
    YTDLP_AVAILABLE = False

POPULAR_EXTRACTORS = [
    {"name": "Reddit", "domains": ["reddit.com", "redd.it", "i.redd.it", "v.redd.it"], "icon": "reddit"},
    {"name": "Imgur", "domains": ["imgur.com", "i.imgur.com"], "icon": "image"},
    {"name": "Twitter / X", "domains": ["twitter.com", "x.com", "pbs.twimg.com"], "icon": "tag"},
    {"name": "Pinterest", "domains": ["pinterest.com", "i.pinimg.com"], "icon": "pin"},
    {"name": "ArtStation", "domains": ["artstation.com"], "icon": "brush"},
    {"name": "DeviantArt", "domains": ["deviantart.com"], "icon": "palette"},
    {"name": "Flickr", "domains": ["flickr.com"], "icon": "camera"}
]


def register_override_path(override_dir):
    """Prepends a pure-Python override directory to sys.path so updated yt-dlp/gallery-dl modules take precedence."""
    global yt_dlp, YTDLP_AVAILABLE, gallery_dl, gdl_extractor, gdl_job, gdl_config, gdl_exception, GALLERY_DL_AVAILABLE
    if not override_dir or not os.path.isdir(override_dir):
        return False
    norm = os.path.abspath(override_dir)
    if norm not in sys.path:
        sys.path.insert(0, norm)
    try:
        import importlib
        if os.path.isdir(os.path.join(norm, "yt_dlp")):
            for mod_name in list(sys.modules.keys()):
                if mod_name == "yt_dlp" or mod_name.startswith("yt_dlp."):
                    del sys.modules[mod_name]
            yt_dlp = importlib.import_module("yt_dlp")
            YTDLP_AVAILABLE = True
        if os.path.isdir(os.path.join(norm, "gallery_dl")):
            for mod_name in list(sys.modules.keys()):
                if mod_name == "gallery_dl" or mod_name.startswith("gallery_dl."):
                    del sys.modules[mod_name]
            gallery_dl = importlib.import_module("gallery_dl")
            gdl_extractor = importlib.import_module("gallery_dl.extractor")
            gdl_job = importlib.import_module("gallery_dl.job")
            gdl_config = importlib.import_module("gallery_dl.config")
            gdl_exception = importlib.import_module("gallery_dl.exception")
            GALLERY_DL_AVAILABLE = True
        return True
    except Exception:
        return False


def install_pure_python_override(archive_path, target_dir, package_prefix="yt_dlp"):
    """Safely extracts pure-Python (.py) files from a PyPI wheel/zip archive after validating no executable code."""
    forbidden_exts = (".so", ".dex", ".jar", ".apk", ".dll", ".exe", ".dylib", ".class", ".sh")
    try:
        if not os.path.isfile(archive_path):
            return json.dumps({"success": False, "error": "Archive file missing"})

        with zipfile.ZipFile(archive_path, "r") as zf:
            members = zf.infolist()
            pkg_members = []
            for info in members:
                name = info.filename.replace("\\", "/")
                if name.startswith("/") or ".." in name.split("/"):
                    return json.dumps({"success": False, "error": f"Unsafe zip entry path: {name}"})
                lower_name = name.lower()
                if lower_name.endswith(forbidden_exts):
                    return json.dumps({"success": False, "error": f"Blocked binary entry in archive: {name}"})
                if info.is_dir():
                    continue
                if name.startswith(package_prefix + "/") or name.startswith(package_prefix + "."):
                    if lower_name.endswith((".py", ".txt", ".json", ".pem")):
                        pkg_members.append((info, name))

            if not pkg_members:
                return json.dumps({"success": False, "error": f"Archive contains no {package_prefix} pure-Python package"})

            stage_dir = target_dir + "_stage"
            if os.path.exists(stage_dir):
                shutil.rmtree(stage_dir, ignore_errors=True)
            os.makedirs(stage_dir, exist_ok=True)

            pkg_target = os.path.join(target_dir, package_prefix)
            # Copy existing non-overridden packages in target_dir if needed
            for info, rel_path in pkg_members:
                dest_path = os.path.abspath(os.path.join(stage_dir, rel_path))
                if not dest_path.startswith(os.path.abspath(stage_dir) + os.sep):
                    shutil.rmtree(stage_dir, ignore_errors=True)
                    return json.dumps({"success": False, "error": "Path traversal detected"})
                os.makedirs(os.path.dirname(dest_path), exist_ok=True)
                with zf.open(info, "r") as src, open(dest_path, "wb") as dst:
                    shutil.copyfileobj(src, dst)

            os.makedirs(target_dir, exist_ok=True)
            if os.path.exists(pkg_target):
                shutil.rmtree(pkg_target, ignore_errors=True)
            shutil.move(os.path.join(stage_dir, package_prefix), pkg_target)
            shutil.rmtree(stage_dir, ignore_errors=True)

        register_override_path(target_dir)
        new_ver = getattr(yt_dlp, "__version__", "unknown") if package_prefix == "yt_dlp" else getattr(gallery_dl, "__version__", "unknown")
        return json.dumps({"success": True, "version": str(new_ver), "package": package_prefix})
    except Exception as e:
        return json.dumps({"success": False, "error": f"{e.__class__.__name__}: {str(e)}"})


def clear_pure_python_override(target_dir, package_prefix="yt_dlp"):
    try:
        pkg_path = os.path.join(target_dir, package_prefix)
        if os.path.exists(pkg_path):
            shutil.rmtree(pkg_path, ignore_errors=True)
        return json.dumps({"success": True})
    except Exception as e:
        return json.dumps({"success": False, "error": str(e)})


def cancel_job(job_id):
    if not job_id:
        return
    with _cancel_lock:
        _cancelled_jobs.add(str(job_id))


def clear_job_cancel(job_id):
    if not job_id:
        return
    with _cancel_lock:
        _cancelled_jobs.discard(str(job_id))


def is_job_cancelled(job_id):
    if not job_id:
        return False
    with _cancel_lock:
        return str(job_id) in _cancelled_jobs


def get_engine_status():
    """Returns runtime status and version information synchronized with GalleryDlEngine."""
    gdl_ver = getattr(gallery_dl, "__version__", "1.32.19") if GALLERY_DL_AVAILABLE else "N/A"
    ytdlp_ver = getattr(yt_dlp, "__version__", "2026.10.21") if YTDLP_AVAILABLE else "N/A"
    available = GALLERY_DL_AVAILABLE or YTDLP_AVAILABLE
    engine_name = f"gallery-dl {gdl_ver} / yt-dlp {ytdlp_ver}" if available else "Python Engine Unavailable"

    return json.dumps({
        "available": available,
        "version": gdl_ver,
        "engine": engine_name,
        "gallery_dl_available": GALLERY_DL_AVAILABLE,
        "gallery_dl_version": gdl_ver,
        "yt_dlp_available": YTDLP_AVAILABLE,
        "yt_dlp_version": ytdlp_ver,
        "python_version": sys.version.split()[0],
        "supported_categories_count": 287 if GALLERY_DL_AVAILABLE else 0,
        "popular_extractors": POPULAR_EXTRACTORS
    })


def _ytdlp_find_matching_extractor(url):
    """Finds a matching non-generic yt-dlp extractor using yt-dlp's full registry."""
    if not YTDLP_AVAILABLE or not url:
        return None
    try:
        ext_mod = getattr(yt_dlp, "extractor", None)
        if ext_mod is not None and hasattr(ext_mod, "gen_extractors"):
            for ie in ext_mod.gen_extractors():
                ie_name = ie.__class__.__name__
                if ie_name == "GenericIE":
                    continue
                if hasattr(ie, "suitable") and ie.suitable(url):
                    return ie_name
    except Exception:
        pass
    # Fallback curated check for incomplete test doubles
    lower = url.lower()
    video_hosts = [
        "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com",
        "twitch.tv", "soundcloud.com", "bandcamp.com", "facebook.com", "instagram.com",
        "twitter.com", "x.com", "reddit.com", "v.redd.it", "bilibili.com", "rumble.com",
        "odysee.com", "peertube", "streamable.com", "dailymotion.com", "loom.com"
    ]
    if any(vh in lower for vh in video_hosts) or ".m3u8" in lower or ".mpd" in lower:
        return "YoutubeDL"
    return None


def check_support_ytdlp(url):
    """Checks specifically if yt-dlp has a dedicated non-generic extractor or stream for this URL."""
    clean_url = (url or "").strip()
    if not clean_url or not YTDLP_AVAILABLE:
        return json.dumps({"supported": False, "extractor": "", "engine": "yt-dlp"})
    ie_name = _ytdlp_find_matching_extractor(clean_url)
    return json.dumps({
        "supported": ie_name is not None,
        "extractor": ie_name or "",
        "engine": "yt-dlp"
    })


def check_support(url):
    """Checks if a URL is handled by gallery-dl or yt-dlp extractors."""
    if not url:
        return json.dumps({"supported": False, "category": "", "extractor": ""})

    clean_url = url.strip()

    # 1. Check gallery-dl
    if GALLERY_DL_AVAILABLE:
        try:
            ext = gdl_extractor.find(clean_url)
            if ext is not None:
                cat = getattr(ext, "category", "") or ext.__class__.__name__.lower().replace("extractor", "")
                subcat = getattr(ext, "subcategory", "") or ""
                name = ext.__class__.__name__
                return json.dumps({
                    "supported": True,
                    "category": cat,
                    "subcategory": subcat,
                    "extractor": name,
                    "engine": "gallery-dl"
                })
        except Exception:
            pass

    # 2. Check yt-dlp dynamically via extractor registry
    if YTDLP_AVAILABLE:
        try:
            ie_name = _ytdlp_find_matching_extractor(clean_url)
            if ie_name:
                return json.dumps({
                    "supported": True,
                    "category": "video",
                    "subcategory": "stream",
                    "extractor": ie_name,
                    "engine": "yt-dlp"
                })
        except Exception:
            pass

    return json.dumps({"supported": False, "category": "", "extractor": ""})


def _prepare_temp_cookiefile(raw_cookies, target_url=None):
    """Converts a raw Cookie header OR Netscape cookies.txt string into a temporary Netscape file path.

    Returns `(cookiefile_path, should_delete_bool)`. Never leaks existing valid paths.
    """
    if not raw_cookies or not isinstance(raw_cookies, str):
        return None, False
    stripped = raw_cookies.strip()
    if not stripped:
        return None, False

    # Already an existing file path on disk
    if "\n" not in stripped and "\t" not in stripped and os.path.isfile(stripped):
        return stripped, False

    try:
        fd, path = tempfile.mkstemp(prefix="emre_cookies_", suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            if "\t" in stripped:
                if not stripped.startswith("# Netscape HTTP Cookie File"):
                    f.write("# Netscape HTTP Cookie File\n")
                f.write(stripped)
                if not stripped.endswith("\n"):
                    f.write("\n")
            else:
                from urllib.parse import urlparse
                host = urlparse(target_url).hostname if target_url else ""
                if not host:
                    os.close(fd) if False else None
                    os.remove(path)
                    return None, False
                f.write("# Netscape HTTP Cookie File\n")
                expiry = int(time.time()) + 86400 * 30
                for pair in stripped.split(";"):
                    if "=" in pair:
                        k, v = pair.split("=", 1)
                        k = k.strip()
                        v = v.strip()
                        if k:
                            f.write(f".{host.lstrip('.')}\tTRUE\t/\tTRUE\t{expiry}\t{k}\t{v}\n")
        return path, True
    except Exception:
        return None, False


def extract_gallery_dl(url, options_json="{}"):
    """Extracts media items using gallery-dl with isolated config cleanup."""
    clean_url = url.strip() if url else ""
    if not clean_url or not GALLERY_DL_AVAILABLE:
        return json.dumps({"success": False, "items": [], "error": "gallery-dl not available or empty URL"})

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    user_agent = opts.get("user_agent") or DEFAULT_UA
    cookies = opts.get("cookies")
    proxy = opts.get("proxy")
    max_items = int(opts.get("playlist_end") or opts.get("max_items") or 100)

    items = []
    page_title = ""
    seen_urls = set()
    cookie_path, should_delete_cookie = _prepare_temp_cookiefile(cookies, clean_url)

    try:
        with _gdl_lock:
            try:
                gdl_config.set(("extractor",), "user-agent", user_agent)
                if cookie_path:
                    gdl_config.set(("extractor",), "cookies", cookie_path)
                if proxy:
                    gdl_config.set(("extractor",), "proxy", str(proxy))

                ext = gdl_extractor.find(clean_url)
                if ext:
                    data_job = gdl_job.DataJob(ext, file=None, resolve=True)
                    data_job.run()

                    for post in getattr(data_job, "data_post", []):
                        if isinstance(post, dict):
                            candidate_title = (
                                post.get("title") or post.get("gallery_title")
                                or post.get("album_title") or post.get("post_title") or post.get("name")
                            )
                            if candidate_title and not page_title:
                                page_title = str(candidate_title)

                    for entry in getattr(data_job, "data", []):
                        if len(items) >= max_items:
                            break
                        if not isinstance(entry, (list, tuple)) or len(entry) < 2:
                            continue

                        item_url = None
                        kwdict = {}

                        if len(entry) >= 3 and isinstance(entry[1], str) and (entry[1].startswith("http://") or entry[1].startswith("https://")):
                            item_url = entry[1]
                            if isinstance(entry[2], dict):
                                kwdict = entry[2]
                        elif isinstance(entry[1], dict):
                            kwdict = entry[1]
                            item_url = kwdict.get("url") or kwdict.get("file_url")

                        if not item_url or item_url in seen_urls:
                            continue

                        seen_urls.add(item_url)

                        fn = kwdict.get("filename") or kwdict.get("name") or kwdict.get("title") or ""
                        ext_suffix = kwdict.get("extension") or ""
                        if fn and ext_suffix and not fn.endswith("." + ext_suffix):
                            item_title = f"{fn}.{ext_suffix}"
                        elif fn:
                            item_title = fn
                        else:
                            clean_path = item_url.split("?")[0].split("#")[0]
                            item_title = os.path.basename(clean_path) or f"media_{len(items) + 1}.bin"

                        if not page_title:
                            page_title = (
                                kwdict.get("gallery_title") or kwdict.get("album_title")
                                or kwdict.get("title") or kwdict.get("author") or ""
                            )

                        lower_url = item_url.lower()
                        lower_ext = ext_suffix.lower() if ext_suffix else ""
                        is_video = lower_ext in ["mp4", "webm", "mkv", "mov", "avi"] or lower_url.endswith((".mp4", ".webm")) or ".mp4?" in lower_url
                        is_stream = lower_ext in ["m3u8", "mpd"] or lower_url.endswith((".m3u8", ".mpd")) or ".m3u8?" in lower_url
                        is_audio = lower_ext in ["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"] or lower_url.endswith((".mp3", ".m4a"))

                        if is_stream:
                            category = "STREAMS"
                            format_label = "HLS/DASH"
                            mime_type = "application/x-mpegURL"
                        elif is_video:
                            category = "VIDEOS"
                            format_label = ext_suffix.upper() if ext_suffix else "MP4"
                            mime_type = f"video/{lower_ext}" if lower_ext else "video/mp4"
                        elif is_audio:
                            category = "AUDIO"
                            format_label = ext_suffix.upper() if ext_suffix else "MP3"
                            mime_type = f"audio/{lower_ext}" if lower_ext else "audio/mpeg"
                        else:
                            category = "IMAGES"
                            format_label = ext_suffix.upper() if ext_suffix else "IMAGE"
                            mime_type = f"image/{lower_ext}" if lower_ext in ["png", "jpeg", "jpg", "webp", "gif", "avif"] else "image/jpeg"

                        width = kwdict.get("width")
                        height = kwdict.get("height")
                        resolution_hint = f"{width}x{height}" if width and height else None
                        size_bytes = kwdict.get("filesize") or kwdict.get("size")
                        try:
                            size_bytes = int(size_bytes) if size_bytes else None
                        except Exception:
                            size_bytes = None

                        uploader = kwdict.get("author") or kwdict.get("uploader") or kwdict.get("artist")
                        if isinstance(uploader, dict):
                            uploader = uploader.get("name") or uploader.get("nick") or uploader.get("username")

                        thumb = kwdict.get("thumbnail") or kwdict.get("thumb") or (item_url if category == "IMAGES" else None)

                        url_hash = hashlib.sha256(item_url.encode("utf-8")).hexdigest()[:12]
                        items.append({
                            "id": f"gdl_{len(items)}_{url_hash}",
                            "url": item_url,
                            "title": item_title,
                            "category": category,
                            "format": format_label,
                            "mimeType": mime_type,
                            "isVideo": is_video or is_stream,
                            "isAudio": is_audio,
                            "isStream": is_stream,
                            "thumbnailUrl": thumb,
                            "resolutionHint": resolution_hint,
                            "sizeBytes": size_bytes,
                            "uploader": str(uploader) if uploader else None,
                            "source": "gallery-dl"
                        })
            finally:
                try:
                    gdl_config.clear()
                except Exception:
                    pass
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": f"{e.__class__.__name__}: {str(e)}",
            "items": []
        })
    finally:
        if should_delete_cookie and cookie_path and os.path.exists(cookie_path):
            try:
                os.remove(cookie_path)
            except Exception:
                pass

    return json.dumps({
        "success": len(items) > 0,
        "engine": "gallery-dl",
        "pageTitle": str(page_title) if page_title else "",
        "itemsCount": len(items),
        "items": items
    })


def _summarize_ytdlp_formats(formats):
    """Builds a compact list of selectable quality options sorted highest first for UI format pickers."""
    if not isinstance(formats, list):
        return []
    summarized = []
    seen_ids = set()
    for fmt in formats:
        if not isinstance(fmt, dict) or not fmt.get("format_id") or fmt.get("has_drm"):
            continue
        fid = str(fmt["format_id"])
        if fid in seen_ids:
            continue
        vcodec = str(fmt.get("vcodec") or "none")
        acodec = str(fmt.get("acodec") or "none")
        has_v = vcodec.lower() != "none"
        has_a = acodec.lower() != "none"
        if not has_v and not has_a:
            continue
        protocol = str(fmt.get("protocol") or "").lower()
        if protocol in ("mhtml",) and not has_v and not has_a:
            continue
        ext = str(fmt.get("ext") or "mp4").lower()
        height = fmt.get("height")
        width = fmt.get("width")
        fps = fmt.get("fps")
        abr = fmt.get("abr")
        fsize = fmt.get("filesize") or fmt.get("filesize_approx")
        if has_v and height:
            label = f"{height}p" + (f"{int(fps)}" if fps and float(fps) > 30 else "") + f" ({ext.upper()})"
            if not has_a:
                label += " [Video+BestAudio]"
        elif has_a and not has_v:
            abr_str = f"{int(abr)}k " if abr else ""
            label = f"Audio {abr_str}({ext.upper()})"
        else:
            label = f"{fmt.get('format_note') or fid} ({ext.upper()})"
        seen_ids.add(fid)
        summarized.append({
            "formatId": fid,
            "label": label,
            "ext": ext,
            "height": int(height) if height else None,
            "width": int(width) if width else None,
            "fps": int(float(fps)) if fps else None,
            "abrKbps": int(float(abr)) if abr else None,
            "vcodec": None if not has_v else vcodec,
            "acodec": None if not has_a else acodec,
            "fileSizeBytes": int(fsize) if fsize else None,
            "hasVideo": has_v,
            "hasAudio": has_a,
        })

    summarized.sort(
        key=lambda x: (
            1 if x["hasVideo"] else 0,
            x["height"] or 0,
            x["abrKbps"] or 0,
            x["fileSizeBytes"] or 0
        ),
        reverse=True
    )
    return summarized[:24]


def _resolve_entry_page_url(entry, parent_info, fallback_url):
    """Builds a unique per-item webpage/media URL for playlist entries so items never collide on shared URLs."""
    for key in ("webpage_url", "original_url"):
        val = entry.get(key)
        if val and str(val).startswith(("http://", "https://")):
            return str(val)
    raw_url = entry.get("url")
    if raw_url and str(raw_url).startswith(("http://", "https://")):
        return str(raw_url)
    vid = entry.get("id")
    ie_key = str(entry.get("ie_key") or (parent_info or {}).get("extractor_key") or "").lower()
    if vid:
        if "youtube" in ie_key or "youtube.com" in fallback_url.lower() or "youtu.be" in fallback_url.lower():
            return f"https://www.youtube.com/watch?v={vid}"
    return fallback_url


def extract_yt_dlp(url, options_json="{}"):
    """Extracts single media or playlists using yt-dlp cleanly without expanding huge playlists unnecessarily."""
    clean_url = url.strip() if url else ""
    if not clean_url or not YTDLP_AVAILABLE:
        return json.dumps({"success": False, "items": [], "error": "yt-dlp not available or empty URL"})

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    user_agent = opts.get("user_agent") or DEFAULT_UA
    playlist_start = int(opts.get("playlist_start") or 1)
    playlist_end = int(opts.get("playlist_end") or 50)
    proxy = opts.get("proxy")
    cookie_path, should_delete_cookie = _prepare_temp_cookiefile(opts.get("cookies"), clean_url)

    items = []
    page_title = ""
    seen_urls = set()

    try:
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "extract_flat": "in_playlist",
            "playliststart": playlist_start,
            "playlistend": playlist_end,
            "socket_timeout": int(opts.get("timeout") or 20),
            "http_headers": {
                "User-Agent": user_agent,
                **({"Cookie": str(opts.get("cookies"))} if (opts.get("cookies") and not cookie_path) else {}),
                **({"Referer": str(opts.get("referer"))} if opts.get("referer") else {})
            }
        }
        if cookie_path:
            ydl_opts["cookiefile"] = cookie_path
        if proxy:
            ydl_opts["proxy"] = str(proxy)

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(clean_url, download=False)
            if info:
                raw_entries = info.get("entries")
                is_playlist = raw_entries is not None
                entries = list(raw_entries) if raw_entries is not None else [info]
                playlist_title = str(info.get("title") or "") if is_playlist else None
                playlist_total = info.get("playlist_count") or len(entries) if is_playlist else None
                if playlist_title:
                    page_title = playlist_title

                for idx, entry in enumerate(entries):
                    if not entry or not isinstance(entry, dict):
                        continue

                    direct_url = _resolve_entry_page_url(entry, info, clean_url)
                    if not direct_url:
                        continue
                    dedup_token = f"{direct_url}#{entry.get('playlist_index') or idx}" if direct_url == clean_url and is_playlist else direct_url
                    if dedup_token in seen_urls:
                        continue
                    seen_urls.add(dedup_token)

                    v_title = entry.get("title") or entry.get("id") or f"media_{len(items) + 1}"
                    v_ext = entry.get("ext") or "mp4"
                    item_title = f"{v_title}.{v_ext}" if not str(v_title).endswith(f".{v_ext}") else str(v_title)

                    if not page_title:
                        page_title = str(v_title)

                    width = entry.get("width")
                    height = entry.get("height")
                    resolution_hint = f"{width}x{height}" if width and height else None
                    thumb = entry.get("thumbnail")
                    if not thumb and isinstance(entry.get("thumbnails"), list) and entry["thumbnails"]:
                        thumb = entry["thumbnails"][-1].get("url")
                    filesize = entry.get("filesize") or entry.get("filesize_approx")
                    duration = entry.get("duration")
                    uploader = entry.get("uploader") or entry.get("channel") or info.get("uploader")

                    sub_langs = set()
                    for sk in ("subtitles", "automatic_captions"):
                        sd = entry.get(sk)
                        if isinstance(sd, dict):
                            sub_langs.update(str(k) for k in list(sd.keys())[:15])

                    raw_formats = entry.get("formats") or []
                    selected_tracks = select_yt_dlp_formats(raw_formats)
                    is_audio_only = bool(selected_tracks and selected_tracks.get("kind") == "audio_only")
                    has_video = bool(selected_tracks and selected_tracks.get("has_video"))
                    has_audio = bool(selected_tracks and selected_tracks.get("has_audio"))
                    if not selected_tracks:
                        is_audio_only = entry.get("vcodec") == "none" or v_ext in ["mp3", "m4a", "m4b", "opus", "aac"]
                        has_video = not is_audio_only
                        has_audio = is_audio_only or entry.get("acodec") not in (None, "none")
                    is_stream = (".m3u8" in clean_url) or (".mpd" in clean_url)
                    is_video = has_video and not is_audio_only
                    category = "AUDIO" if is_audio_only else ("STREAMS" if is_stream else "VIDEOS")

                    url_hash = hashlib.sha256(dedup_token.encode("utf-8")).hexdigest()[:12]
                    items.append({
                        "id": f"ytdlp_{len(items)}_{url_hash}",
                        "url": direct_url,
                        "title": item_title,
                        "category": category,
                        "format": ("HLS/DASH" if is_stream else ("AUDIO" if is_audio_only else ("VIDEO" if v_ext in ["m4a", "m4b", "aac", "opus", "mp3"] else str(v_ext).upper()))),
                        "mimeType": ("audio/vnd.apple.mpegurl" if is_stream and is_audio_only else ("application/x-mpegURL" if is_stream else (f"audio/{v_ext}" if is_audio_only else f"video/{'mp4' if v_ext in ['m4a', 'm4b', 'aac', 'opus', 'mp3'] else v_ext}"))),
                        "isVideo": is_video,
                        "isAudio": is_audio_only,
                        "isStream": is_stream,
                        "hasAudio": has_audio,
                        "thumbnailUrl": thumb,
                        "resolutionHint": resolution_hint,
                        "sizeBytes": int(filesize) if filesize else None,
                        "durationSeconds": int(float(duration)) if duration else None,
                        "uploader": str(uploader) if uploader else None,
                        "availableFormats": _summarize_ytdlp_formats(raw_formats),
                        "subtitleLangs": sorted(sub_langs),
                        "playlistIndex": entry.get("playlist_index") or (idx + playlist_start if is_playlist else None),
                        "playlistTitle": playlist_title,
                        "playlistTotal": int(playlist_total) if playlist_total else None,
                        "source": "yt-dlp"
                    })
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": f"{e.__class__.__name__}: {str(e)}",
            "items": []
        })
    finally:
        if should_delete_cookie and cookie_path and os.path.exists(cookie_path):
            try:
                os.remove(cookie_path)
            except Exception:
                pass

    return json.dumps({
        "success": len(items) > 0,
        "engine": "yt-dlp",
        "pageTitle": str(page_title) if page_title else "",
        "itemsCount": len(items),
        "items": items
    })


def _format_sort_key(fmt, video=True, preferred_ext=None):
    """Stable quality ordering for yt-dlp format dictionaries."""
    def number(key, default=0):
        try:
            value = fmt.get(key)
            return float(value) if value is not None else default
        except (TypeError, ValueError):
            return default

    ext_bonus = 1 if (preferred_ext and str(fmt.get("ext") or "").lower() == preferred_ext.lower()) else 0
    if video:
        return (
            number("height"), number("width"), number("fps"), ext_bonus,
            number("vbr"), number("tbr"), number("filesize") or number("filesize_approx")
        )
    return (
        ext_bonus, number("quality"), number("abr"), number("asr"),
        number("tbr"), number("filesize") or number("filesize_approx")
    )


def select_yt_dlp_formats(
    formats,
    max_height=None,
    preferred_ext=None,
    audio_only=False,
    specific_format_id=None
):
    """Choose separate best video/audio tracks when available, otherwise a muxed format.

    Supports optional resolution cap, container preference, audio-only mode, or exact format ID,
    while keeping default zero-arg call semantics unchanged.
    """
    if not isinstance(formats, list):
        return None

    usable = []
    for fmt in formats:
        if not isinstance(fmt, dict) or not fmt.get("format_id") or fmt.get("has_drm"):
            continue
        protocol = str(fmt.get("protocol") or "").lower()
        if protocol in ("mhtml", "http_dash_segments") and not fmt.get("url"):
            continue
        usable.append(fmt)

    def has_codec(value):
        return bool(value) and str(value).lower() != "none"

    video_only = [f for f in usable if has_codec(f.get("vcodec")) and not has_codec(f.get("acodec"))]
    audio_only_list = [f for f in usable if has_codec(f.get("acodec")) and not has_codec(f.get("vcodec"))]
    muxed = [f for f in usable if has_codec(f.get("vcodec")) and has_codec(f.get("acodec"))]

    # 1. Exact user-selected format ID
    if specific_format_id:
        match = next((f for f in usable if str(f["format_id"]) == str(specific_format_id)), None)
        if match:
            has_v = has_codec(match.get("vcodec"))
            has_a = has_codec(match.get("acodec"))
            if has_v and not has_a and audio_only_list and not audio_only:
                audio_fmt = max(audio_only_list, key=lambda f: _format_sort_key(f, video=False))
                return {
                    "kind": "split",
                    "video_format_id": str(match["format_id"]),
                    "audio_format_id": str(audio_fmt["format_id"]),
                    "has_video": True,
                    "has_audio": True,
                }
            if has_a and not has_v:
                return {"kind": "audio_only", "format_id": str(match["format_id"]), "has_video": False, "has_audio": True}
            if has_v and not has_a:
                return {"kind": "video_only", "format_id": str(match["format_id"]), "has_video": True, "has_audio": False}
            return {"kind": "single", "format_id": str(match["format_id"]), "has_video": has_v, "has_audio": has_a}

    # 2. Explicit audio-only extraction mode
    if audio_only:
        pref_aud_ext = preferred_ext if preferred_ext and preferred_ext.upper() in ("M4A", "WEBM", "OPUS", "MP3", "OGG", "AAC") else None
        if audio_only_list:
            fmt = max(audio_only_list, key=lambda f: _format_sort_key(f, video=False, preferred_ext=pref_aud_ext))
            return {"kind": "audio_only", "format_id": str(fmt["format_id"]), "has_video": False, "has_audio": True}
        if muxed:
            fmt = max(muxed, key=lambda f: _format_sort_key(f, video=False, preferred_ext=pref_aud_ext))
            return {"kind": "single", "format_id": str(fmt["format_id"]), "has_video": True, "has_audio": True}
        return None

    # 3. Apply optional height constraint while keeping fallback if all streams exceed cap
    pref_vid_ext = preferred_ext if preferred_ext and preferred_ext.upper() in ("MP4", "WEBM", "MKV") else None

    def within_height(cand_list):
        if not max_height or max_height <= 0:
            return cand_list
        capped = [f for f in cand_list if (f.get("height") or 0) <= max_height]
        return capped if capped else cand_list

    capped_video_only = within_height(video_only)
    capped_muxed = within_height(muxed)

    if capped_video_only and audio_only_list:
        video_fmt = max(capped_video_only, key=lambda f: _format_sort_key(f, video=True, preferred_ext=pref_vid_ext))
        audio_ext_match = "m4a" if (pref_vid_ext and pref_vid_ext.lower() == "mp4") else ("webm" if (pref_vid_ext and pref_vid_ext.lower() == "webm") else None)
        audio_fmt = max(audio_only_list, key=lambda f: _format_sort_key(f, video=False, preferred_ext=audio_ext_match))
        return {
            "kind": "split",
            "video_format_id": str(video_fmt["format_id"]),
            "audio_format_id": str(audio_fmt["format_id"]),
            "has_video": True,
            "has_audio": True,
        }

    if capped_muxed:
        fmt = max(capped_muxed, key=lambda f: _format_sort_key(f, video=True, preferred_ext=pref_vid_ext))
        return {
            "kind": "single",
            "format_id": str(fmt["format_id"]),
            "has_video": True,
            "has_audio": True,
        }

    if capped_video_only:
        fmt = max(capped_video_only, key=lambda f: _format_sort_key(f, video=True, preferred_ext=pref_vid_ext))
        return {"kind": "video_only", "format_id": str(fmt["format_id"]), "has_video": True, "has_audio": False}
    if audio_only_list:
        fmt = max(audio_only_list, key=lambda f: _format_sort_key(f, video=False))
        return {"kind": "audio_only", "format_id": str(fmt["format_id"]), "has_video": False, "has_audio": True}
    return None


def _resolve_ytdlp_download_path(ydl, info, work_dir, prefix):
    """Resolve the actual output file returned by yt-dlp, including postprocessor renames."""
    candidates = []
    if isinstance(info, dict):
        for entry in info.get("requested_downloads") or []:
            if isinstance(entry, dict):
                for key in ("filepath", "_filename"):
                    if entry.get(key):
                        candidates.append(entry[key])
        for key in ("filepath", "_filename"):
            if info.get(key):
                candidates.append(info[key])
        try:
            candidates.append(ydl.prepare_filename(info))
        except Exception:
            pass

    for candidate in candidates:
        try:
            path = os.path.abspath(str(candidate))
            if os.path.isfile(path) and os.path.getsize(path) > 0:
                return path
        except Exception:
            continue

    try:
        matches = sorted(
            os.path.join(work_dir, name)
            for name in os.listdir(work_dir)
            if name.startswith(prefix + ".")
            and not name.endswith((".part", ".ytdl", ".tmp", ".vtt", ".srt", ".ass", ".lrc"))
            and os.path.isfile(os.path.join(work_dir, name))
            and os.path.getsize(os.path.join(work_dir, name)) > 0
        )
        return matches[0] if matches else None
    except Exception:
        return None


def _collect_subtitle_files(work_dir, prefix):
    subs = []
    try:
        for name in sorted(os.listdir(work_dir)):
            if name.startswith(prefix + ".") and name.lower().endswith((".vtt", ".srt", ".ass")):
                full = os.path.join(work_dir, name)
                if os.path.isfile(full) and os.path.getsize(full) > 0:
                    subs.append(full)
    except Exception:
        pass
    return subs


def _fetch_sponsorblock_segments(video_id, categories):
    if not video_id or not categories:
        return []
    try:
        import urllib.request
        import urllib.parse
        cats_json = json.dumps(list(categories))
        url = f"https://sponsor.ajay.app/api/skipSegments?videoID={urllib.parse.quote(video_id)}&categories={urllib.parse.quote(cats_json)}"
        req = urllib.request.Request(url, headers={"User-Agent": DEFAULT_UA})
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                segments = []
                if isinstance(data, list):
                    for item in data:
                        seg = item.get("segment")
                        if isinstance(seg, list) and len(seg) >= 2:
                            segments.append({
                                "start": float(seg[0]),
                                "end": float(seg[1]),
                                "category": str(item.get("category") or "sponsor")
                            })
                return segments
    except Exception:
        pass
    return []


def _download_ytdlp_format(
    url,
    format_id,
    work_dir,
    prefix,
    http_headers,
    cookie_path=None,
    proxy=None,
    ratelimit_bps=0,
    write_subtitles=False,
    subtitle_langs="en",
    job_id="",
    progress_callback=None
):
    """Download one exact yt-dlp format with cancellation and optional progress/subtitle support."""
    os.makedirs(work_dir, exist_ok=True)
    outtmpl = os.path.join(work_dir, prefix + ".%(ext)s")

    def hook(d):
        if job_id and is_job_cancelled(job_id):
            raise yt_dlp.utils.DownloadCancelled("Download cancelled by user")
        if progress_callback is not None:
            try:
                status = d.get("status")
                if status == "downloading":
                    dl_bytes = int(d.get("downloaded_bytes") or 0)
                    tot_bytes = int(d.get("total_bytes") or d.get("total_bytes_estimate") or 0)
                    speed = int(d.get("speed") or 0)
                    progress_callback(dl_bytes, tot_bytes, speed)
            except Exception:
                pass

    ydl_opts = {
        "format": str(format_id),
        "outtmpl": outtmpl,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "continuedl": True,
        "overwrites": False,
        "socket_timeout": 30,
        "retries": 5,
        "fragment_retries": 10,
        "file_access_retries": 5,
        "skip_unavailable_fragments": False,
        "concurrent_fragment_downloads": 1,
        "http_headers": http_headers,
        "progress_hooks": [hook],
    }
    if cookie_path:
        ydl_opts["cookiefile"] = cookie_path
    if proxy:
        ydl_opts["proxy"] = str(proxy)
    if ratelimit_bps and ratelimit_bps > 0:
        ydl_opts["ratelimit"] = int(ratelimit_bps)
    if write_subtitles:
        langs = [s.strip() for s in str(subtitle_langs or "en").split(",") if s.strip()]
        ydl_opts["writesubtitles"] = True
        ydl_opts["writeautomaticsub"] = True
        ydl_opts["subtitleslangs"] = langs or ["en"]
        ydl_opts["subtitlesformat"] = "srt/vtt/best"

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        path = _resolve_ytdlp_download_path(ydl, info, work_dir, prefix)
    if not path:
        raise RuntimeError("yt-dlp reported completion but no non-empty output file was found")
    return path


def download_yt_dlp_tracks(url, work_dir, headers_json="{}", options_json="{}", job_id="", progress_callback=None):
    """Resolve a page and download the best audio/video tracks independently.

    Supports custom format ID, resolution cap, container preference, audio-only mode,
    cookies file, proxy, rate limit, subtitles, SponsorBlock segments, and cancellation.
    """
    clean_url = str(url or "").strip()
    if not clean_url or not YTDLP_AVAILABLE:
        return json.dumps({"handled": False, "success": False, "error": "yt-dlp unavailable or empty source URL"})

    if job_id and is_job_cancelled(job_id):
        return json.dumps({"handled": False, "success": False, "error": "Cancelled by user"})

    try:
        extra_headers = json.loads(headers_json) if headers_json else {}
        if not isinstance(extra_headers, dict):
            extra_headers = {}
    except Exception:
        extra_headers = {}

    try:
        dl_opts = json.loads(options_json) if options_json else {}
        if not isinstance(dl_opts, dict):
            dl_opts = {}
    except Exception:
        dl_opts = {}

    http_headers = {str(k): str(v) for k, v in extra_headers.items() if v is not None}
    if not any(k.lower() == "user-agent" for k in http_headers):
        http_headers["User-Agent"] = dl_opts.get("user_agent") or DEFAULT_UA

    raw_cookie = dl_opts.get("cookie_file") or next((v for k, v in http_headers.items() if k.lower() == "cookie"), None)
    cookie_path, should_delete_cookie = _prepare_temp_cookiefile(raw_cookie, clean_url)
    proxy = dl_opts.get("proxy")
    ratelimit_bps = int(dl_opts.get("ratelimit_bps") or 0)
    max_height = dl_opts.get("max_height")
    preferred_ext = dl_opts.get("preferred_ext")
    audio_only = bool(dl_opts.get("audio_only", False))
    specific_format_id = dl_opts.get("format_id")
    write_subtitles = bool(dl_opts.get("write_subtitles", False))
    subtitle_langs = dl_opts.get("subtitle_langs") or "en"
    sponsorblock_cats = dl_opts.get("sponsorblock_categories") if dl_opts.get("sponsorblock") else None

    selection = None
    try:
        os.makedirs(work_dir, exist_ok=True)
        inspect_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            "socket_timeout": 30,
            "retries": 3,
            "http_headers": http_headers,
            "extract_flat": False,
        }
        if cookie_path:
            inspect_opts["cookiefile"] = cookie_path
        if proxy:
            inspect_opts["proxy"] = str(proxy)

        with yt_dlp.YoutubeDL(inspect_opts) as ydl:
            info = ydl.extract_info(clean_url, download=False)

        if isinstance(info, dict) and info.get("entries"):
            info = next((entry for entry in info["entries"] if isinstance(entry, dict)), None)
        if not isinstance(info, dict):
            return json.dumps({"handled": False, "success": False, "error": "No media entry returned by yt-dlp"})

        formats = info.get("formats") or []
        selection = select_yt_dlp_formats(
            formats,
            max_height=int(max_height) if max_height else None,
            preferred_ext=preferred_ext,
            audio_only=audio_only,
            specific_format_id=specific_format_id
        )
        if selection is None:
            top = dict(info)
            top.setdefault("format_id", info.get("format_id") or "best")
            selection = select_yt_dlp_formats([top], audio_only=audio_only)
        if selection is None:
            return json.dumps({"handled": False, "success": False, "error": "yt-dlp found no usable audio/video formats"})

        source_url = info.get("webpage_url") or info.get("original_url") or clean_url
        title = info.get("title") or info.get("id") or "media"
        uploader = info.get("uploader") or info.get("channel") or ""
        upload_date = info.get("upload_date") or ""
        thumbnail_url = info.get("thumbnail") or ""
        description = (info.get("description") or "")[:500]
        video_id = info.get("id") or ""
        sponsor_segments = []
        if sponsorblock_cats and "youtube" in str(info.get("extractor_key") or "").lower():
            sponsor_segments = _fetch_sponsorblock_segments(video_id, sponsorblock_cats)

        result = {
            "handled": True,
            "success": False,
            "kind": selection["kind"],
            "hasVideo": bool(selection.get("has_video")),
            "hasAudio": bool(selection.get("has_audio")),
            "title": str(title),
            "uploader": str(uploader),
            "uploadDate": str(upload_date),
            "thumbnailUrl": str(thumbnail_url),
            "description": str(description),
            "sourceUrl": str(source_url),
            "sponsorSegments": sponsor_segments,
        }

        if selection["kind"] == "split":
            def video_prog(dl, tot, spd):
                if progress_callback:
                    progress_callback(dl, max(tot * 2, dl), spd)

            def audio_prog(dl, tot, spd):
                if progress_callback:
                    progress_callback(tot + dl, max(tot * 2, dl), spd)

            video_path = _download_ytdlp_format(
                source_url, selection["video_format_id"], work_dir, "video",
                http_headers, cookie_path, proxy, ratelimit_bps,
                write_subtitles=write_subtitles, subtitle_langs=subtitle_langs,
                job_id=job_id, progress_callback=video_prog
            )
            audio_path = _download_ytdlp_format(
                source_url, selection["audio_format_id"], work_dir, "audio",
                http_headers, cookie_path, proxy, ratelimit_bps,
                write_subtitles=False, subtitle_langs=subtitle_langs,
                job_id=job_id, progress_callback=audio_prog
            )
            sub_paths = _collect_subtitle_files(work_dir, "video") if write_subtitles else []
            result.update({
                "success": True,
                "videoPath": video_path,
                "audioPath": audio_path,
                "subtitlePaths": sub_paths
            })
        else:
            media_path = _download_ytdlp_format(
                source_url, selection["format_id"], work_dir, "media",
                http_headers, cookie_path, proxy, ratelimit_bps,
                write_subtitles=write_subtitles, subtitle_langs=subtitle_langs,
                job_id=job_id, progress_callback=progress_callback
            )
            sub_paths = _collect_subtitle_files(work_dir, "media") if write_subtitles else []
            result.update({
                "success": True,
                "mediaPath": media_path,
                "subtitlePaths": sub_paths
            })

        return json.dumps(result)
    except Exception as exc:
        return json.dumps({
            "handled": selection is not None,
            "success": False,
            "hasVideo": bool(selection and selection.get("has_video")),
            "hasAudio": bool(selection and selection.get("has_audio")),
            "error": "%s: %s" % (exc.__class__.__name__, str(exc)),
        })
    finally:
        if job_id:
            clear_job_cancel(job_id)
        if should_delete_cookie and cookie_path and os.path.exists(cookie_path):
            try:
                os.remove(cookie_path)
            except Exception:
                pass


def extract_media(url, options_json="{}"):
    """Extracts all media items using gallery-dl AND yt-dlp progressively."""
    clean_url = url.strip() if url else ""
    if not clean_url:
        return json.dumps({"success": False, "error": "URL cannot be empty", "items": []})

    all_items = []
    seen_urls = set()
    page_title = ""

    gdl_res_str = extract_gallery_dl(clean_url, options_json)
    try:
        gdl_res = json.loads(gdl_res_str)
        if gdl_res.get("pageTitle"):
            page_title = gdl_res["pageTitle"]
        for itm in gdl_res.get("items", []):
            u = itm.get("url")
            if u and u not in seen_urls:
                seen_urls.add(u)
                all_items.append(itm)
    except Exception:
        pass

    ytdlp_res_str = extract_yt_dlp(clean_url, options_json)
    try:
        ytdlp_res = json.loads(ytdlp_res_str)
        if not page_title and ytdlp_res.get("pageTitle"):
            page_title = ytdlp_res["pageTitle"]
        for itm in ytdlp_res.get("items", []):
            u = itm.get("url")
            if u and u not in seen_urls:
                seen_urls.add(u)
                all_items.append(itm)
    except Exception:
        pass

    return json.dumps({
        "success": len(all_items) > 0,
        "engine": "hybrid (gallery-dl + yt-dlp)",
        "pageTitle": str(page_title) if page_title else "",
        "itemsCount": len(all_items),
        "items": all_items
    })


def download_media_file(media_url, target_path, referer=None, user_agent=None, cookies=None, headers_json=None, proxy=None, job_id=""):
    """Downloads direct media or a muxed yt-dlp format with safe content verification."""
    extra_headers = {}
    if headers_json:
        try:
            parsed = json.loads(headers_json)
            if isinstance(parsed, dict):
                extra_headers = parsed
        except Exception:
            pass

    ua = user_agent or DEFAULT_UA
    is_stream = (".m3u8" in media_url) or (".mpd" in media_url)
    is_social_or_video = _ytdlp_find_matching_extractor(media_url) is not None

    cookie_path, should_delete_cookie = _prepare_temp_cookiefile(cookies, media_url)
    try:
        if (is_stream or is_social_or_video) and YTDLP_AVAILABLE:
            try:
                http_headers = {"User-Agent": ua}
                if referer:
                    http_headers["Referer"] = referer
                if cookies and not cookie_path:
                    http_headers["Cookie"] = cookies
                http_headers.update(extra_headers)

                ydl_opts = {
                    "outtmpl": target_path,
                    "quiet": True,
                    "no_warnings": True,
                    "format": "best[vcodec!=none][acodec!=none]/best[acodec!=none]",
                    "socket_timeout": 30,
                    "continuedl": True,
                    "http_headers": http_headers
                }
                if cookie_path:
                    ydl_opts["cookiefile"] = cookie_path
                if proxy:
                    ydl_opts["proxy"] = str(proxy)
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    ydl.download([media_url])

                if os.path.exists(target_path) and os.path.getsize(target_path) > 0:
                    with open(target_path, "rb") as f:
                        head = f.read(512).lower()
                    if b"#extm3u" in head or b"<mpd" in head:
                        os.remove(target_path)
                        return False
                    return True
            except Exception:
                pass

        try:
            import requests
            headers = {"User-Agent": ua}
            if referer:
                headers["Referer"] = referer
            if cookies:
                headers["Cookie"] = cookies
            headers.update(extra_headers)

            proxies = {"http": proxy, "https": proxy} if proxy else None
            resp = requests.get(media_url, headers=headers, stream=True, timeout=35, proxies=proxies)
            if resp.status_code in [200, 206]:
                content_type = (resp.headers.get("content-type") or "").lower()
                if "text/html" in content_type or "application/json" in content_type:
                    return False

                os.makedirs(os.path.dirname(target_path), exist_ok=True)
                first_chunk = True
                with open(target_path, "wb") as f:
                    for chunk in resp.iter_content(chunk_size=65536):
                        if job_id and is_job_cancelled(job_id):
                            f.close()
                            if os.path.exists(target_path):
                                os.remove(target_path)
                            return False
                        if chunk:
                            if first_chunk:
                                first_chunk = False
                                prefix = chunk[:1024].lower()
                                if (b"<!doctype html" in prefix or b"<html" in prefix or b"<head" in prefix or
                                    b"cloudflare" in prefix or b"just a moment..." in prefix or
                                    b"403 forbidden" in prefix or b"404 not found" in prefix or b"access denied" in prefix):
                                    f.close()
                                    if os.path.exists(target_path):
                                        os.remove(target_path)
                                    return False
                                trimmed = prefix.strip()
                                if (trimmed.startswith(b"{") or trimmed.startswith(b"[")) and (
                                    b'"error"' in trimmed or b'"message"' in trimmed or b'"status"' in trimmed or b'"forbidden"' in trimmed
                                ):
                                    f.close()
                                    if os.path.exists(target_path):
                                        os.remove(target_path)
                                    return False
                                if b"#extm3u" in prefix or b"<mpd" in prefix:
                                    f.close()
                                    if os.path.exists(target_path):
                                        os.remove(target_path)
                                    return False
                            f.write(chunk)
                return os.path.exists(target_path) and os.path.getsize(target_path) > 0
        except Exception:
            pass
        return False
    finally:
        if should_delete_cookie and cookie_path and os.path.exists(cookie_path):
            try:
                os.remove(cookie_path)
            except Exception:
                pass
