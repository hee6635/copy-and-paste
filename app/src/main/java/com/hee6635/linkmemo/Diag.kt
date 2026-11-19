package com.hee6635.linkmemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 검사: 링크 하나로 저장 과정을 처음부터 한 단계씩 돌려 보고, 단계마다 ✓ / ✕ 와 이유를 알려 준다.
 * (저장은 하지 않음)
 */
object Diag {
    private class Step(val i: Int, val name: String) {
        var state = "run"; var msg = ""; var detail = ""
        fun json() = JSONObject().put("i", i).put("name", name).put("state", state).put("msg", msg).put("detail", detail)
    }

    fun run(ctx: Context, url: String, emit: (JSONObject) -> Unit) {
        var n = 0
        fun step(name: String, body: (Step) -> Unit): Step {
            val s = Step(n++, name)
            emit(s.json())
            val t0 = System.currentTimeMillis()
            try {
                body(s)
                if (s.state == "run") s.state = "ok"
            } catch (e: Throwable) {
                s.state = "fail"
                if (s.msg.isEmpty()) s.msg = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(160) ?: e.toString()
                s.detail = (s.detail + "\n" + (e.message ?: e.toString())).trim().takeLast(3000)
            }
            s.detail = (s.detail + "\n(${System.currentTimeMillis() - t0}ms)").trim()
            emit(s.json())
            return s
        }
        fun skip(name: String, why: String) {
            emit(Step(n++, name).also { it.state = "skip"; it.msg = why }.json())
        }

        val platform = SaveService.platformOf(url)
        var info: JSONObject? = null

        step("링크 종류") { s ->
            if (!url.startsWith("http")) throw IllegalStateException("링크가 아니에요")
            s.msg = when (platform) { "youtube" -> "유튜브"; "instagram" -> "인스타"; else -> "일반 웹페이지" }
            if (platform == "instagram") {
                val code = Ig.code(url) ?: throw IllegalStateException("인스타 링크인데 게시물 번호가 없어요 (프로필 · 스토리 링크는 안 돼요)")
                s.msg += " · 게시물 $code"
                s.detail = "media id ${Ig.mediaId(code)}"
            }
        }

        step("인터넷 연결") { s ->
            val host = when (platform) { "youtube" -> "https://www.youtube.com/robots.txt"; "instagram" -> "https://www.instagram.com/robots.txt"; else -> url }
            val c = URL(host).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", SaveService.UA)
            val code = c.responseCode
            s.msg = "응답 $code"
            if (code >= 500) throw IllegalStateException("사이트 응답 $code")
        }

        if (platform == "instagram") step("인스타 로그인") { s ->
            if (!Ig.loggedIn()) throw IllegalStateException("로그인 안 됨 → 설정 › 인스타 로그인")
            s.msg = "로그인됨"
        }

        if (platform != "web") {
            step("영상 도구 준비") { s ->
                Tools.init(ctx)
                s.msg = "yt-dlp " + (YoutubeDL.getInstance().versionName(ctx) ?: "기본")
            }

            val read = step("내용 읽기 (yt-dlp)") { s ->
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("-J")
                if (platform == "youtube") {
                    req.addOption("--write-comments")
                    req.addOption("--extractor-args", "youtube:max_comments=20,all,0,0;comment_sort=top")
                }
                if (platform == "instagram") Ig.cookieFile(ctx)?.let { req.addOption("--cookies", it.absolutePath) }
                val r = YoutubeDL.getInstance().execute(req, null, false, null)
                val j = JSONObject(r.out.trim().lineSequence().last { it.startsWith("{") })
                info = j
                val desc = j.optString("description")
                s.msg = "제목 ✓ · 설명 ${desc.length}자"
                s.detail = "title: ${j.optString("title").take(100)}\nuploader: ${j.optString("uploader")} / ${j.optString("channel")}\nduration: ${j.optDouble("duration", 0.0)}"
                if (r.err.isNotBlank()) s.detail += "\n\n[경고]\n" + r.err.takeLast(800)
            }

            if (read.state == "ok") step("영상 받기 가능") { s ->
                val fs = info!!.optJSONArray("formats")
                var maxH = 0; var videos = 0
                if (fs != null) for (i in 0 until fs.length()) {
                    val f = fs.getJSONObject(i)
                    if (f.optString("vcodec") != "none" && f.optInt("height") > 0) { videos++; maxH = maxOf(maxH, f.optInt("height")) }
                }
                if (videos == 0 && info!!.optString("url").isEmpty()) throw IllegalStateException("받을 수 있는 영상이 없어요 (사진 게시물일 수 있어요)")
                val q = Prefs.quality(ctx)
                s.msg = "영상 형식 ${videos}개 · 최대 ${maxH}p · 설정 ${if (q == "best") "최고" else q + "p"}"
            } else skip("영상 받기 가능", "앞 단계 실패")
        }

        if (platform == "instagram") {
            step("퍼가기 페이지 (예비 방법)") { s ->
                val e = Ig.embed(url)
                s.msg = "설명 ${e.caption.length}자 · 사진 ${if (e.img.isNotEmpty()) "✓" else "✕"} · 영상 주소 ${if (e.video != null) "✓" else "✕"}"
                s.detail = "user: ${e.user}\n${e.caption.take(200)}"
            }
            if (Ig.loggedIn()) step("댓글 가져오기") { s ->
                val cs = Ig.comments(ctx, url, info?.optString("channel") ?: "")
                var reps = 0
                for (i in 0 until cs.length()) reps += cs.getJSONObject(i).optJSONArray("r")?.length() ?: 0
                s.msg = "댓글 ${cs.length()}개 · 작성자 답글 $reps"
                if (cs.length() == 0) s.detail = "인스타가 댓글 0개를 돌려줬어요 (댓글이 막혀 있거나 없음)"
            } else skip("댓글 가져오기", "인스타 로그인이 필요해요")
        }

        if (platform == "youtube") {
            if (info != null) step("댓글 가져오기") { s ->
                val cs = info!!.optJSONArray("comments")
                s.msg = "댓글 ${cs?.length() ?: 0}개"
                if (cs == null) throw IllegalStateException("댓글 정보가 없어요 (댓글이 꺼져 있을 수 있어요)")
            } else skip("댓글 가져오기", "앞 단계 실패")
        }

        if (platform == "web") step("웹페이지 읽기") { s ->
            val doc = Jsoup.connect(url).userAgent(SaveService.UA).timeout(20000).followRedirects(true).get()
            val title = doc.select("meta[property=og:title]").attr("content").ifEmpty { doc.title() }
            val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            val len = root?.select("p, li, h1, h2, h3")?.sumOf { it.text().length } ?: 0
            s.msg = "제목 ${if (title.isNotBlank()) "✓" else "✕"} · 본문 ${len}자"
            s.detail = title
            if (len == 0) throw IllegalStateException("본문 글자를 못 찾았어요 (화면을 그림으로 그리는 사이트일 수 있어요)")
        }

        step("화면 글자 읽기 (캡처용)") { s ->
            val bmp = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawText("검사 123", 30f, 105f, Paint().apply { color = Color.BLACK; textSize = 72f; isAntiAlias = true })
            }
            val r = Tasks.await(
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bmp, 0)),
                40, TimeUnit.SECONDS
            )
            val text = r.text.replace("\n", " ").trim()
            s.msg = if (text.isEmpty()) "글자를 못 읽음" else "읽음: $text"
            if (text.isEmpty()) throw IllegalStateException("글자 읽기 모델이 아직 준비 안 됨 (구글 Play 서비스가 받는 중일 수 있어요)")
        }

        step("저장 공간") { s ->
            val free = ctx.filesDir.usableSpace
            s.msg = "남은 공간 ${free / 1_000_000}MB · 앱 사용 ${Store.usedBytes(ctx) / 1_000_000}MB"
            if (free < 200_000_000) throw IllegalStateException("폰 저장 공간이 부족해요 (${free / 1_000_000}MB)")
        }

        emit(JSONObject().put("end", true))
    }
}
