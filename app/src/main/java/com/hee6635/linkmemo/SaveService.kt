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
        val action = intent.getStringExtra("action") ?: "save"
        running.incrementAndGet()
        scope.launch {
            queue.withLock {
                when (action) {
                    "comments" -> try { refetchComments(id) } catch (_: Throwable) {}
                    "ai" -> try { aiInto(id, true) } catch (_: Throwable) {}
                    else -> {
                        try { process(id) } catch (e: Throwable) { fail(id, e.message ?: e.toString()) }
                        if (Store.get(this@SaveService, id)?.optString("status") == "done") try { aiInto(id, false) } catch (_: Throwable) {}
                    }
                }
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

    private fun friendly(msg: String): String {
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
                try {
                    saveWithYtdlp(id, url, withVideo)
                } catch (e: Throwable) {
                    try { saveIgEmbed(id, url, withVideo) } catch (e2: Throwable) {
                        throw IllegalStateException("[yt-dlp] ${e.message}\n\n[embed] ${e2.message}")
                    }
                }
                igCommentsInto(id, url)
                emit(Store.patch(this, id) { it.put("status", "done"); it.remove("stage") })
            }
        }
    }

    /** 폴더 경로 목록 (meta.json) — "여행 › 나트랑" */
    private fun folderPaths(): List<String> = try {
        val fs = JSONObject(Store.meta(this)).optJSONArray("folders") ?: JSONArray()
        val byId = (0 until fs.length()).map { fs.getJSONObject(it) }.associateBy { it.optString("id") }
        byId.values.map { f ->
            val p = f.optString("parent").takeIf { it.isNotEmpty() && it != "null" }?.let { byId[it]?.optString("name") }
            if (p != null) "$p › ${f.optString("name")}" else f.optString("name")
        }
    } catch (_: Throwable) { emptyList() }

    /** Gemini 정리. 실패해도 저장은 그대로 두고 aiError 만 남김 */
    private fun aiInto(id: String, force: Boolean) {
        if (!(if (force) Gemini.key(this).isNotEmpty() else Gemini.on(this))) {
            if (force) emit(Store.patch(this, id) { it.put("aiError", "Gemini API 키가 없어요 (설정에서 넣어 주세요)") })
            return
        }
        val item = Store.get(this, id) ?: return
        emit(Store.patch(this, id) { it.put("aiBusy", "AI 정리 중"); it.remove("aiError") })
        updateNotif("AI 정리 중")
        try {
            val ai = Gemini.organize(this, item, folderPaths()) { st ->
                emit(Store.patch(this, id) { it.put("aiBusy", "AI $st") })
            }
            emit(Store.patch(this, id) { o ->
                o.put("ai", ai)
                // 자동 태그: 있는 태그에 더하기
                val tags = o.optJSONArray("tags") ?: JSONArray()
                val have = (0 until tags.length()).map { tags.getString(it) }.toMutableSet()
                ai.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) { val v = t.getString(i).removePrefix("#").trim(); if (v.isNotEmpty() && have.add(v) && tags.length() < 8) tags.put(v) } }
                o.put("tags", tags)
                // 정보 있는 댓글 ★
                val cs = o.optJSONArray("comments")
                val star = ai.optJSONArray("star")
                if (cs != null) {
                    for (i in 0 until cs.length()) cs.getJSONObject(i).remove("star")
                    if (star != null) for (i in 0 until star.length()) cs.optJSONObject(star.optInt(i, -1))?.put("star", true)
                }
                o.remove("aiBusy"); o.remove("aiError")
            })
        } catch (e: Throwable) {
            emit(Store.patch(this, id) { it.remove("aiBusy"); it.put("aiError", e.message ?: e.toString()) })
        }
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
            if (thumb != null) it.put("thumb", thumb)
            if (videoName != null) { it.put("video", videoName); it.put("videoSize", File(dir, videoName).length()) }
        })
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
        if (platformOf(url) == "instagram") Ig.cookieFile(this)?.let { req.addOption("--cookies", it.absolutePath) }
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

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }

    companion object {
        const val CH = "save"
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
