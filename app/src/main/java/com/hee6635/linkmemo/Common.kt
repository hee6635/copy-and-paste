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

/** 화면에 "이 메모가 바뀌었어요"를 알리는 통로 (MainActivity 가 듣는다) */
object Events {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: ((JSONObject) -> Unit)? = null
    fun item(o: JSONObject) { val l = listener ?: return; main.post { l(o) } }
    /** AI 가 쓰는 중인 글 (주르륵 보여 주기용) */
    /** 폰이 먼저 읽은 화면 글자 (영상 보는 동안) */
    @Volatile var ocr: ((String, String) -> Unit)? = null
    fun ocr(id: String, json: String) { val l = ocr ?: return; main.post { l(id, json) } }
    /** 긴 영상 목차가 부분마다 채워질 때 */
    @Volatile var chapters: ((String, String) -> Unit)? = null
    fun chapters(id: String, json: String) { val l = chapters ?: return; main.post { l(id, json) } }
    @Volatile var partial: ((String, String) -> Unit)? = null
    fun partial(id: String, text: String) { val l = partial ?: return; main.post { l(id, text) } }
}

/** 링크 저장 시작 (앱 화면 · 공유 작은 창 같이 씀). 이미 저장한 링크면 {"dup": id} */
object Saver {
    fun add(ctx: android.content.Context, url: String, mode: String, memo: String): JSONObject {
        Store.findByUrl(ctx, url)?.let { return JSONObject().put("dup", it.optString("id")) }
        val id = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()
        val o = JSONObject()
            .put("id", id).put("url", url).put("mode", mode).put("memo", memo)
            .put("platform", SaveService.platformOf(url))
            .put("title", url).put("status", "saving").put("stage", "대기 중").put("progress", 0)
            .put("tags", org.json.JSONArray()).put("captures", org.json.JSONArray()).put("comments", org.json.JSONArray())
            .put("createdAt", System.currentTimeMillis())
        Store.put(ctx, o)
        SaveService.start(ctx, id)
        Events.item(o)
        return o
    }
}
