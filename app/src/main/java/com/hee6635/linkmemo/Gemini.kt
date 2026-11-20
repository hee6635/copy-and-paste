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
    val CATEGORIES = listOf("여행", "맛집", "요리", "쇼핑", "디자인", "개발·IT", "공부", "운동·건강", "영화·책", "게임", "음악", "생활팁", "기타")
    /**
     * 쓸 모델 순서. 이름을 고정하지 않고, 하루 한 번 받은 "쓸 수 있는 모델 목록"에서 고른다.
     * 빠르게(기본): flash-lite → 최신 flash → 가장 새 flash 번호 모델 / 꼼꼼하게: 최신 flash 먼저.
     */
    fun speed(ctx: Context) = sp(ctx).getString("speed", "fast") ?: "fast"
    fun setSpeed(ctx: Context, v: String) = sp(ctx).edit().putString("speed", v).apply()

    private fun available(ctx: Context): List<String> {
        val at = sp(ctx).getLong("modelsAt", 0)
        if (System.currentTimeMillis() - at > 24 * 3600 * 1000L) try {
            val list = listModels(ctx)
            if (list.isNotEmpty()) sp(ctx).edit().putString("models", list.joinToString(",")).putLong("modelsAt", System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
        return (sp(ctx).getString("models", "") ?: "").split(",").filter { it.isNotBlank() }
    }
    fun forget(ctx: Context, model: String) {
        val l = (sp(ctx).getString("models", "") ?: "").split(",").filter { it.isNotBlank() && it != model }
        sp(ctx).edit().putString("models", l.joinToString(",")).apply()
    }
    fun order(ctx: Context): List<String> {
        val have = available(ctx)
        val newest = have.filter { Regex("^gemini-[0-9.]+-flash$").matches(it) }
            .sortedByDescending { it.removePrefix("gemini-").removeSuffix("-flash").split(".").map { p -> p.toIntOrNull() ?: 0 }.let { v -> v.getOrElse(0) { 0 } * 1000 + v.getOrElse(1) { 0 } } }
            .firstOrNull()
        val base = if (speed(ctx) == "smart") listOf("gemini-flash-latest", "gemini-flash-lite-latest") else listOf("gemini-flash-lite-latest", "gemini-flash-latest")
        val want = (base + listOfNotNull(newest)).distinct()
        val ok = if (have.isEmpty()) want else want.filter { it in have }
        return ok.ifEmpty { want }
    }

    class HttpErr(val code: Int, msg: String) : IllegalStateException(msg)
    @Volatile var lastErrRaw = ""
    /** 실패해도 기록(log)은 남김 */
    class AiFail(msg: String, val log: JSONObject) : IllegalStateException(msg)

    fun label(m: String) = when (m) { "gemini-flash-latest" -> "최신 flash"; "gemini-flash-lite-latest" -> "flash-lite"; else -> m.removePrefix("gemini-") }

    /** 마지막 요청의 시간 나눔: connect · send · wait(첫 답까지) · read · server(구글 처리) */
    @Volatile var last: JSONObject = JSONObject()
    private fun sec(ms: Long) = if (ms < 1000) "${ms}ms" else String.format(java.util.Locale.US, "%.1f초", ms / 1000.0)
    fun timingText(t: JSONObject): String {
        val p = mutableListOf<String>()
        if (t.has("connect")) p += "연결 " + sec(t.optLong("connect"))
        if (t.has("send")) p += "보내기 " + sec(t.optLong("send"))
        if (t.has("wait")) p += "첫 답까지 " + sec(t.optLong("wait"))
        if (t.has("server")) p += "(그중 구글 처리 " + sec(t.optLong("server")) + ")"
        if (t.has("first")) p += "첫 글자까지 +" + sec(t.optLong("first"))
        if (t.has("read")) p += "받기 " + sec(t.optLong("read"))
        return p.joinToString(" · ")
    }

    // 중지: 지금 연결을 끊어서 기다림을 바로 끝냄
    @Volatile internal var cancelled = false
    @Volatile private var current: HttpURLConnection? = null
    fun cancel() { cancelled = true; Thread { try { current?.disconnect() } catch (_: Throwable) {} }.start() }
    internal fun checkCancel() { if (cancelled) throw IllegalStateException("중지했어요") }

    /** 이번 정리 기록: 단계마다 걸린 시간 · 결과 */
    class RunLog(private val ctx: Context) {
        val t0 = System.currentTimeMillis()
        val rows = JSONArray()
        var okTiming: JSONObject? = null
        fun add(step: String, ms: Long, note: String, ok: Boolean, timing: JSONObject? = null) {
            val r = JSONObject().put("step", step).put("ms", ms).put("note", note).put("ok", ok)
            if (timing != null) r.put("t", timing)
            rows.put(r)
        }
        fun json(usage: JSONObject?, extraWhy: List<String> = emptyList()): JSONObject {
            val total = System.currentTimeMillis() - t0
            val why = mutableListOf<String>()
            if (total < 10000) return JSONObject().put("total", total).put("rows", rows).put("usage", usage ?: JSONObject()).put("why", JSONArray())
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
            if (thoughts > 4000) why += "AI가 답하기 전에 오래 생각함 (생각 ${thoughts} 토큰)"
            // 비교: 응답 없던 모델 vs 성공한 모델
            var slowRow: JSONObject? = null; var okRow: JSONObject? = null
            for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); if (!r.optBoolean("ok") && r.optString("note").contains("답이 없어요") && slowRow == null) slowRow = r; if (r.optBoolean("ok") && r.has("t")) okRow = r }
            if (slowRow != null && okRow != null) why += "${slowRow.optString("step").substringBefore(" #")}는 ${slowRow.optLong("ms") / 1000}초 동안 답이 없었고, ${okRow.optString("step").substringBefore(" #")}는 ${okRow.optLong("ms") / 1000}초 만에 답함 → 그 모델만 붐볐어요"
            val vidTok = usage?.optInt("videoTokens") ?: 0
            if (vidTok > 30000) why += "영상이 길어서 볼 양이 많음 (영상 ${vidTok} 토큰)"
            // 성공한 요청의 시간 나눔으로 "서버 쪽이냐 폰 · 인터넷 쪽이냐"를 가름
            val t = okTiming
            if (t != null && genOk > 20000) {
                val wait = t.optLong("wait"); val server = t.optLong("server", -1)
                when {
                    server >= 0 && server > wait * 0.7 -> why += "구글 서버가 처리하는 데 ${server / 1000}초 (서버 혼잡) — 폰 · 인터넷 문제 아님"
                    server >= 0 -> why += "구글 처리는 ${server / 1000}초뿐인데 답이 오기까지 ${wait / 1000}초 → 폰 · 인터넷 쪽에서 ${(wait - server) / 1000}초 지연"
                    t.optLong("connect") > 5000 -> why += "서버에 연결하는 데만 ${t.optLong("connect") / 1000}초 (인터넷 느림 · 끊김)"
                    else -> why += "첫 답까지 ${wait / 1000}초 (구글이 처리 시간을 안 알려 줘서 서버 · 폰 중 어디인지 불명)"
                }
            }
            val evs = PhoneState.between(t0, System.currentTimeMillis())
            if (evs.isNotEmpty() && genOk > 20000) {
                val n = evs.groupingBy { it.what }.eachCount()
                val bg = n["앱이 뒤로 감"] ?: 0
                val parts = mutableListOf<String>()
                if (bg > 0) parts += "앱 뒤/앞 전환 ${bg}번"
                n.filterKeys { it != "앱이 뒤로 감" && it != "앱 화면 앞으로" }.forEach { (k, v) -> parts += "$k ${v}번" }
                if (parts.isNotEmpty()) why += "그동안 폰: " + parts.joinToString(", ")
            }
            if (genOk > 20000 && (t == null || t.optLong("server", -1) < 0 || t.optLong("wait") - t.optLong("server") > 5000))
                PhoneState.throttles(ctx).forEach { why += "폰 설정: $it" }
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
            e is java.net.SocketTimeoutException -> "정해진 시간 안에 답이 없어요 → 이 모델이 붐비는 중 (다른 모델로 넘어감)"
            e is javax.net.ssl.SSLException || "ssl" in m || "certificate" in m -> "보안 연결 실패 → 폰 날짜·시간이 틀렸거나, 공용 와이파이 로그인 화면, VPN · 보안 앱이 가로챔"
            e is java.net.ConnectException -> "서버에 닿지 못했어요 → 인터넷 끊김, 방화벽 · 회사 와이파이 차단"
            "abort" in m || "reset" in m || "broken pipe" in m || "unexpected end" in m ->
                "연결이 중간에 끊겼어요 → 와이파이↔데이터 전환, 앱이 뒤로 가서 절전, 또는 서버 혼잡"
            else -> e.message ?: e.toString()
        }
    }
    internal const val BASE = "https://generativelanguage.googleapis.com"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
    fun key(ctx: Context) = sp(ctx).getString("key", "") ?: ""
    fun setKey(ctx: Context, k: String) = sp(ctx).edit().putString("key", k.trim()).apply()
    fun on(ctx: Context) = sp(ctx).getBoolean("on", true) && key(ctx).isNotEmpty()
    fun setOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("on", v).apply()
    private fun month() = SimpleDateFormat("yyyyMM", Locale.US).format(Date())
    fun usedThisMonth(ctx: Context) = sp(ctx).getInt("n" + month(), 0)
    internal fun count(ctx: Context) { val k = "n" + month(); sp(ctx).edit().putInt(k, sp(ctx).getInt(k, 0) + 1).apply() }

    internal fun http(method: String, url: String, key: String, body: ByteArray?, headers: Map<String, String> = emptyMap(),
                     timeout: Int = 120000): Pair<HttpURLConnection, String> {
        checkCancel()
        val c = URL(url).openConnection() as HttpURLConnection
        current = c
        c.requestMethod = method
        c.connectTimeout = 20000; c.readTimeout = timeout
        c.setRequestProperty("x-goog-api-key", key)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val tm = JSONObject(); last = tm
        val t0 = System.currentTimeMillis()
        if (body != null) c.doOutput = true
        c.connect(); val t1 = System.currentTimeMillis(); tm.put("connect", t1 - t0)
        if (body != null) c.outputStream.use { it.write(body) }
        val t2 = System.currentTimeMillis(); tm.put("send", t2 - t1)
        val code = c.responseCode
        val t3 = System.currentTimeMillis(); tm.put("wait", t3 - t2)
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        tm.put("read", System.currentTimeMillis() - t3)
        // 구글이 알려 주는 "서버에서 실제로 처리한 시간"
        c.getHeaderField("server-timing")?.let { st -> Regex("dur=([0-9.]+)").find(st)?.groupValues?.get(1)?.toDoubleOrNull()?.let { tm.put("server", it.toLong()) } }
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null } ?: text.take(200)
            lastErrRaw = text
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
    internal fun upload(ctx: Context, key: String, f: File, mime: String, log: RunLog? = null): String {
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

    /** 메모 하나 정리. 결과 JSON(ai) 을 돌려준다 */
    /** 링크만 저장한 것도 영상을 보게 할지 (기본 켬) */
    fun watch(ctx: Context) = sp(ctx).getBoolean("watch", true)
    fun setWatch(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("watch", v).apply()

    /** 유튜브 링크 → Gemini 가 바로 보는 형식 (watch?v=) */
    fun ytWatchUrl(url: String): String? {
        val id = Regex("(?:youtu\\.be/|/shorts/|/live/|[?&]v=)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: return null
        return "https://www.youtube.com/watch?v=$id"
    }

    /**
     * tempVideo: 링크만 저장한 인스타 영상을 잠깐 받은 파일 · ytUrl: 유튜브 링크(받지 않고 그대로 넘김)
     * pre: 정리 전에 한 일(영상 잠깐 받기 등) 기록
     */
    fun organize(ctx: Context, item: JSONObject, folders: List<String>, onStage: (String) -> Unit,
                 tempVideo: File? = null, ytUrl: String? = null, pre: List<Triple<String, Long, String>> = emptyList(),
                 onPartial: ((String) -> Unit)? = null): JSONObject {
        val key = key(ctx)
        if (key.isEmpty()) throw IllegalStateException("Gemini API 키가 없어요 (설정에서 넣어 주세요)")
        cancelled = false
        val log = RunLog(ctx)
        pre.forEach { (step, ms, note) -> log.add(step, ms, note, !note.startsWith("실패")) }
        return try { organizeImpl(ctx, key, item, folders, onStage, log, tempVideo, ytUrl, onPartial) }
        catch (e: Throwable) { throw AiFail(if (cancelled) "중지했어요" else explain(e), log.json(null)) }
    }

    private fun organizeImpl(ctx: Context, key: String, item: JSONObject, folders: List<String>, onStage: (String) -> Unit, log: RunLog,
                             tempVideo: File?, ytUrl: String?, onPartial: ((String) -> Unit)?): JSONObject {
        val parts = JSONArray()
        val vName = item.optString("video")
        val saved = if (vName.isNotEmpty()) File(Store.dir(ctx, item.optString("id")), vName) else null
        val vFile = (saved?.takeIf { it.exists() } ?: tempVideo?.takeIf { it.exists() })?.takeIf { it.length() < 1_900_000_000L }
        var withVideo = false
        if (vFile != null) {
            onStage("영상 올리는 중")
            val mime = if (vFile.name.endsWith(".webm")) "video/webm" else "video/mp4"
            val uri = upload(ctx, key, vFile, mime, log)
            parts.put(JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", uri)))
            withVideo = true
        } else if (ytUrl != null) {
            // 유튜브는 받지 않고 링크를 그대로 넘김 (공개 영상만)
            parts.put(JSONObject().put("file_data", JSONObject().put("file_uri", ytUrl)))
            log.add("유튜브 링크로 보기", 0, ytUrl, true)
            withVideo = true
        }
        parts.put(JSONObject().put("text", prompt(item, folders, withVideo)))
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema())
                .put("temperature", 0.3)
                .also { if (withVideo) it.put("mediaResolution", "MEDIA_RESOLUTION_LOW") })
        onStage(if (withVideo) "받아쓰는 중" else "정리 중")
        val (text, used) = generate(ctx, key, req, onStage, log, withVideo, onPartial)
        count(ctx)
        val res = JSONObject(text)
        val um = res.optJSONObject("usageMetadata")
        Usage.ok(ctx, used, um?.optInt("totalTokenCount") ?: 0)
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
            .put("at", System.currentTimeMillis()).put("video", withVideo).put("model", used)
            .put("modelVersion", res.optString("modelVersion")).put("log", log.json(usage).put("modelVersion", res.optString("modelVersion")))
    }

    /**
     * 모델마다 최대 3번(2초 · 5초 쉬고). 응답 없음(글만 40초 · 영상 120초)이면 바로 다음 모델로.
     * 키 문제(키 틀림 400 · 401 · 403)는 바로 멈춤. 생각 과정은 끔(2.5 모델).
     */
    /**
     * 주르륵 받기(SSE): 조각이 올 때마다 지금까지 모인 글을 onPartial 로 (0.25초에 한 번).
     * 다 받으면 보통 응답과 같은 모양(candidates · usageMetadata · modelVersion)으로 돌려줌.
     */
    private fun streamPost(url: String, key: String, body: ByteArray, timeout: Int, onPartial: ((String) -> Unit)?): String {
        checkCancel()
        val c = URL(url).openConnection() as HttpURLConnection
        current = c
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = timeout
        c.setRequestProperty("x-goog-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        val tm = JSONObject(); last = tm
        val t0 = System.currentTimeMillis()
        c.connect(); val t1 = System.currentTimeMillis(); tm.put("connect", t1 - t0)
        c.outputStream.use { it.write(body) }
        val t2 = System.currentTimeMillis(); tm.put("send", t2 - t1)
        val code = c.responseCode
        val t3 = System.currentTimeMillis(); tm.put("wait", t3 - t2)
        c.getHeaderField("server-timing")?.let { st -> Regex("dur=([0-9.]+)").find(st)?.groupValues?.get(1)?.toDoubleOrNull()?.let { tm.put("server", it.toLong()) } }
        if (code !in 200..299) {
            val text = c.errorStream?.bufferedReader()?.readText() ?: ""
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null } ?: text.take(200)
            lastErrRaw = text
            throw HttpErr(code, when (code) {
                400 -> if (text.contains("API key not valid") || text.contains("API_KEY_INVALID")) "400 키가 맞지 않아요 → 키를 다시 복사해서 넣기" else "400 요청 오류: $msg"
                401 -> "401 인증 실패 → 키를 다시 넣기"
                403 -> "403 이 키로 사용이 막혀 있어요 ($msg)"
                404 -> "404 모델을 찾을 수 없어요 ($msg)"
                429 -> "429 사용량 한도 → 무료 분당 · 하루 한도를 넘었어요. 잠시 뒤 또는 내일"
                503 -> "503 구글 서버가 바빠요(모델 혼잡) → 잠시 뒤 또는 다른 모델"
                else -> "$code $msg"
            })
        }
        val acc = StringBuilder()
        var usage: JSONObject? = null; var ver = ""; var finish = ""
        var lastEmit = 0L; var firstChunk = -1L
        c.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val j = try { JSONObject(line.removePrefix("data:").trim()) } catch (_: Exception) { continue }
                if (firstChunk < 0) firstChunk = System.currentTimeMillis() - t3
                j.optJSONObject("usageMetadata")?.let { usage = it }
                j.optString("modelVersion").takeIf { it.isNotEmpty() }?.let { ver = it }
                val cand = j.optJSONArray("candidates")?.optJSONObject(0)
                cand?.optString("finishReason")?.takeIf { it.isNotEmpty() }?.let { finish = it }
                val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) for (i in 0 until parts.length()) acc.append(parts.getJSONObject(i).optString("text"))
                val now = System.currentTimeMillis()
                if (onPartial != null && now - lastEmit > 250) { lastEmit = now; onPartial(acc.toString()) }
            }
        }
        tm.put("read", System.currentTimeMillis() - t3)
        if (firstChunk >= 0) tm.put("first", firstChunk)
        onPartial?.invoke(acc.toString())
        return JSONObject()
            .put("candidates", JSONArray().put(JSONObject().put("finishReason", finish)
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", acc.toString()))))))
            .put("usageMetadata", usage ?: JSONObject()).put("modelVersion", ver).toString()
    }

    internal fun generate(ctx: Context, key: String, req: JSONObject, onStage: (String) -> Unit, log: RunLog, withVideo: Boolean,
                         onPartial: ((String) -> Unit)? = null): Pair<String, String> {
        val tried = StringBuilder()
        val limit = if (withVideo) 120000 else 40000
        val models = order(ctx)
        for ((mi, model) in models.withIndex()) {
            val body = JSONObject(req.toString())
            // 생각 과정: 2.x 는 끔(thinkingBudget 0). 3.x 이상 · lite 는 기본값 그대로 (lite 는 원래 생각을 거의 안 함)
            val gc = body.getJSONObject("generationConfig")
            val ver = Regex("^gemini-([0-9]+)").find(model)?.groupValues?.get(1)?.toIntOrNull()
            if (ver == 2) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            for (attempt in 0 until 3) {
                checkCancel()
                onStage("${label(model)}로 정리 중" + if (attempt > 0) " (${attempt + 1}번째)" else "")
                val t0 = System.currentTimeMillis()
                try {
                    val t = streamPost("$BASE/v1beta/models/$model:streamGenerateContent?alt=sse", key, body.toString().toByteArray(), limit, onPartial)
                    val tm = last
                    log.okTiming = tm
                    log.add("${label(model)} #${attempt + 1}", System.currentTimeMillis() - t0, "성공 · " + timingText(tm), true, tm)
                    return t to model
                } catch (e: Throwable) {
                    if (cancelled) throw e
                    val why = explain(e)
                    if (e is HttpErr && e.code == 429) Usage.limit(ctx, model, lastErrRaw)
                    val short = why.substringBefore(" →")
                    log.add("${label(model)} #${attempt + 1}", System.currentTimeMillis() - t0, why + " · " + timingText(last), false, last)
                    tried.append("${label(model)} #${attempt + 1}: $why\n")
                    val keyBad = e is HttpErr && (e.code == 401 || e.code == 403 || (e.code == 400 && (e.message ?: "").contains("키가 맞지")))
                    if (keyBad) throw IllegalStateException(tried.toString().trim())
                    val timeout = e is java.net.SocketTimeoutException
                    val skipModel = timeout || (e is HttpErr && (e.code == 400 || e.code == 404 || e.code == 429))
                    val shortMsg = if (timeout) "${limit / 1000}초 응답 없음" else short
                    if (e is HttpErr && e.code == 404) forget(ctx, model)
                    val next = models.getOrNull(mi + 1)
                    if (attempt < 2 && !skipModel) {
                        onStage("${label(model)} 안 됨 ($shortMsg) → 같은 모델로 다시 시도")
                        Thread.sleep(if (attempt == 0) 2000 else 5000)
                    } else {
                        if (next != null) onStage("${label(model)} 안 됨 ($shortMsg) → ${label(next)}로 바꿔서 시도")
                        break
                    }
                }
            }
        }
        throw IllegalStateException("모두 안 됨\n" + tried.toString().trim())
    }

}
