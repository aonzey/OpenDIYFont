package com.opendiyfont.app.core

/** 用户可选择的执行模式。AUTO 会在运行时按 ROOT -> SHIZUKU 的顺序选择。 */
enum class ApplyMode(val key: String) {
    AUTO("auto"),
    ROOT("root"),
    SHIZUKU("shizuku");

    companion object {
        fun fromKey(key: String?): ApplyMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** 用户可选择的字体应用方式。 */
enum class ApplyStrategyMode(val key: String, val label: String) {
    AUTO("auto", "自动（推荐）"),
    THEME("theme", "厂商主题注入"),
    SYSTEMLESS("systemless", "Systemless 模块");

    companion object {
        fun fromKey(key: String?): ApplyStrategyMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** 实际被选中的执行通道。 */
enum class Channel(val label: String) {
    ROOT("Root"),
    SHIZUKU("Shizuku"),
    NONE("无可用通道")
}

data class Capabilities(
    val rootAvailable: Boolean,
    val rootDetail: String,
    val shizukuAvailable: Boolean,
    val shizukuDetail: String,
    val shizukuUid: Int = -1
) {
    val hasAny: Boolean get() = rootAvailable || shizukuAvailable

    fun pick(mode: ApplyMode): Channel = when {
        mode == ApplyMode.ROOT && rootAvailable -> Channel.ROOT
        mode == ApplyMode.SHIZUKU && shizukuAvailable -> Channel.SHIZUKU
        mode == ApplyMode.ROOT -> Channel.NONE
        mode == ApplyMode.SHIZUKU -> Channel.NONE
        rootAvailable -> Channel.ROOT
        shizukuAvailable -> Channel.SHIZUKU
        else -> Channel.NONE
    }
}

data class FontItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val format: String,
    val addedAt: Long
)

/** 字体在系统中需要覆盖的目标文件族。 */
enum class FontScope(val key: String, val label: String) {
    SANS("sans", "系统无衬线（Roboto / Noto Sans）"),
    SANS_CJK("sans_cjk", "无衬线 + 中文（Noto Sans CJK）"),
    ALL("all", "全部常见系统字体（高风险）");

    companion object {
        fun fromKey(key: String?): FontScope = values().firstOrNull { it.key == key } ?: SANS
    }
}

data class ApplyRequest(
    val font: FontItem,
    val scope: FontScope,
    val mode: ApplyMode,
    val rebootAfter: Boolean = false,
    val strategy: ApplyStrategyMode = ApplyStrategyMode.AUTO
)

data class ApplyResult(
    val success: Boolean,
    val channel: Channel,
    val title: String,
    val detail: String,
    val rebootSuggested: Boolean,
    val logs: List<String>
)

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
    val combined: String get() = buildString {
        if (stdout.isNotBlank()) append(stdout.trim())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("stderr: ").append(stderr.trim())
        }
    }
}

data class LogEntry(val time: Long, val text: String)
