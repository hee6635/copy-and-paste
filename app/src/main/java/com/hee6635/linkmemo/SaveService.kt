package com.hee6635.linkmemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 링크 저장 일꾼. 공유 · 붙여넣기로 받은 메모를 하나씩 처리한다.
 * - 유튜브 · 인스타: yt-dlp 로 설명 · 댓글 · 썸네일 (+ 영상)
 * - 그 밖의 웹페이지: 제목 · 본문 · 대표 그림
 */
class SaveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alive = true
        startAsForeground("저장 준비 중")
        val id = intent?.getStringExtra("id")
        if (id == null) { stopIfIdle(); return START_NOT_STICKY }
        val action = intent.getStringExtra("action") ?: "save"
        running.incrementAndGet()
        scope.launch {
            queue.withLock {
                when (action) {
                    "comments" -> try { refetchComments(id) } catch (_: Throwable) {}
                    "video" -> try { downloadLater(id) } catch (_: Throwable) {}
                    "igPhotos" -> try { igPhotosLater(id) } catch (_: Throwable) {}
                    "ai" -> try { aiInto(id, true) } catch (_: Throwable) {}
                    "aiAuto" -> try { if (id in AiRetry.due(this@SaveService)) aiInto(id, true, auto = true) } catch (_: Throwable) {}
                    else -> {
                        try { process(id) } catch (e: Throwable) { fail(id, e.message ?: e.toString()) }
                        if (Store.get(this@SaveService, id)?.optString("status") == "done") try { aiInto(id, false) } catch (_: Throwable) {}
                    }
                }
                // 실패했던 AI 정리 중 다시 할 차례인 것 (일꾼이 깨어 있는 김에)
                if (action != "aiAuto") AiRetry.due(this@SaveService).forEach { d -> try { aiInto(d, true, auto = true) } catch (_: Throwable) {} }
            }
            if (running.decrementAndGet() == 0) stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (running.get() > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "저장", NotificationManager.IMPORTANCE_LOW))
        }
        val n = notif(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    internal fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(text))
    }

    internal fun emit(o: JSONObject?) { if (o != null) Events.item(o) }

    internal fun stage(id: String, text: String, progress: Int? = null) {
        emit(Store.patch(this, id) {
            it.put("status", "saving"); it.put("stage", text)
            if (progress != null) it.put("progress", progress)
        })
    }

    private fun fail(id: String, msg: String) {
        emit(Store.patch(this, id) {
            it.put("status", "error"); it.put("error", friendly(msg)); it.remove("stage")
            it.put("errorDetail", msg.takeLast(4000))
        })
    }

    internal fun friendly(msg: String): String {
        val m = msg.lowercase()
        return when {
            ("instagram" in m || "[embed]" in m) && !Ig.loggedIn() -> "인스타 로그인이 필요해요"
            "login" in m || "cookies" in m || "rate-limit" in m || "401" in m -> "인스타 로그인이 필요해요"
            "private" in m -> "비공개 게시물이에요"
            "unable to resolve" in m || "network" in m || "timed out" in m -> "인터넷 연결을 확인해 주세요"
            else -> msg.lineSequence().lastOrNull { it.isNotBlank() }?.take(200) ?: "저장하지 못했어요"
        }
    }

    private fun process(id: String) {
        val item = Store.get(this, id) ?: return
        val url = item.optString("url")
        updateNotif("저장 중")
        val withVideo = item.optString("mode") != "link"
        when (platformOf(url)) {
            "web" -> saveWeb(id, url)
            "youtube" -> saveWithYtdlp(id, url, withVideo)
            else -> {
                // 인스타: yt-dlp → 안 되면 퍼가기(embed) 페이지 → 그래도 안 되면 두 오류를 같이 남김
                // 인스타: yt-dlp → 안 되면(사진 게시물 등) 로그인된 인스타 통로(사진 전부 · 캡션) → 그래도 안 되면 퍼가기 페이지
                try {
                    saveWithYtdlp(id, url, withVideo)
                } catch (e: Throwable) {
                    try { saveIgApi(id, url, withVideo) } catch (e1: Throwable) {
                        try { saveIgEmbed(id, url, withVideo) } catch (e2: Throwable) {
                            throw IllegalStateException("[yt-dlp] ${e.message}\n\n[인스타] ${e1.message}\n\n[embed] ${e2.message}")
                        }
                    }
                }
                igCommentsInto(id, url)
                emit(Store.patch(this, id) { it.put("status", "done"); it.remove("stage") })
            }
        }
    }

    /** AI 가 볼 영상을 작게(360p 이하) 잠깐 받기 */
    internal fun tempVideo(id: String, url: String): File {
        Tools.init(this)
        val dir = File(cacheDir, "aiv").also { it.mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith(id) }?.forEach { it.delete() }
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-f", "b[height<=360][ext=mp4]/bv*[height<=360]+ba/b[height<=360]/wv*+ba/w")
        req.addOption("--merge-output-format", "mp4")
        req.addOption("-o", File(dir, "$id.%(ext)s").absolutePath)
        if (platformOf(url) == "instagram") Ig.cookieFile(this)?.let { req.addOption("--cookies", it.absolutePath) }
        YoutubeDL.getInstance().execute(req, "aiv-$id", false, null)
        return dir.listFiles()?.firstOrNull { it.name.startsWith("$id.") && it.extension in setOf("mp4", "webm", "mkv", "mov") }
            ?: throw IllegalStateException("영상 파일이 안 생겼어요")
    }

    /** 인스타 댓글을 받아 메모에 넣기. 실패하면 이유를 commentError 에 남김 */
    private fun igCommentsInto(id: String, url: String) {
        val owner = Store.get(this, id)?.optString("author") ?: ""
        try {
            val cs = Ig.comments(this, url, owner)
            Store.patch(this, id) { it.put("comments", cs); it.remove("commentError") }
        } catch (e: Throwable) {
            Store.patch(this, id) { it.put("commentError", e.message ?: e.toString()) }
        }
    }

    /** 이미 저장한 메모의 댓글만 다시 */
    private fun refetchComments(id: String) {
        val item = Store.get(this, id) ?: return
        val url = item.optString("url")
        emit(Store.patch(this, id) { it.put("commentBusy", true) })
        try {
            if (platformOf(url) == "instagram") igCommentsInto(id, url)
            else if (platformOf(url) == "youtube") {
                try {
                    val cs = ytComments(url)
                    Store.patch(this, id) { it.put("comments", cs); it.remove("commentError") }
                } catch (e: Throwable) {
                    Store.patch(this, id) { it.put("commentError", e.message ?: e.toString()) }
                }
            }
        } finally {
            emit(Store.patch(this, id) { it.remove("commentBusy") })
        }
    }

    private fun ytComments(url: String): JSONArray {
        Tools.init(this)
        val dir = File(cacheDir, "ytc").also { it.deleteRecursively(); it.mkdirs() }
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist"); req.addOption("--skip-download")
        req.addOption("--write-info-json"); req.addOption("--write-comments")
        req.addOption("--extractor-args", "youtube:max_comments=60,all,0,0;comment_sort=top")
        req.addOption("-o", File(dir, "m.%(ext)s").absolutePath)
        YoutubeDL.getInstance().execute(req, null, false, null)
        val f = File(dir, "m.info.json")
        if (!f.exists()) throw IllegalStateException("정보 파일이 없어요")
        return topComments(JSONObject(f.readText()).optJSONArray("comments"))
    }

    // ── 인스타: 퍼가기(embed) 페이지 ──
    private fun saveIgEmbed(id: String, url: String, withVideo: Boolean) {
        stage(id, "내용 읽는 중", 0)
        val e = Ig.embed(url)
        val dir = Store.dir(this, id)
        var thumb: String? = null
        if (e.img.isNotEmpty()) try {
            URL(e.img).openStream().use { inp -> File(dir, "thumb.jpg").outputStream().use { inp.copyTo(it) } }
            thumb = "thumb.jpg"
        } catch (_: Throwable) {}
        var videoName: String? = null
        if (withVideo && e.video != null) try {
            stage(id, "영상 받는 중", 30)
            URL(e.video).openStream().use { inp -> File(dir, "video.mp4").outputStream().use { inp.copyTo(it) } }
            videoName = "video.mp4"
        } catch (_: Throwable) {}

        emit(Store.patch(this, id) {
            it.put("status", "done"); it.remove("stage"); it.remove("error"); it.put("progress", 100)
            it.put("platform", "instagram")
            it.put("kind", if (e.video != null) "릴스" else "게시물")
            it.put("title", firstLine(e.caption).ifEmpty { "${e.user} 게시물" })
            it.put("author", e.user)
            it.put("desc", e.caption)
            it.put("comments", JSONArray())
            if (thumb != null) { it.put("thumb", thumb); it.put("photos", JSONArray().put(JSONObject().put("file", thumb).put("text", Ocr.textOf(File(dir, thumb))))) }
            if (videoName != null) { it.put("video", videoName); it.put("videoSize", File(dir, videoName).length()) }
        })
    }

    // ── 유튜브 · 인스타 ──
    /** 10분 넘으면 긴 영상: 영상은 받지 않고(링크만), 목차 방식으로 정리 */
    private val LONG_SEC = 600.0

    private fun saveWithYtdlp(id: String, url: String, withVideo: Boolean) {
        stage(id, "준비 중", 0)
        Tools.init(this)
        val dir = Store.dir(this, id)
        dir.listFiles()?.filter { it.name.startsWith("media.") }?.forEach { it.delete() }
        val platform = platformOf(url)

        // 1) 내용 먼저: 설명 · 댓글 · 썸네일 · (유튜브) 자막
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--skip-download")
        req.addOption("--write-info-json")
        req.addOption("--write-thumbnail")
        req.addOption("--convert-thumbnails", "jpg")
        req.addOption("--write-comments")
        req.addOption("--extractor-args", "youtube:max_comments=60,all,0,0;comment_sort=top")
        req.addOption("--socket-timeout", "30")
        if (platform == "youtube") {
            req.addOption("--write-subs"); req.addOption("--write-auto-subs")
            req.addOption("--sub-langs", "ko,.*-orig")  // 한국어 + 영상 원래 말 자막만 (적게 받아야 유튜브 429 를 덜 맞음)
            req.addOption("--sub-format", "vtt")
        }
        req.addOption("-o", File(dir, "media.%(ext)s").absolutePath)
        if (platform == "instagram") Ig.cookieFile(this)?.let { req.addOption("--cookies", it.absolutePath) }
        stage(id, "내용 읽는 중", 0)
        // 자막 · 썸네일 같은 곁가지가 실패해도(예: 자막 429) 내용(info.json)을 읽었으면 그대로 저장
        val infoFile = File(dir, "media.info.json")
        try { YoutubeDL.getInstance().execute(req, "save-$id", false, null) }
        catch (e: Throwable) {
            if (platform != "youtube") throw e
            if (!infoFile.exists()) {
                // 자막 때문에 내용까지 못 받았으면 자막 없이 한 번 더
                stage(id, "자막 없이 다시 읽는 중", 0)
                val r2 = YoutubeDLRequest(url)
                listOf("--no-playlist", "--skip-download", "--write-info-json", "--write-thumbnail", "--write-comments").forEach { r2.addOption(it) }
                r2.addOption("--convert-thumbnails", "jpg")
                r2.addOption("--extractor-args", "youtube:max_comments=60,all,0,0;comment_sort=top")
                r2.addOption("--socket-timeout", "30")
                r2.addOption("-o", File(dir, "media.%(ext)s").absolutePath)
                try { YoutubeDL.getInstance().execute(r2, "save2-$id", false, null) } catch (e2: Throwable) { if (!infoFile.exists()) throw e2 }
            }
        }
        if (!infoFile.exists()) throw IllegalStateException("내용을 읽지 못했어요")
        val info = JSONObject(infoFile.readText())
        val thumb = File(dir, "media.jpg")
        if (thumb.exists()) thumb.renameTo(File(dir, "thumb.jpg"))
        val subs = readSubs(dir)
        infoFile.delete()
        dir.listFiles()?.filter { it.name.startsWith("media.") }?.forEach { it.delete() }

        val dur = info.optDouble("duration", 0.0)
        val long = dur >= LONG_SEC

        // 2) 영상: 짧은 것만 (긴 영상은 링크만 + 나중에 [영상 받기])
        var videoName: String? = null
        if (withVideo && !long) videoName = downloadVideoFile(id, url)

        val desc = info.optString("description").trim()
        val w = info.optInt("width"); val h = info.optInt("height")
        val rawTitle = info.optString("title").trim()
        val title = if (platform == "instagram" || rawTitle.isEmpty() || rawTitle.startsWith("Video by"))
            firstLine(desc).ifEmpty { rawTitle } else rawTitle
        val author = listOf("channel", "uploader", "uploader_id")
            .map { info.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: ""
        val kind = when {
            platform == "youtube" && (url.contains("/shorts/") || (h > w && dur in 1.0..180.0)) -> "쇼츠"
            platform == "youtube" -> "영상"
            info.has("duration") || videoName != null -> "릴스"
            else -> "게시물"
        }
        val chapters = JSONArray()
        info.optJSONArray("chapters")?.let { c -> for (i in 0 until c.length()) { val x = c.getJSONObject(i); chapters.put(JSONObject().put("t", x.optDouble("start_time")).put("title", x.optString("title"))) } }

        val done = Store.patch(this, id) {
            it.put("status", "done"); it.remove("stage"); it.remove("error"); it.put("progress", 100)
            it.put("platform", platform)
            it.put("kind", kind)
            it.put("title", title.take(120))
            it.put("author", author)
            it.put("desc", desc)
            it.put("duration", dur)
            it.put("comments", topComments(info.optJSONArray("comments")))
            if (File(dir, "thumb.jpg").exists()) it.put("thumb", "thumb.jpg")
            if (w > 0 && h > 0) { it.put("w", w); it.put("h", h) }
            if (subs.length() > 0) it.put("subs", subs)
            if (chapters.length() > 0) it.put("ytChapters", chapters)
            if (long) it.put("long", true)
            if (long && withVideo) it.put("longSkipped", true)
            if (videoName != null) { it.put("video", videoName); it.put("videoSize", File(dir, videoName).length()) }
        }
        emit(done)
    }

    /** 영상 파일만 받기 (진행률 표시) → video.<확장자> */

    /** 긴 영상도 [영상 받기]를 누르면 받기 */
    private fun downloadLater(id: String) {
        val item = Store.get(this, id) ?: return
        try {
            val name = downloadVideoFile(id, item.optString("url")) ?: throw IllegalStateException("영상 파일이 안 생겼어요")
            emit(Store.patch(this, id) { it.put("status", "done"); it.remove("stage"); it.remove("longSkipped"); it.remove("videoError"); it.put("video", name); it.put("videoAt", System.currentTimeMillis()); it.put("videoSize", File(Store.dir(this, id), name).length()) })
        } catch (e: Throwable) {
            emit(Store.patch(this, id) { it.put("status", "done"); it.remove("stage"); it.put("videoError", friendly(e.message ?: e.toString())) })
        }
    }

    /** 자막(vtt) → [{t, text}] 약 10초씩 묶음. 한국어 → 영어 → 그 밖 순으로 하나 고름 */
    private fun readSubs(dir: File): JSONArray {
        val files = dir.listFiles()?.filter { it.name.startsWith("media.") && it.name.endsWith(".vtt") } ?: emptyList()
        val pick = listOf("media.ko.", "media.ko-", "media.en.", "media.en-").firstNotNullOfOrNull { pre -> files.firstOrNull { it.name.startsWith(pre) } } ?: files.firstOrNull()
            ?: return JSONArray()
        val out = JSONArray()
        val tm = Regex("(\\d+):(\\d{2}):(\\d{2})[.,](\\d{3})\\s+-->")
        var cur = -1.0; var start = 0.0; val buf = StringBuilder(); var lastLine = ""; var total = 0
        fun flush() { if (buf.isNotBlank()) { out.put(JSONObject().put("t", start).put("text", buf.toString().trim())); total += buf.length }; buf.setLength(0) }
        for (raw in pick.readLines()) {
            val m = tm.find(raw)
            if (m != null) {
                val (hh, mm, ss, ms) = m.destructured
                cur = hh.toInt() * 3600 + mm.toInt() * 60 + ss.toInt() + ms.toInt() / 1000.0
                if (buf.isEmpty()) start = cur
                if (cur - start >= 10) { flush(); start = cur }
                continue
            }
            if (cur < 0 || raw.isBlank() || raw.startsWith("WEBVTT") || raw.startsWith("Kind:") || raw.startsWith("Language:")) continue
            val line = raw.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
            if (line.isEmpty() || line == lastLine) continue
            lastLine = line
            buf.append(line).append(' ')
            if (total > 80000) break
        }
        flush()
        return out
    }


    /** 좋아요 많은 순 20개 + 각 댓글에 단 작성자 답글 */
    private fun topComments(arr: JSONArray?): JSONArray {
        val list = mutableListOf<JSONObject>()
        val byId = mutableMapOf<String, JSONObject>()
        val replies = mutableListOf<Pair<String, JSONObject>>()
        if (arr != null) for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val parent = c.optString("parent", "root")
            val text = c.optString("text").trim()
            if (text.isEmpty()) continue
            val o = JSONObject().put("a", c.optString("author").removePrefix("@"))
                .put("t", text).put("l", c.optInt("like_count", 0))
            if (parent != "root" && parent.isNotEmpty()) {
                if (c.optBoolean("author_is_uploader")) replies.add(parent to o.put("own", true))
                continue
            }
            byId[c.optString("id")] = o
            list.add(o)
        }
        replies.forEach { (p, o) ->
            val parent = byId[p] ?: return@forEach
            val r = parent.optJSONArray("r") ?: JSONArray().also { parent.put("r", it) }
            if (r.length() < 3) r.put(o)
        }
        list.sortByDescending { it.optInt("l") }
        return JSONArray().also { out -> list.take(20).forEach { out.put(it) } }
    }

    // ── 일반 웹페이지 ──
    private fun saveWeb(id: String, url: String) {
        stage(id, "내용 읽는 중", 0)
        val conn = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .timeout(20000).followRedirects(true)
        if (platformOf(url) == "instagram") CookieManager.getInstance().getCookie("https://www.instagram.com")
            ?.let { conn.header("Cookie", it) }
        val doc = conn.get()
        fun meta(p: String) = doc.select("meta[property=$p], meta[name=$p]").attr("content").trim()

        val title = meta("og:title").ifEmpty { doc.title() }.trim()
        val desc = meta("og:description").ifEmpty { meta("description") }
        val site = meta("og:site_name").ifEmpty { URL(url).host.removePrefix("www.") }
        val img = meta("og:image")

        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val parts = root?.select("h1, h2, h3, p, li, blockquote")
            ?.map { it.text().trim() }?.filter { it.length > 1 }?.distinct() ?: emptyList()
        val body = parts.joinToString("\n\n").take(20000)

        val dir = Store.dir(this, id)
        var thumb: String? = null
        if (img.isNotEmpty()) try {
            val abs = URL(URL(url), img).toString()
            URL(abs).openStream().use { inp -> File(dir, "thumb.jpg").outputStream().use { inp.copyTo(it) } }
            thumb = "thumb.jpg"
        } catch (_: Throwable) {}

        val platform = platformOf(url)
        emit(Store.patch(this, id) {
            it.put("status", "done"); it.remove("stage"); it.remove("error"); it.put("progress", 100)
            it.put("platform", platform)
            it.put("kind", if (platform == "instagram") "게시물" else "웹")
            it.put("title", title.take(120).ifEmpty { site })
            it.put("author", site)
            it.put("desc", desc)
            it.put("body", body)
            it.put("comments", JSONArray())
            if (thumb != null) it.put("thumb", thumb)
        })
    }

    override fun onDestroy() { alive = false; scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }

    companion object {
        const val CH = "save"
        @Volatile var alive = false
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        fun platformOf(url: String): String {
            val h = try { URL(url).host.lowercase() } catch (_: Exception) { "" }
            return when {
                h.endsWith("youtube.com") || h == "youtu.be" -> "youtube"
                h.endsWith("instagram.com") -> "instagram"
                else -> "web"
            }
        }
        fun firstLine(s: String): String =
            s.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: ""

        fun start(ctx: Context, id: String, action: String = "save") {
            val i = Intent(ctx, SaveService::class.java).putExtra("id", id).putExtra("action", action)
            ctx.startForegroundService(i)
        }
    }
}
