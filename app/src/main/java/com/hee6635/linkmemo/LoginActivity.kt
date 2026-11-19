package com.hee6635.linkmemo

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 앱 안에서 인스타 로그인 (한 번). 쿠키는 저장 일꾼(yt-dlp)이 같이 씀 */
class LoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
        val title = TextView(this).apply {
            text = "인스타 로그인"; textSize = 17f; setTextColor(Color.parseColor("#191f28"))
            paint.isFakeBoldText = true
        }
        val done = TextView(this).apply {
            text = "완료"; textSize = 16f; setTextColor(Color.parseColor("#191f28")); paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            setOnClickListener { finish() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, (56 * d).toInt(), 1f))
        bar.addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (56 * d).toInt()))
        val web = WebView(this)
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, (56 * d).toInt()))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                val c = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
                if (c.contains("sessionid") && !url.contains("/accounts/")) done.text = "완료 ✓"
            }
        }
        web.loadUrl("https://www.instagram.com/accounts/login/")
    }

    override fun onPause() { CookieManager.getInstance().flush(); super.onPause() }
}
