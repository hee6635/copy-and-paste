package com.hee6635.linkmemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 백업 파일(zip): data/items.json · data/meta.json · items/<id>/… (영상은 고를 수 있음)
 * 저장 위치는 사용자가 고름(내 폰 · 구글 드라이브 등 안드로이드 저장 창).
 */
object Backup {
    fun make(ctx: Context, out: OutputStream, withVideo: Boolean): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            // 목록: 영상을 빼면 video 칸도 뺌
            val arr = JSONArray(Store.all(ctx))
            if (!withVideo) for (i in 0 until arr.length()) arr.getJSONObject(i).apply { remove("video"); remove("videoSize") }
            z.putNextEntry(ZipEntry("data/items.json")); z.write(arr.toString().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("data/meta.json")); z.write(Store.meta(ctx).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("linkmemo.json")); z.write(JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("items", arr.length()).toString().toByteArray()); z.closeEntry()
            val root = Store.itemsRoot(ctx)
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                if (!withVideo && f.name.startsWith("video.")) return@forEach
                z.putNextEntry(ZipEntry("items/" + f.relativeTo(root).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry(); n++
            }
        }
        return arr0(ctx)
    }
    private fun arr0(ctx: Context) = JSONArray(Store.all(ctx)).length()

    /** 되살리기: 먼저 임시 폴더에 풀어 확인한 뒤 바꿔 넣음. 돌려주는 값 = 메모 수 */
    fun restore(ctx: Context, input: InputStream): Int {
        val tmp = File(ctx.cacheDir, "restore").also { it.deleteRecursively(); it.mkdirs() }
        ZipInputStream(input.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(tmp, e.name)
                if (!f.canonicalPath.startsWith(tmp.canonicalPath)) throw IllegalStateException("잘못된 백업 파일")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                e = z.nextEntry
            }
        }
        val items = File(tmp, "data/items.json")
        if (!items.exists()) throw IllegalStateException("링크메모 백업 파일이 아니에요")
        val n = JSONArray(items.readText()).length()
        val data = File(ctx.filesDir, "data"); val root = Store.itemsRoot(ctx)
        data.deleteRecursively(); root.deleteRecursively()
        File(tmp, "data").copyRecursively(data, true)
        File(tmp, "items").takeIf { it.exists() }?.copyRecursively(root, true)
        root.mkdirs()
        tmp.deleteRecursively()
        Store.reset()
        return n
    }
}
