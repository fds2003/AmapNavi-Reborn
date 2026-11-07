package tech.touka.amapnavi.core.model

/**
 * 自主超车引擎的可调参数全集。
 *
 * 默认值遵循契约：高速准入 60km/h、预备灯 1.2s、标准冷却 20s、打断惩罚 60s、
 * 遥测超时 1s、执行超时 4.5s、驾驶员反向打轮阈值 25°。
 */
data class OvertakeSettings(
    val enabled: Boolean = true,
    val minEngageSpeedKmh: Int = 60,
    val prepareBlinkMs: Long = 1200L,
    val standardCooldownMs: Long = 20000L,
    val interruptCooldownMs: Long = 60000L,
    val telemetryTimeoutMs: Long = 1000L,
    val executeTimeoutMs: Long = 4500L,
    val driverSteerThresholdDeg: Float = 25f,
    val allowRightOvertake: Boolean = false,
    // GAP-1：可配安全阈值（对应 6 个雷达 KEY + 风格三档 + 接管惩罚 + 准入车速）
    val minSafeTtcSeconds: Float = 2.5f,          // 后向 TTC 门限（风格 0=1.8/1=2.5/2=3.5）
    val minRearClearanceM: Float = 10.0f,         // 后向静态净空（KEY_REAR_THRESH_VAL）
    val minRearTimeHeadway: Float = 2.0f,         // 后向动态时距（KEY_REAR_VREL_TIME）
    val minFrontClearanceM: Float = 20.0f,        // 目标车道前车净空（KEY_FRONT_THRESH_VAL）
)
