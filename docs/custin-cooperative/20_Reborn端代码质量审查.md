# 20_Reborn 端代码质量深度审查

> 基线：2026-11-08
> 审查范围：`app/src/main/java/tech/touka/amapnavi/` 全部 Kotlin 源文件（28 个）
> 结论以 HEAD 代码为准；所有问题均标注文件与行号。
>
> **修复进度（2026-11-08 同步）**：R-2 / R-4 / R-5 / R-6 / R-7 / R-8 已按 TDD 修复并通过
> 全量 171 项 JVM 单测 + `assembleDebug` 构建（Reborn 仓库 `main` 分支，commit 9b4f959）。
> 测试：`LaneWayParserTest` 新增 R-8 多推荐车道用例；`ClusterPublisherTest` 新增
> R-2 快照读一致性 + 过期帧负向对照。R-1 / R-9 / R-10 / R-3 维持「规划/标定后」档。

---

## 0. 评分总表

| 维度 | 评分 | 说明 |
|---|:---:|---|
| **架构分层** | ⭐⭐⭐⭐⭐ | Clean Architecture 三层落地彻底，零循环依赖 |
| **不可变数据流** | ⭐⭐⭐⭐⭐ | 全链路 `data class` + `StateFlow`，无共享可变状态 |
| **线程安全** | ⭐⭐⭐⭐☆ | Coroutine 化彻底；`ClusterPublisher` 存在跨线程字段访问 |
| **可测试性** | ⭐⭐⭐⭐☆ | 核心逻辑可注入时钟，可离线单测；设置页无法自动化测试 |
| **可维护性** | ⭐⭐⭐☆☆ | 设置页两套重复实现；硬编码字符串散落；Magic Number 未命名 |
| **性能** | ⭐⭐⭐⭐⭐ | Zero-Bitmap、零对象分配扫描器、ViewStub 惰性加载全部落地 |
| **安全性** | ⭐⭐⭐⭐☆ | UDP 白名单学习正确；单次 Binder 权限拒绝后无重试窗口 |
| **错误处理** | ⭐⭐⭐☆☆ | Binder 层吞 Throwable 细节；设置页数值无范围校验 |

**综合结论**：架构与性能是亮点，对 Android 4.4 / Dalvik 约束处理专业。主要债务集中在**设置页重复代码**和**若干与决策脱钩的参数 KEY**，不影响核心安全链路，属于可计划偿还的技术债。

---

## 1. 亮点（不需改动）

### 1.1 不可变数据流 — `CarState` / `NaviState` / `OvertakeStatus`

```kotlin
// CarState.kt — data class，所有字段 val，isTelemetryFresh 是纯函数
data class CarState(
    val isOnroad: Boolean = false,
    val vEgo: Int = 0,
    ...
    val timestamp: Long = 0L
) {
    fun isTelemetryFresh(nowMs: Long, timeoutMs: Long): Boolean =
        timestamp > 0L && nowMs - timestamp <= timeoutMs
}
```

整条数据流 `UDP → CarrotTelemetryParser.parse() → CarState → StateFlow → OvertakeStateMachine.tick()` 全部不可变，跨线程安全，无锁争用。

### 1.2 `OvertakeStateMachine` — 7 态完整状态机，可注入时钟

```kotlin
// OvertakeStateMachine.kt:31
private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
```

注入时钟使状态机可以在纯 JVM 单测中精确控制时间，`TelemetryWatchdog` 同样如此。

### 1.3 `CarrotTelemetryParser` — 零对象分配扫描器

```kotlin
// 逐字符累加，不用 substring / toXxx，消灭 Dalvik GC_FOR_ALLOC
private fun scanFloat(s: String, key: String, defVal: Float): Float { ... }
```

在 20Hz 高频 UDP 路径上每帧只分配 1 个 `CarState`，彻底规避 Dalvik 全局停顿。

### 1.4 `ClusterRuntime` — `Executors.newSingleThreadScheduledExecutor`

```kotlin
// ClusterRuntime.kt:25-27
val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "cluster-tick").apply { isDaemon = true }
}
```

所有 Binder 调用都在 `cluster-tick` 单线程，彻底避免 Binder 多线程死锁。`stop()` 把 `release()` 通过 `scheduler.submit { }.get(300ms)` 提交到同一线程执行，确保资源正确归还。

### 1.5 `HsaeClusterTransport` — `Parcel` 的 finally 回收

```kotlin
// HsaeClusterTransport.kt:125-127
} finally {
    reply.recycle()
    data.recycle()
}
```

每次 Binder 事务无论成败都回收 Parcel，不泄漏。

---

## 2. 问题清单

### R-1 — 设置页两套重复实现（`SettingActivity` + `SettingViewController`）

**位置**

```
ui/setting/SettingActivity.kt        423 行
ui/setting/SettingViewController.kt  399 行
```

**问题**

两个类实现几乎完全相同的三个 Tab（通用/领航/超车）：
- `initGeneralTab()` 在 `SettingActivity` 和 `SettingViewController` 中各写一份，逐行相同（约 150 行）
- `bindStepField()` 在两个文件中各实现一次，逻辑完全一致（18 行）
- `initNavTab()` / `initOvertakeTab()` 同上（各约 70 行）
- 保存按钮的回调（`btn_save_nav`、`btn_save_overtake`）在两个类中各绑定一次

`SettingActivity` 是通过独立 `Activity` 进入的旧路径，`SettingViewController` 是 In-Window SAA 架构的新路径（内嵌 `ViewStub`）。两套实现并存导致：修改任何设置控件行为，必须同步改两处，否则两种进入方式结果不一致。

**方向**

```
SettingActivity 应只做壳：
  override fun onCreate() {
      setContentView(R.layout.activity_setting)
      SettingViewController(
          activity = this,
          rootView = window.decorView,
          repo = UserSettingsRepository(this),
          onCloseRequested = { finish() }
      )
  }
```

统一使用 `SettingViewController`，`SettingActivity` 退化为纯容器，彻底删除 `SettingActivity` 中的所有 `init*Tab()` 副本。

---

### R-2 — `ClusterPublisher` 跨线程字段访问

**位置**

```kotlin
// ClusterPublisher.kt:31-35
@Volatile
private var latestNavi = NaviState()   // 由协程写（Dispatchers.Default）

@Volatile
private var lastNavAt = 0L
```

```kotlin
// ClusterPubltime.kt:50-52  — 协程线程写
fun onNaviState(navi: NaviState, nowMs: Long = clock()) {
    latestNavi = navi        // ← 写
    lastNavAt = nowMs        // ← 写
}

// ClusterPublisher.kt:61-62  — cluster-tick 线程读
val navi = latestNavi        // ← 读
if (!navi.navValid || now - lastNavAt > TIMEOUT_MS) {
```

`onNaviState` 在 `Dispatchers.Default` 协程线程写入 `latestNavi` 和 `lastNavAt`，`tick()` 在 `cluster-tick` 线程读取两者。两次写入不是原子操作：可能出现 `latestNavi` 更新了但 `lastNavAt` 还是旧值，导致 `now - lastNavAt > TIMEOUT_MS` 误判超时，进而触发不必要的 `release()`。

`@Volatile` 保证单个字段可见性，但不保证两个字段的组合读取一致性。

**修复方向**

把两个字段合并为单个原子快照：

```kotlin
// 合并为一个不可变快照
private data class NavSnapshot(val navi: NaviState, val receivedAt: Long)

@Volatile
private var navSnapshot = NavSnapshot(NaviState(), 0L)

fun onNaviState(navi: NaviState, nowMs: Long = clock()) {
    navSnapshot = NavSnapshot(navi, nowMs)   // 单次引用赋值是原子的
}

fun tick(now: Long = clock()) {
    val snap = navSnapshot   // 读一次，后续全用 snap
    if (!snap.navi.navValid || now - snap.receivedAt > TIMEOUT_MS) { ... }
}
```

> ✅ 状态：已修复。`latestNavi` / `lastNavAt` 两个 `@Volatile` 字段合并为
> `NavSnapshot(navi, receivedAt)`，`onNaviState` 单次引用赋值，`tick()` 读一次快照后用
> `snap`。`ClusterPublisherTest` 新增「快照读：tick 内 navi 与到达时间来自同一原子快照」
> + 「过期帧仍按快照内时间戳判超时」两个契约用例。

---

### R-3 — `AdasPayloadCodec` 字段全用 `fieldN` 无语义名

**位置**

```kotlin
// AdasPayloadCodec.kt:10-25
data class PositionFields(
    val field0: Int = 0,
    val field1: Int = 0,   // 注释：路线编号
    val field2: Int = 0,
    ...
    val field13: Int = 0
)
```

5 个 `*Fields` 数据类共 60+ 个字段全部命名为 `fieldN`。注释里写了部分语义（`field1` = 路线编号，`field3` = 时间戳高 8 位等），但调用点代码如：

```kotlin
AdasPayloadCodec.buildPosition(PositionFields(field0 = laneId, field3 = tsHigh))
```

阅读时必须翻文件头注释才能理解每个位的含义，且注释与字段可能漂移不同步。

**说明**：这是逆向工程的合理起点，`17_未完成工作` 中有标注"实车标定后回填语义名"。**标定完成后必须用语义名替换 `fieldN`**，否则该文件永远无法在不看 Ghidra 文档的情况下维护。

---

### R-4 — `UserSettingsRepository` 存在裸字符串 KEY

**位置**

```kotlin
// SettingActivity.kt:119 和 SettingViewController.kt:101
cbVideoShowFast?.isChecked = repo.getBoolean("video_show_fast", false)
cbLaneColor?.isChecked     = repo.getBoolean("lane_color", true)
```

```kotlin
// SettingActivity.kt:155 和 SettingViewController.kt:134
val etLastLaneSuppress = bindStepField(..., "last_lane_suppress_s", ...)
val etNewLaneAppear    = bindStepField(..., "new_lane_appear_s", ...)
```

这 4 个 KEY（`"video_show_fast"`、`"lane_color"`、`"last_lane_suppress_s"`、`"new_lane_appear_s"`）直接用裸字符串字面量，没有在 `UserSettingsRepository.companion` 中定义常量。两个文件各写了一遍，拼写错误无法编译期发现，重命名需要全局搜索。

**修复**：在 `UserSettingsRepository.companion` 补齐 4 个常量，调用方改为引用常量。

> ✅ 状态：已修复。`UserSettingsRepository.companion` 新增 `KEY_VIDEO_SHOW_FAST` /
> `KEY_LANE_COLOR` / `KEY_LAST_LANE_SUPPRESS_S` / `KEY_NEW_LANE_APPEAR_S`，
> `SettingActivity` 与 `SettingViewController` 两文件共 12 处裸字符串改引用常量
> （4 个 KEY 各 2 处 get + 部分 set 路径），默认值保持原样。

---

### R-5 — 设置页数值无输入范围校验

**位置**

```kotlin
// SettingActivity.kt:265-268 / SettingViewController.kt:241-245
private fun saveIntFromEditText(et: EditText?, key: String, defVal: Int) {
    if (et == null) return
    val v = et.text.toString().toIntOrNull() ?: defVal
    repo.setInt(key, v)   // ← 直接写入，无范围检查
}
```

`bindStepField` 通过按钮步进保证范围，但 `EditText` 允许用户直接键盘输入。用户手动输入 `9999` 存入 `KEY_MAX_SPEED_LIMIT`，或输入负值存入 `KEY_MIN_SPEED_LIMIT`，会绕过所有步进限制，直接持久化异常值影响行车安全（限速计算）。

**修复**

```kotlin
private fun saveIntFromEditText(et: EditText?, key: String, defVal: Int,
                                min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) {
    if (et == null) return
    val v = (et.text.toString().toIntOrNull() ?: defVal).coerceIn(min, max)
    repo.setInt(key, v)
}
```

调用方传入与步进按钮一致的 `min`/`max`。

> ✅ 状态：已修复。`SettingActivity` / `SettingViewController` 两处 `saveIntFromEditText`
> 均加 `min`/`max` 参数并 `coerceIn`，通用 Tab 全部 16 个字段调用方传入与对应
> `bindStepField` 一致的 min/max（如 `etMaxSpeed` 60..150、`etLaneInterval` 50..1000）。

---

### R-6 — `SettingViewController.initOvertakeTab()` 保存时不更新 OvertakeSettings 缓存

**位置**

```kotlin
// SettingViewController.kt:390-392
repo.setFloat(UserSettingsRepository.KEY_REAR_THRESH_VAL, rearThresh)
repo.setFloat(UserSettingsRepository.KEY_REAR_VREL_TIME, vrelTime)
repo.setInt(UserSettingsRepository.KEY_REVERSE_COOLDOWN_S, cooldownS)
```

```kotlin
// UserSettingsRepository.kt:123-126
val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == KEY_OVERTAKE_ENABLED || key == KEY_REVERSE_COOLDOWN_S || key == "allow_right_overtake") {
        cachedOvertakeSettings = loadOvertakeSettings()   // 仅监听 3 个 KEY
    }
}
```

`KEY_REAR_THRESH_VAL` 和 `KEY_REAR_VREL_TIME` 写入 SharedPreferences 后，`prefListener` 不监听这两个 KEY，`cachedOvertakeSettings` 不更新。用户保存后必须重启 App 才能让新阈值生效（即使 `loadOvertakeSettings()` 已经按 GAP-1 方案扩展了读取）。

**修复**：在 `prefListener` 的监听列表中加入所有超车参数相关 KEY，或改为在 `setFloat()` 末尾直接刷新缓存：

```kotlin
fun setFloat(key: String, value: Float) {
    prefs.edit().putFloat(key, value).apply()
    if (key.startsWith("lidar_") || key.startsWith("overtake_")) {
        cachedOvertakeSettings = loadOvertakeSettings()
    }
}
```

> ✅ 状态：已修复（精确 KEY 版）。`setFloat` 末尾对 4 个雷达阈值 KEY
> （`KEY_REAR_THRESH_VAL` / `KEY_REAR_VREL_TIME` / `KEY_FRONT_THRESH_VAL` /
> `KEY_FRONT_VREL_TIME`）刷新 `cachedOvertakeSettings`；`prefListener` 原监听不动
> （双保险）。未用 `startsWith("lidar_")` 宽匹配，避免误触发非超车参数 KEY。

---

### R-7 — `KeepAliveService.onStartCommand` 重复调用 `start()`

**位置**

```kotlin
// KeepAliveService.kt:28-38
override fun onCreate() {
    ...
    RelayEngine.get(this).start()   // 第一次
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification())
    RelayEngine.get(this).start()   // 第二次（系统重启服务时）
    return START_STICKY
}
```

`RelayEngine.start()` 有幂等保护（`if (running) return`），所以不会双重启动，但注册广播接收器的代码在两处都会走到 `try { appContext.registerReceiver(...) } catch`。Android 4.4 重复注册同一 `BroadcastReceiver` 实例会抛 `IllegalArgumentException`，当前用 `catch` 吞掉，行为正确但掩盖了意图。

**修复**：`onStartCommand` 只调用 `startForeground`，不重复 `start()`：

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification())
    return START_STICKY   // RelayEngine 已在 onCreate 启动
}
```

> ✅ 状态：已修复。`onStartCommand` 仅保留 `startForeground` + `START_STICKY`，
> 删除重复 `RelayEngine.start()`，注释说明「RelayEngine 已在 onCreate 启动，避免
> Android 4.4 重复注册 BroadcastReceiver 被 catch 掩盖」。

---

### R-8 — `LaneWayParser.targetLaneIndex` 仅取第一个推荐车道

**位置**

```kotlin
// LaneWayParser.kt:31-40
fun targetLaneIndex(direction: String): Int {
    if (suggest.isEmpty() || laneCount == 0) return -1
    val current = suggest[0]   // ← 只取第一个推荐车道
    val target = when (direction) {
        "LEFT" -> current - 1
        "RIGHT" -> current + 1
        else -> return -1
    }
    return if (target in 0 until laneCount) target else -1
}
```

高德广播可以同时推荐多个车道（如 4 车道路中推荐第 2、3 车道）。当 `suggest = [1, 2]`，自车在第 2 车道，向左超车时：`current = suggest[0] = 1`，`target = 0`（最左车道），但实际自车在第 2 车道，正确目标应是第 1 车道。

这会导致在多推荐车道场景下，超车方向判断偏移一格。

**修复**：用自车最近推荐车道（距 `suggest` 中位数最近的索引）作为基准，或在缺乏自车精确车道信息时选取中间推荐车道：

```kotlin
fun targetLaneIndex(direction: String): Int {
    if (suggest.isEmpty() || laneCount == 0) return -1
    // 取推荐车道中位数作为自车估计位置（单推荐时与原逻辑一致）
    val current = suggest[suggest.size / 2]
    val target = when (direction) {
        "LEFT" -> current - 1
        "RIGHT" -> current + 1
        else -> return -1
    }
    return if (target in 0 until laneCount) target else -1
}
```

> ✅ 状态：已修复。`suggest[0]` 改为 `suggest[suggest.size / 2]`（取中间推荐车道为
> 自车估计位置，单推荐时与旧行为一致），KDoc 补 R-8 说明。
> `LaneWayParserTest` 新增「多推荐车道取中间车道为自车估计位置（R-8）」用例：
> 4 车道 `suggest=[1,2]` 时 LEFT→1、RIGHT→3；单推荐回归不变。

---

### R-9 — Magic Number 未命名

**位置**

```kotlin
// RelayEngine.kt:53-55
private const val RAMP_PLAN_START_DISTANCE_M = 500   // ✅ 命名了

// OvertakeSettings.kt:16-17  — 以下未在设置页文档中说明含义
val executeTimeoutMs: Long = 4500L         // 变道执行超时
val driverSteerThresholdDeg: Float = 25f  // 驾驶员介入角度阈值
```

```kotlin
// RampSpeedPlanner.kt:9-11
class RampSpeedPlanner(
    private val rampTargetSpeedKmh: Int = 60,     // 匝道目标速度
    private val decelAcceleration: Float = 1.0f,  // 减速度
    private val decelStartDistanceM: Int = 500    // 开始减速距离
)
```

`RampSpeedPlanner` 的三个参数无法从外部配置，调整匝道目标速度（60 km/h）需要改源码。建议把 `rampTargetSpeedKmh` 接入设置页（领航参数 Tab 的匝道速度步进器），`RelayEngine` 构造时从 `settingsRepository` 读取。

---

### R-10 — `HsaeClusterTransport.transact` 异常后同时清空 `nav` 和 `vehicle`

**位置**

```kotlin
// HsaeClusterTransport.kt:120-123
} catch (t: Throwable) {
    lastError = "${t.javaClass.simpleName}: ${t.message}"
    nav = null       // ← 无论哪个 binder 出错，两个都清空
    vehicle = null
    false
}
```

TBT、速度、ETA 使用 `navBinder()`，文字信息使用 `vehicleBinder()`。当 `vehicle` Binder 断开时（如 `vehicleBinder().transact` 抛异常），`nav` 也会被清空，导致下次 `tick()` 重新反射获取 `nav` Binder，引入不必要的 `Class.forName` + `getMethod` 开销（约 1ms，在 100ms 周期内可接受，但不必要）。

**修复**：只清空出错的那个 Binder：

```kotlin
private fun transact(
    binder: IBinder?,
    descriptor: String,
    code: Int,
    write: (Parcel) -> Unit,
    onError: () -> Unit = {}   // 新增：指定清空哪个 binder 的回调
): Boolean {
    ...
    } catch (t: Throwable) {
        lastError = "..."
        onError()
        false
    }
}

// 调用方
override fun sendTbt(frame: TbtFrame): Boolean =
    transact(navBinder(), NAV_DESCRIPTOR, TX_TBT, onError = { nav = null }) { p ->
        HsaeFrameCodec.writeTbt(ParcelSinkAdapter(p), frame)
    }
```

---

## 3. 实施优先级

| 阶段 | 编号 | 改动量 | 风险 | 收益 | 状态（2026-11-08） |
|---|---|---|---|---|---|
| **立即** | R-4 裸字符串 KEY 补常量 | 10 行 | 零 | 编译期检查 | ✅ 已修 |
| **立即** | R-7 onStartCommand 去重 | 2 行删除 | 零 | 语义清晰 | ✅ 已修 |
| **立即** | R-5 saveIntFromEditText 加范围检查 | 5 行 | 低 | 防止异常值入库 | ✅ 已修 |
| **近期** | R-6 prefListener 监听完整 KEY 集 | 5 行 | 低 | 参数立即生效 | ✅ 已修（setFloat 末尾刷新） |
| **近期** | R-2 ClusterPublisher 快照化 | 15 行 | 低 | 消除跨线程组合读 | ✅ 已修 |
| **近期** | R-8 targetLaneIndex 中位数取法 | 3 行 | 低 | 多推荐车道场景正确 | ✅ 已修 |
| **近期** | R-10 transact 只清空出错 binder | 20 行重构 | 低 | 减少不必要的反射 | ⏸ 未做（Binder 反射路径，需实车验证） |
| **规划** | R-1 SettingActivity 退化为壳 | 删除 ~200 行 | 中（需完整回归） | 消灭重复代码 | ⏸ 未做 |
| **规划** | R-9 RampSpeedPlanner 参数接入设置页 | 30 行 | 低 | 匝道目标速度可配 | ⏸ 未做 |
| **标定后** | R-3 AdasPayloadCodec 语义名替换 | 60+ 字段重命名 | 低（机械替换） | 可维护性 | ⏸ 待实车标定 |

---

## 4. 对比 C3 端

| 维度 | Reborn（Kotlin） | C3（Python） |
|---|---|---|
| 数据不可变性 | ✅ 全链路 data class | ❌ SharedData 50+ 裸可变字段 |
| 线程安全 | ⚠️ 2 处跨线程组合读 | ⚠️ 读写锁不一致 |
| 重复代码 | ⚠️ 设置页两套实现 | ⚠️ make_*_message() 三份重复 |
| 错误处理分层 | ⚠️ Binder 层 catch Throwable | ❌ 全部 except Exception |
| 可测试性 | ✅ 核心逻辑可离线单测 | ❌ SharedData 无快照，单测 mock 成本极高 |
| 命名质量 | ⚠️ 裸字符串 KEY / fieldN | ❌ f1()/f2()，语义为零 |
| 死代码 | ✅ 极少 | ❌ ~80 处注释掉的代码块 |

Reborn 整体代码质量显著优于 C3 端，主要债务量级小且风险低，可在正常迭代中消化。
