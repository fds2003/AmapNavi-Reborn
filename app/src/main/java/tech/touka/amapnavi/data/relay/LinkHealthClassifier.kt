package tech.touka.amapnavi.data.relay

/**
 * E4：UDP 链路健康三档（按「距上次遥测包」时长判定）。
 *
 * - [STABLE]：正常（蓝，< 2s）
 * - [WEAK]  ：信号弱（黄闪，2~10s），语音「C3 信号弱」
 * - [LOST]  ：断联（红常亮，> 10s），语音「智驾断联，请接管」+ 自动发 CANCEL
 */
enum class LinkHealthLevel {
    STABLE, WEAK, LOST
}

/**
 * E4 三档分类器（纯逻辑，注入 elapsedMs，可离线单测）。
 *
 * 阈值：2s（弱信号起点）、10s（断联/需接管）。
 */
class LinkHealthClassifier(
    private val weakThresholdMs: Long = 2_000L,
    private val lostThresholdMs: Long = 10_000L
) {
    fun classify(elapsedMs: Long): LinkHealthLevel = when {
        elapsedMs < 0 -> LinkHealthLevel.STABLE
        elapsedMs < weakThresholdMs -> LinkHealthLevel.STABLE
        elapsedMs <= lostThresholdMs -> LinkHealthLevel.WEAK
        else -> LinkHealthLevel.LOST
    }
}
