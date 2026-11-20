package com.hee6635.linkmemo

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/* 인스타 사진 게시물: 로그인된 인스타 통로로 사진 전부 · 캡션 받기 + 사진마다 글자 읽기 (SaveService 가 부름) */

/** 사진들을 p1.jpg, p2.jpg … 로 받고 글자를 읽어 [{file, text}] 로 */
internal fun SaveService.downloadPhotos(id: String, urls: List<String>): JSONArray {
    val dir = Store.dir(this, id)
    dir.listFiles()?.filter { Regex("^p\\d+\\.jpg$").matches(it.name) }?.forEach { it.delete() }
    val out = JSONArray()
    urls.forEachIndexed { i, u ->
        stage(id, "사진 받는 중 ${i + 1}/${urls.size}", 10 + 60 * i / maxOf(1, urls.size))
        val f = File(dir, "p${i + 1}.jpg")
        try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", SaveService.UA); c.connectTimeout = 15000; c.readTimeout = 30000
            c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            out.put(JSONObject().put("file", f.name).put("text", ""))
        } catch (_: Throwable) { f.delete() }
    }
    for (i in 0 until out.length()) {
        stage(id, "사진 글자 읽는 중 ${i + 1}/${out.length()}", 70 + 25 * i / maxOf(1, out.length()))
        val p = out.getJSONObject(i)
        p.put("text", Ocr.textOf(File(dir, p.getString("file"))))
    }
    return out
}

/** 저장할 때: yt-dlp 가 못 읽은 인스타 게시물(사진 게시물 등) */
internal fun SaveService.saveIgApi(id: String, url: String, withVideo: Boolean) {
    stage(id, "인스타 게시물 읽는 중", 5)
    val m = Ig.media(this, url)
    if (m.images.isEmpty() && m.video == null && m.caption.isBlank()) throw IllegalStateException("게시물 정보가 비어 있어요")
    val dir = Store.dir(this, id)
    val photos = if (m.images.isNotEmpty()) downloadPhotos(id, m.images) else JSONArray()
    var videoName: String? = null
    if (withVideo && m.video != null) try {
        stage(id, "영상 받는 중", 50)
        URL(m.video).openStream().use { inp -> File(dir, "video.mp4").outputStream().use { inp.copyTo(it) } }
        videoName = "video.mp4"
    } catch (_: Throwable) {}
    emit(Store.patch(this, id) {
        it.put("status", "done"); it.remove("stage"); it.remove("error"); it.put("progress", 100)
        it.put("platform", "instagram")
        it.put("kind", when { m.video != null -> "릴스"; photos.length() > 1 -> "사진 ${photos.length()}"; else -> "사진" })
        it.put("title", SaveService.firstLine(m.caption).ifEmpty { "${m.user} 게시물" })
        it.put("author", m.user)
        it.put("desc", m.caption)
        it.put("comments", JSONArray())
        if (photos.length() > 0) { it.put("photos", photos); it.put("thumb", photos.getJSONObject(0).getString("file")) }
        if (videoName != null) { it.put("video", videoName); it.put("videoSize", File(dir, videoName).length()) }
    })
}

/** 이미 저장한 메모에 사진 다시 받기 (상세의 "사진 가져오기") → 받으면 AI 다시 정리 */
internal fun SaveService.igPhotosLater(id: String) {
    val item = Store.get(this, id) ?: return
    emit(Store.patch(this, id) { it.put("photoBusy", true); it.remove("photoError") })
    try {
        val m = Ig.media(this, item.optString("url"))
        if (m.images.isEmpty()) throw IllegalStateException(if (m.video != null) "영상 게시물이에요 (사진 없음)" else "사진을 찾지 못했어요")
        val photos = downloadPhotos(id, m.images)
        if (photos.length() == 0) throw IllegalStateException("사진을 받지 못했어요")
        emit(Store.patch(this, id) {
            it.remove("photoBusy"); it.remove("stage"); it.put("status", "done")
            it.put("photos", photos); it.put("thumb", photos.getJSONObject(0).getString("file"))
            it.put("kind", if (photos.length() > 1) "사진 ${photos.length()}" else "사진")
            if (it.optString("desc").isBlank() && m.caption.isNotBlank()) it.put("desc", m.caption)
        })
        aiInto(id, true)
    } catch (e: Throwable) {
        emit(Store.patch(this, id) { it.remove("photoBusy"); it.remove("stage"); it.put("status", "done"); it.put("photoError", e.message ?: e.toString()) })
    }
}
