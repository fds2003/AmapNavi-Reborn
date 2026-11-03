package tech.touka.amapnavi.domain.safety

import tech.touka.amapnavi.core.event.BlockCause

/**
 * 侧向安全审计结果。
 */
data class SafetyAuditResult(
    val isSafe: Boolean,
    val blockCause: BlockCause = BlockCause.NONE,
    val isSolidBlocked: Boolean = false,
    val isBsdBlocked: Boolean = false,
    val isLidarBlocked: Boolean = false,
    val reasonDesc: String = ""
)
