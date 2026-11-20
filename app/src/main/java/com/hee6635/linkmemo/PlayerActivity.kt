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
import android.widget.VideoView
import java.io.File

/**
 * 안드로이드 기본 플레이어로 전체 화면 재생 (갤러리와 같은 방식).
 * 앱 화면(WebView)이 소리 형식(HE-AAC 등)을 못 풀어 0.2초에 멈추는 영상용. extra: path, t(초)
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
        setContentView(root)
        val mc = MediaController(this); mc.setAnchorView(v); v.setMediaController(mc)
        val t = (intent.getDoubleExtra("t", 0.0) * 1000).toInt()
        v.setOnPreparedListener { if (t > 0) v.seekTo(t); v.start(); mc.show(3000) }
        v.setOnErrorListener { _, what, extra ->
            close.text = "✕  이 폰에서도 재생이 안 돼요 ($what/$extra)"; close.layoutParams.width = WRAP_CONTENT; close.requestLayout(); true
        }
        v.setVideoPath(f.absolutePath)
    }
}
