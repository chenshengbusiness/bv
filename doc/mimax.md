# 极致性能优化方案评审（对 `doc/stats.md` 的补充建议）

> 本文档基于 `doc/stats.md` 中提出的 4 大 Feature（高级设置/智能最高画质/起播前 GC/ExoPlayer 内核调优）进行评审，**不重复** `stats.md` 已有的实施拆解，只补充**质疑点、风险点与具体解决思路**。

---

## 1. 总体评价

| 维度 | 评价 |
|------|------|
| 优化方向（4K/杜比流畅度） | ✅ 赞同，确实是 TV 客户端的核心痛点 |
| 5 个独立开关设计 | ✅ 赞同，便于在不同硬件上做 A/B 比对 |
| 「默认全开」的策略 | ⚠️ **不赞同**，Tunneling / MediaCodecPriority / SmartGc / SmartHighestQuality 建议默认关闭 |
| 缺 A/B 对比与回退工具 | ❌ 建议补一个「当前生效配置」可视化入口 |
| 跨 Feature 的副作用 | ⚠️ SmartHighestQuality + DynamicLoadControl 叠加后会放大内存压力，需联动调参 |

`stats.md` 的描述更像**实验室调参套件**，但目前 5 个开关都「默认开启」——这把实验室的激进档位直接当成稳定版发，**对老款/低端电视盒子存在回归风险**。

---

## 2. 逐 Feature 的质疑与解决思路

### Feature 1：高级设置与 Prefs 层

**赞同。** 5 个开关进 `Prefs.kt` 与现有模式一致，添加负担小。

**需要提前做的两件事**：

1. **`VideoPlayerOptions` 要先扩展字段**。当前 `VideoPlayerOptions` 只透传 `enableFfmpegAudioRenderer` / `enableSoftwareVideoDecoder`（见 `bv-player/VideoPlayerOptions.kt`），**没有 `enableTunneling` / `enableMediaCodecHighPriority` / `enableDynamicLoadControl`** 三个字段。如果 Feature 1 先开 Prefs 但 `VideoPlayerOptions` 没接，Feature 4 就没有落地处，会出现「开关存了但不生效」的隐性 bug。

2. **默认值与开关语义要一致**。当前 `Prefs` 使用 `PrefDelegate` 模式：声明时 `var foo: Boolean by PrefDelegate(booleanPreferencesKey("foo"), true)`，内存中 `MutableStateFlow` 默认就是 `true`。这与「默认开启」在语法层一致，**改默认值时直接改构造参数即可**，无需改 `init` 逻辑。

---

### Feature 2：智能最高画质

**部分赞同，但有 3 个隐患。**

#### 2a. 缺「最高支持画质」的判定逻辑

`stats.md` 写「强制请求当前账号所支持的最高 qn」，但 B 站画质体系是分档的：

- 4K 杜比视界（qn=120）
- 1080P 高码率（qn=116，仅大会员）
- 1080P 60FPS（qn=116/112）
- 1080P（qn=80，需登录）
- 720P60（qn=64）
- 默认 480P（qn=32）

`Prefs` 中 `apiType` 只区分 Web/App，**不区分大会员状态**。需要在 ViewModel 层取登录态（`AuthRepository.mid` / `biliJct` / 已有 vip 信息）来推最高 qn。

**建议补充判定逻辑**（写在 ViewModel 注释里）：

```kotlin
private fun pickHighestQn(vipType: Int, vipStatus: Int): Int = when {
    vipType == 2 && vipStatus == 1 -> 120   // 大会员 + 有效 → 4K 杜比
    vipType == 2 -> 116                      // 大会员 → 1080P 高码率
    vipStatus == 1 -> 80                     // 登录用户 → 1080P
    else -> 64                               // 未登录 → 720P60
}
```

#### 2b. 屏蔽手动画质 UI 的位置

`VideoPlayerV3ViewModel.updateMediaProfile` 在画质切换时**会重启播放**（pause → playUrl → prepare → seekTo）。如果把"屏蔽"放在 UI（`VideoMenu`）：

- ✅ 好处：实现简单
- ❌ 风险：自动续播、合集下一集等 ViewModel 内部触发画质变更时会被绕过

**建议**：

- ViewModel 端**强制覆盖** qn（不依赖 UI）
- UI 端**仅禁用**按钮 + 弹 toast「已开启智能最高画质，请前往高级设置关闭」
- `VideoMenu` 拦截到 SetQuality 时返回 false / 提示，不要影响 ViewModel 的内部状态机

#### 2c. 与 DynamicLoadControl 叠加放大了内存

SmartHighestQuality 走 4K 杜比 → 单帧可能 20MB+ 显存/缓冲；DynamicLoadControl 拉到 120s 缓冲 → 缓冲池几十 MB。**两个一起开，Feature 3 的 70% 阈值偏松**。

**建议**：联动调整。SmartGc 阈值与 DynamicLoadControl 互斥时降级到 60%；或者当 SmartHighestQuality 开启时把 LoadControl 的 `maxBufferMs` 封顶在 60s。

---

### Feature 3：起播前智能 GC

**方向赞同，但具体实现需要细化。**

#### 3a. `System.gc()` 的"建议非命令"特性

JVM 的 `System.gc()` 只是**建议**，不能保证立即触发。常见反模式：连续两次播放都进入临界区，反复调 `gc()`，实际效果是一次也没触发、还产生了无意义的 stop-the-world。

**建议**：
- 加节流：上次 GC 距今 < 30s 跳过
- 最多触发一次：`if (tried) return`，避免循环

#### 3b. 阈值指标单一

`stats.md` 给出的是 `(totalMemory - freeMemory) / maxMemory`——这是**当前已分配堆**的占比，不是设备整体压力。低负载设备 70% 是个宽松值；高负载设备 70% 也不一定真危险。

**建议加兜底条件**（二者任一满足即触发）：

```kotlin
val usage = (Runtime.getRuntime().totalMemory() - freeMemory).toDouble() / maxMemory
val freeMB = freeMemory / 1024 / 1024
val shouldGc = usage > 0.7 || (freeMB < 200 && usage > 0.6)
```

#### 3c. GC 触发的代码位置

`stats.md` 写「在 `initVideoPlayer()` 的最前线」——这是对的，但**别把 `gc()` 调进 `loadVideoWithResources()` 的 `Dispatchers.IO` 协程**。`gc()` 是 STW（stop-the-world）的，会把 IO 调度上 `loadDanmaku` / `updateSubtitle` / `updateVideoShot` / `updateVideoPages` 的并行任务一起拖慢。

**建议**：在 `initVideoPlayer()` 主线程同步触发；不 await；`ExoPlayer.Builder` 调用之前同步完成。

#### 3d. 可观测性

GC 触发了不代表一定有用。建议在日志里埋：

```kotlin
logger.fInfo { "SmartGC: usage=${"%.2f".format(usage)}, freeMB=$freeMB, triggered=$shouldGc" }
```

否则用户报"开了 GC 还是卡"时没法定位。

---

### Feature 4：ExoPlayer 内核调优

**整体赞同，但 Tuneling / MediaCodecPriority 两个开关风险高。**

#### 4a. 动态 LoadControl

赞同。TV 端网速波动比手机大，120s 缓冲 + `TargetBufferBytes` 自适应是合理方向。

**注意事项**：
- `TargetBufferBytes` 必须**按视频码率**算——`MediaItem` 拉起来后才知道。第一次开播仍走默认值，**切下一集才自适应**。这个要在 `AdvancedSetting` 页面写清楚，否则用户以为没生效。
- `DefaultLoadControl.Builder` 已经有 `setBufferDurationsMs(...)` 和 `setTargetBufferBytes(...)` 两个 API；建议在 `ExoMediaPlayer.initPlayer` 里按开关组装。

#### 4b. Tunneling（**风险点**）

赞同是赞同，**但默认开启风险高**。Tunneling 的硬件门槛：
- Android TV 8.0+（`FEATURE_TunneledPlaybackProvider`）
- 设备驱动声明支持 tunneled 解码
- 媒体格式不包含 `B-frame`（H.264 多数 OK，HEVC 复杂）

`MediaCodecUtil.getDecoderInfos(mime, false, true)` 已经能过滤 `requiresTunnelingDecoder`，但**默认开启**对低端盒子反而可能黑屏无声音（tunneled 解码器拉不起来 → ExoPlayer 不会自动回退到 surface）。

**建议**（三选一）：

1. **默认关闭**，让用户在高级设置手动开
2. 启动时能力探测：

   ```kotlin
   val tunneledDecoders = MediaCodecUtil.getDecoderInfos("video/avc", false, true)
   val supportsTunneling = tunneledDecoders.isNotEmpty() && 
                           context.packageManager.hasSystemFeature("android.hardware.tunneled_playback")
   ```

   探测失败则关闭并 toast 提示
3. ExoPlayer 自身有 `setPreferredVideoEffectsEnabled` / `setTunnelingEnabled(...)`（API 35+）——优先用 ExoPlayer 自带 API，不要手动 hack `MediaCodecSelector`

#### 4c. MediaCodec 优先级 `KEY_PRIORITY = 0`（**风险点**）

`KEY_PRIORITY` 是**提示**而不是**强制**——OEM 可以忽略。**手动覆盖**需要看清楚 ExoPlayer 的 `MediaCodecVideoRenderer.configureCodec` 回调逻辑。

**实测警告**：某型号电视盒子把 `KEY_PRIORITY` 设了之后系统把硬解 fallback 到软解（OEM 看到这个 flag 反而走了更保守的路径）。**默认开启在低端设备上风险高**。

**建议**：
- 默认关闭
- 仅在 ExoPlayer 的 `configureCodec` 回调里 `setParameters`，不要直接 `MediaCodec.setParameters` 抢在 ExoPlayer 前面

#### 4d. 进度条缩略图是另一个大头（**stats.md 没提**）

`ExoMediaPlayer` 取缩略图用 `VideoShot`，是另一条独立性能链路。之前的 commit 历史里有「大幅优化进度条缩略图显示流畅度」（`feat: 优化合集连播与选集` 之后那条），说明这块在 TV 上也卡过。

**4K 视频 + 120s 缓冲 + 缩略图缓存** 三者叠加时，缩略图缩放很吃 CPU。建议：
- Coil 取缩略图时限定 `size(200, 120)`
- 缩略图采样 `BitmapFactory.Options.inSampleSize`
- 走 `VideoShot` 的 `getImageByTimeMs` 时缓存到 LruCache，命中即跳过 IO

---

## 3. 跨 Feature 的宏观建议

### 3a. 5 个开关的默认值建议

| 开关 | 建议默认 | 理由 |
|------|----------|------|
| `enableDynamicLoadControl` | **开** | 副作用最小，TV 网速波动确实需要 |
| `enableTunneling` | **关** | 低端盒子兼容性差，需能力探测 |
| `enableMediaCodecHighPriority` | **关** | OEM 行为不可控，可能反而回退软解 |
| `enableSmartGcBeforePlay` | **关** | 默认 JVM GC 已经够用，给高级用户手动开 |
| `enableSmartHighestQuality` | **关** | 4K 杜比占带宽，与 LoadControl 叠加放大内存 |

`stats.md` 当前 5 个全默认开——这相当于「把实验室调参直接当稳定版发」。

### 3b. 缺 A/B 对比工具

`stats.md` 的 5 个开关是**叠加测试用**的，但**没有写 A/B 对比方法论**。建议：

1. **「恢复默认」按钮**：在 `AdvancedSetting.kt` 顶部，所有开关一键回到默认状态
2. **「当前生效配置」可视化**：在 `LogsActivity` 增加一个 tab 或在 `AdvancedSetting.kt` 底部加一个只读面板，显示：

   ```
   Player Type: Media3 (ExoPlayer 1.8.0)
   Decoder: OMX.qcom.video.decoder.avc (Hardware)
   Tunneling: OFF (unsupported on this device)
   LoadControl: maxBufferMs=120000, targetBufferBytes=auto
   Playback: 4K HDR (qn=120, dolby=true)
   ```

   没有可视化对比，调参就是盲调。

### 3c. 启用开关时的副作用日志

5 个开关应在 **每次变更生效点**输出 `fInfo` 日志（不是首次启动打一次）。这样报问题时能精确还原"用户开了哪些开关 → ExoPlayer 用了什么配置 → 是否回退"。

埋点建议位置：
- `ExoMediaPlayer.initPlayer`：Tunneling / Priority / LoadControl
- `VideoPlayerV3ViewModel.initVideoPlayer`：SmartGc
- `VideoPlayerV3ViewModel.fetchMediaUrls` / `resolveMediaUrls`：SmartHighestQuality

---

## 4. 落地顺序建议

按风险从低到高实施：

1. **Feature 1（Prefs + AdvancedSetting 页面）**——无副作用，纯 UI
2. **Feature 4a（DynamicLoadControl）**——默认开，纯增益
3. **Feature 3（SmartGc）**——默认关，先加节流 + 日志
4. **Feature 2（SmartHighestQuality）**——补 2a 的 vip 判定 + 2b 的 ViewModel 强制覆盖
5. **Feature 4b（4c（Tunneling + Priority）**——**最后做**，先做能力探测 + 探测失败回退 + 默认关

每做完一个 Feature，写一条 commit message 记录开关位 + 行为变更 + 已知回退路径。

---

## 5. 一句话总结

**`stats.md` 的方向对、开关粒度好，但默认全开偏激进。** 建议：

- Tunneling / Priority / SmartGc / SmartHighestQuality **默认关**
- 补 vip 判定 + 能力探测 + 「当前生效配置」可视化
- SmartHighestQuality 与 DynamicLoadControl 联动调参
- 5 个开关落地前先扩 `VideoPlayerOptions` 字段
