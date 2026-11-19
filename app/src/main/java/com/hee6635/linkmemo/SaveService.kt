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

/** 화면에 "이 메모가 바뀌었어요"를 알리는 통로 (MainActivity 가 듣는다) */
object Events {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: ((JSONObject) -> Unit)? = null
    fun item(o: JSONObject) { val l = listener ?: return; main.post { l(o) } }
}

/** yt-dlp · ffmpeg 준비 (처음 한 번 압축을 풀어서 몇 초 걸림) */
object Tools {
    @Volatile private var ready = false
    @Synchronized fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun quality(ctx: Context): String = sp(ctx).getString("quality", "720") ?: "720"
    fun setQuality(ctx: Context, q: String) = sp(ctx).edit().putString("quality", q).apply()
}

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
        startAsForeground("저장 준비 중")
        val id = intent?.getStringExtra("id")
        if (id == null) { stopIfIdle(); return START_NOT_STICKY }
        running.incrementAndGet()
        scope.launch {
            queue.withLock {
                try { process(id) } catch (e: Throwable) { fail(id, e.message ?: e.toString()) }
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

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(text))
    }

    private fun emit(o: JSONObject?) { if (o != null) Events.item(o) }

    private fun stage(id: String, text: String, progress: Int? = null) {
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

    private fun igLoggedIn() =
        CookieManager.getInstance().getCookie("https://www.instagram.com")?.contains("sessionid") == true

    private fun friendly(msg: String): String {
        val m = msg.lowercase()
        return when {
            ("instagram" in m || "[embed]" in m) && !igLoggedIn() -> "인스타 로그인이 필요해요"
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
                try {
                    saveWithYtdlp(id, url, withVideo)
                } catch (e: Throwable) {
                    try { saveIgEmbed(id, url, withVideo) } catch (e2: Throwable) {
                        throw IllegalStateException("[yt-dlp] ${e.message}\n\n[embed] ${e2.message}")
                    }
                }
                // 댓글은 yt-dlp 가 못 가져와서 인스타 웹 통로로 따로 (로그인했을 때만)
                try {
                    val owner = Store.get(this, id)?.optString("author") ?: ""
                    igComments(url, owner)?.let { cs ->
                        Store.patch(this, id) { it.put("comments", cs) }
                    }
                } catch (_: Throwable) {}
                emit(Store.patch(this, id) { it.put("status", "done"); it.remove("stage") })
            }
        }
    }

    // ── 인스타: 퍼가기(embed) 페이지 ──
    private fun saveIgEmbed(id: String, url: String, withVideo: Boolean) {
        stage(id, "내용 읽는 중", 0)
        val code = igCode(url) ?: throw IllegalStateException("링크에서 게시물 번호를 찾지 못했어요")
        val conn = Jsoup.connect("https://www.instagram.com/p/$code/embed/captioned/")
            .userAgent(UA).timeout(20000).followRedirects(true)
        CookieManager.getInstance().getCookie("https://www.instagram.com")?.let { conn.header("Cookie", it) }
        val doc = conn.get()
        val html = doc.html()
        val user = doc.selectFirst(".UsernameText, .Username")?.text()?.trim() ?: ""
        val capEl = doc.selectFirst(".Caption")
        capEl?.select(".CaptionUsername, .CaptionComments")?.remove()
        capEl?.select("br")?.forEach { it.after(org.jsoup.nodes.TextNode("\n")) }
        val caption = capEl?.wholeText()?.trim()?.replace(Regex("\n{3,}"), "\n\n") ?: ""
        val img = doc.selectFirst("img.EmbeddedMediaImage")?.attr("src")?.trim() ?: ""
        val video = Regex("video_url\\\\*\":\\\\*\"(https:[^\"]+?)\\\\*\"").find(html)?.groupValues?.get(1)
            ?.replace("\\\\u0026", "&")?.replace("\\u0026", "&")?.replace("\\", "")
        if (caption.isEmpty() && img.isEmpty() && video == null) throw IllegalStateException("퍼가기 페이지가 비어 있어요")

        val dir = Store.dir(this, id)
        var thumb: String? = null
        if (img.isNotEmpty()) try {
            URL(img).openStream().use { inp -> File(dir, "thumb.jpg").outputStream().use { inp.copyTo(it) } }
            thumb = "thumb.jpg"
        } catch (_: Throwable) {}
        var videoName: String? = null
        if (withVideo && video != null) try {
            stage(id, "영상 받는 중", 30)
            URL(video).openStream().use { inp -> File(dir, "video.mp4").outputStream().use { inp.copyTo(it) } }
            videoName = "video.mp4"
        } catch (_: Throwable) {}

        emit(Store.patch(this, id) {
            it.put("status", "done"); it.remove("stage"); it.remove("error"); it.put("progress", 100)
            it.put("platform", "instagram")
            it.put("kind", if (video != null) "릴스" else "게시물")
            it.put("title", firstLine(caption).ifEmpty { "$user 게시물" })
            it.put("author", user)
            it.put("desc", caption)
            it.put("comments", JSONArray())
            if (thumb != null) it.put("thumb", thumb)
            if (videoName != null) { it.put("video", videoName); it.put("videoSize", File(dir, videoName).length()) }
        })
    }

    // ── 인스타 댓글 (좋아요순 20개 + 작성자 답글) ──
    private fun igComments(url: String, ownerHint: String): JSONArray? {
        val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return null
        if (!cookies.contains("sessionid")) return null
        val code = igCode(url) ?: return null
        val csrf = Regex("csrftoken=([^;]+)").find(cookies)?.groupValues?.get(1) ?: ""
        val mid = igMediaId(code)
        fun get(u: String): JSONObject {
            val c = URL(u).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Cookie", cookies)
            c.setRequestProperty("X-IG-App-ID", "936619743392459")
            c.setRequestProperty("X-CSRFToken", csrf)
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Referer", "https://www.instagram.com/p/$code/")
            c.connectTimeout = 15000; c.readTimeout = 20000
            if (c.responseCode != 200) throw IllegalStateException("댓글 ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        }
        val base = "https://www.instagram.com/api/v1/media/$mid/comments/"
        val all = mutableListOf<JSONObject>()
        var owner = ownerHint
        var minId: String? = null
        for (page in 0 until 3) {
            val q = "?can_support_threading=true&permalink_enabled=false" +
                (minId?.let { "&min_id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val j = get(base + q)
            j.optJSONObject("caption")?.optJSONObject("user")?.optString("username")?.takeIf { it.isNotEmpty() }?.let { owner = it }
            val arr = j.optJSONArray("comments") ?: break
            for (i in 0 until arr.length()) all.add(arr.getJSONObject(i))
            minId = j.optString("next_min_id").takeIf { it.isNotEmpty() && it != "null" } ?: break
        }
        all.sortByDescending { it.optInt("comment_like_count") }
        val out = JSONArray()
        var childCalls = 0
        for (c in all.take(20)) {
            val o = JSONObject()
                .put("a", c.optJSONObject("user")?.optString("username") ?: "")
                .put("t", c.optString("text"))
                .put("l", c.optInt("comment_like_count"))
            // 작성자 답글: 미리 온 것 → 부족하면 따로 받기
            var kids = c.optJSONArray("preview_child_comments") ?: JSONArray()
            if (c.optInt("child_comment_count") > kids.length() && childCalls < 8) {
                childCalls++
                try { kids = get(base + c.optString("pk") + "/child_comments/").optJSONArray("child_comments") ?: kids } catch (_: Throwable) {}
            }
            val reps = JSONArray()
            for (i in 0 until kids.length()) {
                val k = kids.getJSONObject(i)
                val ku = k.optJSONObject("user")?.optString("username") ?: ""
                if (ku.isNotEmpty() && ku == owner && reps.length() < 3)
                    reps.put(JSONObject().put("a", ku).put("t", k.optString("text")).put("l", k.optInt("comment_like_count")).put("own", true))
            }
            if (reps.length() > 0) o.put("r", reps)
            out.put(o)
        }
        return out
    }

    // ── 유튜브 · 인스타 ──
    private fun saveWithYtdlp(id: String, url: String, withVideo: Boolean) {
        stage(id, "준비 중", 0)
        Tools.init(this)
        val dir = Store.dir(this, id)
        dir.listFiles()?.filter { it.name.startsWith("media.") }?.forEach { it.delete() }

        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--write-info-json")
        req.addOption("--write-thumbnail")
        req.addOption("--convert-thumbnails", "jpg")
        req.addOption("--write-comments")
        req.addOption("--extractor-args", "youtube:max_comments=60,all,0,0;comment_sort=top")
        req.addOption("--socket-timeout", "30")
        req.addOption("-o", File(dir, "media.%(ext)s").absolutePath)
        if (platformOf(url) == "instagram") cookieFile()?.let { req.addOption("--cookies", it.absolutePath) }
        if (withVideo) {
            req.addOption("-f", formatFor(Prefs.quality(this)))
            req.addOption("--merge-output-format", "mp4")
        } else {
            req.addOption("--skip-download")
        }

        stage(id, if (withVideo) "영상 받는 중" else "내용 읽는 중", 0)
        var last = 0L
        YoutubeDL.getInstance().execute(req, "save-$id", false) { p, _, _ ->
            val now = System.currentTimeMillis()
            if (now - last > 700) {
                last = now
                val pct = p.toInt().coerceIn(0, 100)
                stage(id, "영상 받는 중", pct)
                updateNotif("저장 중 $pct%")
            }
        }

        val infoFile = File(dir, "media.info.json")
        if (!infoFile.exists()) throw IllegalStateException("내용을 읽지 못했어요")
        val info = JSONObject(infoFile.readText())

        val thumb = File(dir, "media.jpg")
        if (thumb.exists()) thumb.renameTo(File(dir, "thumb.jpg"))
        val video = dir.listFiles()?.firstOrNull {
            it.name.startsWith("media.") && it.extension in setOf("mp4", "webm", "mkv", "mov")
        }
        var videoName: String? = null
        if (video != null) {
            videoName = "video." + video.extension
            video.renameTo(File(dir, videoName))
        }
        infoFile.delete()
        dir.listFiles()?.filter { it.name.startsWith("media.") }?.forEach { it.delete() }

        val platform = platformOf(url)
        val desc = info.optString("description").trim()
        val w = info.optInt("width"); val h = info.optInt("height")
        val dur = info.optDouble("duration", 0.0)
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
            if (videoName != null) {
                it.put("video", videoName); it.put("videoSize", File(dir, videoName).length())
                if (w > 0 && h > 0) { it.put("w", w); it.put("h", h) }
            }
        }
        emit(done)
    }

    private fun formatFor(q: String): String = when (q) {
        "best" -> "bv*+ba/b"
        else -> "bv*[height<=$q][ext=mp4]+ba[ext=m4a]/b[height<=$q][ext=mp4]/bv*[height<=$q]+ba/b[height<=$q]/b"
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

    /** 앱 안에서 로그인한 인스타 쿠키를 yt-dlp 가 읽는 파일(Netscape 형식)로 */
    private fun cookieFile(): File? {
        val raw = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return null
        if (!raw.contains("sessionid")) return null
        val exp = System.currentTimeMillis() / 1000 + 3600 * 24 * 30
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
            val k = it.substringBefore("="); val v = it.substringAfter("=")
            sb.append(".instagram.com\tTRUE\t/\tTRUE\t$exp\t$k\t$v\n")
        }
        val f = File(cacheDir, "ig_cookies.txt")
        f.writeText(sb.toString())
        return f
    }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }

    companion object {
        const val CH = "save"
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        /** instagram.com/reel/<code>/ · /p/<code>/ · /reels/<code>/ · /tv/<code>/ */
        fun igCode(url: String): String? =
            Regex("instagram\\.com/(?:[^/]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

        /** 게시물 번호(shortcode) → 숫자 id */
        fun igMediaId(code: String): String {
            val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var n = java.math.BigInteger.ZERO
            val b64 = java.math.BigInteger.valueOf(64)
            for (ch in code.take(11)) n = n.multiply(b64).add(java.math.BigInteger.valueOf(abc.indexOf(ch).toLong()))
            return n.toString()
        }
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

        fun start(ctx: Context, id: String) {
            val i = Intent(ctx, SaveService::class.java).putExtra("id", id)
            ctx.startForegroundService(i)
        }
    }
}
