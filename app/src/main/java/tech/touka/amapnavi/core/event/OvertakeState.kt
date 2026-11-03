package tech.touka.amapnavi.core.event

/**
 * 自主超车状态机的 7 个确定性阶段。
 */
enum class OvertakeState {
    IDLE,
    EVALUATING,
    SIDE_CHECKING,
    PREPARE,
    EXECUTING,
    FINISHING,
    COOLDOWN
}
