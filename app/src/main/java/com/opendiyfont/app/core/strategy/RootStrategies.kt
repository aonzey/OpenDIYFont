package com.opendiyfont.app.core.strategy

import com.opendiyfont.app.core.ApplyRequest
import com.opendiyfont.app.core.ApplyResult
import com.opendiyfont.app.core.Channel
import com.opendiyfont.app.core.device.FontTargets
import com.opendiyfont.app.core.module.ModuleScriptBuilder
import com.opendiyfont.app.core.shell.Shell
import java.io.File

/** 首选 Root 方案：写入 Magisk / KernelSU / APatch 通用的 systemless 模块目录。 */
class RootModuleStrategy : FontStrategy {
    override val name = "Systemless 模块"
    override val channel = Channel.ROOT

    override fun apply(
        request: ApplyRequest,
        shell: Shell,
        stagedFont: File,
        logs: MutableList<String>
    ): ApplyResult {
        val script = ModuleScriptBuilder.installScript(stagedFont.absolutePath, request.scope.key)
        val result = shell.exec(script, 90_000)
        logs += "[$name] exit=${result.exitCode}"
        result.combined.lines().filter { it.isNotBlank() }.forEach { logs += "  $it" }

        val ok = result.ok && result.stdout.contains("APPLY_OK")
        return ApplyResult(
            success = ok,
            channel = channel,
            title = if (ok) "Root 模块已写入" else "Root 模块写入失败",
            detail = if (ok) {
                "已创建 ${ModuleScriptBuilder.MODULE_DIR}，重启后生效。\n" +
                    "可在 Root 管理器「模块」中看到 OpenDIYFont，禁用或删除即可恢复。"
            } else {
                "特权 shell 未能创建模块目录。请确认已授予 Root 权限，或改用 Root 直写方案。"
            },
            rebootSuggested = ok,
            logs = logs
        )
    }

    override fun restore(shell: Shell, logs: MutableList<String>): ApplyResult {
        val result = shell.exec(ModuleScriptBuilder.removeScript(), 30_000)
        logs += "[$name/恢复] exit=${result.exitCode} ${result.combined}"
        // 兼容历史上使用过直写方案的设备：一并还原备份，避免遗漏。
        val direct = RootDirectStrategy().restore(shell, logs)
        val ok = result.ok && result.stdout.contains("RESTORE_OK") && direct.success
        return ApplyResult(
            success = ok,
            channel = channel,
            title = if (ok) "已移除 OpenDIYFont 模块并还原备份" else "恢复未完成",
            detail = if (ok) "重启后系统将恢复默认字体。" else "请检查 Root 授权、模块目录及备份目录后重试。",
            rebootSuggested = ok,
            logs = logs
        )
    }
}

/** 兜底 Root 方案：经典 su 环境下直接覆盖字体文件，并保存原始文件用于恢复。 */
class RootDirectStrategy : FontStrategy {
    override val name = "Root 直写（带备份）"
    override val channel = Channel.ROOT

    companion object {
        const val BACKUP_DIR = "/data/local/tmp/opendiyfont-backup"
    }

    override fun apply(
        request: ApplyRequest,
        shell: Shell,
        stagedFont: File,
        logs: MutableList<String>
    ): ApplyResult {
        val targets = FontTargets.forScope(request.scope.key).joinToString(" ")
        val dirs = "/system/fonts /product/fonts /vendor/fonts /system/product/fonts"
        val script = """
            SRC='${stagedFont.absolutePath}'
            BK=$BACKUP_DIR
            mkdir -p "${'$'}BK" 2>/dev/null
            : > "${'$'}BK/paths.txt"
            echo '${request.scope.key}' > "${'$'}BK/scope.txt"
            mount -o remount,rw /system 2>/dev/null || true
            mount -o remount,rw / 2>/dev/null || true
            for n in $targets; do
              for d in $dirs; do
                p="${'$'}d/${'$'}n"
                [ -e "${'$'}p" ] || continue
                [ -f "${'$'}BK/${'$'}n" ] || cp -p "${'$'}p" "${'$'}BK/${'$'}n" 2>/dev/null || true
                echo "${'$'}p" >> "${'$'}BK/paths.txt"
                cp -f "${'$'}SRC" "${'$'}p" 2>/dev/null && chmod 0644 "${'$'}p" && echo "patched ${'$'}p"
              done
            done
            patched=${'$'}(grep -c '^/' "${'$'}BK/paths.txt" 2>/dev/null || echo 0)
            echo "PATCHED=${'$'}patched"
            [ "${'$'}patched" -gt 0 ] && echo APPLY_OK
        """.trimIndent()

        val result = shell.exec(script, 90_000)
        logs += "[$name] exit=${result.exitCode}"
        result.combined.lines().filter { it.isNotBlank() }.forEach { logs += "  $it" }
        val ok = result.ok && result.stdout.contains("APPLY_OK")
        return ApplyResult(
            success = ok,
            channel = channel,
            title = if (ok) "已直写系统字体" else "直写系统字体失败",
            detail = if (ok) {
                "原始字体已备份到 $BACKUP_DIR，可一键恢复；建议重启以刷新字体缓存。"
            } else {
                "系统分区不可写。现代 Android 通常禁止直写，请在 Root 管理器中确认模块方案。"
            },
            rebootSuggested = ok,
            logs = logs
        )
    }

    override fun restore(shell: Shell, logs: MutableList<String>): ApplyResult {
        val script = """
            BK=$BACKUP_DIR
            if [ -f "${'$'}BK/paths.txt" ]; then
              while IFS= read -r p; do
                [ -z "${'$'}p" ] && continue
                n=${'$'}(basename "${'$'}p")
                mount -o remount,rw "${'$'}(dirname "${'$'}p")" 2>/dev/null || true
                if [ -f "${'$'}BK/${'$'}n" ]; then
                  cp -f "${'$'}BK/${'$'}n" "${'$'}p" 2>/dev/null && echo "restored ${'$'}p"
                fi
              done < "${'$'}BK/paths.txt"
            fi
            rm -rf "${'$'}BK"
            echo RESTORE_OK
        """.trimIndent()
        val result = shell.exec(script, 60_000)
        logs += "[$name/恢复] exit=${result.exitCode} ${result.combined}"
        val ok = result.ok && result.stdout.contains("RESTORE_OK")
        return ApplyResult(
            success = ok,
            channel = channel,
            title = if (ok) "已恢复直写前的字体" else "恢复失败",
            detail = if (ok) "原始字体文件已写回，建议重启。" else "请检查 Root 授权及备份目录是否存在。",
            rebootSuggested = ok,
            logs = logs
        )
    }
}
