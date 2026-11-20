package com.hee6635.linkmemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import kotlin.concurrent.thread

/** 캡처 그림의 글자 읽기 + 영상에서 장면 잘라 내기 */
object Ocr {
    fun run(ctx: Context, id: String, capId: String, file: File) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            .process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { task ->
                val text = if (task.isSuccessful) task.result.text.trim() else ""
                thread {
                    Store.patch(ctx, id) {
                        val arr = it.optJSONArray("captures") ?: return@patch
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            if (c.optString("id") == capId) { c.put("text", text); c.remove("ocr") }
                        }
                    }?.let { Events.item(it) }
                }
            }
    }

    /** 사진 한 장의 글자 (일꾼 스레드에서 기다려서 읽음, 실패면 빈칸) */
    fun textOf(file: File): String = try {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        if (bmp == null) "" else com.google.android.gms.tasks.Tasks.await(
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bmp, 0)),
            20, java.util.concurrent.TimeUnit.SECONDS).text.trim()
    } catch (_: Throwable) { "" }

    /**
     * Gemini 가 영상을 보는 동안 폰이 먼저 화면 글자 읽기: 2초마다 장면 → 글자 → emit(json)
     * json: {"t":초,"text":"…","p":진행 0~1} / 글자가 없으면 text 없이 진행만 / 끝나면 {"done":true}
     */
    fun live(video: File, stop: java.util.concurrent.atomic.AtomicBoolean, emit: (org.json.JSONObject) -> Unit) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            if (dur <= 0) return
            val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            var last = ""
            var t = 0.0
            while (!stop.get() && t < dur) {
                val bmp = r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val o = org.json.JSONObject().put("t", t).put("p", minOf(1.0, (t + 2) / dur))
                if (bmp != null) {
                    val sc = minOf(1f, 720f / maxOf(bmp.width, bmp.height))
                    val b = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
                    val text = try {
                        com.google.android.gms.tasks.Tasks.await(client.process(InputImage.fromBitmap(b, 0)), 10, java.util.concurrent.TimeUnit.SECONDS)
                            .text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                    } catch (_: Throwable) { "" }
                    val clean = text.take(80)
                    val same = clean == last || (clean.length > 4 && last.length > 4 && (last.contains(clean) || clean.contains(last)))
                    if (Regex("[가-힣A-Za-z0-9]{2,}").containsMatchIn(clean) && !same) o.put("text", clean)
                    if (clean.isNotEmpty()) last = clean
                }
                emit(o)
                t += 2.0
            }
        } catch (_: Throwable) {
        } finally {
            try { r.release() } catch (_: Throwable) {}
            emit(org.json.JSONObject().put("done", true))
        }
    }

    /** 영상의 t초 장면을 JPG 로 (긴 변 1280 이하). 실패하면 false */
    fun frame(video: File, tSec: Double, out: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            val bmp = r.getFrameAtTime((tSec * 1_000_000).toLong().coerceAtLeast(0), MediaMetadataRetriever.OPTION_CLOSEST) ?: return false
            val s = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
            val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
            out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            true
        } catch (_: Throwable) { false } finally { try { r.release() } catch (_: Throwable) {} }
    }
}
