package com.hee6635.linkmemo

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * AI 사용량: 앱이 직접 센 횟수 · 토큰 (날짜 · 모델별) + 마지막으로 걸린 한도(429) 내용.
 * 구글은 "남은 횟수"를 알려 주지 않아서, 걸렸을 때 구글이 준 정보를 그대로 보여 준다.
 * 무료 하루 한도는 미국 태평양 시간 자정에 초기화.
 */
object Usage {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }.format(Date())
    private fun load(ctx: Context) = try { JSONObject(sp(ctx).getString("u", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** 모델별 하루 한도(대략값으로 시작 · 429 에서 받은 숫자로 바뀜 · 설정에서 고칠 수 있음) */
    private val DEFAULT_LIMIT = mapOf("gemini-flash-lite-latest" to 1000, "gemini-flash-latest" to 250)
    fun limitOf(ctx: Context, model: String): Int = sp(ctx).getInt("lim_$model", DEFAULT_LIMIT[model] ?: 250)
    fun setLimit(ctx: Context, model: String, n: Int) = sp(ctx).edit().putInt("lim_$model", n).putBoolean("limSet_$model", true).apply()

    @Synchronized fun ok(ctx: Context, model: String, tokens: Int) {
        val u = load(ctx)
        val days = u.optJSONObject("days") ?: JSONObject().also { u.put("days", it) }
        val d = days.optJSONObject(day()) ?: JSONObject().also { days.put(day(), it) }
        val m = d.optJSONObject(model) ?: JSONObject().also { d.put(model, it) }
        m.put("n", m.optInt("n") + 1).put("tok", m.optLong("tok") + tokens)
        // 40일 넘은 날은 지움
        val keys = days.keys().asSequence().toList().sorted()
        if (keys.size > 40) keys.take(keys.size - 40).forEach { days.remove(it) }
        sp(ctx).edit().putString("u", u.toString()).apply()
    }

    /** 메모 하나를 정리할 때마다 (평균 계산용) */
    fun memo(ctx: Context) { val k = "memos_" + day().take(6); sp(ctx).edit().putInt(k, sp(ctx).getInt(k, 0) + 1).apply() }

    /** 429 응답 본문에서 어떤 한도에 걸렸는지 · 얼마 뒤 되는지 */
    @Synchronized fun limit(ctx: Context, model: String, raw: String) {
        val u = load(ctx)
        val o = JSONObject().put("at", System.currentTimeMillis()).put("model", model)
        try {
            val err = JSONObject(raw).optJSONObject("error")
            o.put("msg", err?.optString("message")?.take(300) ?: "")
            val det = err?.optJSONArray("details")
            if (det != null) for (i in 0 until det.length()) {
                val d = det.getJSONObject(i)
                val type = d.optString("@type")
                if (type.endsWith("QuotaFailure")) {
                    val v = d.optJSONArray("violations")?.optJSONObject(0)
                    if (v != null) o.put("quotaId", v.optString("quotaId")).put("metric", v.optString("quotaMetric")).put("value", v.optString("quotaValue"))
                }
                if (type.endsWith("RetryInfo")) o.put("retry", d.optString("retryDelay"))
            }
        } catch (_: Exception) { o.put("msg", raw.take(300)) }
        u.put("last429", o)
        sp(ctx).edit().putString("u", u.toString()).apply()
        // 하루 한도 숫자를 구글이 알려 줬으면 그걸로 바꿈
        val v = o.optString("value").toIntOrNull()
        val per = (o.optString("metric") + o.optString("quotaId")).lowercase()
        if (v != null && v > 0 && ("day" in per)) sp(ctx).edit().putInt("lim_$model", v).apply()
    }

    /** 화면용: 오늘 · 이번 달 모델별 + 마지막 한도 + 다음 초기화(한국 시각) */
    fun summary(ctx: Context): String {
        val u = load(ctx)
        val days = u.optJSONObject("days") ?: JSONObject()
        val today = days.optJSONObject(day()) ?: JSONObject()
        val month = day().take(6)
        val mon = JSONObject()
        days.keys().forEach { k ->
            if (!k.startsWith(month)) return@forEach
            val d = days.getJSONObject(k)
            d.keys().forEach { m -> val x = d.getJSONObject(m); val t = mon.optJSONObject(m) ?: JSONObject().also { mon.put(m, it) }; t.put("n", t.optInt("n") + x.optInt("n")).put("tok", t.optLong("tok") + x.optLong("tok")) }
        }
        // 태평양 다음 자정 → 한국 시각
        val la = java.util.Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")).apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }
        val reset = SimpleDateFormat("M월 d일 a h시", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(la.time)
        // 예상: 이번 달 하루 평균 · 남은 날 (태평양 날짜 기준)
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        val dom = cal.get(java.util.Calendar.DAY_OF_MONTH); val dim = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val models = (DEFAULT_LIMIT.keys + today.keys().asSequence().toList()).distinct()
        val lim = JSONObject(); models.forEach { lim.put(it, limitOf(ctx, it)) }
        // 메모 하나에 평균 몇 번 (AI 정리 1번 + 긴 영상 부분 등) — 이번 달 기준
        val memos = sp(ctx).getInt("memos_$month", 0)
        return JSONObject().put("today", today).put("month", mon).put("last429", u.optJSONObject("last429") ?: JSONObject()).put("reset", reset)
            .put("limits", lim).put("dom", dom).put("dim", dim).put("memos", memos).toString()
    }
}
