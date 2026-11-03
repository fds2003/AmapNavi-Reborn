package tech.touka.amapnavi.domain.safety

import tech.touka.amapnavi.core.event.BlockCause
import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.model.CarState

/**
 * 多传感器融合侧向安全评估器（纯 Kotlin）。
 *
 * 熔断优先级：实线标线 > 毫米波 BSD 盲区 > 激光雷达盲区标志 > 动态制动安全净空。
 * 任何一项不满足即 100% 阻断变道。
 */
class LateralSafetyEvaluator(
    private val minSafeTimeHeadway: Float = 2.0f,
    private val minStaticClearanceM: Float = 10.0f
) {

    fun evaluateSide(action: LaneAction, car: CarState, isTargetSolid: Boolean): SafetyAuditResult {
        if (action == LaneAction.KEEP) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.NO_TARGET_LANE,
                reasonDesc = "未指定目标车道"
            )
        }

        // 1. 标线准入法规检查
        if (isTargetSolid) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.SOLID_LINE,
                isSolidBlocked = true,
                reasonDesc = "侧向为实线或禁行标线，禁止变道"
            )
        }

        // 2. 原车毫米波盲区检查
        val bsdAlarm = if (action == LaneAction.LEFT) car.leftBlindspot else car.rightBlindspot
        if (bsdAlarm) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.BSD_ALARM,
                isBsdBlocked = true,
                reasonDesc = "毫米波雷达盲区有车"
            )
        }

        // 3. 激光雷达盲区标志检查
        val lidarBlind = if (action == LaneAction.LEFT) {
            car.lidarLeftRearBlind
        } else {
            car.lidarRightRearBlind
        }
        if (lidarBlind) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.LIDAR_HAZARD,
                isLidarBlocked = true,
                reasonDesc = "激光雷达侧后盲区报警"
            )
        }

        // 4. 激光雷达动态制动净空模型
        // D_safe = max(10.0, v_ego(m/s) * T_headway)
        val actualRearDistance = if (action == LaneAction.LEFT) {
            car.leftRearDistance
        } else {
            car.rightRearDistance
        }
        val vEgoMeterPerSecond = car.vEgo / 3.6f
        val requiredDistance = Math.max(
            minStaticClearanceM,
            vEgoMeterPerSecond * minSafeTimeHeadway
        )

        if (actualRearDistance < requiredDistance) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.LIDAR_HAZARD,
                isLidarBlocked = true,
                reasonDesc = "侧后方车距过近(实际:${formatDistance(actualRearDistance)}m < 门限:${formatDistance(requiredDistance)}m)"
            )
        }

        return SafetyAuditResult(
            isSafe = true,
            blockCause = BlockCause.NONE,
            reasonDesc = "侧向安全，允许变道"
        )
    }

    private fun formatDistance(value: Float): String {
        return String.format(java.util.Locale.US, "%.1f", value)
    }
}
