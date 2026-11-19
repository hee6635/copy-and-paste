package com.hee6635.linkmemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemini 로 정리: 요약 · 태그 · 폴더 추천 · 댓글 꿀팁 · 레시피/체크리스트 · 받아쓰기(영상이 있으면).
 * 키는 폰 안(SharedPreferences)에만 둔다.
 */
object Gemini {
    const val MODEL = "gemini-flash-latest"
    /** 차례로 시도: 최신 → 안정판 → 가벼운 것 */
    val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest")

    class HttpErr(val code: Int, msg: String) : IllegalStateException(msg)
    /** 실패해도 기록(log)은 남김 */
    class AiFail(msg: String, val log: JSONObject) : IllegalStateException(msg)

    fun label(m: String) = when (m) { "gemini-flash-latest" -> "최신 flash"; "gemini-2.5-flash" -> "2.5 flash"; "gemini-flash-lite-latest" -> "flash-lite"; else -> m }

    // 중지: 지금 연결을 끊어서 기다림을 바로 끝냄
    @Volatile private var cancelled = false
    @Volatile private var current: HttpURLConnection? = null
    fun cancel() { cancelled = true; Thread { try { current?.disconnect() } catch (_: Throwable) {} }.start() }
    private fun checkCancel() { if (cancelled) throw IllegalStateException("중지했어요") }

    /** 이번 정리 기록: 단계마다 걸린 시간 · 결과 */
    class RunLog {
        val t0 = System.currentTimeMillis()
        val rows = JSONArray()
        fun add(step: String, ms: Long, note: String, ok: Boolean) {
            rows.put(JSONObject().put("step", step).put("ms", ms).put("note", note).put("ok", ok))
        }
        fun json(usage: JSONObject?, extraWhy: List<String> = emptyList()): JSONObject {
            val total = System.currentTimeMillis() - t0
            val why = mutableListOf<String>()
            var upload = 0L; var wait = 0L; var fails = 0; var genOk = 0L
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                when {
                    r.optString("step") == "영상 올리기" -> upload += r.optLong("ms")
                    r.optString("step") == "영상 처리 대기" -> wait += r.optLong("ms")
                    !r.optBoolean("ok") -> fails++
                    else -> genOk += r.optLong("ms")
                }
            }
            if (upload > 15000) why += "영상 올리기가 ${upload / 1000}초 (영상이 크거나 인터넷이 느림)"
            if (wait > 15000) why += "구글이 영상을 준비하는 데 ${wait / 1000}초"
            if (fails > 0) why += "실패 ${fails}번 → 다시 시도 · 다른 모델로 넘어감"
            val thoughts = usage?.optInt("thoughtsTokenCount") ?: 0
            val outTok = usage?.optInt("candidatesTokenCount") ?: 0
            if (thoughts > outTok && thoughts > 500) why += "AI가 답하기 전에 오래 생각함 (생각 ${thoughts} · 답 ${outTok} 토큰)"
            val vidTok = usage?.optInt("videoTokens") ?: 0
            if (vidTok > 5000) why += "영상이 길어서 볼 양이 많음 (영상 ${vidTok} 토큰)"
            if (genOk > 60000 && why.isEmpty()) why += "모델 응답 자체가 ${genOk / 1000}초 걸림 (서버 혼잡 가능)"
            why += extraWhy
            return JSONObject().put("total", total).put("rows", rows).put("usage", usage ?: JSONObject()).put("why", JSONArray(why))
        }
    }

    /** 오류를 쉬운 말로 (원인 후보 포함) */
    fun explain(e: Throwable): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is HttpErr -> e.message ?: "오류 ${e.code}"
            e is java.net.UnknownHostException -> "구글 서버 주소를 못 찾아요 → 인터넷이 끊겼거나 DNS · 개인 DNS(광고 차단) 설정 문제"
            e is java.net.SocketTimeoutException -> "응답이 너무 늦어요(시간 초과) → 서버 혼잡이거나 인터넷이 느림"
            e is javax.net.ssl.SSLException || "ssl" in m || "certificate" in m -> "보안 연결 실패 → 폰 날짜·시간이 틀렸거나, 공용 와이파이 로그인 화면, VPN · 보안 앱이 가로챔"
            e is java.net.ConnectException -> "서버에 닿지 못했어요 → 인터넷 끊김, 방화벽 · 회사 와이파이 차단"
            "abort" in m || "reset" in m || "broken pipe" in m || "unexpected end" in m ->
                "연결이 중간에 끊겼어요 → 와이파이↔데이터 전환, 앱이 뒤로 가서 절전, 또는 서버 혼잡"
            else -> e.message ?: e.toString()
        }
    }
    private const val BASE = "https://generativelanguage.googleapis.com"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
    fun key(ctx: Context) = sp(ctx).getString("key", "") ?: ""
    fun setKey(ctx: Context, k: String) = sp(ctx).edit().putString("key", k.trim()).apply()
    fun on(ctx: Context) = sp(ctx).getBoolean("on", true) && key(ctx).isNotEmpty()
    fun setOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("on", v).apply()
    private fun month() = SimpleDateFormat("yyyyMM", Locale.US).format(Date())
    fun usedThisMonth(ctx: Context) = sp(ctx).getInt("n" + month(), 0)
    private fun count(ctx: Context) { val k = "n" + month(); sp(ctx).edit().putInt(k, sp(ctx).getInt(k, 0) + 1).apply() }

    private fun http(method: String, url: String, key: String, body: ByteArray?, headers: Map<String, String> = emptyMap(),
                     timeout: Int = 120000): Pair<HttpURLConnection, String> {
        checkCancel()
        val c = URL(url).openConnection() as HttpURLConnection
        current = c
        c.requestMethod = method
        c.connectTimeout = 20000; c.readTimeout = timeout
        c.setRequestProperty("x-goog-api-key", key)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null } ?: text.take(200)
            throw HttpErr(code, when (code) {
                400 -> if (text.contains("API key not valid") || text.contains("API_KEY_INVALID")) "400 키가 맞지 않아요 → 키를 다시 복사해서 넣기" else "400 요청 오류: $msg"
                401 -> "401 인증 실패 → 키를 다시 넣기"
                403 -> "403 이 키로 사용이 막혀 있어요 → AI Studio 에서 키 · API 사용 설정, 지역 제한 확인 ($msg)"
                404 -> "404 모델을 찾을 수 없어요 ($msg)"
                429 -> "429 사용량 한도 → 무료 분당 · 하루 한도를 넘었어요. 잠시 뒤 또는 내일"
                500, 502, 504 -> "$code 구글 서버 오류 → 잠시 뒤 다시"
                503 -> "503 구글 서버가 바빠요(모델 혼잡) → 잠시 뒤 또는 다른 모델"
                else -> "$code $msg"
            })
        }
        return c to text
    }

    /** 영상 올리기 (Files API, 이어 올리기 방식) → file uri */
    private fun upload(ctx: Context, key: String, f: File, mime: String, log: RunLog? = null): String {
        val tUp = System.currentTimeMillis()
        val start = JSONObject().put("file", JSONObject().put("display_name", f.name)).toString().toByteArray()
        val (c1, _) = http("POST", "$BASE/upload/v1beta/files", key, start, mapOf(
            "X-Goog-Upload-Protocol" to "resumable",
            "X-Goog-Upload-Command" to "start",
            "X-Goog-Upload-Header-Content-Length" to f.length().toString(),
            "X-Goog-Upload-Header-Content-Type" to mime,
            "Content-Type" to "application/json"))
        val upUrl = c1.getHeaderField("X-Goog-Upload-URL") ?: throw IllegalStateException("영상 올리기 주소를 못 받았어요")
        val c = URL(upUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 300000
        c.setFixedLengthStreamingMode(f.length())
        c.setRequestProperty("X-Goog-Upload-Offset", "0")
        c.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        current = c
        c.outputStream.use { out -> f.inputStream().use { it.copyTo(out) } }
        if (c.responseCode !in 200..299) throw IllegalStateException("영상 올리기 실패 ${c.responseCode}")
        val file = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("file")
        log?.add("영상 올리기", System.currentTimeMillis() - tUp, String.format(java.util.Locale.US, "%.1fMB", f.length() / 1e6), true)
        val tWait = System.currentTimeMillis()
        val name = file.getString("name")
        var state = file.optString("state")
        var uri = file.optString("uri")
        var tries = 0
        while (state == "PROCESSING" && tries++ < 60) {
            Thread.sleep(2000)
            val (_, t) = http("GET", "$BASE/v1beta/$name", key, null)
            val j = JSONObject(t); state = j.optString("state"); uri = j.optString("uri")
        }
        log?.add("영상 처리 대기", System.currentTimeMillis() - tWait, "확인 ${tries}번", state != "FAILED")
        if (state == "FAILED") throw IllegalStateException("Gemini 가 영상을 읽지 못했어요")
        return uri
    }

    private fun prompt(item: JSONObject, folders: List<String>, withVideo: Boolean): String {
        val sb = StringBuilder()
        sb.append("""너는 사용자가 저장한 SNS 게시물 · 영상 · 웹페이지를 메모로 정리하는 도우미야. 한국어로, 짧고 쉽게.
규칙:
- summary: 핵심 1~3줄. 각 줄 40자 안팎. 광고 문구 · 해시태그 · "저장하세요" 같은 말은 빼고 정보만.
- tags: 2~5개, # 없이 짧은 명사 (지명 · 음식 · 주제).
- folder: 아래 "내 폴더" 중 가장 맞는 것의 경로를 그대로 쓰기. 맞는 게 없으면 "큰폴더 › 작은폴더" 또는 "큰폴더" 형식으로 새로 제안. 작은 폴더는 지명이나 구체 주제일 때만.
- kind: 요리법이면 recipe, 할 일 · 준비물 목록이면 checklist, 그 밖은 general.
- recipe: kind 가 recipe 일 때만. 재료(양 포함)와 순서.
- checklist: kind 가 checklist 일 때만.
- tips: 댓글과 작성자 답글에서 실제로 쓸모 있는 정보만 0~5개 (추천 · 주의 · 가격 · 시간 · 위치 등). 인사 · 태그 · 감탄은 빼기. from 은 작성자 답글이면 author, 아니면 comment. who 는 아이디.
- star: 정보가 있는 댓글의 번호(0부터) 목록.
""")
        if (withVideo) sb.append("- transcript: 영상에서 말하는 내용을 시간(초)과 함께 받아쓰기. 화면에 크게 나오는 글자도 [화면] 으로 넣기. 말이 없으면 빈 목록.\n")
        else sb.append("- transcript: 빈 목록.\n")
        sb.append("\n내 폴더: ").append(if (folders.isEmpty()) "(없음)" else folders.joinToString(" / ")).append("\n\n")
        sb.append("[출처] ").append(item.optString("platform")).append(" · ").append(item.optString("author")).append("\n")
        sb.append("[제목] ").append(item.optString("title")).append("\n")
        item.optString("desc").takeIf { it.isNotBlank() }?.let { sb.append("[설명]\n").append(it.take(6000)).append("\n") }
        item.optString("body").takeIf { it.isNotBlank() }?.let { sb.append("[본문]\n").append(it.take(8000)).append("\n") }
        item.optString("memo").takeIf { it.isNotBlank() }?.let { sb.append("[내 메모] ").append(it).append("\n") }
        val cs = item.optJSONArray("comments")
        if (cs != null && cs.length() > 0) {
            sb.append("[댓글]\n")
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                sb.append(i).append(". @").append(c.optString("a")).append(": ").append(c.optString("t").take(400)).append("\n")
                val r = c.optJSONArray("r")
                if (r != null) for (k in 0 until r.length()) {
                    val x = r.getJSONObject(k)
                    sb.append("   ↳ [작성자 답글] @").append(x.optString("a")).append(": ").append(x.optString("t").take(400)).append("\n")
                }
            }
        }
        return sb.toString()
    }

    private fun schema(): JSONObject {
        fun str() = JSONObject().put("type", "STRING")
        fun arr(of: JSONObject) = JSONObject().put("type", "ARRAY").put("items", of)
        fun obj(vararg p: Pair<String, JSONObject>, req: List<String> = emptyList()) = JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().also { o -> p.forEach { o.put(it.first, it.second) } })
            .also { if (req.isNotEmpty()) it.put("required", JSONArray(req)) }
        return obj(
            "summary" to arr(str()),
            "tags" to arr(str()),
            "folder" to str(),
            "kind" to JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("recipe", "checklist", "general"))),
            "recipe" to obj("ingredients" to arr(str()), "steps" to arr(str())),
            "checklist" to arr(str()),
            "tips" to arr(obj("text" to str(), "from" to str(), "who" to str(), req = listOf("text"))),
            "star" to arr(JSONObject().put("type", "INTEGER")),
            "transcript" to arr(obj("t" to JSONObject().put("type", "NUMBER"), "text" to str(), req = listOf("t", "text"))),
            req = listOf("summary", "tags", "folder", "kind", "tips", "star", "transcript")
        )
    }

    /** 메모 하나 정리. 결과 JSON(ai) 을 돌려준다 */
    fun organize(ctx: Context, item: JSONObject, folders: List<String>, onStage: (String) -> Unit): JSONObject {
        val key = key(ctx)
        if (key.isEmpty()) throw IllegalStateException("Gemini API 키가 없어요 (설정에서 넣어 주세요)")
        cancelled = false
        val log = RunLog()
        return try { organizeImpl(ctx, key, item, folders, onStage, log) }
        catch (e: Throwable) { throw AiFail(if (cancelled) "중지했어요" else explain(e), log.json(null)) }
    }

    private fun organizeImpl(ctx: Context, key: String, item: JSONObject, folders: List<String>, onStage: (String) -> Unit, log: RunLog): JSONObject {
        val parts = JSONArray()
        val vName = item.optString("video")
        val vFile = if (vName.isNotEmpty()) File(Store.dir(ctx, item.optString("id")), vName) else null
        val withVideo = vFile != null && vFile.exists() && vFile.length() < 1_900_000_000L
        if (withVideo) {
            onStage("영상 올리는 중")
            val uri = upload(ctx, key, vFile!!, if (vName.endsWith(".webm")) "video/webm" else "video/mp4", log)
            parts.put(JSONObject().put("file_data", JSONObject().put("mime_type", if (vName.endsWith(".webm")) "video/webm" else "video/mp4").put("file_uri", uri)))
        }
        parts.put(JSONObject().put("text", prompt(item, folders, withVideo)))
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema())
                .put("temperature", 0.3))
        onStage(if (withVideo) "받아쓰는 중" else "정리 중")
        val (text, used) = generate(key, req, onStage, log)
        count(ctx)
        val res = JSONObject(text)
        val um = res.optJSONObject("usageMetadata")
        val usage = JSONObject()
        if (um != null) {
            listOf("promptTokenCount", "candidatesTokenCount", "thoughtsTokenCount", "totalTokenCount").forEach { usage.put(it, um.optInt(it)) }
            val det = um.optJSONArray("promptTokensDetails")
            if (det != null) for (i in 0 until det.length()) {
                val d = det.getJSONObject(i)
                when (d.optString("modality")) { "VIDEO" -> usage.put("videoTokens", d.optInt("tokenCount")); "AUDIO" -> usage.put("audioTokens", d.optInt("tokenCount")) }
            }
        }
        val cand = res.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalStateException("Gemini 가 답을 주지 않았어요: " + (res.optJSONObject("promptFeedback")?.toString() ?: text.take(200)))
        val out = cand.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: throw IllegalStateException("답이 비어 있어요 (${cand.optString("finishReason")})")
        return JSONObject(out.trim().removePrefix("```json").removeSuffix("```").trim())
            .put("at", System.currentTimeMillis()).put("video", withVideo).put("model", used).put("log", log.json(usage))
    }

    /** 모델마다 최대 3번(2초 · 5초 쉬고), 바쁘거나 끊기면 다음 모델로. 키 문제(400/401/403)는 바로 멈춤 */
    private fun generate(key: String, req: JSONObject, onStage: (String) -> Unit, log: RunLog): Pair<String, String> {
        val tried = StringBuilder()
        for ((mi, model) in MODELS.withIndex()) {
            for (attempt in 0 until 3) {
                checkCancel()
                onStage("${label(model)}로 정리 중" + if (attempt > 0) " (${attempt + 1}번째)" else "")
                val t0 = System.currentTimeMillis()
                try {
                    val (_, t) = http("POST", "$BASE/v1beta/models/$model:generateContent", key,
                        req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), 300000)
                    log.add("${label(model)} #${attempt + 1}", System.currentTimeMillis() - t0, "성공", true)
                    return t to model
                } catch (e: Throwable) {
                    if (cancelled) throw e
                    val why = explain(e)
                    val short = why.substringBefore(" →")
                    log.add("${label(model)} #${attempt + 1}", System.currentTimeMillis() - t0, why, false)
                    tried.append("${label(model)} #${attempt + 1}: $why\n")
                    if (e is HttpErr && e.code in listOf(400, 401, 403)) throw IllegalStateException(tried.toString().trim())
                    val skipModel = e is HttpErr && (e.code == 404 || e.code == 429)
                    val next = MODELS.getOrNull(mi + 1)
                    if (attempt < 2 && !skipModel) {
                        onStage("${label(model)} 안 됨 ($short) → 같은 모델로 다시 시도")
                        Thread.sleep(if (attempt == 0) 2000 else 5000)
                    } else {
                        if (next != null) onStage("${label(model)} 안 됨 ($short) → ${label(next)}로 바꿔서 시도")
                        break
                    }
                }
            }
        }
        throw IllegalStateException("모두 안 됨\n" + tried.toString().trim())
    }

    // ── 검사용 ──
    fun keyShape(k: String): String? = when {
        k.isEmpty() -> "키가 없어요"
        k != k.trim() || k.contains(" ") || k.contains("\n") -> "키에 공백 · 줄바꿈이 섞였어요"
        !k.startsWith("AIza") -> "AIza 로 시작하지 않아요 (다른 키를 복사했을 수 있어요)"
        k.length != 39 -> "길이가 ${k.length}자예요 (보통 39자 → 잘렸거나 더 붙었어요)"
        else -> null
    }

    /** 키로 모델 목록 받기 → 사용 가능한 모델 이름들 */
    fun listModels(ctx: Context): List<String> {
        val (_, t) = http("GET", "$BASE/v1beta/models?pageSize=200", key(ctx), null, timeout = 20000)
        val a = JSONObject(t).optJSONArray("models") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).optString("name").removePrefix("models/") }
    }

    fun pingModel(ctx: Context, model: String): String {
        val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "한 단어로: 안녕")))))
        val (_, t) = http("POST", "$BASE/v1beta/models/$model:generateContent", key(ctx), req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), 30000)
        return JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.trim()?.take(20) ?: "응답 ✓"
    }

    /** 작은 파일을 올렸다가 지우기 (영상 올리기 통로 확인) */
    fun uploadTest(ctx: Context): String {
        val f = File(ctx.cacheDir, "gemini_test.txt").also { it.writeText("linkmemo test") }
        val uri = upload(ctx, key(ctx), f, "text/plain")
        val name = "files/" + uri.substringAfterLast("/files/")
        try { http("DELETE", "$BASE/v1beta/$name", key(ctx), null, timeout = 15000) } catch (_: Throwable) {}
        return "올리기 ✓"
    }

}
