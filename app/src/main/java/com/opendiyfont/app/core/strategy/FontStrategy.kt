package com.opendiyfont.app.core.strategy

import com.opendiyfont.app.core.ApplyRequest
import com.opendiyfont.app.core.ApplyResult
import com.opendiyfont.app.core.Channel
import com.opendiyfont.app.core.shell.Shell
import java.io.File

interface FontStrategy {
    val name: String
    val channel: Channel

    fun apply(
        request: ApplyRequest,
        shell: Shell,
        stagedFont: File,
        logs: MutableList<String>
    ): ApplyResult

    fun restore(shell: Shell, logs: MutableList<String>): ApplyResult
}
