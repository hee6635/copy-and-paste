package com.hee6635.linkmemo

import org.json.JSONArray
import org.json.JSONObject

// Gemini.kt 에서 나눔: 정리 요청 글(prompt) · 답 모양(schema)
internal fun Gemini.prompt(item: JSONObject, folders: List<String>, withVideo: Boolean, noTranscript: Boolean = false): String {
    val sb = StringBuilder()
    sb.append("""너는 사용자가 저장한 SNS 게시물 · 영상 · 웹페이지를 실용 메모로 정리하는 도우미야. 한국어로, 짧고 담백하게.

[절대 규칙]
1. 사실만 쓰기: 영상(말 · 화면 자막 · 간판 · 가격표) · 설명 · 본문 · 사진 · 댓글에 실제로 나온 내용만 쓰기. 없는 사실을 추측하거나 "구글 평점 기반", "분위기 좋은", "즐거운 여행", "추천하는 리스트입니다" 같은 근거 없는 수식어 · 감상 · 제목 풀어쓰기 절대 금지. (places 의 need 판단만은 상식을 써도 됨)
2. 중복 금지: places.info 나 recipe.steps 에 이미 쓴 메뉴 · 가격 · 조리 과정을 tips 에 되풀이하지 말기.
3. 숨은 알짜 정보 우선: 영상 말뿐 아니라 화면 자막 · 간판에 짧게 지나가는 할인 · 제휴 · 영업시간 · 예약 조건, 그리고 작성자 답글 속 비교 · 주의사항을 놓치지 말기.
4. 강조 표시: 중요한 숫자 · 가격 · 시간 · 비율 · 할인명은 [[ ]] 로 감싸기 (예: [[오전 6시]], [[2,500~5,500원]], [[라면 1개당 1큰술]]).

[칸별 규칙 — JSON 순서대로 작성]
- category: 아래 목록 중 딱 하나: ${CATEGORIES.joinToString(", ")}
- folder: 위에서 고른 category 와 주제가 일치하는 폴더만 아래 "내 폴더"에서 그대로 고르기. 주제가 다르면 기존 폴더를 억지로 고르지 말고 "category › 구체주제" 또는 "category" 로 새로 제안 (구체주제는 지명이나 세부 주제일 때만).
- kind: 요리법이면 recipe, 준비물 · 할 일 목록이면 checklist, 그 밖은 general.
- area: 장소들이 있는 도시 · 지역 이름 하나 (지도 검색용, 예: 나트랑 · 성수동). 장소 글이 아니면 빈칸.
- listName: places 에 담은 것이 장소가 아닐 때만 항목 종류 한 단어 (예: 스킬 · 앱 · 책 · 제품). 장소면 빈칸.
- places:
  1) 가게 · 장소 · 숙소가 나오면 나온 순서대로 전부. name 은 정확한 상호명(화면 자막 · 간판 우선), t 는 처음 보이는 시각(초, 영상이 아니면 -1), info 는 핵심 1~2줄(70~90자 안팎). 대표 메뉴 · 가격대(최저~최고) · 영업시간 · 위치/동선 특징 · 추천 대상(아이 동반 · 해장 · 향신료 못 먹는 사람 등) · 대안 정보를 아는 만큼 구체적으로.
  2) "추천 N개" · "○○ 모음" · "TOP 10"처럼 여러 항목을 소개하면 장소가 아니어도 빠짐없이 순서대로 전부. name 은 항목 이름 그대로, info 는 용도 · 특징 한 줄.
  3) need: must(작성자가 '또간집 · 강추 · 필수'로 직접 강조했거나 가장 핵심인 항목 — 전체 중 최대 2개까지만, 절반 이상에 붙이지 말기), maybe(보통 · 상황 따라), skip(대부분 필요 없음 · 중복 · 오래됨). 애매하면 maybe.
  4) 해당 없으면 빈 목록.
- recipe: kind 가 recipe 일 때만.
  * servings: 몇 인분인지. 라면 · 만두 등 봉지/개수 기준 요리는 1봉지(1개)=1인분으로 계산 (예: 라면 3봉지면 servings=3). 나오지 않으면 짐작, 모르면 1.
  * ingredients: 재료와 전체 양(servings 기준). 양은 숫자로 (예: 사리곰탕 3개, 대파 2대, 물 1800ml). 영상에 "소주잔 1잔 미만", "종이컵 반 컵" 같은 구체적 생활 계량이 나오면 "적당량"으로 뭉개지 말고 그대로 쓰기. 계량이 아예 없을 때만 "적당량".
  * steps: 순서대로. 인분 조절을 위해 문장 안에 전체 절대 용량(예: 1800ml, 3큰술, 2대)을 쓰지 말고, "준비한 대파를 썰고", "고춧가루를 넣고"처럼 동작 · 불 세기 · 타이밍 · 상태 중심으로 쓰기. 각 단계마다 t 에 해당 동작이 시작하는 영상 시각(초, 영상이 아니면 -1).
- checklist: kind 가 checklist 일 때만. 항목별로 짧고 명확하게.
- events: 날짜가 정해진 행사 · 할인 · 공연 · 마감 · 예약 오픈만 0~3개. title 은 짧게, date 는 YYYY-MM-DD (연도가 없으면 오늘 기준 가장 가까운 앞날), time 은 HH:MM (없으면 빈칸), end 는 끝나는 날 YYYY-MM-DD (없으면 빈칸), place 는 장소 (없으면 빈칸). 날짜가 없으면 빈 목록.
- tips: 아래 기준에 따라 실제로 쓸모 있는 핵심 팁만.
  * places · recipe · checklist 중 하나라도 있으면: 0~5개. 이미 앞 칸에 쓴 내용은 절대 반복 금지. 억지로 채우지 말고 쓸 만한 팁이 없으면 빈 목록 허용.
    우선순위: ① 할인 · 제휴 · 사전 준비(카페 등업, 댓글로 지도/링크 받기 등) ② 비교 · 대안 · 선택 기준(A vs B, 예약 실패 시 대안, 상황별 추천) ③ 조리 비율 공식(1인분/1개 기준) · 실패 방지 요령 · 방문 주의사항 ④ 작성자 답글 · 댓글 속 실질 Q&A · 재방문 후기.
  * places · recipe · checklist 가 모두 비어 있는 일반 정보 · 강의 · 리뷰 글이면: 핵심 요약 3~6개.
  * 각 항목 구성:
    - label: 팁의 성격 3~8자 (예: 제휴 할인, 숙소 비교, 비율 공식, 실패 방지, 대안 맛집, 구글지도)
    - text: 핵심 내용 1줄 (55자 안팎, 숫자 · 시간 · 비율은 [[ ]])
    - from: 출처 하나 — video(영상 말 · 화면 자막), author(작성자 답글), comment(유저 댓글), body(설명 · 본문), photo(사진). 영상에 나온 내용이면 댓글에 언급이 있어도 video 로 표기.
    - t: from 이 video 면 해당 시각(초), 아니면 -1.
    - who: from 이 author 나 comment 면 아이디, 아니면 빈칸.
- star: 실질적 정보(질문 · 답변 · 후기 · 가격 · 비교 · 팁)가 있는 댓글의 번호 목록. [댓글] 줄 앞에 붙은 숫자를 그대로 쓰기 (쓸모없는 댓글은 미리 빼서 번호가 건너뛰어도 다시 세지 말기). 단순 감탄 · 이모지 · 지인 태그 댓글은 제외.
- tags: 2~4개, # 없이 짧은 명사 (지명 · 음식 · 주제). category 와 같은 말 · 뜻이 겹치는 말은 빼기.
- keyshots: (영상 있을 때만) 메뉴판 · 가격표 · 할인 안내 · 지도 · 완성 요리처럼 정보가 화면에 또렷이 보이는 장면 3~5개. places 의 t 와 겹치는 장소 대표 장면은 제외. t 는 시각(초), why 는 무엇이 보이는지 짧게, sec(tips | steps) 와 idx(0부터)는 사진에 보이는 것이 그 줄에 직접 일치할 때만 (맞는 줄이 없으면 idx 는 -1). (영상 없으면 빈 목록)
- desc_ko: [설명]이 한국어가 아니면 자연스러운 한국어 번역, 한국어면 빈칸.
- transcript: (영상 있을 때만) 영상의 말과 화면에 크게 나오는 핵심 글자([화면])를 시간(초)과 함께 받아쓰기. 외국어면 text 는 원문, ko 에 자연스러운 한국어 번역 (한국어면 ko 는 빈칸). (영상 없으면 빈 목록)
""")
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
    fun arr(of: JSONObject) = JSONObject().put("type", "ARRAY").put("items", of)
    fun en(vararg v: String) = JSONObject().put("type", "STRING").put("enum", JSONArray(v.toList()))
    fun obj(vararg p: Pair<String, JSONObject>, req: List<String> = emptyList()) = JSONObject().put("type", "OBJECT")
        .put("properties", JSONObject().also { o -> p.forEach { o.put(it.first, it.second) } })
        .also { if (req.isNotEmpty()) it.put("required", JSONArray(req)) }
    return obj(
        "category" to JSONObject().put("type", "STRING").put("enum", JSONArray(CATEGORIES)),
        "folder" to str(),
        "kind" to en("recipe", "checklist", "general"),
        "area" to str(),
        "listName" to str(),
        "places" to arr(obj("name" to str(), "t" to num(), "info" to str(), "need" to en("must", "maybe", "skip"), req = listOf("name", "t", "info"))),
        "recipe" to obj("servings" to JSONObject().put("type", "INTEGER"), "ingredients" to arr(str()), "steps" to arr(obj("text" to str(), "t" to num(), req = listOf("text")))),
        "checklist" to arr(str()),
        "events" to arr(obj("title" to str(), "date" to str(), "time" to str(), "end" to str(), "place" to str(), req = listOf("title", "date"))),
        "tips" to arr(obj("label" to str(), "text" to str(), "from" to en("video", "author", "comment", "body", "photo"), "t" to num(), "who" to str(), req = listOf("label", "text", "from"))),
        "star" to arr(JSONObject().put("type", "INTEGER")),
        "tags" to arr(str()),
        "keyshots" to arr(obj("t" to num(), "why" to str(), "sec" to en("tips", "steps"), "idx" to JSONObject().put("type", "INTEGER"), req = listOf("t", "why"))),
        "desc_ko" to str(),
        "transcript" to arr(obj("t" to num(), "text" to str(), "ko" to str(), req = listOf("t", "text"))),
        req = listOf("category", "folder", "kind", "places", "tips", "star", "tags")
    ).put("propertyOrdering", JSONArray(listOf(
        // 받아쓰기는 맨 뒤: 답이 끊겨도(저작권 차단 · 길이) 장소 · 팁은 남게
        "category", "folder", "kind", "area", "listName", "places", "recipe", "checklist", "events",
        "tips", "star", "tags", "keyshots", "desc_ko", "transcript")))
}

/**
 * 새 답 모양 → 화면이 쓰는 모양으로 (예전 메모와 같게):
 * tips(label · text · from · t · who) → summary(글) + sumFrom(출처 칩) + sumLabel(이름표) + sumMeta(출처 · 시각) · 레시피 순서 {text, t} → 글 + stepT · keyshots sec tips → summary
 */
internal fun Gemini.normalize(ai: JSONObject): JSONObject {
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
        ai.optJSONArray("keyshots")?.let { k -> for (i in 0 until k.length()) k.optJSONObject(i)?.let { if (it.optString("sec") == "tips") it.put("sec", "summary") } }
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
