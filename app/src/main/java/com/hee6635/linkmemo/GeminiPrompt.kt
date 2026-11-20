package com.hee6635.linkmemo

import org.json.JSONArray
import org.json.JSONObject

// Gemini.kt 에서 나눔: 정리 요청 글(prompt) · 답 모양(schema)
internal fun Gemini.prompt(item: JSONObject, folders: List<String>, withVideo: Boolean, noTranscript: Boolean = false): String {
    val sb = StringBuilder()
    sb.append("""너는 사용자가 저장한 SNS 게시물 · 영상 · 웹페이지를 메모로 정리하는 도우미야. 한국어로, 짧고 쉽게.
규칙:
- summary: "꼭 알아둘 팁" 2~3줄. 각 줄 40자 안팎. places 의 info 에 이미 쓴 말 · 장소 나열은 되풀이하지 말고, 전체에 걸친 실용 팁(주의 · 추천 · 고르는 법 · 받는 법)만. 숫자 · 시간대는 [[ ]] 로 (예: [[오전]]에 가기). 영상(말 · 화면 글자) · 설명 · 댓글 · 작성자 답글에서. 광고 · 해시태그 · 인사는 빼기. 장소도 레시피도 아닌 영상이면 핵심 내용 2~4줄.
- sumFrom: summary 각 줄이 어디서 나왔는지 같은 순서로 한 단어씩: 영상 · 본문 · 사진 · 댓글 · 작성자 댓글 중 하나.
- tags: 2~5개, # 없이 짧은 명사 (지명 · 음식 · 주제). category 와 같은 말 · 서로 뜻이 겹치는 말(개발 · 개발·IT)은 빼기.
- folder: 아래 "내 폴더" 중 가장 맞는 것의 경로를 그대로 쓰기. 주제가 다르면 억지로 고르지 말고 "큰폴더 › 작은폴더" 또는 "큰폴더" 형식으로 새로 제안 (category 이름을 큰폴더로 써도 됨). 작은 폴더는 지명이나 구체 주제일 때만.
- places: 가게 · 장소 · 숙소가 나오면 나온 순서대로. name 은 짧은 이름, t 는 처음 보이는 시각(초, 영상이 아니면 -1), info 는 그곳의 핵심 정보 한 줄(메뉴 · 가격 · 시간대 · 주의 · 추천). info 의 숫자 · 시간은 [[ ]] 로. 장소가 없으면 빈 목록.
  글 · 영상이 "추천 N개" · "○○ 모음" · "TOP 10"처럼 여러 항목을 소개하면 장소가 아니어도 그 항목을 하나도 빼지 말고 모두 places 에 (10개면 10개, 소개한 순서대로). name 은 항목 이름 그대로, info 는 무엇에 좋은지 · 언제 쓰는지 한 줄.
  need: 항목마다 보통 사람에게 필요한 정도. must(꼭 · 처음부터), maybe(상황 따라), skip(대부분 필요 없음 · 겹침 · 오래됨). 글 · 댓글 · 상식으로 판단, 애매하면 maybe.
- listName: places 에 담은 것이 장소가 아니면 항목 종류 한 단어 (예: 스킬 · 앱 · 책 · 제품). 장소면 빈칸.
- area: places 가 장소면 그곳들이 있는 도시 · 지역 이름 하나 (지도에서 찾을 때 붙임, 예: 나트랑 · 성수동). 아니면 빈칸.
- course: 빈 목록 (places 로 대신).
- category: 아래 목록 중 딱 하나 (큰 갈래): ${CATEGORIES.joinToString(", ")}
- kind: 요리법이면 recipe, 할 일 · 준비물 목록이면 checklist, 그 밖은 general.
- recipe: kind 가 recipe 일 때만. 재료(양 포함)와 순서. 재료 양은 숫자로 (예: 감자 2개, 간장 1.5큰술, 물 200ml — "적당량"은 그대로). servings 는 몇 인분인지 (나오지 않으면 짐작, 모르면 1).
- events: 날짜가 정해진 행사 · 할인 · 공연 · 마감 · 예약 오픈만 0~3개. title 은 짧게, date 는 YYYY-MM-DD (연도가 없으면 오늘 기준 가장 가까운 앞날), time 은 HH:MM (없으면 빈칸), end 는 끝나는 날 YYYY-MM-DD (없으면 빈칸), place 는 장소 (없으면 빈칸). 날짜가 없으면 빈 목록.
- checklist: kind 가 checklist 일 때만.
- tips: 영상(말 · 화면 글자) · 댓글 · 작성자 답글에서 실제로 쓸모 있는 정보만 0~6개 (추천 · 주의 · 가격 · 시간 · 위치 등). 인사 · 태그 · 감탄은 빼기. from 은 영상이면 video(그 시각 t 초), 작성자 답글이면 author, 댓글이면 comment, 글 본문이면 body(t 는 -1). who 는 댓글 쓴 사람 아이디(영상 · 본문이면 빈칸).
- star: 정보가 있는 댓글의 번호(0부터) 목록.
""")
    sb.append("- facts: 영상 · 글에서 나온 구체 정보만 0~8개 (가게 · 장소 이름, 가격, 시간, 수치, 방법). 영상에서 나온 것이면 t 에 그 시각(초), 아니면 -1.\n")
    if (withVideo) sb.append("- keyshots: 캡처해 둘 만한 중요한 장면 3~5개 (메뉴판 · 가격표 · 간판 · 지도 · 완성 요리처럼 정보가 화면에 보이는 순간). t 는 그 시각(초, 화면이 또렷한 순간), why 는 무엇이 보이는지 짧게, sec · idx 는 사진에 보이는 것이 그 줄에 직접 나오는 경우만 (summary · facts · steps(레시피 순서) 와 그 줄 번호 0부터). 장소 사진은 places 의 t 로 따로 찍으니 keyshots 에 넣지 않아도 됨. 예: 망고빙수 사진을 호텔 줄에 붙이면 안 됨. 맞는 줄이 없으면 idx 는 -1.\n")
    else sb.append("- keyshots: 빈 목록.\n")
    sb.append("- 번역: transcript 의 말이나 화면 글자가 한국어가 아니면 text 는 원문 그대로 두고 ko 에 자연스러운 한국어 번역 (한국어면 ko 는 빈칸). desc_ko 는 [설명]이 한국어가 아니면 한국어 번역, 한국어면 빈칸. 다른 칸(summary · places · tips 등)은 언제나 한국어로.\n")
    if (withVideo && !noTranscript) sb.append("- transcript: 영상에서 말하는 내용을 시간(초)과 함께 받아쓰기. 화면에 크게 나오는 글자도 [화면] 으로 넣기. 말이 없으면 빈 목록.\n")
    else sb.append("- transcript: 빈 목록.\n")
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
        if (ph.length() > 0) sb.append("[사진] ${ph.length()}장이 같이 와요. 사진 속 정보(카드뉴스 글 · 표 · 가격 · 순서)가 핵심이면 summary · facts · checklist · recipe 에 꼭 담기.\n")
        if (t.isNotEmpty()) sb.append("[사진 속 글자 (폰이 읽음, 틀릴 수 있음)]\n").append(t.joinToString("\n")).append("\n")
    }
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

internal fun Gemini.schema(): JSONObject {
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
        "category" to JSONObject().put("type", "STRING").put("enum", JSONArray(CATEGORIES)),
        "recipe" to obj("servings" to JSONObject().put("type", "INTEGER"), "ingredients" to arr(str()), "steps" to arr(str())),
        "events" to arr(obj("title" to str(), "date" to str(), "time" to str(), "end" to str(), "place" to str(), req = listOf("title", "date"))),
        "checklist" to arr(str()),
        "tips" to arr(obj("text" to str(), "from" to str(), "who" to str(), "t" to JSONObject().put("type", "NUMBER"), req = listOf("text"))),
        "keyshots" to arr(obj("t" to JSONObject().put("type", "NUMBER"), "why" to str(),
            "sec" to JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("summary", "facts", "steps"))),
            "idx" to JSONObject().put("type", "INTEGER"), req = listOf("t", "why"))),
        "star" to arr(JSONObject().put("type", "INTEGER")),
        "transcript" to arr(obj("t" to JSONObject().put("type", "NUMBER"), "text" to str(), "ko" to str(), req = listOf("t", "text"))),
        "desc_ko" to str(),
        "facts" to arr(obj("t" to JSONObject().put("type", "NUMBER"), "text" to str(), req = listOf("t", "text"))),
        "course" to arr(str()),
        "places" to arr(obj("name" to str(), "t" to JSONObject().put("type", "NUMBER"), "info" to str(),
            "need" to JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("must", "maybe", "skip"))), req = listOf("name", "t", "info"))),
        "sumFrom" to arr(str()),
        "listName" to str(),
        "area" to str(),
        req = listOf("summary", "tags", "folder", "kind", "category", "tips", "star", "transcript")
    ).put("propertyOrdering", JSONArray(listOf(
        // 화면에 먼저 보일 것부터: 받아쓰기 → 정보 → 요약 → 코스 → 꿀팁 → 나머지
        // 받아쓰기는 맨 뒤: 답이 끊겨도(저작권 차단 · 길이) 장소 · 요약은 남게
        "listName", "area", "places", "facts", "summary", "sumFrom", "course", "tips", "keyshots", "recipe", "checklist", "events",
        "tags", "folder", "kind", "category", "star", "desc_ko", "transcript")))
}
