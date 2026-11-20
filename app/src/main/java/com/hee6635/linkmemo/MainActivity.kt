package com.hee6635.linkmemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private var pendingShare: String? = null

    // 백업 파일 저장 · 열기 (안드로이드 저장 창: 내 폰 · 구글 드라이브 등)
    private var backupVideo = false
    private val createBackup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            try {
                val n = contentResolver.openOutputStream(uri)!!.use { Backup.make(this, it, backupVideo) }
                toast("백업했어요 · 메모 ${n}개")
            } catch (e: Throwable) { toast("백업하지 못했어요: ${e.message}") }
        }
    }
    private val openBackup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            try {
                val n = contentResolver.openInputStream(uri)!!.use { Backup.restore(this, it) }
                toast("되살렸어요 · 메모 ${n}개")
                js("App.onResume&&App.onResume()")
            } catch (e: Throwable) { toast("되살리지 못했어요: ${e.message}") }
        }
    }

    private val assets by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!SaveService.alive) Store.fixStale(this)
        PhoneState.start(this)
        web = WebView(this)
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            js("window.App&&App.insets(${b.top / d},${b.bottom / d})")
            insets
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val path = req.url.path ?: return null
                if (req.url.host == HOST && path.startsWith("/files/")) return serveFile(path.removePrefix("/files/"), req)
                return assets.shouldInterceptRequest(req.url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                ViewCompat.requestApplyInsets(web)
                pendingShare?.let { deliverShare(it) }
                pendingShare = null
            }
        }
        Events.listener = { o -> js("App.onItem(${JSONObject.quote(o.toString())})") }
        Events.chapters = { id, j -> js("App.onChapters&&App.onChapters(${JSONObject.quote(id)},$j)") }
        Events.ocr = { id, j -> js("App.onOcr&&App.onOcr(${JSONObject.quote(id)},${j})") }
        Events.partial = { id, txt -> js("App.onPartial&&App.onPartial(${JSONObject.quote(id)},${JSONObject.quote(txt)})") }
        web.loadUrl("https://$HOST/assets/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("App.onBack()") { r -> if (r != "true") finish() }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PhoneState.markForeground(true)
        if (pageReady) js("App.onResume&&App.onResume()")
        try { AiRetry.kick(this) } catch (_: Throwable) {}
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        if (pageReady) deliverShare(text) else pendingShare = text
    }

    private fun deliverShare(text: String) = js("App.onShare(${JSONObject.quote(text)})")

    private fun js(code: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }

    /** 저장한 영상 · 그림 보내기. 영상 넘기기(구간 이동)를 위해 Range 요청을 받는다 */
    private fun serveFile(rel: String, req: WebResourceRequest): WebResourceResponse {
        val root = Store.itemsRoot(this)
        val f = File(root, Uri.decode(rel))
        if (!f.canonicalPath.startsWith(root.canonicalPath) || !f.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
        }
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
            "png" -> "image/png"; else -> "image/jpeg"
        }
        val len = f.length()
        val range = req.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-cache")
        if (range != null && range.startsWith("bytes=")) {
            val (a, b) = range.removePrefix("bytes=").split(",")[0].split("-").let { it[0] to it.getOrElse(1) { "" } }
            val start = a.toLongOrNull() ?: 0L
            val end = (b.toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            val n = (end - start + 1).coerceAtLeast(0)
            val ins = FileInputStream(f).also { it.skip(start) }
            headers["Content-Range"] = "bytes $start-$end/$len"
            headers["Content-Length"] = n.toString()
            return WebResourceResponse(mime, null, 206, "Partial Content", headers, Limited(ins, n))
        }
        headers["Content-Length"] = len.toString()
        return WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(f))
    }

    private class Limited(private val s: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = s.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = s.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
        override fun close() = s.close()
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    inner class Bridge {
        private val ctx get() = this@MainActivity

        @JavascriptInterface fun items(): String = Store.all(ctx)

        /** 새 링크 저장. 이미 저장한 링크면 {"dup": id} */
        @JavascriptInterface fun add(url: String, mode: String, memo: String): String = Saver.add(ctx, url, mode, memo).toString()

        /** 화면에서 고친 칸만 덮어쓰기 (메모 · 태그 · 캡처 메모 · 제목) */
        @JavascriptInterface fun update(id: String, fields: String): String {
            val f = JSONObject(fields)
            return Store.patch(ctx, id) { o -> f.keys().forEach { k -> o.put(k, f.get(k)) } }?.toString() ?: ""
        }

        @JavascriptInterface fun remove(id: String) = Store.delete(ctx, id)

        @JavascriptInterface fun removeMany(ids: String) {
            val a = JSONArray(ids); for (i in 0 until a.length()) Store.delete(ctx, a.getString(i))
        }
        @JavascriptInterface fun updateMany(ids: String, fields: String) {
            val a = JSONArray(ids); val set = (0 until a.length()).map { a.getString(it) }.toSet()
            Store.patchMany(ctx, set, JSONObject(fields))
        }
        @JavascriptInterface fun meta(): String = Store.meta(ctx)
        @JavascriptInterface fun setMeta(json: String) = Store.setMeta(ctx, json)

        @JavascriptInterface fun retry(id: String) {
            Store.patch(ctx, id) { it.put("status", "saving"); it.put("stage", "대기 중"); it.remove("error") }
                ?.let { Events.item(it) }
            SaveService.start(ctx, id)
        }

        /** 지금 장면 캡처: 화면이 보낸 JPG(base64) 저장 → 글자 읽기는 뒤에서 */
        /** at: 붙일 곳 {"sec":"summary|facts","i":n} 또는 "" (캡처 칸에만) */
        @JavascriptInterface fun capture(id: String, t: Double, b64: String, at: String): String {
            val capId = "cap_" + System.currentTimeMillis()
            val file = File(Store.dir(ctx, id), "$capId.jpg")
            file.writeBytes(Base64.decode(b64.substringAfter(","), Base64.DEFAULT))
            val o = Store.patch(ctx, id) {
                val arr = it.optJSONArray("captures") ?: JSONArray()
                val c = JSONObject().put("id", capId).put("t", t).put("file", file.name).put("text", "").put("memo", "").put("ocr", "busy")
                if (at.isNotBlank()) c.put("at", JSONObject(at))
                arr.put(c)
                it.put("captures", arr)
            }
            Ocr.run(ctx, id, capId, file)
            return o?.toString() ?: ""
        }

        /** 캡처 여러 개 지우기 */
        @JavascriptInterface fun removeCaptures(id: String, ids: String): String {
            val set = JSONArray(ids).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            set.forEach { File(Store.dir(ctx, id), "$it.jpg").delete() }
            return Store.patch(ctx, id) {
                val arr = it.optJSONArray("captures") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") !in set) out.put(arr.get(i))
                it.put("captures", out)
            }?.toString() ?: ""
        }

        @JavascriptInterface fun removeCapture(id: String, capId: String): String {
            File(Store.dir(ctx, id), "$capId.jpg").delete()
            return Store.patch(ctx, id) {
                val arr = it.optJSONArray("captures") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") != capId) out.put(arr.get(i))
                it.put("captures", out)
            }?.toString() ?: ""
        }

        @JavascriptInterface fun open(url: String) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("열 수 있는 앱이 없어요") }
        }

        /** 반자동: 제미나이 앱에 부탁 (글 복사 + 앱 열기) / 복사해 온 답 넣기 */
        @JavascriptInterface fun manualAsk(id: String) {
            if (Manual.send(this@MainActivity, id)) toast("부탁 글을 복사했어요 · 제미나이에 붙여넣고 보내 주세요")
            else toast("제미나이 앱을 열지 못했어요")
        }
        @JavascriptInterface fun manualPaste(id: String): String = Manual.paste(this@MainActivity, id)?.toString() ?: ""

        /** 캘린더 앱에 일정 넣기 화면 열기. ev = {title, date:YYYY-MM-DD, time:HH:MM, end:YYYY-MM-DD, place, note} */
        @JavascriptInterface fun addCalendar(ev: String) {
            try {
                val o = org.json.JSONObject(ev)
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val dt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                val time = o.optString("time").trim()
                val allDay = !Regex("^\\d{1,2}:\\d{2}$").matches(time)
                val begin = (if (allDay) day.parse(o.optString("date")) else dt.parse(o.optString("date") + " " + time))?.time ?: throw IllegalArgumentException()
                val endDay = o.optString("end").takeIf { it.isNotBlank() }?.let { day.parse(it)?.time }
                val end = when {
                    endDay != null && endDay >= begin -> if (allDay) endDay + 86_400_000L else endDay + 86_400_000L - 60_000L
                    allDay -> begin + 86_400_000L
                    else -> begin + 3_600_000L
                }
                val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, o.optString("title"))
                    .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, o.optString("place"))
                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, o.optString("note"))
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
                startActivity(i)
            } catch (_: android.content.ActivityNotFoundException) { toast("캘린더 앱이 없어요") }
            catch (_: Exception) { toast("날짜를 읽지 못했어요") }
        }

        @JavascriptInterface fun copy(text: String) {
            runOnUiThread {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("메모", text))
            }
        }

        @JavascriptInterface fun shareText(name: String, text: String) {
            val dir = File(cacheDir, "share").also { it.mkdirs() }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().take(40).ifEmpty { "메모" }
            val f = File(dir, "$safe.txt")
            f.writeText(text)
            val uri = FileProvider.getUriForFile(ctx, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "내보내기"))
        }

        /** 영상(또는 캡처 그림)을 갤러리로 */
        @JavascriptInterface fun exportFile(id: String, name: String) {
            thread {
                try {
                    val src = File(Store.dir(ctx, id), name)
                    val isVideo = name.startsWith("video")
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "linkmemo_${id}_$name")
                        put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/LinkMemo" else "Pictures/LinkMemo")
                    }
                    val col = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val uri = contentResolver.insert(col, cv) ?: throw IllegalStateException()
                    contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                    toast("갤러리에 저장했어요")
                } catch (e: Exception) { toast("저장하지 못했어요") }
            }
        }

        @JavascriptInterface fun settings(): String {
            val ig = CookieManager.getInstance().getCookie("https://www.instagram.com")?.contains("sessionid") == true
            return JSONObject()
                .put("quality", Prefs.quality(ctx))
                .put("igLogin", ig)
                .put("used", Store.usedBytes(ctx))
                .put("tool", try { YoutubeDL.getInstance().versionName(ctx) ?: "" } catch (_: Throwable) { "" })
                .put("app", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("aiOn", Gemini.on(ctx))
                .put("hasKey", Gemini.key(ctx).isNotEmpty())
                .put("keyTail", Gemini.key(ctx).takeLast(4))
                .put("aiUsed", Gemini.usedThisMonth(ctx))
                .put("aiSpeed", Gemini.speed(ctx))
                .put("watch", Gemini.watch(ctx))
                .put("batteryOpt", !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                .toString()
        }

        @JavascriptInterface fun setQuality(q: String) = Prefs.setQuality(ctx, q)
        @JavascriptInterface fun setGeminiKey(k: String) = Gemini.setKey(ctx, k)
        @JavascriptInterface fun setAiOn(v: Boolean) = Gemini.setOn(ctx, v)
        @JavascriptInterface fun aiRedo(id: String) = SaveService.start(ctx, id, "ai")
        @JavascriptInterface fun aiCancel() = Gemini.cancel()
        @JavascriptInterface fun usage(): String = Usage.summary(ctx)
        @JavascriptInterface fun setLimit(model: String, n: Int) = Usage.setLimit(ctx, model, n)
        /** 긴 영상도 영상 받기 */
        @JavascriptInterface fun downloadVideo(id: String) = SaveService.start(ctx, id, "video")
        /** 앱 화면이 못 푸는 영상: 안드로이드 기본 플레이어로 (t 초부터) */
        @JavascriptInterface fun playNative(id: String, name: String, t: Double) {
            startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("path", File(Store.dir(ctx, id), name).absolutePath).putExtra("t", t))
        }
        @JavascriptInterface fun igPhotos(id: String) = SaveService.start(ctx, id, "igPhotos")
        @JavascriptInterface fun backup(withVideo: Boolean) {
            backupVideo = withVideo
            val d = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            runOnUiThread { createBackup.launch("linkmemo-$d${if (withVideo) "-video" else ""}.zip") }
        }
        @JavascriptInterface fun restore() { runOnUiThread { openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } }
        /** 질문하기: 답은 App.onAsk(결과) 로 */
        @JavascriptInterface fun ask(q: String, context: String, history: String) {
            thread {
                val r = try { JSONObject(Gemini.ask(ctx, q, context, history)) } catch (e: Throwable) { JSONObject().put("error", Gemini.explain(e)) }
                js("App.onAsk&&App.onAsk(${JSONObject.quote(r.toString())})")
            }
        }
        @JavascriptInterface fun setAiSpeed(v: String) = Gemini.setSpeed(ctx, v)
        @JavascriptInterface fun setWatch(v: Boolean) = Gemini.setWatch(ctx, v)
        /** 배터리 최적화 해제 요청 (뒤에서도 통신이 늦어지지 않게) */
        @JavascriptInterface fun batteryUnrestrict() {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        @JavascriptInterface fun igLogin() = startActivity(Intent(ctx, LoginActivity::class.java))

        @JavascriptInterface fun igLogout() {
            runOnUiThread {
                val cm = CookieManager.getInstance()
                cm.getCookie("https://www.instagram.com")?.split(";")?.forEach {
                    val k = it.substringBefore("=").trim()
                    cm.setCookie("https://www.instagram.com", "$k=; Max-Age=0; Domain=.instagram.com; Path=/")
                }
                cm.flush()
                js("App.onResume&&App.onResume()")
            }
        }

        @JavascriptInterface fun deleteVideos() { Store.deleteVideos(ctx) }

        @JavascriptInterface fun updateTool() {
            thread {
                try {
                    Tools.init(ctx)
                    val r = YoutubeDL.getInstance().updateYoutubeDL(ctx)
                    toast(if (r == YoutubeDL.UpdateStatus.DONE) "최신으로 바꿨어요" else "이미 최신이에요")
                } catch (e: Throwable) { toast("업데이트하지 못했어요") }
                js("App.onResume&&App.onResume()")
            }
        }

        @JavascriptInterface fun toast(msg: String) = this@MainActivity.toast(msg)

        /** 검사: 단계마다 App.onDiag(결과) */
        @JavascriptInterface fun diag(url: String) {
            thread {
                try {
                    Diag.run(ctx, url) { o -> js("App.onDiag(${JSONObject.quote(o.toString())})") }
                } catch (e: Throwable) {
                    js("App.onDiag(${JSONObject.quote(JSONObject().put("end", true).put("error", e.toString()).toString())})")
                }
            }
        }

        /** 이미 저장한 메모의 댓글만 다시 가져오기 */
        @JavascriptInterface fun refetchComments(id: String) = SaveService.start(ctx, id, "comments")
    }

    override fun onPause() { PhoneState.markForeground(false); super.onPause() }

    override fun onDestroy() {
        if (Events.listener != null) Events.listener = null
        super.onDestroy()
    }

    companion object { const val HOST = "appassets.androidplatform.net" }
}
