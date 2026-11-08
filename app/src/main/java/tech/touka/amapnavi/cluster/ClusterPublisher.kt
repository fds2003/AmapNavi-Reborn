package tech.touka.amapnavi.cluster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.touka.amapnavi.core.model.NaviState

/**
 * 仪表发布器：状态机 + 去重 + 节流 + 保活 + 交还。
 *
 * 纯逻辑，不持有 Context、不启动线程；生产环境由 ClusterRuntime 按 100ms 调 [tick]。
 */
class ClusterPublisher(
    private val transport: ClusterTransport,
    private val settings: ClusterSettings,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /**
         * 导航数据超时。
         *
         * 高德 10001 是事件驱动，实车观察到导航中也会出现 4~5 秒的空档；
         * 原本 3 秒会在空档期把仪表交还、下一条广播再接管，造成反复闪烁。
         * 取 8 秒：既覆盖正常空档，又能在导航真正中断时及时交还。
         */
        private const val TIMEOUT_MS = 8_000L

        /**
         * 罗盘模式（地图态、无导航）的数据超时。
         *
         * 高德未导航时只发 10001 的 CAR_DIRECTION，且是事件驱动（十几秒一条）；
         * 罗盘仅是显示用途、不参与控车，不能用导航的 3 秒看门狗，否则会反复
         * 交还/接管，导致仪表外圈反复重绘。
         */
        private const val COMPASS_TIMEOUT_MS = 30_000L

        /** HSAE 罗盘通道 displayType。 */
        private const val DISPLAY_TYPE_COMPASS = 8

        private const val TBT_MIN_INTERVAL_MS = 500L
        private const val TBT_KEEPALIVE_MS = 2_000L
        private const val SPEED_MIN_INTERVAL_MS = 1_000L
        private const val EST_MIN_INTERVAL_MS = 1_000L
        private const val EST_KEEPALIVE_MS = 10_000L
        private const val TEXT_MIN_INTERVAL_MS = 1_000L
    }

    private val _status = MutableStateFlow(ClusterStatus())
    val status: StateFlow<ClusterStatus> = _status

    /** R-2：navi 与到达时间合并为单一不可变快照，单次引用赋值原子，消除跨线程组合读撕裂。 */
    private data class NavSnapshot(val navi: NaviState, val receivedAt: Long)

    @Volatile
    private var navSnapshot = NavSnapshot(NaviState(), 0L)

    private var state = ClusterState.RELEASED

    private var lastTbt: TbtFrame? = null
    private var lastSpeed: SpeedFrame? = null
    private var lastEst: EstFrame? = null
    private var lastText: TextFrame? = null

    private var lastTbtAt = 0L
    private var lastSpeedAt = 0L
    private var lastEstAt = 0L
    private var lastTextAt = 0L
    private var lastSentSpeed = 0
    private var lastIdleLogAt = 0L

    /**
     * 诊断回调（默认 null，生产零开销、JVM 单测不受影响）。
     * 由宿主注入后，每次真正下发帧时回调通道/帧内容/原因，用于定位仪表重绘闪动。
     */
    @JvmField
    var debugSink: ((String) -> Unit)? = null

    private fun dbg(channel: String, detail: String) {
        val sink = debugSink ?: return
        try {
            sink("[$channel] $detail")
        } catch (_: Throwable) {
            // 诊断不得影响发送链路
        }
    }

    fun onNaviState(navi: NaviState, nowMs: Long = clock()) {
        val prev = navSnapshot.navi
        navSnapshot = NavSnapshot(navi, nowMs)   // 单次引用赋值是原子的
        // 仅在关键字段变化时输出，避免高频刷屏
        if (prev.navValid != navi.navValid || prev.tbtTurnType != navi.tbtTurnType ||
            prev.carDirection != navi.carDirection || prev.roadSpeedLimit != navi.roadSpeedLimit
        ) {
            dbg("NAVI", "navValid=${navi.navValid} tbtIcon=${navi.tbtTurnType} tbtDist=${navi.tbtDistance} dir=${navi.carDirection} limit=${navi.roadSpeedLimit}")
        }
    }

    fun tick(now: Long = clock()) {
        if (!settings.enabled) {
            if (now - lastIdleLogAt >= 5_000L) {
                lastIdleLogAt = now
                dbg("IDLE", "总开关关闭(settings.enabled=false)，仪表通道不发帧")
            }
            if (state != ClusterState.RELEASED) release(now)
            return
        }

        val snap = navSnapshot   // 读一次快照，后续全用 snap（navi 与 receivedAt 组合一致）
        // 罗盘模式数据超时放宽：地图态航向（车机 GPS 兜底）事件驱动、稀疏，且不参与控车
        val compassHold = !snap.navi.navValid || lastTbt?.displayType == DISPLAY_TYPE_COMPASS
        val timeoutMs = if (compassHold) COMPASS_TIMEOUT_MS else TIMEOUT_MS
        // 有航向即可进入罗盘模式（未导航时航向来自车机 GPS）
        val hasHeading = snap.navi.carDirection in 0..360
        val live = snap.navi.navValid || hasHeading
        if (!live || now - snap.receivedAt > timeoutMs) {
            // 诊断心跳：每约 5 秒打一次"为何不接管"，避免静止时刷屏
            if (now - lastIdleLogAt >= 5_000L) {
                lastIdleLogAt = now
                dbg("IDLE", "enabled=${settings.enabled} navValid=${snap.navi.navValid} age=${now - snap.receivedAt}ms timeout=$timeoutMs state=$state")
            }
            if (state != ClusterState.RELEASED) {
                dbg("RELEASE", "navValid=${snap.navi.navValid} age=${now - snap.receivedAt}ms")
                release(now)
            }
            return
        }

        if (state != ClusterState.ACTIVE) {
            dbg("ACTIVE", "navValid=true age=${now - snap.receivedAt}ms 开始接管仪表")
            state = ClusterState.ACTIVE
        }

        val frame = try {
            ClusterMapper.toFrame(
                snap.navi, now, settings.etaMode, settings.tlEnabled, settings.experimentalIcons
            )
        } catch (t: Throwable) {
            fail("map failed: ${t.message}")
            return
        }

        sendTbtIfNeeded(frame.tbt, now)
        sendSpeedIfNeeded(frame.speed, now)
        sendEstIfNeeded(frame.est, now)
        sendTextIfNeeded(frame.text, now)
        publishState(now)
    }

    fun release(now: Long = clock()) {
        if (state == ClusterState.RELEASED) return
        state = ClusterState.RELEASING
        val ok = listOf(
            transport.sendTbt(TbtFrame(3, 0, 0, 0, 0)),
            transport.sendTbt(TbtFrame(8, 0, 0, 0, 0)),
            transport.sendSpeed(SpeedFrame(0)),
            transport.sendEst(EstFrame(0, 0, 0, 0, 0, 1)),
            transport.sendText(TextFrame(""))
        ).all { it }
        if (!ok) fail("release failed")
        state = ClusterState.RELEASED
        lastTbt = null
        lastSpeed = null
        lastEst = null
        lastText = null
        lastSentSpeed = 0
        publishState(now)
    }

    fun sendTestFrame(now: Long = clock()) {
        val ok = listOf(
            transport.sendTbt(TbtFrame(65, 0, 1, 1, 5)),
            transport.sendSpeed(SpeedFrame(80)),
            transport.sendEst(EstFrame(5, 1, 0, 30, 1, 2)),
            transport.sendText(TextFrame("测试路名"))
        ).all { it }
        if (ok) lastSentAt(now) else fail("test frame failed")
        publishState(now)
    }

    /** 标定用：直接发一个指定 displayType 的 TBT 帧，不经过图标映射/开关。 */
    fun sendRawTbt(frame: TbtFrame, now: Long = clock()): Boolean {
        val ok = transport.sendTbt(frame)
        if (ok) {
            lastTbt = frame
            lastTbtAt = now
            lastSentAt(now)
        } else {
            fail("calibration send failed")
        }
        publishState(now)
        return ok
    }

    private fun sendTbtIfNeeded(frame: TbtFrame, now: Long) {
        if (!settings.tbtEnabled) return
        val changed = frame != lastTbt
        // 罗盘帧不做保活：航向静止时重发只会让 HSAE 仪表反复重绘外圈（圆圈闪烁）
        val keepalive = frame.displayType != DISPLAY_TYPE_COMPASS &&
            now - lastTbtAt >= TBT_KEEPALIVE_MS
        if ((changed || keepalive) && now - lastTbtAt >= TBT_MIN_INTERVAL_MS) {
            if (transport.sendTbt(frame)) {
                dbg("TBT", "type=${frame.displayType} dir=${frame.direction} dist=${frame.distVal} reason=${if (changed) "changed" else "keepalive"}")
                lastTbt = frame
                lastTbtAt = now
                lastSentAt(now)
            } else {
                fail("tbt send failed")
            }
        }
    }

    private fun sendSpeedIfNeeded(frame: SpeedFrame, now: Long) {
        if (!settings.speedEnabled) return
        if (now - lastSpeedAt < SPEED_MIN_INTERVAL_MS) return
        val clamped = ClusterMapper.clampSpeed(lastSentSpeed, frame.kph, now - lastSpeedAt)
        if (lastSpeed != null && clamped == lastSentSpeed) return
        if (transport.sendSpeed(SpeedFrame(clamped))) {
            dbg("SPEED", "kph=$clamped prev=$lastSentSpeed")
            lastSpeed = SpeedFrame(clamped)
            lastSentSpeed = clamped
            lastSpeedAt = now
            lastSentAt(now)
        } else {
            fail("speed send failed")
        }
    }

    private fun sendEstIfNeeded(frame: EstFrame, now: Long) {
        if (!settings.estEnabled) return
        val changed = frame != lastEst
        val keepalive = now - lastEstAt >= EST_KEEPALIVE_MS
        if ((changed || keepalive) && now - lastEstAt >= EST_MIN_INTERVAL_MS) {
            if (transport.sendEst(frame)) {
                dbg("EST", "dist=${frame.distVal} unit=${frame.unit} time=${frame.hour}:${frame.minute} reason=${if (changed) "changed" else "keepalive"}")
                lastEst = frame
                lastEstAt = now
                lastSentAt(now)
            } else {
                fail("est send failed")
            }
        }
    }

    private fun sendTextIfNeeded(frame: TextFrame, now: Long) {
        if (!settings.textEnabled) return
        if (frame != lastText && now - lastTextAt >= TEXT_MIN_INTERVAL_MS) {
            if (transport.sendText(frame)) {
                dbg("TEXT", "'${frame.text}'")
                lastText = frame
                lastTextAt = now
                lastSentAt(now)
            } else {
                fail("text send failed")
            }
        }
    }

    private fun lastSentAt(now: Long) {
        _status.value = _status.value.copy(lastError = null, lastSentAt = now)
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(lastError = message)
    }

    private fun publishState(now: Long) {
        _status.value = _status.value.copy(
            state = state,
            lastTbt = lastTbt?.let { "type=${it.displayType}" } ?: _status.value.lastTbt,
            lastText = lastText?.text ?: _status.value.lastText
        )
    }
}
