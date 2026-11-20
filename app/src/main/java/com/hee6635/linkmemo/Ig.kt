package com.hee6635.linkmemo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 인스타 전용: 퍼가기(embed) 페이지 읽기 · 댓글 가져오기 */
object Ig {
    const val APP_ID = "936619743392459"

    fun cookies(): String? = CookieManager.getInstance().getCookie("https://www.instagram.com")
    fun loggedIn() = cookies()?.contains("sessionid") == true

    /** 로그인 쿠키를 yt-dlp 가 읽는 파일(Netscape 형식)로 */
    fun cookieFile(ctx: Context): java.io.File? {
        val raw = cookies() ?: return null
        if (!raw.contains("sessionid")) return null
        val exp = System.currentTimeMillis() / 1000 + 3600 * 24 * 30
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
            sb.append(".instagram.com\tTRUE\t/\tTRUE\t$exp\t${it.substringBefore("=")}\t${it.substringAfter("=")}\n")
        }
        return java.io.File(ctx.cacheDir, "ig_cookies.txt").also { it.writeText(sb.toString()) }
    }

    /** instagram.com/reel/<code>/ · /p/<code>/ · /reels/<code>/ · /tv/<code>/ */
    fun code(url: String): String? =
        Regex("instagram\\.com/(?:[^/?#]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

    /** 게시물 번호(shortcode) → 숫자 id */
    fun mediaId(code: String): String {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var n = java.math.BigInteger.ZERO
        val b64 = java.math.BigInteger.valueOf(64)
        for (ch in code.take(11)) n = n.multiply(b64).add(java.math.BigInteger.valueOf(abc.indexOf(ch).toLong()))
        return n.toString()
    }

    class Embed(val user: String, val caption: String, val img: String, val video: String?)

    fun embed(url: String): Embed {
        val code = code(url) ?: throw IllegalStateException("링크에서 게시물 번호를 찾지 못했어요")
        val conn = Jsoup.connect("https://www.instagram.com/p/$code/embed/captioned/")
            .userAgent(SaveService.UA).timeout(20000).followRedirects(true)
        cookies()?.let { conn.header("Cookie", it) }
        val doc = conn.get()
        val html = doc.html()
        val user = doc.selectFirst(".UsernameText, .Username")?.text()?.trim() ?: ""
        val capEl = doc.selectFirst(".Caption")
        capEl?.select(".CaptionUsername, .CaptionComments")?.remove()
        capEl?.select("br")?.forEach { it.after(TextNode("\n")) }
        val caption = capEl?.wholeText()?.trim()?.replace(Regex("\n{3,}"), "\n\n") ?: ""
        val img = doc.selectFirst("img.EmbeddedMediaImage")?.attr("src")?.trim() ?: ""
        val video = Regex("video_url\\\\*\":\\\\*\"(https:[^\"]+?)\\\\*\"").find(html)?.groupValues?.get(1)
            ?.replace("\\\\u0026", "&")?.replace("\\u0026", "&")?.replace("\\", "")
        if (caption.isEmpty() && img.isEmpty() && video == null) throw IllegalStateException("퍼가기 페이지가 비어 있어요 (비공개이거나 막힘)")
        return Embed(user, caption, img, video)
    }

    /**
     * 인스타 웹 통로로 GET. 1) 앱 안 보이지 않는 인스타 화면(진짜 브라우저처럼) 2) 안 되면 직접 요청.
     * 실패하면 두 방법의 이유를 같이 담아 던진다.
     */
    fun apiGet(ctx: Context, url: String): JSONObject {
        val why = StringBuilder()
        try {
            val (code, body) = WebFetch.get(ctx, url)
            if (code == 200) return JSONObject(body)
            why.append("[화면] HTTP $code ${body.take(200)}")
        } catch (e: Throwable) { why.append("[화면] ${e.message}") }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            val ck = cookies() ?: ""
            c.setRequestProperty("Cookie", ck)
            c.setRequestProperty("X-IG-App-ID", APP_ID)
            c.setRequestProperty("X-CSRFToken", Regex("csrftoken=([^;]+)").find(ck)?.groupValues?.get(1) ?: "")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("User-Agent", SaveService.UA)
            c.setRequestProperty("Referer", "https://www.instagram.com/")
            c.connectTimeout = 15000; c.readTimeout = 20000
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code == 200) return JSONObject(body)
            why.append("\n[직접] HTTP $code ${body.take(200)}")
        } catch (e: Throwable) { why.append("\n[직접] ${e.message}") }
        throw IllegalStateException(why.toString())
    }

    class Media(val user: String, val caption: String, val images: List<String>, val video: String?)

    /** 로그인된 인스타 통로로 게시물 정보: 작성자 · 캡션 · 사진 전부(여러 장 게시물 포함, 최대 20) · 영상 주소 */
    fun media(ctx: Context, url: String): Media {
        if (!loggedIn()) throw IllegalStateException("인스타 로그인이 안 되어 있어요")
        val code = code(url) ?: throw IllegalStateException("링크에서 게시물 번호를 찾지 못했어요")
        val j = apiGet(ctx, "https://www.instagram.com/api/v1/media/${mediaId(code)}/info/")
        val m0 = j.optJSONArray("items")?.optJSONObject(0) ?: throw IllegalStateException("게시물 정보가 비어 있어요")
        fun best(o: JSONObject): String? = o.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")?.takeIf { u -> u.isNotEmpty() }
        val imgs = mutableListOf<String>()
        var video: String? = null
        val car = m0.optJSONArray("carousel_media")
        if (car != null) {
            for (i in 0 until car.length()) { val m = car.optJSONObject(i) ?: continue; if (m.optInt("media_type") == 1) best(m)?.let { u -> imgs.add(u) } }
        } else if (m0.optInt("media_type") == 2) {
            video = m0.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")?.takeIf { u -> u.isNotEmpty() }
        } else best(m0)?.let { u -> imgs.add(u) }
        val user = m0.optJSONObject("user")?.optString("username") ?: ""
        val caption = m0.optJSONObject("caption")?.optString("text") ?: ""
        return Media(user, caption, imgs.take(20), video)
    }

    /** 좋아요순 20개 + 작성자 답글. 로그인 안 되어 있으면 이유를 던짐 */
    fun comments(ctx: Context, url: String, ownerHint: String): JSONArray {
        if (!loggedIn()) throw IllegalStateException("인스타 로그인이 안 되어 있어요")
        val code = code(url) ?: throw IllegalStateException("링크에서 게시물 번호를 찾지 못했어요")
        val mid = mediaId(code)
        val base = "https://www.instagram.com/api/v1/media/$mid/comments/"
        val all = mutableListOf<JSONObject>()
        var owner = ownerHint
        var minId: String? = null
        for (page in 0 until 3) {
            val q = "?can_support_threading=true&permalink_enabled=false" +
                (minId?.let { "&min_id=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val j = apiGet(ctx, base + q)
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
            var kids = c.optJSONArray("preview_child_comments") ?: JSONArray()
            if (c.optInt("child_comment_count") > kids.length() && childCalls < 8) {
                childCalls++
                try { kids = apiGet(ctx, base + c.optString("pk") + "/child_comments/").optJSONArray("child_comments") ?: kids } catch (_: Throwable) {}
            }
            val reps = JSONArray()
            for (i in 0 until kids.length()) {
                val k = kids.getJSONObject(i)
                val ku = k.optJSONObject("user")?.optString("username") ?: ""
                if (ku.isNotEmpty() && ku.equals(owner, true) && reps.length() < 3)
                    reps.put(JSONObject().put("a", ku).put("t", k.optString("text")).put("l", k.optInt("comment_like_count")).put("own", true))
            }
            if (reps.length() > 0) o.put("r", reps)
            out.put(o)
        }
        return out
    }
}

/** 보이지 않는 인스타 화면 하나를 띄워 두고, 그 안에서 fetch 로 요청 (브라우저와 같은 쿠키 · 헤더) */
@SuppressLint("SetJavaScriptEnabled")
object WebFetch {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready: CountDownLatch? = null
    private val waits = HashMap<String, Pair<CountDownLatch, Array<Any?>>>()
    private var seq = 0
    private var lastUse = 0L

    private class Cb {
        @JavascriptInterface fun done(key: String, code: Int, body: String) {
            synchronized(waits) { waits[key] }?.let { (l, box) -> box[0] = code; box[1] = body; l.countDown() }
        }
    }

    private fun ensure(ctx: Context) {
        val latch: CountDownLatch
        synchronized(this) {
            if (web != null && ready?.count == 0L) return
            latch = ready ?: CountDownLatch(1).also { ready = it }
            if (web == null) main.post {
                val w = WebView(ctx.applicationContext)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.addJavascriptInterface(Cb(), "IGW")
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { latch.countDown() }
                }
                w.loadUrl("https://www.instagram.com/robots.txt")
                web = w
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            close(); throw IllegalStateException("인스타 화면이 열리지 않아요")
        }
    }

    fun get(ctx: Context, url: String): Pair<Int, String> {
        ensure(ctx)
        val key: String
        val l = CountDownLatch(1)
        val box = arrayOfNulls<Any?>(2)
        synchronized(waits) { key = "k" + (++seq); waits[key] = l to box }
        val js = """(async()=>{try{const m=document.cookie.match(/csrftoken=([^;]+)/);
            const r=await fetch(${JSONObject.quote(url)},{credentials:'include',headers:{'X-IG-App-ID':'${Ig.APP_ID}','X-CSRFToken':m?m[1]:'','X-Requested-With':'XMLHttpRequest'}});
            IGW.done('$key',r.status,await r.text());}catch(e){IGW.done('$key',-1,String(e));}})()"""
        main.post { web?.evaluateJavascript(js, null) }
        val ok = l.await(25, TimeUnit.SECONDS)
        synchronized(waits) { waits.remove(key) }
        lastUse = System.currentTimeMillis()
        main.postDelayed({ if (System.currentTimeMillis() - lastUse > 55000) close() }, 60000)
        if (!ok) throw IllegalStateException("시간 초과")
        return (box[0] as Int) to (box[1] as String)
    }

    fun close() {
        synchronized(this) {
            val w = web; web = null; ready = null
            if (w != null) main.post { w.destroy() }
        }
    }
}
