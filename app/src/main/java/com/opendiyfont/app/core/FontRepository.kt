package com.opendiyfont.app.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class FontRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("opendiyfont", Context.MODE_PRIVATE)
    private val fontDir: File
        get() = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    /** 导入字体：校验文件头，去重，复制到应用私有目录并登记元数据。 */
    fun import(uri: Uri, displayName: String?): FontItem {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")
        if (bytes.size < 12) throw IllegalArgumentException("文件过小，不是有效字体")
        val format = detectFormat(bytes)
            ?: throw IllegalArgumentException("不支持的字体格式（需要 TTF / OTF / TTC）")

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        val name = sanitizeName(
            (displayName ?: uri.lastPathSegment ?: "font").substringAfterLast('/')
        )
        val file = File(fontDir, "${id}_$name")
        if (!file.exists()) file.writeBytes(bytes)
        else file.setReadable(true, false)

        val existing = list().toMutableList()
        existing.firstOrNull { it.id == id }?.let { return it }
        val item = FontItem(id, name, file.absolutePath, bytes.size.toLong(), format, System.currentTimeMillis())
        existing.add(0, item)
        save(existing)
        return item
    }

    fun list(): List<FontItem> {
        val raw = prefs.getString("fonts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FontItem(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    path = o.optString("path"),
                    size = o.optLong("size"),
                    format = o.optString("format"),
                    addedAt = o.optLong("addedAt")
                )
            }.filter { File(it.path).exists() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun delete(id: String) {
        val items = list().toMutableList()
        items.firstOrNull { it.id == id }?.let { File(it.path).delete() }
        save(items.filterNot { it.id == id })
    }

    /** 复制到外部 files 目录，确保 Root 与 Shizuku(shell) 都能读取到字体。 */
    fun stageForShell(font: FontItem): File {
        val stageDir = File(context.getExternalFilesDir(null), "stage").apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        val src = File(font.path)
        val dst = File(stageDir, "${font.id}_${src.name}")
        src.copyTo(dst, overwrite = true)
        dst.setReadable(true, false)
        return dst
    }

    fun clearStaging() {
        File(context.getExternalFilesDir(null), "stage").deleteRecursively()
    }

    private fun save(items: List<FontItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("path", it.path)
                put("size", it.size)
                put("format", it.format)
                put("addedAt", it.addedAt)
            })
        }
        prefs.edit().putString("fonts", arr.toString()).apply()
    }

    private fun detectFormat(bytes: ByteArray): String? {
        val head = bytes.take(4).toByteArray()
        val hex = head.joinToString("") { "%02x".format(it) }
        return when {
            hex == "00010000" -> "TrueType"
            String(head, Charsets.US_ASCII) == "OTTO" -> "OpenType"
            String(head, Charsets.US_ASCII) == "ttcf" -> "TrueType Collection"
            String(head, Charsets.US_ASCII) == "true" -> "TrueType (apple)"
            else -> null
        }
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\/:*?\"<>|\\r\\n\\t]"), "_")
        return cleaned.ifBlank { "font.ttf" }.take(120)
    }
}
