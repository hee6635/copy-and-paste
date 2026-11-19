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
