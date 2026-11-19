package com.hee6635.linkmemo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager

/**
 * 오래 걸린 원인을 가르기 위한 폰 상태 기록:
 * 앱이 앞/뒤, 화면 켜짐/꺼짐, 인터넷 바뀜, 절전.
 */
object PhoneState {
    class Ev(val at: Long, val what: String)
    private val evs = ArrayDeque<Ev>()
    @Volatile var foreground = false
    private var started = false

    fun mark(what: String) = synchronized(evs) {
        evs.addLast(Ev(System.currentTimeMillis(), what))
        while (evs.size > 200) evs.removeFirst()
    }

    fun between(a: Long, b: Long): List<Ev> = synchronized(evs) { evs.filter { it.at in a..b } }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                mark(if (i.action == Intent.ACTION_SCREEN_OFF) "화면 꺼짐" else "화면 켜짐")
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pm = c.getSystemService(PowerManager::class.java)
                mark(if (pm.isDeviceIdleMode) "폰 절전(잠자기) 들어감" else "폰 절전(잠자기) 끝")
            }
        }, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = mark("인터넷 연결됨")
                override fun onLost(network: Network) = mark("인터넷 끊김")
            })
        } catch (_: Throwable) {}
    }

    fun setForeground(v: Boolean) { foreground = v; mark(if (v) "앱 화면 앞으로" else "앱이 뒤로 감") }

    /** 지금 폰 설정 중 통신을 늦출 수 있는 것 */
    fun throttles(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) out += "배터리 최적화 켜짐 (뒤에서 통신이 늦어질 수 있어요)"
        if (pm.isPowerSaveMode) out += "절전 모드 켜짐"
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) out += "데이터 절약 켜짐"
        return out
    }
}
