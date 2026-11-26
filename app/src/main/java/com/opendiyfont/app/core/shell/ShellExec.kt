package com.opendiyfont.app.core.shell

import com.opendiyfont.app.core.CommandResult
import java.io.File
import java.util.concurrent.TimeUnit

/** 本地进程执行工具：统一超时、输出收集、强制终止。 */
object ShellExec {

    fun run(args: List<String>, timeoutMs: Long = 15_000, input: File? = null): CommandResult {
        return try {
            val builder = ProcessBuilder(args)
            builder.redirectErrorStream(true)
            input?.let { builder.directory(it) }
            val process = builder.start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            output.append(buf, 0, n)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(1_000)
                return CommandResult(-1, output.toString(), "command timed out after ${timeoutMs}ms", true)
            }
            reader.join(1_000)
            val text = output.toString()
            CommandResult(process.exitValue(), text, "")
        } catch (t: Throwable) {
            CommandResult(-1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    /** 通过 sh 执行一段脚本（避免多层 shell 引号转义）。 */
    fun runShell(body: String, timeoutMs: Long = 15_000): CommandResult =
        run(listOf("sh", "-c", body), timeoutMs)
}
