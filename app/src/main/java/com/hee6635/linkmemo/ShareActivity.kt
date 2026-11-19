package com.hee6635.linkmemo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URL

/**
 * 공유 → 앱을 열지 않고 바로 저장: 보던 앱 위에 작은 창(저장 방식 · 한 줄 메모 · 저장)만 띄움.
 * 저장하면 창이 내려가고 원래 앱으로 돌아감. 저장 · AI 정리는 뒤에서(알림으로 진행).
 */
class ShareActivity : ComponentActivity() {
    private var mode = "video"
    private lateinit var sheet: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        // 제미나이 앱에서 답을 공유해 온 경우: 기다리던 메모에 넣고 끝
        Manual.pending(this)?.let { pid ->
            if (Manual.looksLike(text)) {
                val ok = Manual.apply(this, pid, text) != null
                Toast.makeText(this, if (ok) "제미나이 정리를 넣었어요" else "답을 읽지 못했어요 (JSON 부분까지 복사해 주세요)", Toast.LENGTH_SHORT).show()
                finish(); return
            }
        }
        val url = Regex("https?://[^\\s\"'<>]+").find(text)?.value?.trimEnd(')', '.', ',')
        if (url == null) { Toast.makeText(this, "링크를 찾지 못했어요", Toast.LENGTH_SHORT).show(); finish(); return }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ink = Color.parseColor("#191F28"); val sub = Color.parseColor("#4E5968"); val line = Color.parseColor("#D1D6DB"); val surface = Color.parseColor("#F2F4F6")
        fun round(c: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply { setColor(c); cornerRadius = r * d; if (stroke > 0) setStroke(stroke, sc) }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#73000000")); setOnClickListener { close() } }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = floatArrayOf(26 * d, 26 * d, 26 * d, 26 * d, 0f, 0f, 0f, 0f) }
            setPadding(dp(20), dp(10), dp(20), dp(20))
            isClickable = true
        }
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        sheet.addView(android.view.View(this).apply { background = round(line, 3) }, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        val platform = SaveService.platformOf(url)
        val host = when (platform) { "youtube" -> "youtube"; "instagram" -> "instagram"; else -> try { URL(url).host.removePrefix("www.") } catch (_: Exception) { "링크" } }
        sheet.addView(TextView(this).apply { this.text = "링크메모에 저장"; textSize = 18f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        sheet.addView(TextView(this).apply { this.text = "$host · $url"; textSize = 12.5f; setTextColor(sub); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, dp(14)) })

        // 저장 방식 (웹페이지는 없음)
        val btns = mutableListOf<TextView>()
        if (platform != "web") {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun paint() = btns.forEach { b -> val on = b.tag == mode; b.background = round(Color.WHITE, 14, if (on) dp(2) else dp(1), if (on) ink else line); b.setTextColor(if (on) ink else sub); b.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            listOf("video" to "영상까지", "link" to "링크만").forEachIndexed { i, (v, label) ->
                val b = TextView(this).apply { this.text = label; tag = v; gravity = Gravity.CENTER; textSize = 15f; setOnClickListener { mode = v; paint() } }
                btns += b
                row.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i == 0) marginEnd = dp(8) })
            }
            paint()
            sheet.addView(row)
        } else mode = "link"

        val memo = EditText(this).apply {
            hint = "한 줄 메모"; textSize = 15.5f; setTextColor(ink); setHintTextColor(Color.parseColor("#8B95A1"))
            background = round(surface, 14); setPadding(dp(14), 0, dp(14), 0); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_DONE
        }
        sheet.addView(memo, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        val save = TextView(this).apply {
            this.text = "저장"; gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; background = round(ink, 27)
            setOnClickListener { doSave(url, memo.text.toString().trim()) }
        }
        memo.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { doSave(url, memo.text.toString().trim()); true } else false }
        sheet.addView(save, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(16) })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            sheet.setPadding(dp(20), dp(10), dp(20), dp(20) + b.bottom); insets
        }
        // 아래에서 올라오기
        sheet.translationY = 600 * d
        sheet.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun doSave(url: String, memo: String) {
        val r = Saver.add(this, url, mode, memo)
        Toast.makeText(this, if (r.has("dup")) "이미 저장한 링크예요" else "링크메모에 저장 중이에요", Toast.LENGTH_SHORT).show()
        close()
    }

    private fun close() {
        if (!::sheet.isInitialized) { finish(); return }
        sheet.animate().translationY(sheet.height.toFloat() + 40).setDuration(220).withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }
}
