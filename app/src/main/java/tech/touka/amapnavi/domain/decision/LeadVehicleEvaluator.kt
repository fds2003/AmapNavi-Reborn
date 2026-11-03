package tech.touka.amapnavi.domain.decision

import tech.touka.amapnavi.core.model.CarState

/** 前车压速评估结果。 */
data class LeadEvaluation(
    val triggered: Boolean,
    val reason: String
)

/**
 * 前车压速超车触发评估器。
 *
 * 触发条件：10m ≤ drel ≤ 60m 且 (Δv = v_cruise - v_lead ≥ 10km/h 或 v_lead / v_cruise ≤ 0.88)
 */
class LeadVehicleEvaluator(
    private val minGapM: Float = 10.0f,
    private val maxGapM: Float = 60.0f,
    private val minDeltaVKmh: Int = 10,
    private val maxSpeedRatio: Float = 0.88f
) {

    fun evaluate(car: CarState): LeadEvaluation {
        if (!car.lead1) {
            return LeadEvaluation(false, "前方无目标车辆")
        }
        val vCruise = car.vCruise
        if (vCruise <= 0) {
            return LeadEvaluation(false, "巡航设定车速无效")
        }
        if (car.drel < minGapM) {
            return LeadEvaluation(
                false,
                "前车距离${formatKm(car.drel)}m过近(<${minGapM.toInt()}m)，等待拉开"
            )
        }
        if (car.drel > maxGapM) {
            return LeadEvaluation(
                false,
                "前车距离${formatKm(car.drel)}m过远(>${maxGapM.toInt()}m)"
            )
        }

        val deltaV = vCruise - car.vLead
        val speedRatio = car.vLead / vCruise.toFloat()
        if (deltaV >= minDeltaVKmh || speedRatio <= maxSpeedRatio) {
            return LeadEvaluation(
                true,
                "前车压速(Δv=${deltaV}km/h, 速度比=${formatRatio(speedRatio)})，满足超车触发"
            )
        }

        return LeadEvaluation(
            false,
            "前车速度差不足(Δv=${deltaV}km/h, 速度比=${formatRatio(speedRatio)})"
        )
    }

    private fun formatKm(value: Float): String {
        return String.format(java.util.Locale.US, "%.1f", value)
    }

    private fun formatRatio(value: Float): String {
        return String.format(java.util.Locale.US, "%.2f", value)
    }
}
