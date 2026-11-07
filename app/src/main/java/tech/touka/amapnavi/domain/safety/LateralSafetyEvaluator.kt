package tech.touka.amapnavi.domain.safety

import tech.touka.amapnavi.core.event.BlockCause
import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.model.CarState
import tech.touka.amapnavi.core.model.OvertakeSettings

/**
 * 多传感器融合侧向安全评估器（纯 Kotlin）。
 *
 * 熔断优先级：实线标线 > 毫米波 BSD 盲区 > 激光雷达盲区标志 > 后向 TTC > 动态制动安全净空。
 * 任何一项不满足即 100% 阻断变道；传感器失效（TTC<=0 或距离缺键）同样阻断。
 *
 * 阈值可经 [updateThresholds] 由 [OvertakeSettings] 实时覆盖（GAP-1）。
 */
class LateralSafetyEvaluator(
    private var minSafeTimeHeadway: Float = 2.0f,
    private var minStaticClearanceM: Float = 10.0f,
    private var minSafeTtcSeconds: Float = 2.5f,
    private var minTargetLaneClearanceM: Float = 20.0f
) {

    /**
     * GAP-1：把用户设置的安全阈值注入评估器（原为硬编码，调参无效）。
     * 由 RelayEngine 在每次 tick 前按最新 settings 调用。
     */
    fun updateThresholds(settings: OvertakeSettings) {
        minSafeTimeHeadway = settings.minRearTimeHeadway
        minStaticClearanceM = settings.minRearClearanceM
        minSafeTtcSeconds = settings.minSafeTtcSeconds
        minTargetLaneClearanceM = settings.minFrontClearanceM
    }

    fun evaluateSide(action: LaneAction, car: CarState, isTargetSolid: Boolean): SafetyAuditResult {
        if (action == LaneAction.KEEP) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.NO_TARGET_LANE,
                reasonDesc = "未指定目标车道"
            )
        }

        // 0. 目标侧前角雷达盲区（原版 APK LaneChangeSafetyCheckRaw 返回 -2，优先级最高）
        val frontBlind = if (action == LaneAction.LEFT) car.leftFrontBlind else car.rightFrontBlind
        if (frontBlind) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.FRONT_BLIND,
                reasonDesc = "目标侧前角雷达盲区报警，禁止变道"
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

        // 3.5 目标车道前车占用（原版 sideFrontMinDist 语义，normal=20m）
        val targetLead = if (action == LaneAction.LEFT) car.leftLead else car.rightLead
        val targetLeadDist = if (action == LaneAction.LEFT) car.leftLeadDistance else car.rightLeadDistance
        // fail-closed：报有车但距离缺失/为 0 时同样阻断
        if (targetLead && targetLeadDist < minTargetLaneClearanceM) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.TARGET_LANE_OCCUPIED,
                reasonDesc = "目标车道前车过近(${formatDistance(targetLeadDist)}m < 门限:${formatDistance(minTargetLaneClearanceM)}m)"
            )
        }

        // 4. C3 后向 TTC 门禁（与 C3 desire_helper 的 1.8/2.5/3.5s 档位同源）
        //    C3 语义：0.0 = 传感器失效/无回波，99.0 = 净空；<=0 必须阻断，禁止 fail-open。
        val behindTtc = car.lidarBehindVdistTime
        if (behindTtc <= 0f) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.LIDAR_UNAVAILABLE,
                isLidarBlocked = true,
                reasonDesc = "无有效后向激光雷达数据（TTC 无效），默认禁止超车"
            )
        }
        if (behindTtc < minSafeTtcSeconds) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.LIDAR_HAZARD,
                isLidarBlocked = true,
                reasonDesc = "后向TTC过近(实际:${String.format(java.util.Locale.US, "%.1f", behindTtc)}s < 门限:${minSafeTtcSeconds}s)"
            )
        }

        // 5. 激光雷达动态制动净空模型
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
