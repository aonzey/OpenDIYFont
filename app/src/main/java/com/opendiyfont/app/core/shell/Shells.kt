package com.opendiyfont.app.core.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.opendiyfont.app.core.Channel
import com.opendiyfont.app.core.CommandResult
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 统一的特权命令出口，Root 与 Shizuku 只对这里负责。 */
interface Shell {
    val channel: Channel
    fun exec(script: String, timeoutMs: Long = 30_000): CommandResult
    fun execArgs(args: Array<String>, timeoutMs: Long = 15_000): CommandResult
}

class RootShell(private val context: Context) : Shell {
    override val channel: Channel = Channel.ROOT

    override fun exec(script: String, timeoutMs: Long): CommandResult {
        val file = File(context.cacheDir, "odf_command.sh")
        file.writeText("#!/system/bin/sh\n$script\n")
        file.setReadable(true, false)
        val path = file.absolutePath
        return ShellExec.run(listOf("su", "-c", "sh $path"), timeoutMs)
    }

    override fun execArgs(args: Array<String>, timeoutMs: Long): CommandResult {
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return ShellExec.run(listOf("su", "-c", quoted), timeoutMs)
    }

    companion object {
        /** Root 检测不依赖 su 是否在 PATH 中：直接尝试常见位置。 */
        fun detect(): Pair<Boolean, String> {
            val hints = listOf(
                "/system/xbin/su", "/system/bin/su", "/sbin/su",
                "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap", "/data/adb/apd"
            ).filter { File(it).exists() }

            val result = ShellExec.run(listOf("su", "-c", "id -u"), 5_000)
            val ok = result.ok && result.stdout.trim() == "0"
            val detail = if (ok) {
                val managers = mutableListOf<String>()
                if (File("/data/adb/magisk").exists()) managers += "Magisk"
                if (File("/data/adb/ksu").exists()) managers += "KernelSU"
                if (File("/data/adb/ap").exists() || File("/data/adb/apd").exists()) managers += "APatch"
                val m = if (managers.isEmpty()) "su" else managers.joinToString("/")
                "已获取 Root（$m）" + if (hints.isEmpty()) "" else "，特征：${hints.take(3).joinToString(",")}"
            } else {
                "未获得 Root 授权" + if (hints.isEmpty()) "（未发现 su/管理器特征）" else "（发现特征但未授权）"
            }
            return ok to detail
        }
    }
}

/**
 * Shizuku 通道：v13 已移除公开的 newProcess，改用 UserService。
 * 用户服务以 Root（uid=0）或 ADB Shell（uid=2000）身份执行命令。
 */
class ShizukuShell(private val context: Context) : Shell {
    override val channel: Channel = Channel.SHIZUKU

    private var bindLatch = CountDownLatch(1)
    private var remote: IRemoteShellService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, RemoteShellService::class.java.name)
    ).daemon(false)
        .processNameSuffix("odfshell")
        .version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = if (binder != null && binder.pingBinder()) {
                IRemoteShellService.Stub.asInterface(binder)
            } else null
            bindLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    private fun ensureConnected(timeoutMs: Long): Boolean {
        val current = remote
        if (current != null) {
            try {
                if (current.asBinder().isBinderAlive) return true
            } catch (_: Throwable) {
            }
        }
        return try {
            bindLatch = CountDownLatch(1)
            Shizuku.bindUserService(userServiceArgs, connection)
            bindLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && remote != null
        } catch (_: Throwable) {
            false
        }
    }

    override fun exec(script: String, timeoutMs: Long): CommandResult {
        if (!ensureConnected(20_000)) {
            return CommandResult(-1, "", "无法连接 Shizuku 用户服务，请确认已授权且 Shizuku 正在运行", true)
        }
        return try {
            val raw = remote!!.exec(script, timeoutMs.toInt().coerceAtLeast(1_000))
            val o = JSONObject(raw)
            CommandResult(
                exitCode = o.optInt("exitCode", -1),
                stdout = o.optString("stdout"),
                stderr = o.optString("stderr"),
                timedOut = o.optBoolean("timedOut")
            )
        } catch (t: Throwable) {
            CommandResult(-1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    override fun execArgs(args: Array<String>, timeoutMs: Long): CommandResult =
        exec(args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }, timeoutMs)

    fun close() {
        try {
            remote?.destroy()
        } catch (_: Throwable) {
        }
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Throwable) {
        }
        remote = null
    }

    companion object {
        fun detect(): Triple<Boolean, String, Int> {
            return try {
                if (Shizuku.isPreV11()) {
                    Triple(false, "Shizuku 版本过旧（需 v11+）", -1)
                } else if (!Shizuku.pingBinder()) {
                    Triple(false, "Shizuku 服务未运行或应用未授权", -1)
                } else {
                    val uid = Shizuku.getUid()
                    val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    val who = if (uid == 0) "Root" else if (uid == 2000) "ADB Shell" else "uid=$uid"
                    if (granted) Triple(true, "Shizuku 已连接（$who）", uid)
                    else Triple(false, "Shizuku 已运行但未授权本应用（$who）", uid)
                }
            } catch (t: Throwable) {
                Triple(false, "Shizuku 检测失败：${t.message ?: t.javaClass.simpleName}", -1)
            }
        }
    }
}
