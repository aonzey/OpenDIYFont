# OpenDIYFont

安卓平台自定义字体导入与替换工具：把本地 TTF / OTF / TTC 字体导入应用内，选择执行通道与应用方式，免重启或经 systemless 模块替换系统字体，并可一键恢复系统默认字体。

**项目作者：Aonzey**（GitHub：[@aonzey](https://github.com/aonzey)）

## 功能特性

- **字体导入**：支持 TTF / OTF / TTC，导入后按内容去重，列表按字体实际字形渲染缩略图与预览。
- **执行通道**：自动检测 / Root / Shizuku（AIDL UserService），设置页实时显示各通道状态。
- **应用方式**：
  - 厂商主题注入（小米 / 红米 / POCO，免重启；多字体共存，恢复默认不删除主题列表条目）；
  - Systemless 模块（Root 或 Shizuku Root，需重启）。
- **替换范围**：按策略动态显示，可切换系统无衬线、无衬线 + 中文、全部常见系统字体（高风险）。
- **恢复默认**：真实复核系统字体状态，避免"一闪即成功但实际未生效"；成功切回默认后按钮自动隐藏。
- **兼容**：`minSdk 24`、`targetSdk 35`；已在 Android 13 / MIUI 14（V14.0.27.0.TMRCNXM）真机验证主题注入通道。

## 下载

正式安装包见 [Releases](https://github.com/aonzey/OpenDIYFont/releases)。首个发布版本为 **v1.4.10**。

| 包 | 说明 |
| --- | --- |
| `OpenDIYFont-v1.4.10-release.apk` | 正式版，约 1.1 MB |
| `OpenDIYFont-v1.4.10-debug.apk` | 调试版，便于查看日志 |

## 构建

使用 Gradle 8.9 / AGP 8.6.1 / Kotlin 2.0.21，需 **JDK 17** 与 Android SDK（含 Build Tools 35.0.1）。

仓库内可自带工具链：`.toolchain/jdk-17.0.21.1+2` 与 `.toolchain/sdk`，`local.properties` 中写 `sdk.dir=<SDK 路径>`。

```bash
JAVA_HOME="/path/to/jdk-17" \
PATH="/path/to/jdk-17/bin:$PATH" \
./gradlew assembleDebug assembleRelease --console=plain
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

> release 使用 debug 签名（`signingConfigs.debug`），方便用户直接安装升级；如需正式分发请自行替换签名配置。

## 使用

1. 打开 App，在「主界面」点右下角「＋ 导入字体」选择本地字体文件。
2. 在「设置」里确认执行通道与字体应用方式，按需设置替换范围。
3. 回「主界面」点字体的「应用」，确认后跳转到厂商主题引擎（主题注入）或写入模块后重启（systemless）。
4. 需要恢复时在「设置」里下滑点「一键恢复系统默认字体」；App 会确认系统字体真正切回默认后才报成功。

主题注入方式下，恢复默认**只切回系统字体**，不会删除「主题 → 我的 → 字体」里的字体条目，可随时再次选用。

## 已知说明

- 「字体作者」显示为「来自用户」，这是 App 写入厂商主题资源时的展示信息，与导入字体内容无关。
- MIUI / HyperOS 的主题引擎可能在详情页弹出自身的联网提示（例如 `下载rights失败`、`加载资源失败`）。v1.4.10 已通过固定合法授权模板与真实默认资源 ID 规避多数场景，但 ROM 侧提示无法由本应用完全控制；实际是否生效请以 App 的执行日志与系统字体状态为准。
- 修改系统字体因机型、Android 版本与 ROM 而异。请自行确认字体文件版权，并保留可恢复手段。

## 许可

本项目由 Aonzey 维护。第三方字体素材、厂商主题资源及授权文件的权利归各自权利人所有；本应用不修改其签名内容。
