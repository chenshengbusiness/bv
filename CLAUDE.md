# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中工作时提供指导。

## 项目概览

**BV (Bug Video)** 是基于 **Jetpack Compose for TV**、Kotlin 与 ExoPlayer 开发的第三方 **Bilibili Android TV 客户端**。本项目 fork 自 [aaa1115910/bv](https://github.com/aaa1115910/bv)，由 Frost819 进行了大量自定义优化 —— 详细的功能/界面优化列表见 `README.md`（重做的左侧导航栏、重写的主页列表逻辑、视频卡片长按快捷操作、付费视频试看等），当前 `perf-optimization` 分支的性能调优目标（智能 GC、硬件隧道、动态 LoadControl、智能最高画质）见 `doc/stats.md`。

- **包名**：`dev.aaa1115910.bv`（namespace） / `dev.frost819.bv`（applicationId；因小米电视屏蔽原包名而更换）
- **许可证**：MIT
- **Min/Target/Compile SDK**：21 / 36 / 36
- **工具链**：JDK 17、Gradle 8.11.1、AGP 8.8.0、Kotlin 2.1.21（K2）、KSP 2.1.21-2.0.1

## 构建与运行命令

执行任何构建前，请先确认：已运行 `git submodule update --init --recursive`（`libs/` 目录是未初始化的子模块），并已设置 `ANDROID_HOME` 以及 `local.properties` 指向 SDK 路径。

```bash
# 编译 Debug APK（按 ABI 拆分；输出到 app/build/outputs/apk/<flavor>/<buildType>/）
./gradlew assembleDefaultDebug          # 完整版，含 VLC + FFmpeg + AV1
./gradlew assembleLiteDebug             # 精简版（剔除 VLC 原生库）

# 编译 Release APK（需在仓库根目录放置 signing.properties）
./gradlew assembleDefaultRelease

# 其他 buildType（见 app/build.gradle.kts）：alpha、r8Test
./gradlew assembleDefaultAlpha

# 运行单元测试 / 设备测试
./gradlew test                          # 各模块的 JVM 单元测试
./gradlew connectedAndroidTest          # 需要连接真机/模拟器

# Lint、清理任务
./gradlew lint
./gradlew clean

# 运行单个测试类（以 app 模块为例）
./gradlew :app:testDebugUnitTest --tests "com.example.MyTest"
```

APK 命名规则：`BV_{versionCode}_{versionName}.{buildType}_{flavor}_{abi}.apk` —— `default` 渠道的 release 版本使用更简洁的 `BV_{yyyyMMdd}.apk` 形式。

`versionCode` 和 `versionName` 由 `git rev-list` 动态生成（见 `buildSrc/src/main/kotlin/AppConfiguration.kt`），因此编译环境必须保留完整 Git 历史 —— 不能是浅克隆。

## 模块架构

```
app/             Android application — 所有 Compose UI、ViewModel、仓库、DataStore
bili-api/        Kotlin library — 基于 Ktor 的 B 站 HTTP API（entity/、http/、repositories/）
bili-api-grpc/   Kotlin library — gRPC 绑定（Protobuf 生成的 stub）
bili-subtitle/   Kotlin library — 字幕解析
bv-player/       Android library — 播放器抽象 + ExoPlayer 实现
libs/            Git submodule — 原生库（ffmpegDecoder、av1Decoder、libVLC）
```

- `app` 模块依赖 `bili-api`、`bili-subtitle`、`bv-player`；`settings.gradle.kts` 中链接了子模块库。
- **DI**：使用 **Koin**（注解 + KSP）。`BVApp` 定义 `AppModule`；`bili-api` 定义 `BiliApiModule`（通过 `@ComponentScan` 自动扫描）。
- **序列化**：使用 **kotlinx.serialization** 处理 B 站 JSON 响应。
- **网络**：使用 **Ktor**，同时启用 CIO 与 OkHttp 引擎（见 `bili-api/http/BiliHttpApi.kt`）。

## 应用初始化（`BVApp.onCreate`）

`app/src/main/kotlin/dev/aaa1115910/bv/BVApp.kt` 中的副作用顺序：
1. `initCoreLibraries()` —— 日志、`DataStoreManager`、Koin
2. `Prefs.init()` —— 将全部用户偏好加载到内存（采用 `PrefDelegate` 模式：内存中 `MutableStateFlow` + 异步写 DataStore，见 `util/Prefs.kt`）
3. `initDeviceInfo()` —— 填充 `BiliAppConf` / `BiliWebConf`（系统版本、机型、WebView 版本）
4. `initRepository()` —— 从 Prefs 恢复认证 token 到 `AuthRepository`，并初始化 `ChannelRepository`
5. `initProxy()` —— 仅在 `Prefs.enableProxy` 为 true 时执行；配置 `BiliHttpProxyApi` 与 gRPC 代理通道
6. `BiliHttpApi.init(buvid3 = Prefs.buvid3)` —— 为后续所有请求设置 buvid

`BVApp.context`、`BVApp.dataStoreManager`、`BVApp.koinApplication`、`BVApp.instance` 均为全局访问点，被项目中各模块广泛使用。

## UI / Compose 架构

- **TV 优先**：大量使用 `androidx.tv.material3.*` 与 `androidx.compose.tv.foundation.*`。焦点管理与遥控器交互至关重要 —— 参考 `component/TopNav.kt`、`component/FocusGroup.kt`，以及 `screen/VideoInfoScreen.kt` 中的 `LaunchedEffect + FocusRequester` 模式。
- **导航**：采用经典的 Android「一个 Activity 对应一个页面」模式（**没有**使用 `NavController`）。`screen/` 包内为可组合函数；每个页面由 `activities/` 下的独立 `Activity` 承载。入口：`MainActivity`（根，检查用户锁） → `screen/MainScreen.kt`（导航栏 + 内容区） → 其他 Activity。
- **状态**：ViewModel 暴露 `StateFlow<UiState>` + `Channel<UiEffect>`（一次性事件，见 `ui/state/`、`ui/effect/`）。ViewModel 通过 `koinViewModel()` 注入。
- **Activity / Screen 模式**：`activities/video/VideoInfoActivity.kt` 调用 `setContent { VideoInfoScreen(...) }` —— 大部分 Screen 是纯 Composable 函数，并通过 `koinViewModel()` 获取 ViewModel。

## 数据层

- **本地持久化**：
  - `DataStore`（preferences）通过 `DataStoreManager` + `Prefs`（`PrefDelegate` 模式，约 100+ 项设置）。
  - **Room** 用于 `SearchHistoryDB` 和 `UserDB`（见 `dao/AppDatabase.kt`）。Schema 导出路径通过 KSP 参数 `room.schemaLocation` 配置。
- **`bili-api` 中的 API 客户端**：
  - `BiliHttpApi` —— 主 Ktor HTTP 客户端（公开接口 + 鉴权接口）。
  - `BiliHttpProxyApi` —— 代理变体。
  - `BiliLiveHttpApi`、`BiliPassportHttpApi`（登录 + 验证码）、`BiliPlusHttpApi` —— 第三方兜底。
  - **gRPC** 位于 `bili-api-grpc`，用于流式接口（推荐、动态等）—— 见 `ChannelRepository.initDefaultChannel` / `initProxyChannel`。
- **User 仓库**位于 `app/repository/UserRepository.kt`；大量业务仓库（`SearchRepository`、`HistoryRepository`、`VideoDetailRepository` 等）位于 `bili-api/repositories/`，由 Koin 自动扫描注入。

## 播放器架构（`bv-player`）

```
AbstractVideoPlayer（接口）
   └─ ExoMediaPlayer      ← 当前唯一的具体实现
         ExoPlayer（Media3）+ DefaultRenderersFactory
         可选：FFmpeg 音频渲染器 / 软件视频解码器
```

- 通过 `ExoPlayerFactory().create(context, options)` 构建（在 `app/viewmodel/player/VideoPlayerV3ViewModel.kt` 的 `initVideoPlayer` 中调用）。
- `bv-player/BvVideoPlayer.kt` 中的 `BvVideoPlayer` Composable 通过 `AndroidView` 包装 ExoPlayer 的 `PlayerView`。
- `VideoPlayerOptions` 控制 user-agent（取决于 `Prefs.apiType` = Web 或 App）、referer、是否启用 FFmpeg 音频渲染器、是否启用软件视频解码器。
- **按渠道差异化打包**：`lite` 渠道在打包时排除 `libvlc*.so`（见 `app/build.gradle.kts:122-128`）；播放器代码目前仅保留 ExoPlayer 路径。
- 字幕/进度条缩略图/弹幕的加载由 `VideoPlayerV3ViewModel.loadVideoWithResources()` 统一调度，并行执行 `resolveUrlsAndPlay`、`updateSubtitle`、`loadDanmaku`、`updateDanmakuMask`、`updateVideoShot`、`updateVideoPages`。
- `VideoInfoRepository` 是 `VideoDetailViewModel` 与 `VideoPlayerV3ViewModel` 之间的单例 —— 两者通过 `videoList` 和 `videoDetailState` 两个 Flow 共享状态，用于「自动连播下一集」逻辑。
- `perf-optimization` 分支（见 `doc/stats.md`）新增了 5 个高级开关（`enableDynamicLoadControl`、`enableTunneling`、`enableMediaCodecHighPriority`、`enableSmartGcBeforePlay`、`enableSmartHighestQuality`），分别作用于 `ExoMediaPlayer.initPlayer` 与 `VideoPlayerV3ViewModel`。

## 关键约定

- **偏好设置**：所有设置均在 `Prefs.kt` 中以 `var foo: T by PrefDelegate(...)` 形式声明。读取为同步读内存；写入为异步写 DataStore。新增设置：在 `PrefKeys` 中声明 key，再在 `Prefs` 上添加一个 delegate。
- **DI**：使用 Koin 注解（`@Single`、`@Factory`）标注单例/工厂 —— 现有 `@ComponentScan` 会自动发现。UI 层通过 `koinViewModel<T>()` 获取。
- **Compose 稳定性**：`compose_compiler_config.conf` 控制稳定性。TV 硬件对重组敏感，请谨慎使用 `immutable` / `stable`。
- **日志**：通过 `HandroidLoggerAdapter` + `LogCatcherUtil`（应用内捕获日志，可通过 `LogsActivity` 查看）使用 SLF4J。新代码请使用 `KotlinLogging.logger {}` 或 `logger.fInfo {}`（扩展定义于 `util/Extends.kt`）。
- **CI**：`.github/workflows/` 下有 alpha、release、feature、issue 自动关闭等 workflow。运行在 `macos-latest`，从 secrets 恢复签名，假设 `signing.properties` 与 `app/google-services.json` 已存在。
- **Issue 模板**：`.github/ISSUE_TEMPLATE/` 下定义了 `bug_report.yml`、`feature_request.yml` 和 `config.yml`。

## 分支 / 发布流程

- `master` —— 标签化发布（`vX.Y.Z`）；触发 `.github/workflows/release.yml`。
- `develop` —— alpha 构建；触发 `.github/workflows/alpha.yml`。
- `perf-optimization` —— 当前工作分支（见 `doc/stats.md`）。
- 版本号格式：`0.3.16.r{commitCount}.{shortHash}`（见 `AppConfiguration.kt`）；`app/build.gradle.kts` 会在 APK 输出中重写为 `${versionName}.${buildType}`。

## TV 特有的注意事项

- Compose TV 对 **焦点恢复** 与 **bring-into-view** 计算非常敏感。`screen/VideoInfoScreen.kt` 中自定义的 `BringIntoViewSpec`（将焦点项滚动至距顶部 30% 处）是新增可滚动页面的良好模板。
- TV 上 **没有** `Recompose.highlighter` 或交互式 Compose 工具 —— `component/BvPlayerPreview.kt` 中的 `BvPlayerPreview` Composable 仅用于 `@Preview`，不会自动播放。
- **遥控器按键**（DPad 中心 = 确认、菜单键 = 列表页刷新、长按 = 视频卡片快捷操作）按页面分别处理；新增列表页应参考 `screen/main/pgc/PgcCommon.kt` 与 `screen/main/ugc/UgcCommon.kt` 中的菜单键刷新模式。
- 「自动连播下一集」倒计时、进度条缩略图缓存、弹幕防遮挡蒙版尺寸、Activity 销毁时的播放器清理 —— 这些都是 TV 上被反复修复过的点 —— 请参考 `VideoPlayerV3ViewModel.detachPlayer` / `releaseDanmakuPlayer` 以及 `screen/VideoPlayerV3Screen.kt` 中 `viewModelScope` 的使用模式。
