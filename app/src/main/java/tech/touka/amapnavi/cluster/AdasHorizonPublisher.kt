package tech.touka.amapnavi.cluster

import tech.touka.amapnavi.core.model.NaviState

/**
 * ADAS 电子地平线发布器（transact 14）。
 *
 * 纯逻辑，不持有 Context、不启动线程；由 [ClusterRuntime] 按 100ms 调 [tick]。
 *
 * 数据源：[NaviState] 真实字段（车道建议、航向、限速、TBT 距离）。
 * 发送规则：
 * - Position（458）：100ms 周期随航向/车道变化重发；
 * - Segment（202）：限速、TBT 距离、道路属性变化时重发（去重）。
 *
 * 受 [ClusterSettings.adasEnabled] 门控，默认关闭。
 * 未标定的路线编号（field1）、道路等级（field3）等用 0，实车标定后填入。
 */
class AdasHorizonPublisher(
    private val transport: ClusterTransport,
    private val settings: ClusterSettings,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** 航向量化步长（6°/格，byte2 低 6 位）。 */
        const val HEADING_STEP_DEG = 6

        /** 限速编码步长（5km/h/码，getSpeedLimitbyFormat 还原）。 */
        const val SPEED_LIMIT_CODE_STEP = 5

        /** Segment 段 ID 默认值（单段路线，0 表示当前段）。 */
        const val DEFAULT_SEGMENT_ID = 0
    }

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var latestNavi = NaviState()

    private var lastPositionSent = false
    private var lastSegment: ByteArray? = null
    private var lastSegLimit = 0
    private var lastSegTbtDist = 0
    private var lastCurve: ByteArray? = null

    /** Meta 消息序号（byte0[0..1]，2 位，0..3 循环）。 */
    private var metaCounter = 0

    fun onNaviState(navi: NaviState, nowMs: Long = clock()) {
        latestNavi = navi
    }

    fun tick(now: Long = clock()) {
        if (!settings.adasEnabled) return
        val navi = latestNavi
        if (!navi.navValid) {
            lastSegment = null
            lastCurve = null
            lastPositionSent = false
            return
        }

        val metaOk = sendMeta()
        val positionOk = sendPosition(navi)
        val segmentOk = sendSegment(navi)
        val curveOk = sendCurve(navi)
        if (!metaOk || !positionOk || !segmentOk || !curveOk) {
            lastError = "adas send failed"
        } else {
            lastError = null
        }
    }

    /**
     * 构造 Meta（459）前导帧：消息序号 + 帧类型标志。
     * 序号随发送计数循环（2 位），标志固定为“常规周期帧”。
     */
    fun buildMeta(): ByteArray {
        val fields = MetaFields(
            field0 = metaCounter,                // byte0[0..1] 消息序号
            field2 = 2                          // byte0[5..7] 帧类型标志（常规周期帧）
        )
        return AdasPayloadCodec.buildMeta(fields)
    }

    /** 构造并发送 Position 帧；车道号/航向来自真实数据。 */
    fun buildPosition(navi: NaviState): ByteArray {
        val lane = navi.suggestLanes.size.coerceAtMost(31)
        val heading = navi.carDirection.let { if (it in 0..360) it / HEADING_STEP_DEG else 0 }
        val fields = PositionFields(
            field0 = lane,          // byte0[0..4] 车道号
            field3 = heading,      // byte2[0..5] 航向（6°/格）
            field8 = 0             // byte4 高程（未知，0）
        )
        return AdasPayloadCodec.buildPosition(fields)
    }

    /** 构造并发送 Segment 帧；限速/段 ID 来自真实数据。 */
    fun buildSegment(navi: NaviState): ByteArray {
        val limitCode = navi.roadSpeedLimit / SPEED_LIMIT_CODE_STEP
        val fields = SegmentFields(
            field1 = 2,                                    // 消息类型 = segment（byte0[5..7]）
            field2 = DEFAULT_SEGMENT_ID,                   // 段 ID/偏移（byte1）
            field6 = limitCode.coerceAtMost(0x1F)          // 限速编码（5 位，byte3）
        )
        return AdasPayloadCodec.buildSegment(fields)
    }

    /**
     * 构造弯道类 ProfileShort 帧（type 204，makeCurveMsg 还原）。
     *
     * 曲线类型由 TBT 转向图标推导（左转/右转=进入弯道，掉头/环岛=强弯），
     * 曲率码由 [curvatureCode] 提供（实车接 C3 lane/曲率，当前用固定值标定）。
     * 非转弯时返回零帧（不发射）。
     */
    fun buildCurve(navi: NaviState, curvatureCode: Int = 0x10): ByteArray {
        val curveType = when (navi.tbtTurnType) {
            3, 4 -> 1          // 左转 / 左前
            5, 6 -> 2          // 右转 / 右前
            7 -> 3             // 掉头
            9 -> 4             // 环岛入口
            else -> 0          // 直行，非弯道
        }
        if (curveType == 0) return AdasPayloadCodec.buildProfileShortCurve()
        return AdasPayloadCodec.buildProfileShortCurve(
            curveType = curveType,
            curvature = curvatureCode
        )
    }

    private fun sendMeta(): Boolean {
        val ok = transport.sendAdas(buildMeta(), AdasPayloadCodec.TYPE_META)
        if (ok) {
            metaCounter = (metaCounter + 1) % 4
        }
        return ok
    }

    private fun sendPosition(navi: NaviState): Boolean {
        val payload = buildPosition(navi)
        lastPositionSent = transport.sendAdas(payload, AdasPayloadCodec.TYPE_POSITION)
        return lastPositionSent
    }

    private fun sendSegment(navi: NaviState): Boolean {
        val payload = buildSegment(navi)
        val changed = payload != lastSegment || navi.roadSpeedLimit != lastSegLimit ||
            navi.tbtDistance != lastSegTbtDist
        if (!changed) return true
        val ok = transport.sendAdas(payload, AdasPayloadCodec.TYPE_SEGMENT)
        if (ok) {
            lastSegment = payload
            lastSegLimit = navi.roadSpeedLimit
            lastSegTbtDist = navi.tbtDistance
        }
        return ok
    }

    private fun sendCurve(navi: NaviState): Boolean {
        // 仅当处于转弯时才发射 ProfileShort 弯道帧，去重避免刷屏
        val payload = buildCurve(navi)
        val changed = payload != lastCurve
        if (!changed) return true
        val ok = transport.sendAdas(payload, AdasPayloadCodec.TYPE_PROFILE_SHORT)
        if (ok) lastCurve = payload
        return ok
    }
}
