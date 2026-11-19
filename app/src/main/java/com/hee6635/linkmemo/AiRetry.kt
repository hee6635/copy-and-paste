package com.hee6635.linkmemo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * AI 정리 자동 다시 하기. 실패하면 "실패" 대신 aiRetryAt(다시 할 시각) · aiRetryN(몇 번째) 를 남긴다.
 * 10분 → 30분 → 2시간 (하루 한도면 한도 초기화 뒤). 최대 3번. 키 문제 · 중지는 다시 안 함.
 * 다시 하는 때: 앱을 열 때 · 다른 저장이 끝날 때 · 알람(배터리 제한을 풀었으면 앱이 닫혀 있어도)
 */
object AiRetry {
    private val DELAYS = longArrayOf(10 * 60_000L, 30 * 60_000L, 2 * 3600_000L)
    const val MAX = 3

    /** 실패 뒤: 다시 할 수 있는 실패면 시각을 정해 item 에 넣는다 (patch 안에서 부름) */
    fun plan(ctx: Context, o: JSONObject, e: Throwable, msg: String) {
        val n = o.optInt("aiRetryN", 0)
        val stop = e is Gemini.KeyBad || msg.startsWith("중지") || "키가 없어요" in msg || "키가 맞지" in msg || "401" in msg || "403" in msg
        if (stop || n >= MAX) { o.remove("aiRetryAt"); return }
        val daily = "429" in msg && Gemini.lastErrRaw.lowercase().let { "perday" in it || "per_day" in it }
        val at = if (daily) nextReset() else System.currentTimeMillis() + DELAYS[n.coerceIn(0, DELAYS.size - 1)]
        o.put("aiRetryAt", at).put("aiRetryN", n + 1)
        alarm(ctx, at)
    }

    /** 무료 하루 한도 초기화(태평양 자정) + 5분 */
    private fun nextReset(): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 5); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 지금 다시 할 차례인 id */
    fun due(ctx: Context): List<String> {
        if (!Gemini.on(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val arr = try { JSONArray(Store.all(ctx)) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter {
            val at = it.optLong("aiRetryAt", 0)
            at in 1..now && it.optString("status") == "done" && !it.has("aiBusy")
        }.map { it.optString("id") }
    }

    /** 차례가 된 것이 있으면 저장 일꾼에게 맡김 (앱이 앞에 있거나 배터리 제한을 풀었을 때만 됨) */
    fun kick(ctx: Context) {
        due(ctx).forEach { id -> try { SaveService.start(ctx, id, "aiAuto") } catch (_: Throwable) { rearm(ctx); return } }
        rearm(ctx)
    }

    /** 아직 남은 것 중 가장 이른 시각으로 알람 다시 */
    fun rearm(ctx: Context) {
        val now = System.currentTimeMillis()
        val arr = try { JSONArray(Store.all(ctx)) } catch (_: Exception) { return }
        val next = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optLong("aiRetryAt", 0) }.filter { it > now }.minOrNull()
        if (next != null) alarm(ctx, next, true)
    }

    private fun alarm(ctx: Context, at: Long, force: Boolean = false) {
        try {
            val sp = ctx.getSharedPreferences("ai_retry", Context.MODE_PRIVATE)
            val cur = sp.getLong("next", 0)
            if (!force && cur > System.currentTimeMillis() && cur <= at) return
            sp.edit().putLong("next", at).apply()
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, RetryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 5000, pi)
        } catch (_: Throwable) {}
    }
}

class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) { try { AiRetry.kick(ctx) } catch (_: Throwable) {} }
}
