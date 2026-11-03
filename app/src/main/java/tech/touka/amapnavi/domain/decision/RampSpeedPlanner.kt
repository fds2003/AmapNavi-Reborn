package tech.touka.amapnavi.domain.decision

/**
 * 匝道平滑减速曲线规划器（纯 Kotlin）。
 *
 * v(s) = min(v_cruise, sqrt((v_target/3.6)^2 + 2 * a * s) * 3.6)
 */
class RampSpeedPlanner(
    private val rampTargetSpeedKmh: Int = 60,
    private val decelAcceleration: Float = 1.0f,
    private val decelStartDistanceM: Int = 500
) {

    fun calculateCruiseSpeed(currentDistM: Int, currentCruiseKmh: Int): Int {
        if (currentDistM < 0) return currentCruiseKmh
        if (currentDistM > decelStartDistanceM) return currentCruiseKmh
        if (currentCruiseKmh <= rampTargetSpeedKmh) return currentCruiseKmh

        val targetSpeedMs = rampTargetSpeedKmh / 3.6f
        val allowedSpeedSquared = targetSpeedMs * targetSpeedMs + 2 * decelAcceleration * currentDistM
        val allowedSpeedMs = Math.sqrt(allowedSpeedSquared.toDouble()).toFloat()
        val plannedKmh = (allowedSpeedMs * 3.6f).toInt()

        return plannedKmh.coerceIn(rampTargetSpeedKmh, currentCruiseKmh)
    }
}
