package com.hee6635.linkmemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Gemini.kt 에서 나눔: 긴 영상 목차 · 내 저장함에 질문 · 검사용
// ── 긴 영상: 5분씩 목차 ──
private fun mmss(sec: Double): String { val t = sec.toInt(); return "%d:%02d".format(t / 60, t % 60) }
/**
 * 긴 영상 목차: 5분씩 나눠 차례로. 자막이 있으면 글로만(토큰 적음), 없으면 유튜브 링크의 그 구간을 5초에 한 장면만.
 * onPart(지금 부분, 전체 부분, 지금까지 목차) 로 바로바로 화면에.
 */
fun Gemini.chapters(ctx: Context, item: JSONObject, onStage: (String) -> Unit, onPart: (Int, Int, JSONArray) -> Unit, log: Gemini.RunLog): JSONArray {
    val key = key(ctx)
    if (key.isEmpty()) throw IllegalStateException("Gemini API 키가 없어요 (설정에서 넣어 주세요)")
    cancelled = false
    val dur = item.optDouble("duration", 0.0)
    val parts = maxOf(1, kotlin.math.ceil(dur / 300.0).toInt())
    val subs = item.optJSONArray("subs")
    val hasSubs = subs != null && subs.length() > 0
    val yt = ytWatchUrl(item.optString("url"))
    val yc = item.optJSONArray("ytChapters")
    val all = JSONArray()
    fun str() = JSONObject().put("type", "STRING")
    val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("chapters", JSONObject().put("type", "ARRAY").put("items",
        JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("t", JSONObject().put("type", "NUMBER")).put("title", str()).put("info", str()))
            .put("required", JSONArray(listOf("t", "title", "info")))))).put("required", JSONArray(listOf("chapters")))
    log.add(if (hasSubs) "자막으로 보기" else "영상 듬성듬성 보기(5초에 한 장면)", 0, "${mmss(dur)} · ${parts}부분", true)
    for (p in 0 until parts) {
        checkCancel()
        val st = p * 300.0; val en = minOf(dur, (p + 1) * 300.0)
        onStage("${p + 1} / $parts 부분 보는 중 (${mmss(st)} – ${mmss(en)})")
        val reqParts = JSONArray()
        val ycText = StringBuilder()
        if (yc != null) for (i in 0 until yc.length()) { val c = yc.getJSONObject(i); val t = c.optDouble("t"); if (t >= st - 1 && t < en) ycText.append("${mmss(t)} ${c.optString("title")}\n") }
        val sb = StringBuilder()
        sb.append("이 영상의 ${mmss(st)} ~ ${mmss(en)} 부분 목차를 만들어. 한국어로.\n")
        sb.append("- chapters: 내용이 바뀌는 곳마다 1~4개. t 는 그 내용이 시작되는 시각(초, 영상 전체 기준 ${st.toInt()}~${en.toInt()} 사이), title 은 15자 안팎, info 는 그 부분의 핵심 정보 한 줄(추천 · 가격 · 방법 · 주의). 숫자 · 시간은 [[ ]] 로.\n")
        sb.append("- 인사 · 구독 부탁 · 광고 부분은 빼기.\n")
        if (ycText.isNotEmpty()) sb.append("- 유튜버가 만든 목차(이 경계를 따르기):\n").append(ycText)
        sb.append("[제목] ").append(item.optString("title")).append("\n")
        if (hasSubs) {
            sb.append("[자막]\n")
            for (i in 0 until subs!!.length()) { val x = subs.getJSONObject(i); val t = x.optDouble("t"); if (t >= st && t < en) sb.append("[${mmss(t)}] ").append(x.optString("text")).append("\n") }
        } else if (yt != null) {
            reqParts.put(JSONObject().put("file_data", JSONObject().put("file_uri", yt))
                .put("video_metadata", JSONObject().put("start_offset", "${st.toInt()}s").put("end_offset", "${en.toInt()}s").put("fps", 0.2)))
        } else break
        reqParts.put(JSONObject().put("text", sb.toString()))
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", reqParts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema).put("temperature", 0.3)
                .also { if (!hasSubs) it.put("mediaResolution", "MEDIA_RESOLUTION_LOW") })
        val (text, used) = generate(ctx, key, req, onStage, log, !hasSubs, null)
        count(ctx)
        val res = JSONObject(text)
        Usage.ok(ctx, used, res.optJSONObject("usageMetadata")?.optInt("totalTokenCount") ?: 0)
        val out = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: "{}"
        val ch = try { JSONObject(out.trim().removePrefix("```json").removeSuffix("```").trim()).optJSONArray("chapters") } catch (_: Exception) { null } ?: JSONArray()
        for (i in 0 until ch.length()) {
            val c = ch.getJSONObject(i)
            var t = c.optDouble("t", st)
            if (t < st - 5) t += st          // 부분 안의 시각으로 답했으면 전체 시각으로
            all.put(c.put("t", t.coerceIn(st, maxOf(st, en))))
        }
        onPart(p + 1, parts, all)
    }
    return all
}

// ── 내 저장함에 질문하기 ──
/** context: 화면이 만든 메모 목록(JSON 글). 답 = {"answer":"…","refs":["id",…]} */
fun Gemini.ask(ctx: Context, question: String, context: String, history: String): String {
    val key = key(ctx)
    if (key.isEmpty()) throw IllegalStateException("Gemini API 키가 없어요 (설정에서 넣어 주세요)")
    cancelled = false
    val prompt = """너는 사용자가 저장해 둔 메모들에서 답을 찾아 주는 도우미야. 한국어로 짧고 쉽게.
규칙:
- 아래 [내 메모]에 있는 내용으로만 답하기. 없으면 "저장한 것 중에는 없어요"라고 말하고 비슷한 것이 있으면 알려 주기.
- answer: 2~5줄. 가격 · 시간 · 장소 같은 구체 정보 위주. 숫자 · 시간은 [[ ]] 로 감싸기.
- refs: 답에 쓴 메모의 id 목록 (많아야 5개, 관련 높은 순).
[앞선 대화]
$history
[내 메모]
$context
[질문]
$question"""
    fun str() = JSONObject().put("type", "STRING")
    val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
        .put("answer", str()).put("refs", JSONObject().put("type", "ARRAY").put("items", str())))
        .put("required", JSONArray(listOf("answer", "refs")))
    val req = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema).put("temperature", 0.2))
    val (text, used) = generate(ctx, key, req, {}, Gemini.RunLog(ctx), false, null)
    count(ctx)
    val res = JSONObject(text)
    Usage.ok(ctx, used, res.optJSONObject("usageMetadata")?.optInt("totalTokenCount") ?: 0)
    val out = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
        ?: throw IllegalStateException("답이 비어 있어요")
    return JSONObject(out.trim().removePrefix("```json").removeSuffix("```").trim()).put("model", used).toString()
}

// ── 검사용 ──
fun Gemini.keyShape(k: String): String? = when {
    k.isEmpty() -> "키가 없어요"
    k != k.trim() || k.contains(" ") || k.contains("\n") -> "키에 공백 · 줄바꿈이 섞였어요"
    !k.startsWith("AIza") -> "AIza 로 시작하지 않아요 (다른 키를 복사했을 수 있어요)"
    k.length != 39 -> "길이가 ${k.length}자예요 (보통 39자 → 잘렸거나 더 붙었어요)"
    else -> null
}

/** 키로 모델 목록 받기 → 사용 가능한 모델 이름들 */
fun Gemini.listModels(ctx: Context): List<String> {
    val (_, t) = http("GET", "$BASE/v1beta/models?pageSize=200", key(ctx), null, timeout = 20000)
    val a = JSONObject(t).optJSONArray("models") ?: JSONArray()
    return (0 until a.length()).map { a.getJSONObject(it).optString("name").removePrefix("models/") }
}

fun Gemini.pingModel(ctx: Context, model: String): String {
    val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "한 단어로: 안녕")))))
    val (_, t) = http("POST", "$BASE/v1beta/models/$model:generateContent", key(ctx), req.toString().toByteArray(), mapOf("Content-Type" to "application/json"), 30000)
    return JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        ?.optJSONObject(0)?.optString("text")?.trim()?.take(20) ?: "응답 ✓"
}

/** 작은 파일을 올렸다가 지우기 (영상 올리기 통로 확인) */
fun Gemini.uploadTest(ctx: Context): String {
    val f = File(ctx.cacheDir, "gemini_test.txt").also { it.writeText("linkmemo test") }
    val uri = upload(ctx, key(ctx), f, "text/plain")
    val name = "files/" + uri.substringAfterLast("/files/")
    try { http("DELETE", "$BASE/v1beta/$name", key(ctx), null, timeout = 15000) } catch (_: Throwable) {}
    return "올리기 ✓"
}
