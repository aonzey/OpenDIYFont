package com.opendiyfont.app.core.strategy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.opendiyfont.app.R
import com.opendiyfont.app.core.ApplyRequest
import com.opendiyfont.app.core.ApplyResult
import com.opendiyfont.app.core.Channel
import com.opendiyfont.app.core.shell.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 资源版本标记（写进 descriptions，供幂等探针识别本版生成的资源）。
 * v1.4.10 把作者/设计者从「zFont 3」改为「来自用户」，meta 内容变了，
 * 因此必须升标记：让旧条目在下次应用时重建一次（仅一次，之后仍走快路径），
 * 否则 themeResourcesUpToDate() 会命中旧 meta，详情页作者仍然是 zFont 3。
 */
private const val META_VERSION = "1.4.10"

/**
 * 系统默认字体资源的真实 localId（MIUI V14.0.27.0.TMRCNXM 真机实测）：
 *   /system/media/theme/.data/meta/fonts/default.mrm -> "10"（标题「默认」）
 *   /system/media/theme/.data/meta/theme/default.mrm  -> "0" （标题「经典」）
 *
 * 旧版用伪 ID "default" 发 ViewLocalResource intent。ThemeDetailActivity
 * 找不到与之对应的本地资源时，OnlineResourceDetail.toResource() 返回 null，
 * 于是弹出 string/loading_resource_detail_fail（0x7f1202e6，「加载资源失败」）
 * 并 finish()——尽管 REQUEST_APPLY_EVENT 的应用流程已把字体切回默认。
 *
 * 换用与 REQUEST_RESOURCE_CODE="fonts" 语义匹配的真实 ID 后：
 * DRM_SUCCESS -> Applying theme BEGIN: 默认 / END: 经典 -> Resource=0，
 * 且 /data/system/theme/fonts/ 整个目录被移除（真机已验证）。
 */
private const val DEFAULT_FONT_LOCAL_ID = "10"

/**
 * MIUI 14 / HyperOS 的 ThemeManager 会对本地注入的字体做 DRM 授权校验：
 * .mra 里的 o-ex:id 是对授权内容（含 asset uid）的签名，客户端无法重新签发，
 * 任何改动都会让 `standard format rights file verify is legal` 变成
 * `old format rights file verify result : false`，进而触发 downloadRights，
 * 服务端返回 408「未购买」/ 402「频率太高」，页面显示 downloadRightI402。
 *
 * 因此这里固定使用一份与合法授权严格配套的 meta 模板：hash / size / version /
 * contentPath / builtInPreviews 等字段均不可随意改动，只替换 localId 和标题。
 * 每个导入字体仍使用由内容派生的独立 localId，保证「主题 → 我的 → 字体」
 * 中多个字体可以共存、互不覆盖。
 *
 * 真机验证（Redmi Note 12 Turbo / MIUI V14.0.27.0.TMRCNXM / Android 13）：
 * `standard format rights file verify is legal` → `DRM_SUCCESS` →
 * `Applying theme BEGIN/END`，且 downloadRights 402/408 次数为 0。
 * 应用后的异步审计会记一条 `DRM_ERROR_ASSET_NOT_MATCH`（字体内容 hash 与
 * 固定授权 uid 不同），该检查不阻断本次应用，字体已实际生效。
 */
private const val LEGAL_FONT_HASH = "0a2dd58e9cbb1c025964fc7196b425be5bb921ed"
private const val LEGAL_THEME_HASH = "e4dc9169ca5b502bbdb5c0eda55374c7f6fd8ec9"
private const val LEGAL_META_VERSION = "3.9.2.2"
private const val LEGAL_FONT_SIZE = 5_344_512L
private const val LEGAL_THEME_SIZE = 2_570_776L
private const val LEGAL_CONTENT_PATH_PREFIX =
    "/system/../storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/theme/"

/**
 * 完整合法的授权原文，**一字不可改**（含固定时间戳与 3 天窗口；实测 DRM
 * 只校验签名，不按窗口过期）。asset uid 固定，不随导入字体变化。
 */
private const val LEGAL_RIGHTS_XML = """<o-ex:rights xmlns:o-ex="http://odrl.net/1.1/ODRL-EX" xmlns:o-dd="http://odrl.net/1.1/ODRL-DD" xmlns:oma-dd="http://www.openmobilealliance.com/oma-dd" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" o-ex:id="600615e8f6326b0114fe322056837fc714dbb0932b530edae68003e86376bed7757e3dcab6decbf4311d4530763ad90f49c4fd2f74e2bcdbaad4d6b70f99bffbf561112c2a66e718e5f2acbdc39e92af3859033e73509a9db8fd581a35053365f0df22f939950dab7ef9eb58f0dfdb9f22f6d533329a8f70a5dbc597a4622fab"><o-ex:context><o-dd:version>2.1</o-dd:version><o-dd:uid>1795540925000</o-dd:uid></o-ex:context><o-ex:agreement><o-ex:asset><o-ex:context><o-dd:uid>e4dc9169ca5b502bbdb5c0eda55374c7f6fd8ec9:0a2dd58e9cbb1c025964fc7196b425be5bb921ed</o-dd:uid></o-ex:context><o-ex:digest><ds:DigestMethod Algorithm="nop"/><ds:DigestValue/></o-ex:digest><ds:KeyInfo><xenc:EncryptedKey><xenc:EncryptionMethod Algorithm="nop"/><ds:KeyInfo><ds:RetrievalMethod URI="nop"/></ds:KeyInfo><xenc:CipherData><xenc:CipherValue/></xenc:CipherData></xenc:EncryptedKey></ds:KeyInfo></o-ex:asset><o-ex:permission><o-dd:display><o-ex:constraint><o-dd:count>0</o-dd:count></o-ex:constraint></o-dd:display><o-dd:execute><o-ex:constraint><oma-dd:individual><o-ex:context><o-dd:uid>m-1</o-dd:uid><o-dd:uid>d-1</o-dd:uid></o-ex:context></oma-dd:individual><!-- ISO8601 period --><o-dd:accumulated>P0DT0H5M00S</o-dd:accumulated><o-dd:datetime><o-dd:start>2026-11-24T17:21:05+0000</o-dd:start><o-dd:end>2026-11-27T17:22:05+0000</o-dd:end></o-dd:datetime></o-ex:constraint></o-dd:execute></o-ex:permission></o-ex:agreement></o-ex:rights>"""

/**
 * 小米 / Redmi / POCO（MIUI / HyperOS）主题注入方案。
 *
 * 与改 /system/fonts 不同：把字体按 zFont3 兼容的目录结构写进
 * Android/data/com.android.thememanager/files/MIUI/theme/.data，
 * 再用 ViewLocalResource intent 让 ThemeManager 立即应用，无需重启。
 */
class MiuiThemeStrategy(
    private val context: Context,
    override val channel: Channel
) : FontStrategy {

    override val name = "MIUI 主题注入（免重启）"

    private val prefs = context.getSharedPreferences("opendiyfont", Context.MODE_PRIVATE)
    private val baseDir =
        "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data"

    override fun apply(
        request: ApplyRequest,
        shell: Shell,
        stagedFont: File,
        logs: MutableList<String>
    ): ApplyResult {
        val fontHash = sha1(stagedFont)
        val localId = stableLocalId(fontHash)
        // meta hash 不再按字体内容派生：DRM 授权签名绑定固定 hash，换成动态 hash
        // 会让验签失败并触发 downloadRights（I402）。内容 hash 仅用于 localId 与去重。
        val themeHash = LEGAL_THEME_HASH
        logs += "[MIUI] 字体内容哈希=$fontHash"
        logs += "[MIUI] 稳定主题 localId=$localId"
        logs += "[MIUI] 字体范围 ${request.scope.label} 由主题引擎统一处理"

        // 多字体共存：不再全量清理主题条目，否则应用新字体会把之前已应用的字体
        // 从「主题 → 我的 → 字体」里删掉（覆盖问题）。这里只移除「与当前字体内容
        // 相同、但 localId 不是稳定 ID」的历史遗留条目，其它字体的条目一律保留。
        // 当前字体使用由内容派生的稳定 localId，写入本身就是幂等覆盖，
        // 反复应用同一字体不会产生重复。
        val (dupRemoved, totalInList) = cleanupDuplicateOf(shell, logs, fontHash, localId)
        logs += "[MIUI] 主题字体列表将保留 $totalInList 个字体（含本次「${request.font.name}」）"
        if (dupRemoved > 0) {
            logs += "[MIUI] 已清理 $dupRemoved 条同一字体的历史重复条目"
        }

        // 旧版本条目的作者/设计者沿用合法模板里的「zFont 3」，原地改成「来自用户」。
        // 放在幂等探针之前：被升级的同字体条目会直接命中快路径，无需重复写大字体。
        migrateLegacyMeta(shell, logs)

        // 幂等快速路径：稳定 localId 保证同一字体反复应用写的是同一批主题资源。
        // 目标条目已完整写入且与本次内容一致时，跳过预览图生成（解析大字体 + 绘制位图）
        // 与大字体复制，只通知主题引擎应用，避免重复应用同一字体明显变慢。
        // 探针只读、失败一律回退到完整写入流程，不会跳过实际写入而误报成功。
        if (themeResourcesUpToDate(shell, logs, localId, stagedFont.length())) {
            logs += "[MIUI] 复用已写入的主题资源，跳过预览生成与字体复制"
            launchApply(localId)
            logs += "[MIUI] 已发送立即应用意图，等待 ThemeManager 应用字体"
            return ApplyResult(
                success = true,
                channel = shell.channel,
                title = "已通过 MIUI 主题引擎应用",
                detail = "字体已导入系统主题并立即生效，无需重启。\n" +
                    "已应用的多个字体都会保留在「主题 → 我的 → 字体」中，可随时切换选用。",
                rebootSuggested = false,
                logs = logs
            )
        }

        val stage = File(context.getExternalFilesDir(null), "theme").apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        val fontMeta = File(stage, "font.mrm")
        val themeMeta = File(stage, "theme.mrm")
        val rights = File(stage, "rights.mra")
        val previewBig = File(stage, "preview_fonts_0.jpg")
        val previewSmall = File(stage, "preview_fonts_small_0.png")

        try {
            fontMeta.writeText(fontMetaJson(localId, request.font.name))
            themeMeta.writeText(themeMetaJson(localId, request.font.name))
            rights.writeText(LEGAL_RIGHTS_XML)
            writePreviews(stagedFont, previewBig, previewSmall, request.font.name)
            listOf(fontMeta, themeMeta, rights, previewBig, previewSmall).forEach {
                it.setReadable(true, false)
            }
        } catch (t: Throwable) {
            return fail(shell, logs, "主题元数据生成失败", t)
        }

        val script = buildString {
            appendLine("BASE=$baseDir")
            appendLine("ID=$localId")
            appendLine("TH=$LEGAL_THEME_HASH")
            appendLine("FH=$LEGAL_FONT_HASH")
            appendLine("SRC=${sq(stagedFont.absolutePath)}")
            appendLine("FM=${sq(fontMeta.absolutePath)}")
            appendLine("TM=${sq(themeMeta.absolutePath)}")
            appendLine("RM=${sq(rights.absolutePath)}")
            appendLine("PB=${sq(previewBig.absolutePath)}")
            appendLine("PS=${sq(previewSmall.absolutePath)}")
            appendLine("mkdir -p \"\$BASE/content/fonts\" \"\$BASE/content/theme\" \"\$BASE/meta/fonts\" \"\$BASE/meta/theme\" \"\$BASE/preview/theme/\$ID\" \"\$BASE/rights/theme\" || exit 1")
            appendLine("cp -f \"\$SRC\" \"\$BASE/content/fonts/\$ID.mrc\" || exit 2")
            appendLine(": > \"\$BASE/content/theme/\$ID.mrc\" || exit 3")
            appendLine("cp -f \"\$FM\" \"\$BASE/meta/fonts/\$ID.mrm\" || exit 4")
            appendLine("cp -f \"\$TM\" \"\$BASE/meta/theme/\$ID.mrm\" || exit 5")
            appendLine("cp -f \"\$PB\" \"\$BASE/preview/theme/\$ID/preview_fonts_0.jpg\" || true")
            appendLine("cp -f \"\$PS\" \"\$BASE/preview/theme/\$ID/preview_fonts_small_0.png\" || true")
            appendLine("cp -f \"\$RM\" \"\$BASE/rights/theme/\$FH.mra\" || true")
            appendLine("cp -f \"\$RM\" \"\$BASE/rights/theme/\$TH.mra\" || true")
            appendLine("chown theme:sdcard_rw \"\$BASE/content/fonts/\$ID.mrc\" \"\$BASE/content/theme/\$ID.mrc\" \"\$BASE/meta/fonts/\$ID.mrm\" \"\$BASE/meta/theme/\$ID.mrm\" 2>/dev/null || true")
            appendLine("chown -R theme:sdcard_rw \"\$BASE/preview/theme/\$ID\" 2>/dev/null || true")
            appendLine("chmod 660 \"\$BASE/content/fonts/\$ID.mrc\" \"\$BASE/content/theme/\$ID.mrc\" \"\$BASE/meta/fonts/\$ID.mrm\" \"\$BASE/meta/theme/\$ID.mrm\" 2>/dev/null || true")
            appendLine("echo THEME_WRITTEN")
        }

        val result = shell.exec(script, 90_000)
        logs += "[$name] exit=${result.exitCode}"
        result.combined.lines().filter { it.isNotBlank() }.forEach { logs += "  $it" }
        if (!result.ok || !result.stdout.contains("THEME_WRITTEN")) {
            return fail(shell, logs, "主题目录写入失败", null)
        }

        prefs.edit()
            .putString("miui_theme_id", localId)
            .putString("miui_theme_hash", themeHash)
            .apply()

        launchApply(localId)
        logs += "[MIUI] 已发送立即应用意图，等待 ThemeManager 应用字体"

        return ApplyResult(
            success = true,
            channel = shell.channel,
            title = "已通过 MIUI 主题引擎应用",
            detail = "字体已导入系统主题并立即生效，无需重启。\n" +
                "已应用的多个字体都会保留在「主题 → 我的 → 字体」中，可随时切换选用。",
            rebootSuggested = false,
            logs = logs
        )
    }

    /** 只删除本应用写入的主题资源，不发送“恢复默认”意图，避免打断当前界面。 */
    fun clearInjection(shell: Shell, logs: MutableList<String>): ApplyResult {
        val cleaned = cleanupAllInjection(shell, logs)
        return ApplyResult(
            success = true,
            channel = shell.channel,
            title = "已清理 MIUI 主题注入",
            detail = "已移除 $cleaned 条 OpenDIYFont 主题条目。",
            rebootSuggested = false,
            logs = logs
        )
    }

    /**
     * 扫描并删除本应用写入的所有主题条目。
     *
     * 旧版本每次应用都使用随机 UUID，导致同一字体反复应用后
     * ThemeManager 的字体列表里出现多条重复。这里以 meta 中固定的
     * 描述字段作为标记，连历史随机条目一并清除。
     */
    private fun cleanupAllInjection(shell: Shell, logs: MutableList<String>): Int {
        val script = buildString {
            appendLine("BASE=$baseDir")
            appendLine("n=0")
            appendLine("for f in \"\$BASE\"/meta/theme/*.mrm; do")
            appendLine("  [ -f \"\$f\" ] || continue")
            appendLine("  grep -q 'Imported by OpenDIYFont' \"\$f\" || continue")
            appendLine("  id=\$(basename \"\$f\" .mrm)")
            appendLine("  rm -f \"\$BASE/content/fonts/\$id.mrc\" \"\$BASE/content/theme/\$id.mrc\"")
            appendLine("  rm -f \"\$BASE/meta/fonts/\$id.mrm\" \"\$f\"")
            appendLine("  rm -rf \"\$BASE/preview/theme/\$id\"")
            appendLine("  n=\$((n+1))")
            appendLine("done")
            // 所有字体共用同一份固定授权，只在全量清理时统一移除
            appendLine("rm -f \"\$BASE/rights/theme/$LEGAL_FONT_HASH.mra\" \"\$BASE/rights/theme/$LEGAL_THEME_HASH.mra\"")
            appendLine("echo CLEANED=\$n")
        }
        val result = shell.exec(script, 60_000)
        val cleaned = Regex("CLEANED=(\\d+)")
            .find(result.stdout)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        logs += "[$name/清理] exit=${result.exitCode} 移除条目=$cleaned"
        result.combined.lines()
            .filter { it.isNotBlank() && !it.startsWith("CLEANED=") }
            .forEach { logs += "  $it" }
        prefs.edit().remove("miui_theme_id").remove("miui_theme_hash").apply()
        return cleaned
    }

    /**
     * 只清理「与当前字体内容相同、但 localId 不是稳定 ID」的历史遗留条目
     * （早期版本用过随机 UUID，可能给同一字体留下多个条目），其它字体的
     * 主题条目全部保留，使多种字体可以共存于「主题 → 我的 → 字体」列表。
     *
     * 返回 (清理掉的重复数, 本次写入后列表中的字体总数)。
     */
    private fun cleanupDuplicateOf(
        shell: Shell,
        logs: MutableList<String>,
        fontHash: String,
        keepId: String
    ): Pair<Int, Int> {
        val script = buildString {
            appendLine("BASE=$baseDir")
            appendLine("KEEP=$keepId")
            appendLine("HASH=$fontHash")
            appendLine("n=0")
            appendLine("total=0")
            appendLine("for t in \"\$BASE\"/meta/theme/*.mrm; do")
            appendLine("  [ -f \"\$t\" ] || continue")
            appendLine("  grep -q 'Imported by OpenDIYFont' \"\$t\" || continue")
            appendLine("  id=\$(basename \"\$t\" .mrm)")
            appendLine("  [ \"\$id\" = \"\$KEEP\" ] && continue")
            appendLine("  fh=\$(sha1sum \"\$BASE/content/fonts/\$id.mrc\" 2>/dev/null | cut -c1-40)")
            appendLine("  if [ \"\$fh\" = \"\$HASH\" ]; then")
            appendLine("    rm -f \"\$BASE/content/fonts/\$id.mrc\" \"\$BASE/content/theme/\$id.mrc\"")
            appendLine("    rm -f \"\$BASE/meta/fonts/\$id.mrm\" \"\$t\"")
            appendLine("    rm -rf \"\$BASE/preview/theme/\$id\"")
            appendLine("    n=\$((n+1))")
            appendLine("  else")
            appendLine("    total=\$((total+1))")
            appendLine("  fi")
            appendLine("done")
            appendLine("total=\$((total+1))")
            appendLine("echo CLEANED=\$n TOTAL=\$total")
        }
        val result = shell.exec(script, 60_000)
        val cleaned = Regex("CLEANED=(\\d+)")
            .find(result.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = Regex("TOTAL=(\\d+)")
            .find(result.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        logs += "[$name/去重] exit=${result.exitCode} 移除同字体重复=$cleaned 列表字体总数=$total"
        result.combined.lines()
            .filter { it.isNotBlank() && !it.startsWith("CLEANED=") }
            .forEach { logs += "  $it" }
        return cleaned to total
    }

    /**
     * 把旧版本写入的主题条目原地升级到本版展示信息：作者/设计者由「zFont 3」
     * 改为「来自用户」，descriptions 的版本标记同步到 META_VERSION。
     *
     * 不删除条目、不改动字体内容与预览，因此「主题 → 我的 → 字体」里多个已
     * 应用字体仍然共存。作者字段只是展示信息，不参与 .mra 的 DRM 签名，
     * 真机验证改后仍为 DRM_SUCCESS 且 402/408 为 0。
     *
     * 中文文案用 printf 八进制字节生成（来自用户 = E6 9D A5 E8 87 AA E7 94 A8
     * E6 88 B7），避免设备 shell 对中文参数编码不一致导致 sed 匹配失败。
     */
    private fun migrateLegacyMeta(shell: Shell, logs: MutableList<String>): Int {
        val script = buildString {
            appendLine("BASE=$baseDir")
            appendLine("VER=$META_VERSION")
            appendLine("NEW=\$(printf '\\346\\235\\245\\350\\207\\252\\347\\224\\250\\346\\210\\267')")
            appendLine("n=0")
            appendLine("for t in \"\$BASE\"/meta/theme/*.mrm; do")
            appendLine("  [ -f \"\$t\" ] || continue")
            appendLine("  grep -q 'Imported by OpenDIYFont' \"\$t\" || continue")
            appendLine("  grep -q \"Imported by OpenDIYFont \$VER\" \"\$t\" && continue")
            appendLine("  id=\$(basename \"\$t\" .mrm)")
            appendLine("  f=\"\$BASE/meta/fonts/\$id.mrm\"")
            appendLine("  [ -f \"\$f\" ] || continue")
            appendLine("  sed -i \"s/zFont 3/\$NEW/g; s/Imported by OpenDIYFont [0-9.]*/Imported by OpenDIYFont \$VER/g\" \"\$t\" \"\$f\" 2>/dev/null || true")
            appendLine("  n=\$((n+1))")
            appendLine("done")
            appendLine("echo MIGRATED=\$n")
        }
        val result = shell.exec(script, 30_000)
        val migrated = Regex("MIGRATED=(\\d+)")
            .find(result.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        logs += "[$name/迁移] 已将 $migrated 条旧版本主题条目的作者改为「来自用户」（条目与字体内容全部保留）"
        return migrated
    }

    /** 由字体内容派生稳定 UUID，保证同一字体反复应用只对应一个主题条目。 */
    private fun stableLocalId(fontHash: String): String {
        val h = sha1("opendiyfont:$fontHash".toByteArray())
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
            "${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    /**
     * 只读检查目标 localId 的主题资源是否已完整写入、且与本次字体内容一致。
     * 命中时 apply() 走快速路径；检查失败或异常一律返回 false，回退完整写入流程。
     * 不修改任何文件、不清理任何资源。
     */
    private fun themeResourcesUpToDate(
        shell: Shell,
        logs: MutableList<String>,
        localId: String,
        fontSize: Long
    ): Boolean {
        val script = buildString {
            appendLine("BASE=$baseDir")
            appendLine("ID=$localId")
            appendLine("TH=$LEGAL_THEME_HASH")
            appendLine("FH=$LEGAL_FONT_HASH")
            appendLine("SIZE=$fontSize")
            appendLine("ok=1")
            appendLine("[ -f \"\$BASE/meta/theme/\$ID.mrm\" ] || ok=0")
            appendLine("[ -f \"\$BASE/meta/fonts/\$ID.mrm\" ] || ok=0")
            appendLine("[ -f \"\$BASE/content/theme/\$ID.mrc\" ] || ok=0")
            appendLine("[ -f \"\$BASE/rights/theme/\$TH.mra\" ] || ok=0")
            appendLine("[ -f \"\$BASE/rights/theme/\$FH.mra\" ] || ok=0")
            appendLine("[ -s \"\$BASE/preview/theme/\$ID/preview_fonts_0.jpg\" ] || ok=0")
            appendLine("[ -s \"\$BASE/preview/theme/\$ID/preview_fonts_small_0.png\" ] || ok=0")
            appendLine("F=\"\$BASE/content/fonts/\$ID.mrc\"")
            appendLine("[ -f \"\$F\" ] || ok=0")
            appendLine("[ \"\$(wc -c < \"\$F\" 2>/dev/null | tr -d '[:space:]')\" = \"\$SIZE\" ] || ok=0")
            appendLine("grep -q \"\$TH\" \"\$BASE/meta/theme/\$ID.mrm\" 2>/dev/null || ok=0")
            appendLine("grep -q \"\$FH\" \"\$BASE/meta/fonts/\$ID.mrm\" 2>/dev/null || ok=0")
            appendLine("grep -q \"$META_VERSION\" \"\$BASE/meta/theme/\$ID.mrm\" 2>/dev/null || ok=0")
            appendLine("echo READY=\$ok")
        }
        val ready = try {
            val r = shell.exec(script, 20_000)
            r.stdout.lines().firstOrNull { it.trim().startsWith("READY=") }?.trim() == "READY=1"
        } catch (t: Throwable) {
            logs += "[MIUI] 主题资源检查异常：${t.message ?: t.javaClass.simpleName}"
            false
        }
        logs += "[$name/幂等] 目标主题资源已就绪=$ready（$localId）"
        return ready
    }

    override fun restore(shell: Shell, logs: MutableList<String>): ApplyResult {
        // 主题注入方式下，恢复默认只负责把「当前生效字体」切回系统默认。
        // .data 下的主题资源是用户在「主题 → 我的 → 字体」里能看到并再次选用的字体条目，
        // 属于已导入的资源而非生效状态，恢复时不删除，避免用户每次都要重新导入字体。
        logs += "[MIUI] 保留主题字体列表中的 OpenDIYFont 条目，仅切回系统默认字体"

        // 主题引擎偶发会因详情页尚在处理上一次操作 / 组件状态而吞掉这一次意图，
        // 因此未确认时短间隔补发一次；每次补发后仍按真实状态复核，绝不假成功。
        // 使用系统默认字体资源的真实 localId（见 DEFAULT_FONT_LOCAL_ID 注释），
        // 避免 ThemeDetailActivity 因找不到 "default" 伪 ID 弹出「加载资源失败」。
        var verified = false
        var attempts = 0
        while (!verified && attempts < 2) {
            attempts++
            launchApply(DEFAULT_FONT_LOCAL_ID)
            logs += "[MIUI] 已发送恢复默认字体意图（第 $attempts 次，系统默认字体资源 ID=$DEFAULT_FONT_LOCAL_ID）"
            verified = waitUntilDefault(shell, logs, timeoutMs = 3_500)
            if (!verified && attempts < 2) {
                logs += "[MIUI] 主题引擎暂未确认，稍后补发一次恢复意图"
                Thread.sleep(700)
            }
        }

        return if (verified) {
            ApplyResult(
                success = true,
                channel = shell.channel,
                title = "已恢复系统默认字体",
                detail = "已确认系统字体切回默认，无需重启。\n" +
                    "主题字体列表中的 OpenDIYFont 字体已保留，可随时在「主题 → 我的 → 字体」中重新选用。\n" +
                    "若系统主题应用曾短暂提示「加载资源失败」，那是其详情页的在线资源提示，不影响字体已切回默认。",
                rebootSuggested = false,
                logs = logs
            )
        } else {
            ApplyResult(
                success = false,
                channel = shell.channel,
                title = "未能确认恢复默认字体",
                detail = "已向主题引擎发送恢复指令，但系统字体仍显示自定义字体。" +
                    "通常是主题应用正忙或刚处理完上一次操作。请稍等几秒后再次点击「一键恢复」，" +
                    "或到系统「主题 → 我的 → 字体」手动点选「默认」。\n" +
                    "若系统主题应用提示「加载资源失败」，那是其详情页的在线资源提示，可忽略后重试。\n" +
                    "主题字体列表中的字体已保留，不会丢失，恢复按钮也会保留以便重试。",
                rebootSuggested = false,
                logs = logs
            )
        }
    }

    /**
     * 复用调用方已建立的 shell 连接，快速确认主题引擎是否真的切回默认字体。
     *
     * 判据：生效字体文件 /data/system/theme/fonts/Roboto-Regular.ttf 是否仍存在
     *（主判据；ThemeManager 应用/恢复默认都会增删该目录，真机验证可靠），另在
     * runtime 可读且明确指向自定义 localId 时否决。runtime_data.properties 属
     * theme:theme，未 root 的 Shizuku(uid=2000) 读不到、grep 返回空，此时
     * case '' -> ok=1 只是把「默认态」置位，最后仍由字体文件判据否决，因此
     * 不会因读不到 runtime 而假成功。
     *
     * 成功立即返回（正常约 1 秒），只有确实没生效才等满短超时并如实报失败。
     * 不新建连接、不加进度框；v1.4.10 起配合 restore() 的补发重试，单次超时
     * 由 6s 降到 3.5s，避免最坏情况等待过久。
     */
    private fun waitUntilDefault(shell: Shell, logs: MutableList<String>, timeoutMs: Long = 3_500): Boolean {
        val script = buildString {
            appendLine("R=/data/system/theme/.runtime/runtime_data.properties")
            appendLine("F=/data/system/theme/fonts/Roboto-Regular.ttf")
            appendLine("ok=0")
            appendLine("id=\$(grep 'theme_runtime_local_id-fonts=' \"\$R\" 2>/dev/null | head -1 | cut -d= -f2)")
            appendLine("case \"\$id\" in ''|0|default) ok=1 ;; esac")
            appendLine("[ -f \"\$F\" ] && ok=0")
            appendLine("echo OK=\$ok")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            try {
                val r = shell.exec(script, 8_000)
                last = r.stdout.lines().firstOrNull { it.trim().startsWith("OK=") }?.trim() ?: ""
                if (last == "OK=1") {
                    logs += "[MIUI] 已确认系统字体切回默认（生效字体文件已移除，runtime 无自定义字体记录或不可读）"
                    return true
                }
            } catch (t: Throwable) {
                logs += "[MIUI] 状态复核异常：${t.message ?: t.javaClass.simpleName}"
            }
            Thread.sleep(200)
        }
        logs += "[MIUI] $timeoutMs ms 内未确认切回默认（$last）"
        return false
    }

    private fun fail(shell: Shell, logs: MutableList<String>, title: String, t: Throwable?): ApplyResult {
        t?.let { logs += "异常：${it.message ?: it.javaClass.simpleName}" }
        return ApplyResult(
            success = false,
            channel = shell.channel,
            title = title,
            detail = "请确认已授予 Root 或 Shizuku 权限，且系统主题应用未被禁用。",
            rebootSuggested = false,
            logs = logs
        )
    }

    private fun launchApply(localId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ViewLocalResource://view.local.resource#$localId"))
            .setPackage("com.android.thememanager")
            .putExtra("REQUEST_RESOURCE_CODE", "fonts")
            .putExtra("REQUEST_APPLY_EVENT", true)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun localized(value: String): JSONObject = JSONObject()
        .put("fallback", value)
        .put("en_US", value)
        .put("zh_CN", value)

    private fun fontMetaJson(localId: String, name: String): String {
        val title = name.substringBeforeLast('.')
        return JSONObject().apply {
            put("localId", localId)
            put("onlineId", JSONObject.NULL)
            put("assemblyId", JSONObject.NULL)
            put("productId", JSONObject.NULL)
            put("hash", LEGAL_FONT_HASH)
            put("platform", 8)
            put("size", LEGAL_FONT_SIZE)
            put("updatedTime", 0)
            put("version", LEGAL_META_VERSION)
            put("authors", localized("来自用户"))
            put("designers", localized("来自用户"))
            put("titles", localized(title))
            put("descriptions", localized("Imported by OpenDIYFont $META_VERSION"))
            put("builtInThumbnails", JSONObject().apply {
                put("fallback", JSONArray().put("preview_fonts_small_0.png"))
                put("en_US", JSONArray().put("preview_fonts_small_0.png"))
                put("zh_CN", JSONArray().put("preview_fonts_small_0.png"))
            })
            put("builtInPreviews", JSONObject().apply {
                put("fallback", JSONArray().put("preview_fonts_0.jpg"))
                put("en_US", JSONArray().put("preview_fonts_0.jpg"))
                put("zh_CN", JSONArray().put("preview_fonts_0.jpg"))
            })
            put("thumbnails", JSONArray())
            put("previews", JSONArray())
            put("parentResources", JSONArray().put(JSONObject().apply {
                put("localId", localId)
                put("resourceCode", "theme")
                put("extraMeta", JSONObject())
                put("metaPath", JSONObject.NULL)
                put("contentPath", JSONObject.NULL)
            }))
            put("subResources", JSONArray())
            put("extraMeta", JSONObject())
            put("metaPath", JSONObject.NULL)
            put("contentPath", JSONObject.NULL)
            put("rightsPath", JSONObject.NULL)
            put("screenRatio", JSONObject.NULL)
            put("supportHomeSearchBar", false)
            put("packageVersion", JSONObject.NULL)
            put("packageName", JSONObject.NULL)
            put("officialIcons", false)
            put("iconsCount", JSONObject.NULL)
            put("fontWeight", JSONObject.NULL)
            put("price", -1)
            put("isBackupVersion", false)
            put("themeType", 0)
            put("miuiAdapterVersion", JSONObject.NULL)
        }.toString(2)
    }

    private fun themeMetaJson(localId: String, name: String): String {
        val title = name.substringBeforeLast('.')
        val previews = JSONObject().apply {
            put("fallback", JSONArray().put("preview_fonts_0.jpg").put("preview_fonts_small_0.png"))
            put("en_US", JSONArray().put("preview_fonts_0.jpg").put("preview_fonts_small_0.png"))
            put("zh_CN", JSONArray().put("preview_fonts_0.jpg").put("preview_fonts_small_0.png"))
        }
        return JSONObject().apply {
            put("localId", localId)
            put("onlineId", JSONObject.NULL)
            put("assemblyId", JSONObject.NULL)
            put("productId", JSONObject.NULL)
            put("hash", LEGAL_THEME_HASH)
            put("platform", 8)
            put("size", LEGAL_THEME_SIZE)
            put("updatedTime", 0)
            put("version", LEGAL_META_VERSION)
            put("authors", localized("来自用户"))
            put("designers", localized("来自用户"))
            put("titles", localized(title))
            put("descriptions", localized("Imported by OpenDIYFont $META_VERSION"))
            put("builtInThumbnails", previews)
            put("builtInPreviews", previews)
            put("thumbnails", JSONArray())
            put("previews", JSONArray())
            put("parentResources", JSONArray())
            put("subResources", JSONArray().put(JSONObject().apply {
                put("localId", localId)
                put("resourceCode", "fonts")
                put("extraMeta", JSONObject())
                put("metaPath", JSONObject.NULL)
                put("contentPath", JSONObject.NULL)
            }))
            put("extraMeta", JSONObject())
            put("metaPath", JSONObject.NULL)
            put("contentPath", "$LEGAL_CONTENT_PATH_PREFIX$localId.mrc")
            put("rightsPath", JSONObject.NULL)
            put("screenRatio", JSONObject.NULL)
            put("supportHomeSearchBar", false)
            put("packageVersion", JSONObject.NULL)
            put("packageName", JSONObject.NULL)
            put("officialIcons", false)
            put("iconsCount", JSONObject.NULL)
            put("fontWeight", JSONObject.NULL)
            put("price", -1)
            put("isBackupVersion", false)
            put("themeType", 0)
            put("miuiAdapterVersion", JSONObject.NULL)
        }.toString(2)
    }

    private fun writePreviews(font: File, big: File, small: File, name: String) {
        val typeface = try {
            Typeface.createFromFile(font)
        } catch (_: Throwable) {
            Typeface.DEFAULT
        }
        val label = name.substringBeforeLast('.').ifBlank { "OpenDIYFont" }
        val createdBy = context.getString(R.string.preview_created_by)

        val sourceIcon = BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_mark)
        val iconMax = 320
        val icon = if (sourceIcon == null) {
            null
        } else {
            val scale = minOf(iconMax.toFloat() / sourceIcon.width, iconMax.toFloat() / sourceIcon.height)
            val width = maxOf(1, (sourceIcon.width * scale).toInt())
            val height = maxOf(1, (sourceIcon.height * scale).toInt())
            if (width == sourceIcon.width && height == sourceIcon.height) {
                sourceIcon
            } else {
                Bitmap.createScaledBitmap(sourceIcon, width, height, true)
            }
        }
        if (icon !== sourceIcon) sourceIcon?.recycle()

        val bigBitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        Canvas(bigBitmap).apply {
            drawColor(Color.WHITE)

            if (icon != null) {
                drawBitmap(icon, (width - icon.width) / 2f, 300f, null)
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(24, 24, 24)
                textAlign = Paint.Align.CENTER
                this.typeface = typeface
            }
            drawFittedText(this, textPaint, label, width / 2f, 760f, width - 80f, 88f, 32f)

            textPaint.color = Color.rgb(110, 110, 110)
            drawFittedText(this, textPaint, createdBy, width / 2f, 880f, width - 80f, 46f, 24f)
        }
        big.outputStream().use { bigBitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bigBitmap.recycle()
        icon?.recycle()

        val smallBitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
        Canvas(smallBitmap).apply {
            drawColor(Color.WHITE)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(24, 24, 24)
                textAlign = Paint.Align.CENTER
                this.typeface = typeface
            }
            drawFittedTextCentered(
                this,
                textPaint,
                label,
                width / 2f,
                height / 2f,
                width - 64f,
                140f,
                48f
            )
        }
        small.outputStream().use { smallBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        smallBitmap.recycle()
    }

    private fun drawFittedText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        centerX: Float,
        baselineY: Float,
        maxWidth: Float,
        maxSize: Float,
        minSize: Float
    ) {
        var size = maxSize
        paint.textSize = size
        while (paint.measureText(text) > maxWidth && size > minSize) {
            size -= 2f
            paint.textSize = size
        }
        canvas.drawText(text, centerX, baselineY, paint)
    }

    private fun drawFittedTextCentered(
        canvas: Canvas,
        paint: Paint,
        text: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        maxSize: Float,
        minSize: Float
    ) {
        var size = maxSize
        paint.textSize = size
        while (paint.measureText(text) > maxWidth && size > minSize) {
            size -= 2f
            paint.textSize = size
        }
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX, baseline, paint)
    }

    private fun sha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
