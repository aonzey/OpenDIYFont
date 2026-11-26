package com.opendiyfont.app.core.module

import com.opendiyfont.app.core.device.FontTargets

/** 生成 systemless 模块脚本。只操作本应用自己的模块目录，不碰其他模块。 */
object ModuleScriptBuilder {

    const val MODULE_ID = "opendiyfont"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    /**
     * 注意：这里刻意用 buildString + appendLine 生成脚本，而不是三层引号 + trimIndent。
     * trimIndent 会受插值进来的多行文本影响，曾导致 here-doc 结束符 ODFEOF 带缩进，
     * 在真机上报 `here document 'ODFEOF' unclosed`。module.prop 改用逐行 echo，彻底避开 here-doc。
     */
    fun installScript(fontPath: String, scopeKey: String): String {
        val targets = FontTargets.forScope(scopeKey)
        val safeFontPath = fontPath.replace("'", "'\\''")
        return buildString {
            appendLine("MOD=$MODULE_DIR")
            appendLine("SRC='$safeFontPath'")
            appendLine("if [ ! -d /data/adb/modules ]; then")
            appendLine("  echo NO_MODULE_ROOT")
            appendLine("  exit 2")
            appendLine("fi")
            appendLine("rm -rf \"\$MOD\"")
            appendLine("mkdir -p \"\$MOD/system/fonts\"")

            appendLine("echo 'id=$MODULE_ID' > \"\$MOD/module.prop\"")
            appendLine("echo 'name=OpenDIYFont' >> \"\$MOD/module.prop\"")
            appendLine("echo 'version=1.4.10' >> \"\$MOD/module.prop\"")
            appendLine("echo 'versionCode=17' >> \"\$MOD/module.prop\"")
            appendLine("echo 'author=Aonzey' >> \"\$MOD/module.prop\"")
            appendLine("echo 'description=Systemless font overlay created by OpenDIYFont' >> \"\$MOD/module.prop\"")

            appendLine(FontTargets.scriptForTargets(targets).trim())

            appendLine("found=\$(find \"\$MOD\" -type f ! -name module.prop 2>/dev/null | wc -l)")
            appendLine("if [ \"\$found\" -eq 0 ]; then")
            appendLine("  mkdir -p \"\$MOD/system/fonts\"")
            appendLine("  for n in ${targets.joinToString(" ")}; do")
            appendLine("    cp -f \"\$SRC\" \"\$MOD/system/fonts/\$n\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("  echo WARN:no_existing_target_matched,wrote_full_target_set")
            appendLine("fi")
            appendLine("chmod 0644 \"\$MOD/module.prop\" 2>/dev/null || true")
            appendLine("find \"\$MOD\" -type f ! -name module.prop -exec chmod 0644 {} \\; 2>/dev/null || true")
            appendLine("find \"\$MOD\" -type d -exec chmod 0755 {} \\; 2>/dev/null || true")
            appendLine("rm -f \"\$MOD/disable\" \"\$MOD/remove\" \"\$MOD/skip_mount\"")
            appendLine("echo \"INSTALLED_FILES=\$(find \"\$MOD\" -type f ! -name module.prop 2>/dev/null | wc -l)\"")
            appendLine("echo APPLY_OK")
        }
    }

    fun removeScript(): String = buildString {
        appendLine("MOD=$MODULE_DIR")
        appendLine("if [ -d \"\$MOD\" ]; then")
        appendLine("  rm -rf \"\$MOD\"")
        appendLine("  echo MODULE_REMOVED")
        appendLine("else")
        appendLine("  echo MODULE_ABSENT")
        appendLine("fi")
        appendLine("echo RESTORE_OK")
    }
}
