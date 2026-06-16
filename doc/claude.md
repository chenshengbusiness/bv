# Claude 对极致性能优化方案的分析与落地建议

本文基于 `doc/stats.md`、`doc/mimax.md` 以及当前代码实现进行分析，重点关注 `perf-optimization` 分支中提出的 5 个高级优化开关：`enableDynamicLoadControl`、`enableTunneling`、`enableMediaCodecHighPriority`、`enableSmartGcBeforePlay`、`enableSmartHighestQuality`。

## 总体判断

`doc/stats.md` 的优化方向是正确的。Android TV 设备通常 CPU 和内存弱于手机，但具备较强的视频硬解能力，因此围绕 4K、杜比、高码率播放体验做 LoadControl、解码路径、画质选择和起播前内存整理，是符合项目目标的。

但 `stats.md` 更像一份“实验室极致调参方案”，其中“5 个开关默认全开”的策略不适合作为稳定默认行为。TV 设备碎片化严重，尤其是低端电视盒子、老 Android TV、魔改系统和 OEM 解码器实现不一致。Tunneling、MediaCodec priority、强制最高画质、起播前 GC 都不是稳定收益项，默认开启可能导致黑屏、无声音、解码器初始化失败、软解 fallback、内存压力升高或播放体验反而变差。

`doc/mimax.md` 的评审更贴近当前项目的真实风险。我基本赞同它的核心判断：这些优化应当按风险从低到高分阶段落地，默认只开启副作用较小的 DynamicLoadControl，其余能力作为高级实验开关，配合日志、能力探测和可回退路径。

当前代码中，这 5 个开关尚未真正落地。仓库里没有搜到 `enableDynamicLoadControl`、`enableTunneling`、`enableMediaCodecHighPriority`、`enableSmartGcBeforePlay`、`enableSmartHighestQuality` 的实现；`VideoPlayerOptions` 当前也只有 `enableFfmpegAudioRenderer` 和 `enableSoftwareVideoDecoder` 两个字段。因此现在的主要任务不是继续加激进优化，而是先建立安全的开关传递链路、默认策略、日志和可观测性。

## 对各项 Feature 的分析

### 1. 高级设置与 Prefs 层

这部分应优先落地，风险最低。`Prefs.kt` 目前采用 `PrefDelegate` 模式，新增布尔设置比较简单：在 `PrefKeys` 中添加对应 key，然后在 `Prefs` 中添加 delegate 即可。

建议默认值如下：`enableDynamicLoadControl = true`，`enableTunneling = false`，`enableMediaCodecHighPriority = false`，`enableSmartGcBeforePlay = false`，`enableSmartHighestQuality = false`。

原因是 DynamicLoadControl 的风险相对最低，主要影响缓冲策略；其余四项都可能引入明显副作用。Tunneling 对设备硬件、系统 feature、解码器声明和媒体格式都有要求；MediaCodec priority 是 OEM 可忽略的 hint，甚至可能触发异常 fallback；Smart GC 可能造成 stop-the-world 抖动；Smart Highest Quality 会显著放大网络、内存和解码压力。

设置页方面，可以在 `SettingsScreen.kt` 中新增“极致优化”菜单项，并创建 `AdvancedSetting.kt` 页面绑定这 5 个开关。页面中建议直接写清楚这些开关属于“实验/高级优化”，尤其是 Tunneling 和 MediaCodec priority，不应被描述成必然提升性能。

### 2. Smart Highest Quality

`stats.md` 中“在请求 B 站流媒体 API 之前强制请求最高 qn”的说法需要修正。当前 `VideoPlayRepository.getPlayData()` 和 `getPgcPlayData()` 已经在 Web 和 App 路径中使用 `qn = 127`、`fourk = 1`、`fnval = 4048` 请求高规格播放数据。也就是说，很多情况下项目已经在请求尽可能完整的播放数据。

真正需要改的是拿到 `PlayData` 后的目标流选择逻辑。当前 `VideoPlayerV3ViewModel.loadPlaybackConfig()` 会根据 `playData.dashVideos` 构建 `resolutionMap`，然后调用 `calculateTargetQuality(resolutionMap.keys, Prefs.defaultQuality.code)`。现有逻辑是：如果用户默认画质存在，就用默认画质；否则找不超过默认画质的最高档；再否则取第一个可用档。

Smart Highest Quality 的第一版不建议硬编码大会员状态和 qn 映射。更稳妥的方式是基于服务端实际返回的 `availableQualities` 选择最高档，因为返回结果已经综合了登录态、大会员、版权、地区限制、试看状态、PGC/UGC 差异等因素。客户端自己根据 `vipType/vipStatus` 猜 qn，容易遗漏 HDR、杜比、8K、1080P 高码率、区域代理和试看等复杂情况。

建议将 `calculateTargetQuality()` 改成：当 `Prefs.enableSmartHighestQuality` 开启时，直接返回 `availableQualities.sorted().lastOrNull() ?: 0`；未开启时保持现有逻辑。这样既符合“最高可用画质”，又不会请求服务端没有返回的不可播放档位。

同时，ViewModel 层必须作为强制防线。不能只在 UI 层禁用清晰度按钮，因为自动续播、切下一集、内部状态恢复或未来其他入口都可能绕过 UI。建议在 `VideoPlayerV3ViewModel.updateMediaProfile()` 中拦截 `MediaProfileSettingAction.SetQuality`：如果 Smart Highest Quality 开启，则忽略手动清晰度切换，并通过 UI effect 或 toast 提示“已开启智能最高画质，请前往高级设置关闭”。UI 层的 `PictureMenuList` 可以额外禁用清晰度选项或显示提示，但这只能作为交互优化，不能作为唯一保护。

### 3. 起播前 Smart GC

在 `VideoPlayerV3ViewModel.initVideoPlayer()` 中、创建 ExoPlayer 之前执行内存检查是合理位置，因为此时用户感知上仍处于加载阶段，且不会干扰已经开始播放的视频帧。

但 Smart GC 不应默认开启。`System.gc()` 只是建议，不保证立即执行，也可能造成 stop-the-world 抖动。如果每次起播都调用，或者在连续切集时频繁调用，可能比默认 JVM GC 更糟。

建议抽出 `runSmartGcBeforePlayIfNeeded()`，只在 `Prefs.enableSmartGcBeforePlay` 开启时运行。触发条件不要只看 `(totalMemory - freeMemory) / maxMemory`，可以组合判断：堆使用率超过 70%，或者可用堆内存低于 200MB 且使用率超过 60%。同时必须加节流，例如 30 秒内最多触发一次，并记录日志：usage、freeMB、是否触发、距上次触发多久。

不要把 Smart GC 放进 `loadVideoWithResources()` 的 `Dispatchers.IO` 并发链中。该函数会并行调度播放 URL、字幕、弹幕、弹幕蒙版、进度条缩略图、选集等任务；如果在这里触发 stop-the-world，可能把这些 IO 和解析任务一起拖慢，导致起播更慢。

### 4. Dynamic LoadControl

这是 5 个开关中最适合作为第一轮真实优化落地的能力。当前 `ExoMediaPlayer.initPlayer()` 使用默认 ExoPlayer Builder，没有自定义 `DefaultLoadControl`。可以在 `VideoPlayerOptions` 中新增 `enableDynamicLoadControl`，再在 `ExoMediaPlayer.initPlayer()` 中按开关设置 `DefaultLoadControl`。

第一版不建议过早实现复杂的“按实时视频码率动态 targetBufferBytes”。因为 `initPlayer()` 执行时还不知道实际选中的 `DashVideo.bandwidth`，如果要按码率动态调整，需要改造播放 URL 解析和播放器 options 的传递链路，复杂度较高。建议先采用保守固定策略，例如 `minBufferMs = 30_000`、`maxBufferMs = 90_000 或 120_000`、`bufferForPlaybackMs = 1_500`、`bufferForPlaybackAfterRebufferMs = 3_000`。

如果 Smart Highest Quality 同时开启，建议联动降低 `maxBufferMs`，例如封顶 60_000 或 90_000，避免 4K/杜比高码率与超长缓冲叠加导致内存压力过高。

### 5. Tunneling

Tunneling 的方向可以保留，但必须最后落地，并且默认关闭。它不是通用优化开关，而是强依赖设备能力的硬件路径。部分设备即使声明支持，也可能在特定 codec、profile、分辨率、音频组合下失败。

实现前应先确认当前 Media3 版本提供的官方接入方式，优先使用 TrackSelector 或 ExoPlayer 支持的参数，不建议通过手写 `MediaCodecSelector` 强行 hack。最低限度也要做能力探测：Android 版本、系统是否声明 tunneled playback feature、是否存在 tunneled decoder。探测失败时不应启用，并应输出日志。

不要承诺 ExoPlayer 一定能从 Tunneling 失败自动回退到普通 surface 播放。很多盒子的失败模式并不优雅，可能直接黑屏、无声或初始化失败。因此 Tunneling 应作为高级实验选项，供用户在真机上自行验证。

### 6. MediaCodec High Priority

MediaCodec priority 的风险比收益更不确定。`KEY_PRIORITY = 0` 只是系统 hint，不是强制保证，OEM 可以忽略，也可能因为实现差异触发异常路径。有些设备设置 priority 后反而更保守，甚至可能导致硬解 fallback 到软解。

因此第一轮不建议真正实现底层 priority 注入。可以先保留开关和日志，等 DynamicLoadControl、Smart GC、Smart Highest Quality 稳定后，再单独分支验证。若后续实现，也应通过 Media3 官方扩展点或安全回调接入，不应在 ExoPlayer 管理 codec 生命周期之外直接抢先调用 `MediaCodec.setParameters`。

## 推荐落地顺序

建议按以下顺序实施。

第一步，新增 Prefs 和高级设置页。添加 5 个开关，默认值采用保守策略：只默认开启 DynamicLoadControl，其余默认关闭。新增“极致优化”设置页面，并为高风险开关标注实验性质。

第二步，扩展 `VideoPlayerOptions`。新增 `enableDynamicLoadControl`、`enableTunneling`、`enableMediaCodecHighPriority` 三个字段，并在 `VideoPlayerV3ViewModel.initVideoPlayer()` 中从 Prefs 透传。Smart GC 和 Smart Highest Quality 保持在 app 层处理，不放入播放器模块。

第三步，实现 DynamicLoadControl。修改 `ExoMediaPlayer.initPlayer()`，在 `ExoPlayer.Builder` 上设置 `DefaultLoadControl`。第一版采用固定缓冲参数，不做复杂动态码率估算。开启时打印日志，标明 min/max buffer 和 targetBufferBytes 策略。

第四步，实现 Smart GC。放在 `VideoPlayerV3ViewModel.initVideoPlayer()` 中、创建播放器之前。加阈值、节流和日志。默认关闭。

第五步，实现 Smart Highest Quality。修改目标画质选择逻辑，开启时从服务端返回的 `availableQualities` 中选最高档。ViewModel 层拦截手动清晰度变更，UI 层再做禁用或提示。

第六步，补可观测性。扩展 `ExoMediaPlayer.debugInfo` 或新增“当前生效配置”面板，展示 Media3 版本、当前分辨率、codec、renderer、DynamicLoadControl 状态、Smart Highest Quality 状态、Smart GC 最近触发状态、Tunneling 请求/实际状态等。没有这些信息，用户反馈“卡”或“黑屏”时很难定位。

第七步，最后再做 Tunneling 和 MediaCodec priority。两者默认关闭，单独能力探测、单独日志、单独真机验证。不要和前面几个优化一次性混合提交，否则出现回归时很难定位根因。

## 建议的最小安全实现范围

第一轮最建议实现的范围是：Prefs + AdvancedSetting 页面、`VideoPlayerOptions` 透传、DynamicLoadControl、Smart GC、Smart Highest Quality。Tunneling 和 MediaCodec priority 先只保留开关、说明和日志，不做底层危险实现。

这样可以先获得较稳定的收益：网络波动下缓冲更稳、用户可选起播前内存整理、智能最高画质行为更符合预期；同时避免把最容易导致设备兼容性问题的 Tunneling 和 priority 默认带入播放链路。

## 一句话结论

`stats.md` 的方向正确，但默认全开过于激进；`mimax.md` 的风险评审更适合作为落地依据。建议先做安全、可观测、可回退的第一轮实现：默认只开 DynamicLoadControl，Smart GC 和 Smart Highest Quality 默认关闭但可用，Tunneling 和 MediaCodec priority 延后到真机验证阶段。