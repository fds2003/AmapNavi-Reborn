package tech.touka.amapnavi.data.relay

/**
 * 遥测心跳看门狗（纯 Kotlin，注入时间，可离线单测）。
 *
 * OvertakeStateMachine.tick() 只在收到遥测包时运行，断流时状态机会冻结在 EXECUTING；
 * 由 RelayEngine 的独立协程按固定节拍调用 [shouldTrip] 发现断流。
 *
 * 判定口径与 CarState.isTelemetryFresh 一致：now - last <= timeoutMs 视为新鲜；
 * 同一断流期只触发一次，收到新包后重新武装。
 */
class TelemetryWatchdog(private val timeoutMs: Long) {

    private var lastTelemetryAt = 0L
    private var tripped = false

    /** 收到遥测包：刷新时间戳并重新武装。UDP 线程调用。 */
    @Synchronized
    fun onTelemetry(nowMs: Long) {
        lastTelemetryAt = nowMs
        tripped = false
    }

    /** 返回 true 表示本次需要熔断（同一断流期只返回一次）。心跳协程调用。 */
    @Synchronized
    fun shouldTrip(nowMs: Long): Boolean {
        if (lastTelemetryAt == 0L) return false  // 尚未建立过连接，不报警
        if (nowMs - lastTelemetryAt <= timeoutMs) return false
        if (tripped) return false
        tripped = true
        return true
    }

    /** 中继停止时清空状态。 */
    @Synchronized
    fun reset() {
        lastTelemetryAt = 0L
        tripped = false
    }
}
