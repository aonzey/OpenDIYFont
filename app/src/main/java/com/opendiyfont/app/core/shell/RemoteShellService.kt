package com.opendiyfont.app.core.shell

import org.json.JSONObject

/**
 * 运行在 Shizuku 用户服务进程（uid=0 或 uid=2000）中的命令执行器。
 * 不在 AndroidManifest 中声明：Shizuku 通过 bindUserService 按类名创建它。
 */
class RemoteShellService : IRemoteShellService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(script: String, timeoutMs: Int): String {
        val result = ShellExec.runShell(script, timeoutMs.toLong().coerceAtLeast(1_000))
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timedOut", result.timedOut)
        }.toString()
    }
}
