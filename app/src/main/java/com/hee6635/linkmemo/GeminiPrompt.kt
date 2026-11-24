package com.hee6635.linkmemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// Gemini.kt 에서 나눔: 정리 요청 글(prompt) · 답 모양(schema)
/**
 * 정리 요청 글. 규칙(rules)은 PromptText 가 줌: 저장소에서 받아 둔 최신 글 → 없으면 앱에 든 assets/prompt/organize.txt
 */
internal fun Gemini.prompt(item: JSONObject, folders: List<String>, withVideo: Boolean, noTranscript: Boolean = false, rules: String): String {
    val sb = StringBuilder()
    sb.append(rules.replace("{{CATEGORIES}}", CATEGORIES.joinToString(", ")).trim()).append("\n")
    sb.append(if (withVideo) "\n[이번 자료] 영상이 같이 와요.\n" else "\n[이번 자료] 영상이 없어요 → keyshots · transcript 는 빈 목록, 모든 t 는 -1.\n")
    if (withVideo && noTranscript) sb.append("transcript 는 빈 목록으로 (앞서 답이 길어 끊겼어요).\n")
    sb.append("\n오늘: ").append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())).append("\n")
    sb.append("내 폴더: ").append(if (folders.isEmpty()) "(없음)" else folders.joinToString(" / ")).append("\n\n")
    sb.append("[출처] ").append(item.optString("platform")).append(" · ").append(item.optString("author")).append("\n")
    sb.append("[제목] ").append(item.optString("title")).append("\n")
    item.optString("desc").takeIf { it.isNotBlank() }?.let { sb.append("[설명]\n").append(it.take(6000)).append("\n") }
    item.optString("body").takeIf { it.isNotBlank() }?.let { sb.append("[본문]\n").append(it.take(8000)).append("\n") }
    item.optString("memo").takeIf { it.isNotBlank() }?.let { sb.append("[내 메모] ").append(it).append("\n") }
    // 사진 게시물(카드뉴스 등): 폰이 읽은 사진 속 글자. 사진 자체도 같이 보냄
    item.optJSONArray("photos")?.let { ph ->
        val t = (0 until ph.length()).mapNotNull { i -> ph.optJSONObject(i)?.optString("text")?.takeIf { x -> x.isNotBlank() }?.let { x -> "${i + 1}. " + x.take(1500) } }
        if (ph.length() > 0) sb.append("[사진] ${ph.length()}장이 같이 와요. 사진 속 정보(카드뉴스 글 · 표 · 가격 · 순서)가 핵심이면 tips · checklist · recipe 에 꼭 담기.\n")
        if (t.isNotEmpty()) sb.append("[사진 속 글자 (폰이 읽음, 틀릴 수 있음)]\n").append(t.joinToString("\n")).append("\n")
    }
    val cs = item.optJSONArray("comments")
    if (cs != null && cs.length() > 0) {
        sb.append("[댓글]\n")
        // 쓸모없는 댓글(작성자 답글 없고 @태그 · 이모지 · 공백 빼면 6자 미만)은 빼고 보냄. 번호는 원래 번호 그대로 (star 가 맞게)
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (junkComment(c)) continue
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

internal fun Gemini.schema(): JSONObject {
    fun str() = JSONObject().put("type", "STRING")
    fun num() = JSONObject().put("type", "NUMBER")
    fun int() = JSONObject().put("type", "INTEGER")
    fun bool() = JSONObject().put("type", "BOOLEAN")
    fun arr(of: JSONObject) = JSONObject().put("type", "ARRAY").put("items", of)
    fun en(vararg v: String) = JSONObject().put("type", "STRING").put("enum", JSONArray(v.toList()))
    fun obj(vararg p: Pair<String, JSONObject>, req: List<String> = emptyList()) = JSONObject().put("type", "OBJECT")
        .put("properties", JSONObject().also { o -> p.forEach { o.put(it.first, it.second) } })
        .also { if (req.isNotEmpty()) it.put("required", JSONArray(req)) }
    return obj(
        "title" to str(),
        "category" to JSONObject().put("type", "STRING").put("enum", JSONArray(CATEGORIES)),
        "folder" to str(),
        "kind" to en("recipe", "checklist", "general"),
        "area" to str(),
        "listName" to str(),
        "places" to arr(obj("name" to str(), "t" to num(), "day" to str(), "map" to bool(), "info" to str(), req = listOf("name", "t", "info"))),
        "recipe" to obj(
            "servings" to int(),
            "ingredients" to arr(str()),
            "steps" to arr(obj("text" to str(), "t" to num(), req = listOf("text", "t"))),
            req = listOf("servings", "ingredients", "steps")
        ),
        "checklist" to arr(str()),
        "events" to arr(obj("title" to str(), "date" to str(), "time" to str(), "end" to str(), "place" to str(), req = listOf("title", "date"))),
        "tips" to arr(obj("label" to str(), "text" to str(), "from" to en("video", "author", "comment", "body", "photo"), "t" to num(), "who" to str(), req = listOf("label", "text", "from"))),
        "sections" to arr(obj("title" to str(), "kind" to en("list", "steps", "table", "kv", "text"), "items" to arr(str()), req = listOf("title", "kind", "items"))),
        "links" to arr(obj("label" to str(), "type" to en("url", "copy"), "value" to str(), req = listOf("label", "type", "value"))),
        "star" to arr(int()),
        "tags" to arr(str()),
        "keyshots" to arr(obj("t" to num(), "why" to str(), "sec" to en("tips", "steps", "none"), "idx" to int(), req = listOf("t", "why", "sec", "idx"))),
        "desc_ko" to str(),
        "transcript" to arr(obj("t" to num(), "text" to str(), "ko" to str(), req = listOf("t", "text"))),
        // title 은 선택 (원본 제목이 괜찮으면 빈칸)
        req = listOf("category", "folder", "kind", "places", "tips", "star", "tags")
    ).put("propertyOrdering", JSONArray(listOf(
        // 내용 먼저 → 다 쓴 뒤 제목 · 갈래 · 폴더 → 받아쓰기는 맨 뒤 (답이 끊겨도 장소 · 팁은 남게)
        "kind", "area", "listName", "places", "recipe", "checklist", "events",
        "tips", "sections", "links", "star", "title", "category", "folder", "tags", "keyshots", "desc_ko", "transcript")))
}

/**
 * 새 답 모양 → 화면이 쓰는 모양으로 (예전 메모와 같게):
 * tips(label · text · from · t · who) → summary(글) + sumFrom(출처 칩) + sumLabel(이름표) + sumMeta(출처 · 시각) · 레시피 순서 {text, t} → 글 + stepT · keyshots sec tips → summary
 */
internal fun Gemini.normalize(ai: JSONObject, dur: Double = 0.0): JSONObject {
    // 지역 글인데 폴더의 작은 폴더가 다른 지역이면(후쿠오카 글 → "여행 › 나트랑") 갈래 › 지역 으로
    val area = ai.optString("area").trim(); val fd = ai.optString("folder")
    if (area.isNotEmpty() && fd.contains("›") && !fd.substringAfter("›").contains(area) && !area.contains(fd.substringAfter("›").trim()))
        ai.put("folder", ai.optString("category").ifEmpty { fd.substringBefore("›").trim() } + " › " + area)
    if (ai.optString("kind") == "recipe") ai.put("category", "요리")
    // 영상 길이를 넘는 시각은 AI 가 잘못 짐작한 것 → -1
    if (dur > 0) {
        fun fix(o: JSONObject?) { if (o != null && o.optDouble("t", -1.0) > dur) o.put("t", -1.0) }
        for (k in listOf("places", "tips", "keyshots")) ai.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) fix(a.optJSONObject(i)) }
        ai.optJSONObject("recipe")?.optJSONArray("steps")?.let { a -> for (i in 0 until a.length()) fix(a.optJSONObject(i)) }
    }
    if (!ai.has("summary")) {
        val tips = ai.optJSONArray("tips") ?: JSONArray()
        val sum = JSONArray(); val from = JSONArray(); val lab = JSONArray(); val meta = JSONArray()
        for (i in 0 until tips.length()) {
            val t = tips.optJSONObject(i) ?: continue
            sum.put(t.optString("text"))
            val f = t.optString("from")
            from.put(when (f) { "video" -> "영상"; "author" -> "작성자 댓글"; "comment" -> "댓글"; "photo" -> "사진"; else -> "본문" })
            lab.put(t.optString("label"))
            meta.put(JSONObject().put("from", f).put("t", t.optDouble("t", -1.0)).put("who", t.optString("who")))
        }
        ai.put("summary", sum).put("sumFrom", from).put("sumLabel", lab).put("sumMeta", meta).put("tips", JSONArray())
        val placesArr = ai.optJSONArray("places")
        val placeTs = if (placesArr == null) emptyList() else (0 until placesArr.length())
            .mapNotNull { placesArr.optJSONObject(it)?.optDouble("t", -1.0)?.takeIf { t -> t >= 0 } }
        ai.optJSONArray("keyshots")?.let { k ->
            for (i in 0 until k.length()) k.optJSONObject(i)?.let {
                when (it.optString("sec")) {
                    "tips" -> {
                        val idx = it.optInt("idx", -1)
                        val st = it.optDouble("t", -1.0)
                        val isVideoTip = idx >= 0 && meta.optJSONObject(idx)?.optString("from") == "video"
                        val dupPlace = placeTs.any { pt -> kotlin.math.abs(pt - st) <= 2.0 }
                        if (st >= 0 && isVideoTip && !dupPlace) it.put("sec", "summary")
                        else { it.remove("sec"); it.put("idx", -1) }
                    }
                    "none" -> { it.remove("sec"); it.put("idx", -1) }
                }
            }
        }
    }
    ai.optJSONObject("recipe")?.let { r ->
        val st = r.optJSONArray("steps") ?: return@let
        if (st.length() > 0 && st.opt(0) is JSONObject) {
            val txt = JSONArray(); val tt = JSONArray()
            for (i in 0 until st.length()) { val o = st.optJSONObject(i); txt.put(o?.optString("text") ?: st.optString(i)); tt.put(o?.optDouble("t", -1.0) ?: -1.0) }
            r.put("steps", txt).put("stepT", tt)
        }
    }
    return ai
}

/** 쓸모없는 댓글: 작성자 답글이 없고 @태그 · 이모지 · 공백 · ㅋㅎ 를 빼면 6자 미만 */
internal fun junkComment(c: JSONObject): Boolean {
    val r = c.optJSONArray("r")
    if (r != null) for (k in 0 until r.length()) if (r.optJSONObject(k)?.optBoolean("own") == true) return false
    val t = c.optString("t").replace(Regex("@\\S+"), "").replace(Regex("[\\p{So}\\p{Cs}\\s!?.,~ㅋㅎㅠㅜ♡\\u200d\\ufe0f]"), "")
    return t.length < 6
}

/** 정리 규칙 글: 저장소 글을 고치면 앱 업데이트 없이 바뀜 (앱 열 때마다 받아 둠). 이상한 글이면 안 씀 */
object PromptText {
    private const val URL_RAW = "https://raw.githubusercontent.com/hee6635/copy-and-paste/HEAD/app/src/main/assets/prompt/organize.txt"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("prompt", Context.MODE_PRIVATE)
    /** 꼭 있어야 할 칸 이름이 다 있고 너무 짧지 않아야 씀 (망가진 글 막기) */
    fun ok(t: String) = t.length > 800 && listOf("places", "tips", "links", "star", "category", "{{CATEGORIES}}", "folder", "transcript").all { it in t }
    fun builtIn(ctx: Context) = ctx.assets.open("prompt/organize.txt").bufferedReader().use { it.readText() }
    fun get(ctx: Context): String = sp(ctx).getString("text", null)?.takeIf { ok(it) } ?: builtIn(ctx)
    /** 화면 · 검사용: "저장소 11.24 15:30" / "앱 기본" */
    fun source(ctx: Context): String = if (sp(ctx).getString("text", null)?.let { ok(it) } == true) "저장소 " + (sp(ctx).getString("at", "") ?: "") else "앱 기본"
    /** 받기 (앱 열 때마다 · 설정 [지금 받기]). done(결과 글) 은 일꾼 스레드에서 */
    fun refresh(ctx: Context, done: ((String) -> Unit)? = null) {
        thread {
            try {
                val c = URL(URL_RAW).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                val t = c.inputStream.bufferedReader().use { it.readText() }
                if (c.responseCode == 200 && ok(t)) sp(ctx).edit().putString("text", t)
                    .putString("at", java.text.SimpleDateFormat("MM.dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())).apply()
                done?.invoke(if (c.responseCode == 200 && ok(t)) "최신 규칙을 받았어요 · " + source(ctx) else "받은 글이 이상해서 그대로 둬요 · " + source(ctx))
            } catch (e: Throwable) { done?.invoke("못 받았어요 (인터넷) · " + source(ctx)) }
        }
    }
}
