package tech.touka.amapnavi.core.event

/**
 * 超车引擎对外快照，UI 与日志只读消费该对象。
 */
data class OvertakeStatus(
    val state: OvertakeState = OvertakeState.IDLE,
    val action: LaneAction = LaneAction.KEEP,
    val blockCause: BlockCause = BlockCause.NONE,
    val message: String = "待机",
    val cooldownRemainMs: Long = 0L,
    val updatedAtMs: Long = 0L
)
