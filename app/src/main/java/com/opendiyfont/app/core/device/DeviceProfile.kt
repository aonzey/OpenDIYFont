package com.opendiyfont.app.core.device

import android.os.Build

enum class Vendor {
    SAMSUNG, XIAOMI, OPPO, VIVO, HUAWEI, HONOR, ONEPLUS, OTHER
}

data class DeviceProfile(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val vendor: Vendor
) {
    val androidVersion: String
        get() = when (sdk) {
            35 -> "Android 15"
            34 -> "Android 14"
            33 -> "Android 13"
            32 -> "Android 12L"
            31 -> "Android 12"
            30 -> "Android 11"
            29 -> "Android 10"
            28 -> "Android 9"
            27 -> "Android 8.1"
            26 -> "Android 8.0"
            25 -> "Android 7.1"
            24 -> "Android 7.0"
            23 -> "Android 6.0"
            else -> "API $sdk"
        }

    val displayName: String get() = "$brand $model · $androidVersion"

    /** 给用户的厂商提示：无 Root 时不同厂商的成功率差异很大。 */
    fun hint(): String = when (vendor) {
        Vendor.SAMSUNG -> "三星：Root 模块成功率最高；无 Root 时可尝试系统自带「字体风格」。"
        Vendor.XIAOMI -> "小米 / HyperOS：系统主题字体优先级较高，Root 模块可能需要重启后生效。"
        Vendor.OPPO, Vendor.ONEPLUS -> "ColorOS / OxygenOS：部分版本需把地区切换为缅甸才允许替换字体。"
        Vendor.VIVO -> "OriginOS / FuntouchOS：Android 13 之后兼容性可能下降。"
        Vendor.HUAWEI, Vendor.HONOR -> "EMUI / HarmonyOS / MagicOS：优先使用 Root 模块方案。"
        Vendor.OTHER -> "通用 AOSP 策略：优先替换 /system/fonts 下的 Roboto 字体族。"
    }

    companion object {
        fun detect(): DeviceProfile {
            val brand = (Build.BRAND ?: "unknown").trim()
            val manufacturer = (Build.MANUFACTURER ?: "unknown").trim()
            val model = (Build.MODEL ?: "unknown").trim()
            val raw = "$brand $manufacturer $model".lowercase()
            val vendor = when {
                raw.contains("samsung") || raw.contains("sm-") -> Vendor.SAMSUNG
                raw.contains("xiaomi") || raw.contains("redmi") || raw.contains("poco") || raw.contains("miui") -> Vendor.XIAOMI
                raw.contains("oppo") || raw.contains("realme") || raw.contains("oneplus") -> Vendor.OPPO
                raw.contains("vivo") || raw.contains("iqoo") -> Vendor.VIVO
                raw.contains("huawei") || raw.contains("harmony") -> Vendor.HUAWEI
                raw.contains("honor") -> Vendor.HONOR
                else -> Vendor.OTHER
            }
            return DeviceProfile(brand, manufacturer, model, Build.VERSION.SDK_INT, vendor)
        }
    }
}

/** 字体目标文件清单：按 scope 决定覆盖哪些系统文件。 */
object FontTargets {
    private val SANS = listOf(
        "Roboto-Regular.ttf", "Roboto-Bold.ttf", "Roboto-Italic.ttf", "Roboto-BoldItalic.ttf",
        "Roboto-Thin.ttf", "Roboto-Light.ttf", "Roboto-Medium.ttf", "Roboto-Black.ttf",
        "NotoSans-Regular.ttf", "NotoSans-Bold.ttf"
    )

    private val CJK = listOf(
        "NotoSansCJK-Regular.ttc", "NotoSansCJK-Bold.ttc",
        "NotoSansSC-Regular.otf", "NotoSansSC-Bold.otf"
    )

    private val EXTRA = listOf(
        "RobotoCondensed-Regular.ttf", "RobotoCondensed-Bold.ttf",
        "RobotoSlab-Regular.ttf", "NotoSerif-Regular.ttf"
    )

    fun forScope(scopeKey: String): List<String> = when (scopeKey) {
        "sans_cjk" -> SANS + CJK
        "all" -> SANS + CJK + EXTRA
        else -> SANS
    }

    /** 生成模块内 system/fonts 目录需要写入的文件名。仅覆盖设备上实际存在的名字更稳妥，
     *  这里保留完整清单，由脚本侧过滤（避免误引入不存在的字体导致 XML 引用错乱）。 */
    fun scriptForTargets(targets: List<String>): String {
        val list = targets.joinToString(" ")
        return """
            for n in $list; do
              for d in /system/fonts /product/fonts /vendor/fonts /system/product/fonts; do
                [ -e "${'$'}d/${'$'}n" ] || continue
                rel=${'$'}{d#/}
                mkdir -p "${'$'}MOD/${'$'}rel" 2>/dev/null || true
                cp -f "${'$'}SRC" "${'$'}MOD/${'$'}rel/${'$'}n" 2>/dev/null || true
                echo "target: ${'$'}d/${'$'}n"
              done
            done
        """.trimIndent()
    }
}
