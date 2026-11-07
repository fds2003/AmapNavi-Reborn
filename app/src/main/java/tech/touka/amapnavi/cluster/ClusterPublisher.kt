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
        private const val TIMEOUT_MS = 3_000L
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

    fun onNaviState(navi: NaviState, nowMs: Long = clock()) {
        navSnapshot = NavSnapshot(navi, nowMs)   // 单次引用赋值是原子的
    }

    fun tick(now: Long = clock()) {
        if (!settings.enabled) {
            if (state != ClusterState.RELEASED) release(now)
            return
        }

        val snap = navSnapshot   // 读一次快照，后续全用 snap（navi 与 receivedAt 组合一致）
        if (!snap.navi.navValid || now - snap.receivedAt > TIMEOUT_MS) {
            if (state != ClusterState.RELEASED) release(now)
            return
        }

        if (state != ClusterState.ACTIVE) state = ClusterState.ACTIVE

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
        val keepalive = now - lastTbtAt >= TBT_KEEPALIVE_MS
        if ((changed || keepalive) && now - lastTbtAt >= TBT_MIN_INTERVAL_MS) {
            if (transport.sendTbt(frame)) {
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
