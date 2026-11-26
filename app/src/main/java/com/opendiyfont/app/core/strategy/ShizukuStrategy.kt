package com.opendiyfont.app.core.strategy

import android.content.pm.PackageManager
import com.opendiyfont.app.core.ApplyRequest
import com.opendiyfont.app.core.ApplyResult
import com.opendiyfont.app.core.Channel
import com.opendiyfont.app.core.shell.Shell
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 通道：
 * - 若 Shizuku 由 Root 启动（uid=0），直接复用 Root 模块方案；
 * - 若由 ADB Shell 启动（uid=2000），尝试可写分区/remount 后直写并备份；
 * - 均不可行时返回明确诊断，而不是假装成功。
 */
class ShizukuStrategy : FontStrategy {
    override val name = "Shizuku"
    override val channel = Channel.SHIZUKU

    private val uid: Int
        get() = try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Shizuku.getUid() else -1
        } catch (_: Throwable) {
            -1
        }

    override fun apply(
        request: ApplyRequest,
        shell: Shell,
        stagedFont: File,
        logs: MutableList<String>
    ): ApplyResult {
        if (uid == 0) {
            logs += "[Shizuku] 以 Root 身份运行（uid=0），使用 systemless 模块方案"
            return RootModuleStrategy().apply(request, shell, stagedFont, logs).copy(channel = Channel.SHIZUKU)
        }

        logs += "[Shizuku] uid=$uid（ADB Shell 或未知），探测可写能力"
        val probe = shell.exec(
            """
            id 2>/dev/null
            d=/system/fonts
            touch "${'$'}d/.odf_probe" 2>/dev/null && echo SYSTEM_WRITABLE && rm -f "${'$'}d/.odf_probe" || echo SYSTEM_READONLY
            command -v remount 2>/dev/null || echo NO_REMOUNT_BINARY
            """.trimIndent(),
            20_000
        )
        logs += "[探测] exit=${probe.exitCode}"
        probe.combined.lines().filter { it.isNotBlank() }.forEach { logs += "  $it" }

        var writable = probe.stdout.contains("SYSTEM_WRITABLE")
        if (!writable && probe.stdout.contains("NO_REMOUNT_BINARY").not()) {
            logs += "[Shizuku] 尝试 remount 后再次探测"
            val remounted = shell.exec(
                """
                remount 2>/dev/null || mount -o remount,rw /system 2>/dev/null || true
                touch /system/fonts/.odf_probe 2>/dev/null && echo SYSTEM_WRITABLE && rm -f /system/fonts/.odf_probe || echo SYSTEM_READONLY
                """.trimIndent(),
                30_000
            )
            logs += "[remount] exit=${remounted.exitCode} ${remounted.combined}"
            writable = remounted.stdout.contains("SYSTEM_WRITABLE")
        }

        if (writable) {
            logs += "[Shizuku] 分区可写，使用带备份的直写方案"
            return RootDirectStrategy().apply(request, shell, stagedFont, logs).copy(channel = Channel.SHIZUKU)
        }

        // 最后尝试厂商提供的 shell 命令接口（少数 ROM 支持），并如实记录探测结果。
        val vendor = shell.exec(
            """
            for c in font theme fontmanager; do
              if command -v ${'$'}c >/dev/null 2>&1; then
                echo "FOUND_CMD=${'$'}c"
                ${'$'}c --help 2>&1 | head -5
              fi
            done
            """.trimIndent(),
            20_000
        )
        logs += "[厂商命令探测] ${vendor.combined.ifBlank { "无可用命令" }}"

        return ApplyResult(
            success = false,
            channel = channel,
            title = "Shizuku 无足够权限修改系统字体",
            detail = "Shizuku（ADB Shell）在绝大多数量产 ROM 上无法写入 /system/fonts。" +
                "可行做法：① 用 Root 启动 Shizuku（uid=0）后重试；② 改用 Root 模式；" +
                "③ 使用系统自带的字体/主题设置。详细探测见下方日志。",
            rebootSuggested = false,
            logs = logs
        )
    }

    override fun restore(shell: Shell, logs: MutableList<String>): ApplyResult {
        if (uid == 0) {
            logs += "[Shizuku] uid=0，移除 systemless 模块"
            return RootModuleStrategy().restore(shell, logs).copy(channel = Channel.SHIZUKU)
        }
        logs += "[Shizuku] 尝试恢复 ADB Shell 直写备份"
        return RootDirectStrategy().restore(shell, logs).copy(channel = Channel.SHIZUKU)
    }
}
