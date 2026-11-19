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
    /** AI 가 쓰는 중인 글 (주르륵 보여 주기용) */
    /** 폰이 먼저 읽은 화면 글자 (영상 보는 동안) */
    @Volatile var ocr: ((String, String) -> Unit)? = null
    fun ocr(id: String, json: String) { val l = ocr ?: return; main.post { l(id, json) } }
    @Volatile var partial: ((String, String) -> Unit)? = null
    fun partial(id: String, text: String) { val l = partial ?: return; main.post { l(id, text) } }
}
