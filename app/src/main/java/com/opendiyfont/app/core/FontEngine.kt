package com.opendiyfont.app.core

import android.content.Context
import com.opendiyfont.app.core.device.DeviceProfile
import com.opendiyfont.app.core.device.Vendor
import com.opendiyfont.app.core.shell.RootShell
import com.opendiyfont.app.core.shell.ShizukuShell
import com.opendiyfont.app.core.shell.Shell
import com.opendiyfont.app.core.strategy.FontStrategy
import com.opendiyfont.app.core.strategy.MiuiThemeStrategy
import com.opendiyfont.app.core.strategy.RootDirectStrategy
import com.opendiyfont.app.core.strategy.RootModuleStrategy

/** 组合「能力检测 -> 通道选择 -> 策略执行」，UI 只与它对话。 */
class FontEngine(private val context: Context) {

    val repository = FontRepository(context)

    private val prefs = context.getSharedPreferences("opendiyfont", Context.MODE_PRIVATE)

    var strategyMode: ApplyStrategyMode
        get() = ApplyStrategyMode.fromKey(prefs.getString("font_strategy_mode", null))
        set(value) {
            prefs.edit().putString("font_strategy_mode", value.key).apply()
        }

    /** 本应用是否应用过字体；恢复默认后清除。 */
    var fontApplied: Boolean
        get() = prefs.getBoolean("font_applied", false)
        set(value) {
            prefs.edit().putBoolean("font_applied", value).apply()
        }

    /**
     * 判断「当前生效字体」是否仍由本应用提供。
     *
     * 注意：不能依据主题字体列表里是否存在 OpenDIYFont 条目来判断——恢复默认后
     * 该条目会被保留（方便用户再次选用），它只是已导入的资源，不代表正在生效。
     * 因此改为读取真正生效的状态：主题 runtime 记录、生效字体文件，以及
     * systemless 模块 / Root 直写备份。
     * 没有可用执行通道时退回本地标记。
     */
    fun detectActiveFont(): Boolean {
        val caps = capabilities()
        val channel = caps.pick(ApplyMode.AUTO)
        if (channel == Channel.NONE) return fontApplied
        val shell: Shell = if (channel == Channel.ROOT) RootShell(context) else ShizukuShell(context)
        return try {
            val script = buildString {
                appendLine("R=/data/system/theme/.runtime/runtime_data.properties")
                appendLine("F=/data/system/theme/fonts/Roboto-Regular.ttf")
                appendLine("active=0")
                appendLine("if [ -f \"\$R\" ]; then")
                appendLine("  id=\$(grep 'theme_runtime_local_id-fonts=' \"\$R\" 2>/dev/null | head -1 | cut -d= -f2)")
                appendLine("  case \"\$id\" in ''|0|default) ;; *) active=1 ;; esac")
                appendLine("fi")
                appendLine("[ -f \"\$F\" ] && active=1")
                appendLine("[ -d /data/adb/modules/opendiyfont ] && active=1")
                appendLine("[ -d /data/local/tmp/opendiyfont-backup ] && active=1")
                appendLine("echo ACTIVE=\$active")
            }
            val result = shell.exec(script, 20_000)
            val active = Regex("ACTIVE=(\\d+)")
                .find(result.stdout)
                ?.groupValues?.get(1)?.toIntOrNull() == 1
            fontApplied = active
            active
        } catch (_: Throwable) {
            fontApplied
        } finally {
            closeShell(shell)
        }
    }

    fun capabilities(): Capabilities {
        val (rootOk, rootDetail) = RootShell.detect()
        val (shizukuOk, shizukuDetail, uid) = ShizukuShell.detect()
        return Capabilities(rootOk, rootDetail, shizukuOk, shizukuDetail, uid)
    }

    fun apply(request: ApplyRequest): ApplyResult {
        val logs = mutableListOf<String>()
        val caps = capabilities()
        val channel = caps.pick(request.mode)
        val vendor = DeviceProfile.detect().vendor
        val effective = effectiveStrategy(request.strategy, vendor)
        logs += "设备：${DeviceProfile.detect().displayName}"
        logs += "执行通道：${request.mode.label()}；实际通道：${channel.label}"
        logs += "应用方式：${request.strategy.label}；实际方式：${effective.label}"
        logs += "检测：${caps.rootDetail} / ${caps.shizukuDetail}"

        if (channel == Channel.NONE) {
            val hint = when (request.mode) {
                ApplyMode.ROOT -> "你选择了 Root 模式，但未获得 Root 授权。请授予 su 权限或切换为自动检测。"
                ApplyMode.SHIZUKU -> "你选择了 Shizuku 模式，但服务未运行/未授权。请先在 Shizuku 应用中启动服务并授权本应用。"
                ApplyMode.AUTO -> "未检测到 Root 或 Shizuku。请安装并启动 Shizuku，或为设备获取 Root。"
            }
            return ApplyResult(false, channel, "没有可用的执行通道", hint, false, logs)
        }

        val shell: Shell = when (channel) {
            Channel.ROOT -> RootShell(context)
            Channel.SHIZUKU -> ShizukuShell(context)
            else -> throw IllegalStateException("unreachable")
        }

        logs += "字体：${request.font.name}（${request.font.format}，${request.font.size / 1024} KB）"
        logs += "替换范围：${request.scope.label}"
        val staged = try {
            repository.stageForShell(request.font)
        } catch (t: Throwable) {
            closeShell(shell)
            return ApplyResult(false, channel, "字体暂存失败", t.message ?: "无法复制字体", false, logs)
        }

        return try {
            if (effective == ApplyStrategyMode.SYSTEMLESS && vendor == Vendor.XIAOMI) {
                logs += "[策略] 先清除 MIUI 主题注入，避免覆盖 systemless 模块"
                MiuiThemeStrategy(context, channel).clearInjection(shell, logs)
            }
            val strategy = chooseStrategy(channel, shell, logs, effective, caps.shizukuUid)
            logs += "策略执行：${strategy.name}"
            val result = strategy.apply(request, shell, staged, logs)
            if (channel == Channel.SHIZUKU && effective == ApplyStrategyMode.SYSTEMLESS) {
                result.copy(channel = Channel.SHIZUKU)
            } else {
                result
            }
        } catch (t: Throwable) {
            logs += "异常：${t.message ?: t.javaClass.simpleName}"
            ApplyResult(false, channel, "应用过程发生异常", t.message ?: t.javaClass.simpleName, false, logs)
        } finally {
            closeShell(shell)
        }
    }

    fun restore(mode: ApplyMode): ApplyResult {
        val logs = mutableListOf<String>()
        val caps = capabilities()
        val channel = caps.pick(mode)
        logs += "恢复模式：${mode.label()}；实际通道：${channel.label}"
        logs += "[恢复] 清理 systemless 模块与直写备份；主题注入方式仅切回默认字体，保留主题字体列表条目"
        if (channel == Channel.NONE) {
            return ApplyResult(
                false, channel, "没有可用的执行通道",
                "恢复系统字体同样需要 Root 或 Shizuku 授权。", false, logs
            )
        }
        val shell: Shell = if (channel == Channel.ROOT) RootShell(context) else ShizukuShell(context)
        return try {
            val moduleResult = RootModuleStrategy().restore(shell, logs).copy(channel = channel)
            var themeOk = true
            var themeTitle = "恢复未完成"
            var themeDetail = "请检查 Root/Shizuku 授权后重试，或查看日志了解具体失败项。"
            if (DeviceProfile.detect().vendor == Vendor.XIAOMI) {
                logs += "[恢复] MIUI 主题注入：切回默认字体，保留主题字体列表条目"
                val themeResult = MiuiThemeStrategy(context, channel).restore(shell, logs)
                themeOk = themeResult.success
                // 主题引擎未确认切回默认时，透传「未能确认恢复默认字体」的如实提示，
                // 不能用通用的授权失败文案盖掉，更不能假装成功。
                themeTitle = themeResult.title
                themeDetail = themeResult.detail
            }
            val ok = moduleResult.success && themeOk
            ApplyResult(
                success = ok,
                channel = channel,
                title = if (ok) "已恢复系统默认字体" else if (moduleResult.success) themeTitle else "恢复未完成",
                detail = if (ok) {
                    buildString {
                        append("已清理 OpenDIYFont 写入的模块并还原系统默认字体。")
                        if (DeviceProfile.detect().vendor == Vendor.XIAOMI) {
                            append("主题字体列表中的字体条目已保留，可随时再次选用，无需重新导入。")
                            append("若系统主题应用曾短暂提示「加载资源失败」，那是其详情页的在线资源提示，字体已切回默认，可忽略。")
                        }
                        append("模块方式建议重启后查看最终效果。")
                    }
                } else if (moduleResult.success) {
                    themeDetail
                } else {
                    "请检查 Root/Shizuku 授权后重试，或查看日志了解具体失败项。"
                },
                rebootSuggested = moduleResult.success,
                logs = logs
            )
        } catch (t: Throwable) {
            logs += "异常：${t.message ?: t.javaClass.simpleName}"
            ApplyResult(false, channel, "恢复过程发生异常", t.message ?: t.javaClass.simpleName, false, logs)
        } finally {
            closeShell(shell)
        }
    }

    /** 用户勾选「立即重启」时调用；同样需要特权 shell。 */
    fun reboot(mode: ApplyMode): ApplyResult {
        val logs = mutableListOf<String>()
        val channel = capabilities().pick(mode)
        if (channel == Channel.NONE) {
            return ApplyResult(false, channel, "无法重启", "没有可用的特权通道。请手动重启设备。", false, logs)
        }
        val shell: Shell = if (channel == Channel.ROOT) RootShell(context) else ShizukuShell(context)
        val result = shell.exec("sync; reboot", 15_000)
        logs += "[reboot] exit=${result.exitCode} ${result.combined}"
        closeShell(shell)
        return ApplyResult(
            success = true,
            channel = channel,
            title = "已发送重启指令",
            detail = "设备正在重启。若重启后字体未生效，请检查日志与字体文件兼容性。",
            rebootSuggested = false,
            logs = logs
        )
    }

    private fun closeShell(shell: Shell) {
        (shell as? ShizukuShell)?.close()
    }

    private fun effectiveStrategy(requested: ApplyStrategyMode, vendor: Vendor): ApplyStrategyMode =
        if (requested == ApplyStrategyMode.AUTO) {
            if (vendor == Vendor.XIAOMI) ApplyStrategyMode.THEME else ApplyStrategyMode.SYSTEMLESS
        } else {
            requested
        }

    /** 根据用户选择的应用方式挑选策略。 */
    private fun chooseStrategy(
        channel: Channel,
        shell: Shell,
        logs: MutableList<String>,
        effective: ApplyStrategyMode,
        shizukuUid: Int
    ): FontStrategy {
        val vendor = DeviceProfile.detect().vendor
        return when (effective) {
            ApplyStrategyMode.THEME -> {
                if (vendor != Vendor.XIAOMI) {
                    throw IllegalStateException("厂商主题注入仅支持小米 / Redmi / POCO（MIUI / HyperOS）")
                }
                MiuiThemeStrategy(context, channel)
            }
            ApplyStrategyMode.SYSTEMLESS -> {
                if (channel == Channel.SHIZUKU && shizukuUid != 0) {
                    throw IllegalStateException(
                        "Systemless 模块需要 Root 或 Root 启动的 Shizuku（uid=0）；当前 Shizuku uid=$shizukuUid"
                    )
                }
                when (channel) {
                    Channel.ROOT, Channel.SHIZUKU -> chooseRootStrategy(shell, logs, forceModule = true)
                    else -> throw IllegalStateException("unreachable")
                }
            }
            ApplyStrategyMode.AUTO -> throw IllegalStateException("unreachable")
        }
    }

    /**
     * 自动方式下：有 /data/adb/modules 就用 systemless 模块，否则退回直写。
     * 用户显式选择 systemless 时不做直写回退，而是给出明确诊断。
     */
    private fun chooseRootStrategy(
        shell: Shell,
        logs: MutableList<String>,
        forceModule: Boolean = false
    ): FontStrategy {
        val probe = shell.execArgs(arrayOf("test", "-d", "/data/adb/modules"), 10_000)
        return if (probe.ok) {
            RootModuleStrategy()
        } else {
            if (forceModule) {
                throw IllegalStateException(
                    "未检测到 /data/adb/modules，无法安装 systemless 模块。请确认已安装 Magisk / KernelSU / APatch。"
                )
            }
            logs += "未发现 /data/adb/modules，回退到 Root 直写方案"
            RootDirectStrategy()
        }
    }
}

private fun ApplyMode.label(): String = when (this) {
    ApplyMode.AUTO -> "自动检测"
    ApplyMode.ROOT -> "Root"
    ApplyMode.SHIZUKU -> "Shizuku"
}
