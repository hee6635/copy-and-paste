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
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000; c.readTimeout = timeout
        c.setRequestProperty("x-goog-api-key", key)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
            throw IllegalStateException(when (code) {
                400 -> if (text.contains("API key not valid")) "API 키가 맞지 않아요" else "요청 오류: ${msg ?: text.take(200)}"
                403 -> "API 키 권한이 없어요: ${msg ?: ""}"
                429 -> "무료 사용량을 넘었어요. 잠시 뒤 다시 해 주세요"
                else -> "Gemini 오류 $code: ${msg ?: text.take(200)}"
            })
        }
        return c to text
    }

    /** 영상 올리기 (Files API, 이어 올리기 방식) → file uri */
    private fun upload(ctx: Context, key: String, f: File, mime: String): String {
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
        c.outputStream.use { out -> f.inputStream().use { it.copyTo(out) } }
        if (c.responseCode !in 200..299) throw IllegalStateException("영상 올리기 실패 ${c.responseCode}")
        val file = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("file")
        val name = file.getString("name")
        var state = file.optString("state")
        var uri = file.optString("uri")
        var tries = 0
        while (state == "PROCESSING" && tries++ < 60) {
            Thread.sleep(2000)
            val (_, t) = http("GET", "$BASE/v1beta/$name", key, null)
            val j = JSONObject(t); state = j.optString("state"); uri = j.optString("uri")
        }
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
        val parts = JSONArray()
        val vName = item.optString("video")
        val vFile = if (vName.isNotEmpty()) File(Store.dir(ctx, item.optString("id")), vName) else null
        val withVideo = vFile != null && vFile.exists() && vFile.length() < 1_900_000_000L
        if (withVideo) {
            onStage("영상 올리는 중")
            val uri = upload(ctx, key, vFile!!, if (vName.endsWith(".webm")) "video/webm" else "video/mp4")
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
        val (_, text) = http("POST", "$BASE/v1beta/models/$MODEL:generateContent", key,
            req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), 300000)
        count(ctx)
        val res = JSONObject(text)
        val cand = res.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalStateException("Gemini 가 답을 주지 않았어요: " + (res.optJSONObject("promptFeedback")?.toString() ?: text.take(200)))
        val out = cand.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: throw IllegalStateException("답이 비어 있어요 (${cand.optString("finishReason")})")
        return JSONObject(out.trim().removePrefix("```json").removeSuffix("```").trim())
            .put("at", System.currentTimeMillis()).put("video", withVideo)
    }

    /** 키가 맞는지 짧게 확인 (검사용) */
    fun ping(ctx: Context): String {
        val key = key(ctx)
        if (key.isEmpty()) throw IllegalStateException("API 키가 없어요 (설정 › Gemini API 키)")
        val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "한 단어로: 안녕")))))
        val (_, t) = http("POST", "$BASE/v1beta/models/$MODEL:generateContent", key, req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), 30000)
        return JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.trim()?.take(30) ?: "응답 ✓"
    }
}
