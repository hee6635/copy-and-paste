package com.hee6635.linkmemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/* AI 정리 (SaveService 가 부름): 폴더 목록 · Gemini 정리 · 폰 글자 먼저 · 자동 캡처 */

/** 폴더 경로 목록 (meta.json) — "여행 › 나트랑" */
internal fun android.content.Context.folderPaths(): List<String> = try {
    val fs = JSONObject(Store.meta(this)).optJSONArray("folders") ?: JSONArray()
    val byId = (0 until fs.length()).map { fs.getJSONObject(it) }.associateBy { it.optString("id") }
    byId.values.map { f ->
        val p = f.optString("parent").takeIf { it.isNotEmpty() && it != "null" }?.let { byId[it]?.optString("name") }
        if (p != null) "$p › ${f.optString("name")}" else f.optString("name")
    }
} catch (_: Throwable) { emptyList() }

/**
 * AI 태그 바꾸기: 지난번 AI 태그(aiTags)는 빼고 새 AI 태그(최대 4개)로. 내가 넣은 태그는 그대로.
 * aiTags 기록이 없는 예전 메모는 있던 태그를 모두 AI 것으로 봄 (예전엔 직접 넣은 것과 구분이 안 됐음)
 */
internal fun applyTags(o: JSONObject, ai: JSONObject) {
    val old = o.optJSONArray("tags") ?: JSONArray()
    val oldAi = o.optJSONArray("aiTags")
    val prevAi = if (oldAi == null) null else (0 until oldAi.length()).map { oldAi.optString(it) }.toSet()
    val mine = (0 until old.length()).map { old.optString(it) }.filter { prevAi != null && it !in prevAi }
    val cat = ai.optString("category")
    val fresh = mutableListOf<String>()
    ai.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) { val v = t.optString(i).removePrefix("#").trim(); if (v.isNotEmpty() && v != cat && v !in fresh && v !in mine && fresh.size < 4) fresh.add(v) } }
    o.put("tags", JSONArray(mine + fresh)).put("aiTags", JSONArray(fresh))
}

/** AI 결과를 메모에 넣기: 갈래 · 태그 바꾸기 · 정보 댓글 ★ · 실패 흔적 지우기 */
internal fun mergeAi(o: JSONObject, ai: JSONObject) {
    o.put("ai", ai)
    ai.optString("category").takeIf { it.isNotEmpty() }?.let { o.put("category", it) }
    applyTags(o, ai)
    val cs = o.optJSONArray("comments"); val star = ai.optJSONArray("star")
    if (cs != null) {
        for (i in 0 until cs.length()) cs.optJSONObject(i)?.remove("star")
        if (star != null) for (i in 0 until star.length()) cs.optJSONObject(star.optInt(i, -1))?.put("star", true)
    }
    listOf("aiBusy", "aiBusyAt", "aiError", "aiLog", "aiRetryAt", "aiRetryN").forEach { o.remove(it) }
}

/** Gemini 정리. 실패해도 저장은 그대로 두고 aiError 만 남김 */
internal fun SaveService.aiInto(id: String, force: Boolean, auto: Boolean = false) {
    if (!(if (force) Gemini.key(this).isNotEmpty() else Gemini.on(this))) {
        if (force) emit(Store.patch(this, id) { it.put("aiError", "Gemini API 키가 없어요 (설정에서 넣어 주세요)") })
        return
    }
    val item = Store.get(this, id) ?: return
    Usage.memo(this)
    emit(Store.patch(this, id) { it.put("aiBusy", if (auto) "AI 다시 정리 준비 중" else "AI 정리 준비 중"); it.put("aiBusyAt", System.currentTimeMillis()); it.remove("aiError"); it.remove("aiLog"); it.remove("aiRetryAt"); if (!auto) it.remove("aiRetryN"); it.optJSONObject("ai")?.remove("deepError") })
    updateNotif("AI 정리 중")
    // 긴 영상(10분 넘음): 5분씩 목차 → 목차를 글로 붙여 짧게 한 번 더 정리(팁 · 장소 · 태그)
    if (item.optBoolean("long") && SaveService.platformOf(item.optString("url")) == "youtube") {
        try {
            val log = Gemini.RunLog(this)
            val ch = Gemini.chapters(this, item, { st -> emit(Store.patch(this, id) { it.put("aiBusy", "AI $st") }) },
                { p, n, all -> Events.chapters(id, JSONObject().put("part", p).put("parts", n).put("chapters", all).toString()) }, log)
            val pseudo = JSONObject(item.toString())
            pseudo.remove("subs"); pseudo.remove("video")
            val toc = StringBuilder()
            for (i in 0 until ch.length()) { val c = ch.getJSONObject(i); val t = c.optDouble("t").toInt(); toc.append("%d:%02d %s — %s\n".format(t / 60, t % 60, c.optString("title"), c.optString("info"))) }
            pseudo.put("desc", item.optString("desc").take(3000) + "\n\n[영상 목차]\n" + toc)
            emit(Store.patch(this, id) { it.put("aiBusy", "AI 팁 정리 중") })
            val ai = Gemini.organize(this, pseudo, folderPaths(), { st -> emit(Store.patch(this, id) { it.put("aiBusy", "AI $st") }) })
            ai.put("chapters", ch).put("long", true)
            val rows = ai.optJSONObject("log")?.optJSONArray("rows")
            if (rows != null) for (i in 0 until log.rows.length()) rows.put(log.rows.get(i))
            emit(Store.patch(this, id) { o ->
                o.put("ai", ai)
                ai.optString("category").takeIf { it.isNotEmpty() }?.let { o.put("category", it) }
                applyTags(o, ai)
                o.remove("aiBusy"); o.remove("aiBusyAt"); o.remove("aiError"); o.remove("aiLog"); o.remove("aiRetryAt"); o.remove("aiRetryN")
            })
        } catch (e: Throwable) {
            emit(Store.patch(this, id) {
                it.remove("aiBusy"); it.remove("aiBusyAt")
                it.put("aiError", if (e is Gemini.AiFail) (e.message ?: "") else Gemini.explain(e))
                if (e is Gemini.AiFail) it.put("aiLog", e.log)
                AiRetry.plan(this, it, e, it.optString("aiError"))
            })
        }
        return
    }
    // 1단계: 영상을 볼 메모면 먼저 글만으로 빠르게 정리해 바로 보여 줌 (2단계 영상 보기가 실패해도 남음)
    val plat = SaveService.platformOf(item.optString("url"))
    val hasPhotos = (item.optJSONArray("photos")?.length() ?: 0) > 0
    val videoLikely = item.optString("video").isNotEmpty() || (!hasPhotos && Gemini.watch(this) && (plat == "youtube" || plat == "instagram"))
    var hasAi = item.optJSONObject("ai") != null
    if (videoLikely && !hasAi) {
        try {
            val a1 = Gemini.organize(this, item, folderPaths(), { st -> emit(Store.patch(this, id) { it.put("aiBusy", "AI 빠르게 $st") }) }, quick = true)
            a1.put("light", true)
            emit(Store.patch(this, id) { o -> mergeAi(o, a1); o.put("aiBusy", "AI 영상 자세히 보는 중"); o.put("aiBusyAt", System.currentTimeMillis()) })
            hasAi = true
        } catch (e: Throwable) {
            if (e.message == "중지했어요") { emit(Store.patch(this, id) { it.remove("aiBusy"); it.remove("aiBusyAt") }); return }
        }
    }
    try {
        // 2단계 — 링크만 저장한 것도 영상 보기: 유튜브는 링크 그대로, 인스타는 잠깐 받아서
        var temp: File? = null; var yt: String? = null
        val pre = mutableListOf<Triple<String, Long, String>>()
        var dlFail: String? = null   // 인스타 영상을 못 받아 글만으로 정리하게 된 이유
        val url = item.optString("url")
        if (item.optString("video").isEmpty() && Gemini.watch(this) && !hasPhotos) when (SaveService.platformOf(url)) {
            "youtube" -> yt = Gemini.ytWatchUrl(url)
            "instagram" -> {
                emit(Store.patch(this, id) { it.put("aiBusy", "AI 영상 잠깐 받는 중") })
                val t0 = System.currentTimeMillis()
                try {
                    temp = tempVideo(id, url)
                    pre += Triple("영상 잠깐 받기", System.currentTimeMillis() - t0, String.format(java.util.Locale.US, "%.1fMB (정리 뒤 지움)", (temp?.length() ?: 0) / 1e6))
                } catch (e: Throwable) {
                    dlFail = friendly(e.message ?: e.toString())
                    pre += Triple("영상 잠깐 받기", System.currentTimeMillis() - t0, "실패 → 글만 보고 정리: " + dlFail)
                }
            }
        }
        // 폰이 먼저 화면 글자 읽기 (Gemini 가 영상을 보는 동안). 유튜브는 장면 · 글자용으로 작은 영상을 같이 받음
        val stopOcr = java.util.concurrent.atomic.AtomicBoolean(false)
        val savedVid = item.optString("video").takeIf { it.isNotEmpty() }?.let { File(Store.dir(this, id), it) }?.takeIf { it.exists() }
        val holder = arrayOfNulls<File>(1)
        val firstVid = savedVid ?: temp?.takeIf { it.exists() }
        val ytUrl = yt
        val side = Thread {
            var v: File? = firstVid
            if (v == null && ytUrl != null) v = try { tempVideo(id + "_yt", url).also { holder[0] = it } } catch (_: Throwable) { null }
            if (v != null) Ocr.live(v, stopOcr) { o -> Events.ocr(id, o.toString()) }
        }.also { it.start() }
        val ai = try {
            Gemini.organize(this, item, folderPaths(), { st ->
                emit(Store.patch(this, id) { it.put("aiBusy", "AI $st") })
            }, temp, yt, pre, fallback = !hasAi) { txt -> stopOcr.set(true); Events.partial(id, txt) }
        } catch (e: Throwable) { stopOcr.set(true); side.join(60000); temp?.delete(); holder[0]?.delete(); throw e }
        stopOcr.set(true)
        side.join(60000)
        dlFail?.let { if (!ai.has("noVideo")) ai.put("noVideo", true).put("noVideoWhy", "인스타 영상 받기 실패: " + it) }
        if (temp == null && holder[0] != null) temp = holder[0]
        // AI 가 고른 중요 장면 자동 캡처 (영상 파일이 있을 때: 저장한 영상 · 잠깐 받은 영상 · 유튜브는 작게 잠깐 받기)
        val shots = JSONArray()
        val hasPlaces = (ai.optJSONArray("places")?.length() ?: 0) > 0
        val hasRecipe = ai.optString("kind") == "recipe" && ai.optJSONObject("recipe") != null
        ai.optJSONArray("places")?.let { pl -> for (i in 0 until pl.length()) { val p0 = pl.getJSONObject(i); val tt = p0.optDouble("t", -1.0); if (tt >= 0) shots.put(JSONObject().put("t", tt + 0.6).put("why", p0.optString("name")).put("sec", "places").put("idx", i)) } }
        // 레시피 순서(stepT)도 장소와 똑같이 해당 조리 시각 장면을 직접 캡처
        var hasStepShots = false
        ai.optJSONObject("recipe")?.let { r ->
            val steps = r.optJSONArray("steps")
            val stepT = r.optJSONArray("stepT")
            if (steps != null && stepT != null) for (i in 0 until minOf(steps.length(), stepT.length())) {
                val tt = stepT.optDouble(i, -1.0)
                if (tt >= 0) {
                    shots.put(JSONObject().put("t", tt + 0.6).put("why", steps.optString(i)).put("sec", "steps").put("idx", i))
                    hasStepShots = true
                }
            }
        }
        // 일반 요약 카드(장소·레시피 없을 때)도 각 카드의 영상 시각(t)에 맞춰 1:1 캡처
        val sumTs = mutableListOf<Double>()
        if (!hasPlaces && !hasRecipe) {
            val sum = ai.optJSONArray("summary")
            val meta = ai.optJSONArray("sumMeta")
            if (sum != null && meta != null) for (i in 0 until minOf(sum.length(), meta.length())) {
                val m = meta.optJSONObject(i)
                val tt = m?.optDouble("t", -1.0) ?: -1.0
                if (m?.optString("from") == "video" && tt >= 0) {
                    shots.put(JSONObject().put("t", tt + 0.6).put("why", sum.optString(i)).put("sec", "summary").put("idx", i).put("direct", true))
                    sumTs += tt
                }
            }
        }
        // 장소 · 레시피 순서 · 요약 카드는 1:1 캡처 + 그 외 중요 장면은 5장까지
        ai.optJSONArray("keyshots")?.let { k ->
            for (i in 0 until minOf(5, k.length())) {
                val ks = k.getJSONObject(i)
                if (hasStepShots && ks.optString("sec") == "steps") continue
                if (sumTs.isNotEmpty() && (ks.optString("sec") == "summary" || sumTs.any { st -> kotlin.math.abs(st - ks.optDouble("t", -99.0)) <= 2.0 })) continue
                shots.put(ks)
            }
        }
        if (shots.length() > 0) {
            try {
                emit(Store.patch(this, id) { it.put("aiBusy", "AI 중요 장면 캡처 중") })
                val saved = item.optString("video").takeIf { it.isNotEmpty() }?.let { File(Store.dir(this, id), it) }?.takeIf { it.exists() }
                var src: File? = saved ?: temp?.takeIf { it.exists() }
                if (src == null && SaveService.platformOf(url) == "youtube") {
                    src = try { tempVideo(id, url) } catch (_: Throwable) { null }
                    temp = src
                }
                if (src != null) autoCaptures(id, src, shots, ai)
            } catch (_: Throwable) {
            } finally { temp?.delete() }
        } else temp?.delete()
        emit(Store.patch(this, id) { o ->
            o.put("ai", ai)
            ai.optString("category").takeIf { it.isNotEmpty() }?.let { o.put("category", it) }
            applyTags(o, ai)
            // 정보 있는 댓글 ★
            val cs = o.optJSONArray("comments")
            val star = ai.optJSONArray("star")
            if (cs != null) {
                for (i in 0 until cs.length()) cs.getJSONObject(i).remove("star")
                if (star != null) for (i in 0 until star.length()) cs.optJSONObject(star.optInt(i, -1))?.put("star", true)
            }
            o.remove("aiBusy"); o.remove("aiBusyAt"); o.remove("aiError"); o.remove("aiLog"); o.remove("aiRetryAt"); o.remove("aiRetryN")
        })
    } catch (e: Throwable) {
        emit(Store.patch(this, id) {
            it.remove("aiBusy"); it.remove("aiBusyAt")
            val msg = if (e is Gemini.AiFail) (e.message ?: "") else Gemini.explain(e)
            val cur = it.optJSONObject("ai")
            // 1단계 결과가 있으면 그대로 두고 "영상 자세히 보기"만 실패로 표시
            if (cur != null && cur.optBoolean("light")) { cur.put("deepError", msg.lineSequence().first()); if (e is Gemini.AiFail) cur.put("deepLog", e.log) }
            else { it.put("aiError", msg); if (e is Gemini.AiFail) it.put("aiLog", e.log) }
            AiRetry.plan(this, it, e, msg)
        })
    }
}

/** 예전 자동 캡처는 지우고, 새로 고른 장면을 잘라 붙임 + 글자 읽기 */
/**
 * 캡처를 붙일 줄 확인: AI 가 고른 줄에 사진 설명(why)의 낱말이 하나라도 나와야 붙임.
 * 안 맞으면 낱말이 나오는 다른 줄(정보)을 찾고, 그래도 없으면 캡처 칸에만.
 */
internal fun SaveService.attachFor(k: JSONObject, ai: JSONObject): JSONObject? {
    val words = Regex("[가-힣A-Za-z0-9]{2,}").findAll(k.optString("why")).map { it.value }
        .filter { it !in setOf("화면", "장면", "모습", "사진", "보이는", "있는", "메뉴", "이름") }.toList()
    fun lineText(sec: String, i: Int): String? = when (sec) {
        "summary" -> ai.optJSONArray("summary")?.optString(i)
        "facts" -> ai.optJSONArray("facts")?.optJSONObject(i)?.optString("text")
        else -> null
    }
    fun hit(s: String?) = s != null && words.any { w -> s.contains(w) || (w.length >= 3 && s.contains(w.take(w.length - 1))) }
    val sec = k.optString("sec"); val idx = k.optInt("idx", -1)
    if ((sec == "places" || sec == "steps" || (sec == "summary" && k.optBoolean("direct"))) && idx >= 0) return JSONObject().put("sec", sec).put("i", idx)
    if ((sec == "summary" || sec == "facts") && idx >= 0 && hit(lineText(sec, idx))) return JSONObject().put("sec", sec).put("i", idx)
    val n = ai.optJSONArray("facts")?.length() ?: 0
    for (i in 0 until n) if (hit(lineText("facts", i))) return JSONObject().put("sec", "facts").put("i", i)
    return null
}

internal fun SaveService.autoCaptures(id: String, video: File, shots: JSONArray, ai: JSONObject) {
    val dir = Store.dir(this, id)
    Store.get(this, id)?.optJSONArray("captures")?.let { arr ->
        for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); if (c.optBoolean("ai")) File(dir, c.optString("file")).delete() }
    }
    val made = mutableListOf<JSONObject>()
    for (i in 0 until minOf(30, shots.length())) {
        val k = shots.getJSONObject(i)
        val t = k.optDouble("t", -1.0); if (t < 0) continue
        val capId = "cap_ai_${System.currentTimeMillis()}_$i"
        val f = File(dir, "$capId.jpg")
        // 장소 사진: 글자가 한 자씩 나오는 영상이 있어서 0.5 · 1.5 · 2.5초 뒤 중 장소 이름이 가장 잘 보이는 장면
        val t2 = if (k.optString("sec") == "places") bestFrame(video, t - 0.6, k.optString("why"), f) else t.takeIf { Ocr.frame(video, it, f) }
        if (t2 == null) continue
        val c = JSONObject().put("id", capId).put("t", t2).put("file", f.name).put("text", "").put("memo", k.optString("why")).put("ocr", "busy").put("ai", true)
        attachFor(k, ai)?.let { c.put("at", it) }
        made += c
    }
    emit(Store.patch(this, id) { o ->
        val arr = o.optJSONArray("captures") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) if (!arr.getJSONObject(i).optBoolean("ai")) out.put(arr.get(i))
        made.forEach { out.put(it) }
        o.put("captures", out)
    })
    made.forEach { Ocr.run(this, id, it.getString("id"), File(dir, it.getString("file"))) }
}

/** 장소가 나온 시각 뒤 장면 3장 중 장소 이름 글자가 가장 많이 읽히는 것을 out 에 (같으면 글자가 많은 쪽). 고른 시각, 다 실패면 null */
internal fun bestFrame(video: File, t0: Double, name: String, out: File): Double? {
    val key = name.replace(Regex("\\s"), "")
    var best: Double? = null; var bestScore = -1
    for (d in listOf(0.5, 1.5, 2.5)) {
        val tmp = File(out.parentFile, out.nameWithoutExtension + "_c.jpg")
        if (!Ocr.frame(video, t0 + d, tmp)) continue
        val txt = Ocr.textOf(tmp).replace(Regex("\\s"), "")
        val score = nameScore(key, txt) * 1000 + minOf(txt.length, 999)
        if (score > bestScore) { bestScore = score; best = t0 + d; tmp.copyTo(out, true) }
        tmp.delete()
    }
    return best
}

/** 장소 이름 글자 중 읽힌 글자 수 (전부 읽히면 이름 길이 + 1) */
internal fun nameScore(key: String, txt: String): Int =
    if (key.isNotEmpty() && txt.contains(key, true)) key.length + 1 else key.count { txt.contains(it, true) }
