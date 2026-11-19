package com.hee6635.linkmemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 저장한 메모 목록.
 * - 목록: files/data/items.json (메모 배열)
 * - 메모마다 폴더: files/items/<id>/ (video.mp4 · thumb.jpg · cap_*.jpg)
 * 화면(웹)이 메모 내용을 고치면 통째로 다시 저장한다. 처리 중인 메모는 서비스가 고친다.
 */
object Store {
    private val lock = Any()
    private var cache: JSONArray? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "data/items.json").also { it.parentFile?.mkdirs() }
    fun itemsRoot(ctx: Context) = File(ctx.filesDir, "items").also { it.mkdirs() }
    fun dir(ctx: Context, id: String) = File(itemsRoot(ctx), id).also { it.mkdirs() }

    private fun load(ctx: Context): JSONArray {
        cache?.let { return it }
        val f = file(ctx)
        val arr = try {
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        } catch (e: Exception) {
            // 읽기 실패 시 원본을 지우지 않고 옆에 보관
            f.copyTo(File(f.parentFile, "items.broken.${System.currentTimeMillis()}.json"), true)
            JSONArray()
        }
        cache = arr
        return arr
    }

    private fun save(ctx: Context, arr: JSONArray) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "items.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
        cache = arr
    }

    fun all(ctx: Context): String = synchronized(lock) { load(ctx).toString() }

    fun get(ctx: Context, id: String): JSONObject? = synchronized(lock) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) return JSONObject(o.toString())
        }
        null
    }

    /** 같은 id 가 있으면 바꾸고, 없으면 맨 앞에 넣는다 */
    fun put(ctx: Context, item: JSONObject) = synchronized(lock) {
        val arr = load(ctx)
        val id = item.optString("id")
        val out = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) { out.put(item); found = true } else out.put(o)
        }
        if (!found) {
            val n = JSONArray().put(item)
            for (i in 0 until out.length()) n.put(out.get(i))
            save(ctx, n)
        } else save(ctx, out)
    }

    /** 일부 칸만 고치기 (서비스 진행률 등) */
    fun patch(ctx: Context, id: String, f: (JSONObject) -> Unit): JSONObject? = synchronized(lock) {
        val o = get(ctx, id) ?: return null
        f(o)
        put(ctx, o)
        o
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val arr = load(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) out.put(o)
        }
        save(ctx, out)
        File(itemsRoot(ctx), id).deleteRecursively()
    }

    fun findByUrl(ctx: Context, url: String): JSONObject? = synchronized(lock) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("url") == url) return JSONObject(o.toString())
        }
        null
    }

    /** 여러 개 한 번에: 칸 덮어쓰기 */
    fun patchMany(ctx: Context, ids: Set<String>, fields: JSONObject) = synchronized(lock) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") in ids) fields.keys().forEach { k -> o.put(k, fields.get(k)) }
        }
        save(ctx, arr)
    }

    /** 홈 순서 · 분류 목록 등 (files/data/meta.json) */
    fun meta(ctx: Context): String = synchronized(lock) {
        val f = File(ctx.filesDir, "data/meta.json")
        if (f.exists()) f.readText() else "{}"
    }
    fun setMeta(ctx: Context, json: String) = synchronized(lock) {
        JSONObject(json)
        val f = File(ctx.filesDir, "data/meta.json")
        val tmp = File(f.parentFile, "meta.json.tmp")
        tmp.writeText(json); tmp.renameTo(f)
    }

    /** 앱이 꺼져서 멈춘 "처리 중" 표시 정리 */
    fun fixStale(ctx: Context) = synchronized(lock) {
        val arr = load(ctx); var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.has("aiBusy")) { o.remove("aiBusy"); o.remove("aiBusyAt"); o.put("aiError", "중단됐어요 (정리 중에 앱이 꺼졌어요)"); changed = true }
            if (o.optString("status") == "saving") { o.put("status", "error"); o.put("error", "중단됐어요 (저장 중에 앱이 꺼졌어요)"); o.remove("stage"); changed = true }
            if (o.has("commentBusy")) { o.remove("commentBusy"); changed = true }
        }
        if (changed) save(ctx, arr)
    }

    fun usedBytes(ctx: Context): Long = itemsRoot(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 영상 파일만 지우기 (메모 · 썸네일 · 캡처는 남김) */
    fun deleteVideos(ctx: Context) = synchronized(lock) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = o.optString("video")
            if (v.isNotEmpty()) {
                File(dir(ctx, o.optString("id")), v).delete()
                o.remove("video"); o.remove("videoSize")
            }
        }
        save(ctx, arr)
    }
}
