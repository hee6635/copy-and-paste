package com.hee6635.linkmemo

import org.json.JSONObject

/** 중간에 끊긴 AI 답(JSON) 살리기: 열린 따옴표 · 괄호를 닫고, 안 되면 마지막 쉼표까지 잘라 다시 */
object JsonFix {
    /** 끊긴 이유 → 쉬운 말 */
    fun reason(finish: String) = when (finish) {
        "RECITATION" -> "저작권 차단 (받아쓰기가 원문과 너무 같음)"
        "MAX_TOKENS" -> "답 길이 한도"
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST" -> "안전 차단"
        "", "STOP", "FINISH_REASON_UNSPECIFIED" -> "이유 모름 (연결이 끊겼을 수 있음)"
        else -> finish
    }

    fun repair(s: String): JSONObject? {
        var t = s.trim()
        repeat(60) {
            try { return JSONObject(close(t)) } catch (_: Exception) {}
            val i = lastComma(t)
            if (i <= 0) return null
            t = t.substring(0, i)
        }
        return null
    }

    private fun close(t: String): String {
        val st = ArrayDeque<Char>()
        var inStr = false; var esc = false
        for (c in t) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> st.addLast('}'); '[' -> st.addLast(']'); '}', ']' -> if (st.isNotEmpty()) st.removeLast() }
        }
        var out = t
        if (inStr) { if (esc) out = out.dropLast(1); out += "\"" }
        out = out.trimEnd()
        if (out.endsWith(",")) out = out.dropLast(1)
        if (out.endsWith(":")) out += "null"
        return out + st.reversed().joinToString("")
    }

    /** 따옴표 밖의 마지막 쉼표 자리 */
    private fun lastComma(t: String): Int {
        var inStr = false; var esc = false; var last = -1
        t.forEachIndexed { i, c ->
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else if (c == '"') inStr = true else if (c == ',') last = i
        }
        return last
    }
}
