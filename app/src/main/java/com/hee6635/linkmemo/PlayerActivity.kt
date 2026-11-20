package com.hee6635.linkmemo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import java.io.File

/**
 * 안드로이드 기본 플레이어로 전체 화면 재생 (갤러리와 같은 방식).
 * 앱 화면(WebView)은 일부 소리 형식(HE-AAC 등)을 못 풀어서 영상은 여기서 봄. extra: path, t(초), id(메모 · 캡처 넣을 곳)
 */
class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = File(intent.getStringExtra("path") ?: "")
        // 앱 폴더 안 파일만
        if (!f.isFile || !f.canonicalPath.startsWith(filesDir.canonicalPath)) { finish(); return }
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val v = VideoView(this)
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        val close = TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            contentDescription = "닫기"
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt(), Gravity.TOP or Gravity.START).apply { topMargin = (24 * d).toInt() })
        val id = intent.getStringExtra("id") ?: ""
        val cap = TextView(this).apply {
            text = "캡처"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(140, 0, 0, 0)); setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            setOnClickListener { if (id.isNotEmpty()) capture(id, f, v.currentPosition / 1000.0) }
        }
        root.addView(cap, FrameLayout.LayoutParams(WRAP_CONTENT, (40 * d).toInt(), Gravity.TOP or Gravity.END).apply { topMargin = (32 * d).toInt(); rightMargin = (16 * d).toInt() })
        setContentView(root)
        val mc = MediaController(this); mc.setAnchorView(v); v.setMediaController(mc)
        val t = (intent.getDoubleExtra("t", 0.0) * 1000).toInt()
        v.setOnPreparedListener { if (t > 0) v.seekTo(t); v.start(); mc.show(3000) }
        v.setOnErrorListener { _, what, extra ->
            close.text = "✕  이 폰에서도 재생이 안 돼요 ($what/$extra)"; close.layoutParams.width = WRAP_CONTENT; close.requestLayout(); true
        }
        v.setVideoPath(f.absolutePath)
    }

    /** 지금 장면을 영상 파일에서 뽑아 캡처 칸에 넣고 글자 읽기 */
    private fun capture(id: String, video: File, t: Double) {
        Toast.makeText(this, "캡처했어요", Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        thread {
            val capId = "cap_${System.currentTimeMillis()}"
            val out = File(Store.dir(ctx, id), "$capId.jpg")
            if (!Ocr.frame(video, t, out)) return@thread
            Store.patch(ctx, id) { o ->
                val arr = o.optJSONArray("captures") ?: JSONArray().also { o.put("captures", it) }
                arr.put(JSONObject().put("id", capId).put("t", t).put("file", out.name).put("text", "").put("memo", "").put("ocr", "busy"))
            }?.let { Events.item(it) }
            Ocr.run(ctx, id, capId, out)
        }
    }
}
