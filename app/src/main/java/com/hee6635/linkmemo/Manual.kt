package com.hee6635.linkmemo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * 반자동 AI 정리: 제미나이 앱(내 구독)에 부탁하고, 답을 복사해 오면 넣는다.
 * 1) send: 부탁 글을 클립보드에 복사 + 제미나이 앱 열기 (공유로 글을 넘겨 보고, 안 되면 앱만 열기)
 * 2) paste / ShareActivity: 답(JSON) 을 읽어 메모의 ai 에 넣기
 */
object Manual {
    private const val PKG = "com.google.android.apps.bard"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("manual_ai", Context.MODE_PRIVATE)
    /** 답을 기다리는 메모 (30분 안) */
    fun pending(ctx: Context): String? {
        val p = sp(ctx); val id = p.getString("id", null) ?: return null
        return if (System.currentTimeMillis() - p.getLong("at", 0) < 30 * 60_000L) id else null
    }

    fun prompt(ctx: Context, item: JSONObject): String {
        val yt = SaveService.platformOf(item.optString("url")) == "youtube"
        val sb = StringBuilder()
        if (yt) sb.append("아래 유튜브 영상을 직접 보고 정리해 줘: ").append(item.optString("url")).append("\n\n")
        else sb.append("아래 게시물을 정리해 줘 (링크: ").append(item.optString("url")).append(")\n\n")
        sb.append(Gemini.prompt(item, ctx.folderPaths(), yt, rules = PromptText.get(ctx)))
        sb.append("""

[답 형식] 설명 · 인사 없이 JSON 하나만 ```json 코드 블록으로. 키:
category(문자열), folder(문자열), kind("recipe"|"checklist"|"general"), area(문자열), listName(문자열),
places([{name, t, day, map, info}]), recipe({servings, ingredients, steps: [{text, t}]}), checklist(문자열 목록),
events([{title, date, time, end, place}]), tips([{label, text, from, t, who}]), sections([{title, kind, items}]), links([{label, type, value}]), star(숫자 목록), title(문자열), tags(문자열 목록),
keyshots(빈 목록), desc_ko(문자열), transcript([{t, text, ko}]).
""")
        return sb.toString()
    }

    /** 부탁 글 복사 + 제미나이 앱 열기 */
    fun send(act: Activity, id: String): Boolean {
        val item = Store.get(act, id) ?: return false
        val text = prompt(act, item)
        act.runOnUiThread { act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("제미나이 부탁", text)) }
        sp(act).edit().putString("id", id).putLong("at", System.currentTimeMillis()).apply()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(PKG)
        return try { act.startActivity(share); true } catch (_: Exception) {
            try { act.startActivity(act.packageManager.getLaunchIntentForPackage(PKG) ?: throw IllegalStateException()); true }
            catch (_: Exception) { try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))); true } catch (_: Exception) { false } }
        }
    }

    fun looksLike(text: String) = (text.contains("\"tips\"") || text.contains("\"summary\"")) && text.contains("{")

    /** 답 글에서 JSON 을 꺼내 메모에 넣기. 넣은 메모를 돌려줌 (못 읽으면 null) */
    fun apply(ctx: Context, id: String, text: String): JSONObject? {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val ai = try { JSONObject(text.substring(a, b + 1)) } catch (_: Exception) { return null }
        if (!ai.has("tips") && !ai.has("summary") && !ai.has("places") && !ai.has("recipe")) return null
        Gemini.normalize(ai)
        ai.put("at", System.currentTimeMillis()).put("model", "제미나이 앱").put("manual", true).put("video", false)
        val o = Store.patch(ctx, id) { mergeAi(it, ai) } ?: return null
        sp(ctx).edit().remove("id").apply()
        Events.item(o)
        return o
    }

    /** 클립보드의 답 넣기 (앱이 앞에 있을 때만 읽힘) */
    fun paste(act: Activity, id: String): JSONObject? {
        val clip = act.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        val text = (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(act).toString() }
        return apply(act, id, text)
    }
}
