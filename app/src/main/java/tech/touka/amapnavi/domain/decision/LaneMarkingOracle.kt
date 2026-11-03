package tech.touka.amapnavi.domain.decision

import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.util.LaneWayParser

/**
 * 目标车道标线裁决接口（纯 Kotlin）。
 *
 * 视觉车道线推理属于可选双轨能力：Android 4.4 中继模式下由高德车道级指引裁决，
 * Android 9+ 本地 ONNX 模式下可由视觉结果替换实现。
 */
interface LaneMarkingOracle {

    /** 目标侧是否存在可通行车道。 */
    fun hasTargetLane(navi: NaviState, action: LaneAction): Boolean

    /** 目标车道边界是否为不可跨越的实线（含未知/禁行，故障安全按实线处理）。 */
    fun isTargetSolid(navi: NaviState, action: LaneAction): Boolean
}

/**
 * 基于高德 EXTRA_DRIVE_WAY 的车道标线裁决器。
 *
 * 规则：
 *  - 导航无效或没有推荐车道：无车道，禁止变道；
 *  - 目标车道标记为 'X'（禁行）：视为实线阻挡；
 *  - 目标车道为普通大写/小写字母：视为虚线可跨越。
 */
class RouteLaneMarkingOracle : LaneMarkingOracle {

    override fun hasTargetLane(navi: NaviState, action: LaneAction): Boolean {
        if (!navi.navValid || action == LaneAction.KEEP) return false
        val parsed = LaneWayParser.parse(navi.driveWayRaw)
        return parsed.targetLaneIndex(action.code) >= 0
    }

    override fun isTargetSolid(navi: NaviState, action: LaneAction): Boolean {
        if (!navi.navValid || action == LaneAction.KEEP) return true
        val parsed = LaneWayParser.parse(navi.driveWayRaw)
        val target = parsed.targetLaneIndex(action.code)
        if (target < 0) return true

        val token = parsed.tokenAt(target)
        if (token.isEmpty()) return true
        return token[0] == 'X'
    }
}
