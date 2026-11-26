package com.opendiyfont.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.opendiyfont.app.core.ApplyMode
import com.opendiyfont.app.core.ApplyRequest
import com.opendiyfont.app.core.ApplyStrategyMode
import com.opendiyfont.app.core.ApplyResult
import com.opendiyfont.app.core.Capabilities
import com.opendiyfont.app.core.FontEngine
import com.opendiyfont.app.core.FontItem
import com.opendiyfont.app.core.FontScope
import com.opendiyfont.app.core.device.DeviceProfile
import com.opendiyfont.app.core.device.Vendor
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var engine: FontEngine
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    private var capabilities: Capabilities? = null
    private var pendingAction: (() -> Unit)? = null

    private lateinit var deviceText: TextView
    private lateinit var vendorHint: TextView
    private lateinit var statusText: TextView
    private lateinit var fontCount: TextView
    private lateinit var emptyText: TextView
    private lateinit var fontContainer: LinearLayout
    private lateinit var logText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var strategyGroup: RadioGroup
    private lateinit var scopeSection: View
    private lateinit var scopeSpinner: Spinner
    private lateinit var rebootCheck: CheckBox
    private lateinit var restoreButton: Button

    private lateinit var mainPage: View
    private lateinit var settingsPage: View
    private lateinit var importFab: Button
    private lateinit var tabMain: Button
    private lateinit var tabSettings: Button
    private var settingsVisible = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU) {
            mainHandler.post {
                val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                Toast.makeText(this, if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                appendLog("Shizuku 授权结果：${if (granted) "已授予" else "未授予"}")
                refreshStatus()
                if (granted) {
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                } else {
                    pendingAction = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = FontEngine(this)

        deviceText = findViewById(R.id.deviceText)
        vendorHint = findViewById(R.id.vendorHint)
        statusText = findViewById(R.id.statusText)
        fontCount = findViewById(R.id.fontCount)
        emptyText = findViewById(R.id.emptyText)
        fontContainer = findViewById(R.id.fontContainer)
        logText = findViewById(R.id.logText)
        modeGroup = findViewById(R.id.modeGroup)
        strategyGroup = findViewById(R.id.strategyGroup)
        scopeSection = findViewById(R.id.scopeSection)
        scopeSpinner = findViewById(R.id.scopeSpinner)
        rebootCheck = findViewById(R.id.rebootCheck)
        restoreButton = findViewById(R.id.restoreButton)
        mainPage = findViewById(R.id.mainPage)
        settingsPage = findViewById(R.id.settingsPage)
        importFab = findViewById(R.id.importFab)
        tabMain = findViewById(R.id.tabMain)
        tabSettings = findViewById(R.id.tabSettings)

        val profile = DeviceProfile.detect()
        deviceText.text = profile.displayName
        vendorHint.text = profile.hint()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshStatus() }
        importFab.setOnClickListener { openFontPicker() }
        findViewById<Button>(R.id.restoreButton).setOnClickListener { confirmRestore() }
        findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            logLines.clear()
            renderLogs()
        }
        tabMain.setOnClickListener { showPage(false) }
        tabSettings.setOnClickListener { showPage(true) }

        strategyGroup.check(radioForStrategy(engine.strategyMode))
        strategyGroup.setOnCheckedChangeListener { _, checkedId ->
            val strategy = strategyFromRadio(checkedId)
            engine.strategyMode = strategy
            appendLog("字体应用方式已切换：${strategy.label}")
            updateScopeVisibility()
            refreshStatus()
        }
        // 导入中文字体时默认覆盖中文目标，避免只替换 Roboto 后中文仍走系统字体
        scopeSpinner.setSelection(1)
        updateScopeVisibility()
        updateRestoreVisibility()

        showPage(false)
        refreshStatus()
        renderFonts()
        appendLog("OpenDIYFont 已启动。请先检测通道，再导入字体。")
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun showPage(showSettings: Boolean) {
        settingsVisible = showSettings
        mainPage.visibility = if (showSettings) View.GONE else View.VISIBLE
        settingsPage.visibility = if (showSettings) View.VISIBLE else View.GONE
        importFab.visibility = if (showSettings) View.GONE else View.VISIBLE
        tabMain.isSelected = !showSettings
        tabSettings.isSelected = showSettings
    }

    override fun onBackPressed() {
        if (settingsVisible) showPage(false) else super.onBackPressed()
    }

    private fun selectedMode(): ApplyMode = when (modeGroup.checkedRadioButtonId) {
        R.id.modeRoot -> ApplyMode.ROOT
        R.id.modeShizuku -> ApplyMode.SHIZUKU
        else -> ApplyMode.AUTO
    }

    private fun strategyFromRadio(id: Int): ApplyStrategyMode = when (id) {
        R.id.strategyTheme -> ApplyStrategyMode.THEME
        R.id.strategyModule -> ApplyStrategyMode.SYSTEMLESS
        else -> ApplyStrategyMode.AUTO
    }

    private fun radioForStrategy(strategy: ApplyStrategyMode): Int = when (strategy) {
        ApplyStrategyMode.THEME -> R.id.strategyTheme
        ApplyStrategyMode.SYSTEMLESS -> R.id.strategyModule
        else -> R.id.strategyAuto
    }

    /**
     * 替换范围只对 systemless / 直写方案有意义。
     * 主题注入由 ThemeManager 统一接管，显示该选项只会误导用户。
     */
    private fun updateScopeVisibility() {
        val usesTheme = when (engine.strategyMode) {
            ApplyStrategyMode.THEME -> true
            ApplyStrategyMode.SYSTEMLESS -> false
            else -> DeviceProfile.detect().vendor == Vendor.XIAOMI
        }
        scopeSection.visibility = if (usesTheme) View.GONE else View.VISIBLE
    }

    /** 系统仍是默认字体时不显示恢复按钮，避免出现无意义的操作入口。 */
    private fun updateRestoreVisibility() {
        restoreButton.visibility = if (engine.fontApplied) View.VISIBLE else View.GONE
    }

    private fun selectedScope(): FontScope =
        FontScope.fromKey(resources.getStringArray(R.array.scope_entries).let { entries ->
            when (scopeSpinner.selectedItemPosition) {
                1 -> "sans_cjk"
                2 -> "all"
                else -> "sans"
            }
        })

    private fun refreshStatus() {
        statusText.text = getString(R.string.working)
        executor.execute {
            val active = try {
                engine.detectActiveFont()
            } catch (_: Throwable) {
                engine.fontApplied
            }
            val caps = try {
                engine.capabilities()
            } catch (t: Throwable) {
                Capabilities(false, "检测失败：${t.message}", false, "检测失败：${t.message}")
            }
            mainHandler.post {
                capabilities = caps
                val mode = selectedMode()
                statusText.text = buildString {
                    append("Root：").append(caps.rootDetail).append('\n')
                    append("Shizuku：").append(caps.shizukuDetail).append('\n')
                    append("执行通道：").append(mode.displayLabel())
                        .append("  →  实际通道：").append(caps.pick(mode).label).append('\n')
                    append("应用方式：").append(engine.strategyMode.label)
                }
                updateRestoreVisibility()
                appendLog(
                    "检测完成：Root=${caps.rootAvailable}, Shizuku=${caps.shizukuAvailable}" +
                        "；字体状态=${if (active) "已应用自定义字体" else "系统默认"}"
                )
            }
        }
    }

    private fun openFontPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "font/ttf", "font/otf", "application/x-font-ttf",
                "application/x-font-otf", "application/octet-stream"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_PICK_FONT)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开文件选择器：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FONT || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.data?.let { uris += it }
        data.clipData?.let { clip ->
            (0 until clip.itemCount).forEach { i -> uris += clip.getItemAt(i).uri }
        }
        if (uris.isEmpty()) return

        executor.execute {
            val errors = mutableListOf<String>()
            var imported = 0
            uris.forEach { uri ->
                try {
                    engine.repository.import(uri, queryDisplayName(uri))
                    imported++
                } catch (t: Throwable) {
                    errors += "${queryDisplayName(uri)}：${t.message ?: "导入失败"}"
                }
            }
            mainHandler.post {
                renderFonts()
                Toast.makeText(
                    this,
                    "成功导入 $imported 个字体${if (errors.isEmpty()) "" else "，失败 ${errors.size} 个"}",
                    Toast.LENGTH_LONG
                ).show()
                errors.forEach { appendLog("导入失败 $it") }
                if (imported > 0) appendLog("成功导入 $imported 个字体")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Throwable) {
        }
        return name ?: uri.lastPathSegment ?: "font.ttf"
    }

    private fun renderFonts() {
        fontContainer.removeAllViews()
        val fonts = engine.repository.list()
        fontCount.text = "已导入 ${fonts.size} 个字体"
        emptyText.visibility = if (fonts.isEmpty()) View.VISIBLE else View.GONE

        fonts.forEach { font ->
            val view = layoutInflater.inflate(R.layout.item_font, fontContainer, false)
            val nameText = view.findViewById<TextView>(R.id.fontName)
            val metaText = view.findViewById<TextView>(R.id.fontMeta)
            val previewText = view.findViewById<TextView>(R.id.fontPreview)

            nameText.text = font.name
            metaText.text = "${font.format} · ${font.size / 1024} KB · ${font.id}"
            previewText.typeface = typefaceFor(font)

            view.findViewById<Button>(R.id.applyFontButton).setOnClickListener { confirmApply(font) }
            view.findViewById<Button>(R.id.deleteFontButton).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("删除字体")
                    .setMessage("从应用中删除「${font.name}」？这不会恢复已经应用到系统的字体。")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        engine.repository.delete(font.id)
                        renderFonts()
                        appendLog("已删除字体：${font.name}")
                    }
                    .show()
            }
            fontContainer.addView(view)
        }
    }

    private fun typefaceFor(font: FontItem): Typeface = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Typeface.createFromFile(File(font.path))
        } else {
            Typeface.DEFAULT
        }
    } catch (_: Throwable) {
        Typeface.DEFAULT
    }

    private fun confirmApply(font: FontItem) {
        val mode = selectedMode()
        val channel = capabilities?.pick(mode)?.label ?: "检测中"
        val strategy = engine.strategyMode
        val rebootHint = when (strategy) {
            ApplyStrategyMode.THEME -> "主题注入通常无需重启。"
            ApplyStrategyMode.SYSTEMLESS -> "Systemless 模块需要重启后生效。"
            else -> "模块方式需重启，主题注入无需重启。"
        }
        val scopeLine = if (scopeSection.visibility == View.VISIBLE) {
            "范围：${selectedScope().label}\n"
        } else {
            "范围：由主题引擎统一管理\n"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_apply_title)
            .setMessage(
                "字体：${font.name}\n$scopeLine" +
                    "执行通道：${mode.displayLabel()}（$channel）\n" +
                    "应用方式：${strategy.label}\n\n$rebootHint"
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                ensurePrivilege { doApply(font) }
            }
            .show()
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_restore_title)
            .setMessage(R.string.confirm_restore_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                ensurePrivilege { doRestore() }
            }
            .show()
    }

    /** 需要 Shizuku 时先申请授权，授权回调后再执行用户动作。 */
    private fun ensurePrivilege(action: () -> Unit) {
        val mode = selectedMode()
        val caps = capabilities
        val needsShizuku = mode == ApplyMode.SHIZUKU ||
            (mode == ApplyMode.AUTO && caps?.rootAvailable != true)
        if (!needsShizuku || caps?.shizukuAvailable == true) {
            action()
            return
        }
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !Shizuku.shouldShowRequestPermissionRationale()
            ) {
                pendingAction = action
                Shizuku.requestPermission(REQ_SHIZUKU)
                return
            }
        } catch (t: Throwable) {
            appendLog("Shizuku 权限请求异常：${t.message}")
        }
        action()
    }

    private fun doApply(font: FontItem) {
        val request = ApplyRequest(
            font,
            selectedScope(),
            selectedMode(),
            rebootCheck.isChecked,
            engine.strategyMode
        )
        val scopeText = if (scopeSection.visibility == View.VISIBLE) {
            request.scope.label
        } else {
            "主题引擎统一管理"
        }
        appendLog("开始应用：${font.name} / $scopeText / ${request.mode.displayLabel()} / ${request.strategy.label}")
        Toast.makeText(this, R.string.working, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = engine.apply(request)
            mainHandler.post {
                appendLogs(result.logs)
                if (result.success) {
                    engine.fontApplied = true
                    updateRestoreVisibility()
                }
                showResult(result)
                if (result.success && result.rebootSuggested && rebootCheck.isChecked) doReboot()
            }
        }
    }

    private fun doRestore() {
        appendLog("开始恢复系统默认字体")
        executor.execute {
            val result = engine.restore(selectedMode())
            mainHandler.post {
                appendLogs(result.logs)
                if (result.success) {
                    engine.fontApplied = false
                    updateRestoreVisibility()
                }
                showResult(result)
                if (result.success && result.rebootSuggested && rebootCheck.isChecked) doReboot()
            }
        }
    }

    private fun doReboot() {
        executor.execute {
            val result = engine.reboot(selectedMode())
            mainHandler.post {
                appendLogs(result.logs)
                Toast.makeText(this, result.title, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResult(result: ApplyResult) {
        val builder = AlertDialog.Builder(this)
            .setTitle(if (result.success) "操作成功" else "操作未完成")
            .setMessage(buildString {
                append(result.title).append("\n\n").append(result.detail)
                val tail = result.logs.takeLast(12)
                if (tail.isNotEmpty()) {
                    append("\n\n--- 日志摘要 ---\n").append(tail.joinToString("\n"))
                }
            })
            .setNeutralButton(R.string.copy_logs) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("OpenDIYFont logs", result.logs.joinToString("\n"))
                )
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ok, null)

        if (result.rebootSuggested) {
            builder.setNegativeButton(R.string.reboot_now) { _, _ -> doReboot() }
        }
        builder.show()
    }

    private fun appendLog(text: String) {
        logLines += "${timeFormat.format(Date())}  $text"
        if (logLines.size > 200) logLines.removeAt(0)
        renderLogs()
    }

    private fun appendLogs(lines: List<String>) = lines.forEach(::appendLog)

    private fun renderLogs() {
        logText.text = logLines.joinToString("\n").ifBlank { "（暂无日志）" }
    }

    companion object {
        private const val REQ_PICK_FONT = 1001
        private const val REQ_SHIZUKU = 1002
    }
}

private fun ApplyMode.displayLabel(): String = when (this) {
    ApplyMode.AUTO -> "自动检测"
    ApplyMode.ROOT -> "Root"
    ApplyMode.SHIZUKU -> "Shizuku"
}
