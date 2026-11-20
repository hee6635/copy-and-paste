package com.hee6635.linkmemo

import android.media.MediaExtractor
import android.media.MediaFormat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/* 영상 받기 (SaveService.kt 에서 나눔): 화질 고르기 · 소리를 앱 화면이 푸는 형식으로 · 받은 뒤 소리 형식 확인 */

private fun formatFor(q: String): String = when (q) {
    "best" -> "bv*+ba/b"
    else -> "bv*[height<=$q][ext=mp4]+ba[ext=m4a]/b[height<=$q][ext=mp4]/bv*[height<=$q]+ba/b[height<=$q]/b"
}

/** 영상 · 소리를 꼭 따로 받아 합치기 (합칠 때 소리를 AAC 로 바꾸게). xHE-AAC(mp4a.40.42) 소리는 피함 */
private fun splitFormat(q: String): String {
    val h = if (q == "best") "" else "[height<=$q]"
    return "bv*$h+ba[acodec!*=40.42]/bv*$h+ba"
}

/**
 * 영상 받기 → 파일 이름(video.mp4). 소리 형식은 item.audioFmt 에 남김.
 * 앱 화면(WebView)은 인스타 새 소리 형식(xHE-AAC)을 못 풀어 0.2초에 멈춤(갤러리는 됨) → 따로 받아 AAC 로 바꿔 다시.
 */
internal fun SaveService.downloadVideoFile(id: String, url: String): String? {
    Tools.init(this)
    val q = Prefs.quality(this)
    var name = fetchVideo(id, url, formatFor(q)) ?: return null
    var af = audioFormat(File(Store.dir(this, id), name))
    if (af == "xHE-AAC") {
        // 합쳐진 파일 하나로 받아서 소리가 그대로였음 → 따로 받아 합치며 바꾸기
        val again = try { fetchVideo(id, url, splitFormat(q)) } catch (_: Throwable) { null }
        if (again != null) { name = again; af = audioFormat(File(Store.dir(this, id), name)) }
    }
    emit(Store.patch(this, id) { it.put("audioFmt", af) })
    return name
}

/** yt-dlp 로 받기. 합칠 때 소리 AAC 변환이 실패하면(ffmpeg 가 못 푸는 소리) 변환 없이 한 번 더 */
private fun SaveService.fetchVideo(id: String, url: String, fmt: String): String? {
    return try { fetchOnce(id, url, fmt, true) } catch (e: Throwable) {
        if (Regex("(?i)postprocess|ffmpeg|merg").containsMatchIn(e.message ?: "")) fetchOnce(id, url, fmt, false) else throw e
    }
}

private fun SaveService.fetchOnce(id: String, url: String, fmt: String, aac: Boolean): String? {
    val dir = Store.dir(this, id)
    dir.listFiles()?.filter { it.name.startsWith("media.") }?.forEach { it.delete() }
    val req = YoutubeDLRequest(url)
    req.addOption("--no-playlist"); req.addOption("--no-mtime")
    req.addOption("-f", fmt)
    req.addOption("--merge-output-format", "mp4")
    // 합칠 때: 소리는 표준 AAC 로 · 목차(moov)는 파일 앞에. 영상은 그대로 복사
    req.addOption("--postprocessor-args", if (aac) "Merger+ffmpeg_o1:-c:a aac -b:a 160k -movflags +faststart" else "Merger+ffmpeg_o1:-movflags +faststart")
    req.addOption("--socket-timeout", "30")
    req.addOption("-o", File(dir, "media.%(ext)s").absolutePath)
    if (SaveService.platformOf(url) == "instagram") Ig.cookieFile(this)?.let { req.addOption("--cookies", it.absolutePath) }
    stage(id, "영상 받는 중", 0)
    var last = 0L
    YoutubeDL.getInstance().execute(req, "video-$id", false) { p, _, _ ->
        val now = System.currentTimeMillis()
        if (now - last > 700) { last = now; val pct = p.toInt().coerceIn(0, 100); stage(id, "영상 받는 중", pct); updateNotif("저장 중 $pct%") }
    }
    val v = dir.listFiles()?.firstOrNull { it.name.startsWith("media.") && it.extension in setOf("mp4", "webm", "mkv", "mov") } ?: return null
    val name = "video." + v.extension
    v.renameTo(File(dir, name))
    dir.listFiles()?.filter { it.name.startsWith("media.") }?.forEach { it.delete() }
    return name
}

/** 소리 형식 이름: AAC 면 종류(AAC-LC · HE-AAC · xHE-AAC …), 아니면 mime. 소리가 없으면 "없음" */
internal fun audioFormat(f: File): String {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(f.absolutePath)
        val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return "없음"
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime != "audio/mp4a-latm") return mime.removePrefix("audio/")
        val csd = fmt.getByteBuffer("csd-0") ?: return "AAC"
        aacName(ByteArray(csd.remaining()).also { csd.duplicate().get(it) })
    } catch (_: Throwable) { "알 수 없음" } finally { ex.release() }
}

/** AudioSpecificConfig 앞 5비트(31 이면 뒤 6비트 + 32) = 오디오 객체 종류 */
internal fun aacName(c: ByteArray): String {
    if (c.isEmpty()) return "AAC"
    val b0 = c[0].toInt() and 0xff
    var aot = b0 shr 3
    if (aot == 31 && c.size > 1) aot = 32 + (((b0 and 0x07) shl 3) or ((c[1].toInt() and 0xff) shr 5))
    return when (aot) { 2 -> "AAC-LC"; 5 -> "HE-AAC"; 29 -> "HE-AAC v2"; 42 -> "xHE-AAC"; else -> "AAC($aot)" }
}
