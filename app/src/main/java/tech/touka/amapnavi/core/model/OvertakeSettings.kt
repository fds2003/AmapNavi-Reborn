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
    val allowRightOvertake: Boolean = false
)
