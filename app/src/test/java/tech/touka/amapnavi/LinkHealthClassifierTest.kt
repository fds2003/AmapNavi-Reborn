package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.touka.amapnavi.data.relay.LinkHealthClassifier
import tech.touka.amapnavi.data.relay.LinkHealthLevel

/**
 * E4 UDP 链路断线分级告警测试（纯 JVM）。
 *
 * 三档口径（以「距上次遥测包」时长 elapsedMs 判定）：
 * - < 2s   → STABLE（蓝）正常
 * - 2~10s  → WEAK（黄闪）C3 信号弱
 * - > 10s  → LOST（红常亮）智驾断联，需自动发 CANCEL
 */
class LinkHealthClassifierTest {

    private val c = LinkHealthClassifier()

    @Test
    fun `2秒内为STABLE`() {
        assertEquals(LinkHealthLevel.STABLE, c.classify(0L))
        assertEquals(LinkHealthLevel.STABLE, c.classify(1_500L))
        assertEquals(LinkHealthLevel.STABLE, c.classify(1_999L))
    }

    @Test
    fun `2到10秒为WEAK`() {
        assertEquals(LinkHealthLevel.WEAK, c.classify(2_000L))
        assertEquals(LinkHealthLevel.WEAK, c.classify(5_000L))
        assertEquals(LinkHealthLevel.WEAK, c.classify(10_000L))
    }

    @Test
    fun `超10秒为LOST`() {
        assertEquals(LinkHealthLevel.LOST, c.classify(10_001L))
        assertEquals(LinkHealthLevel.LOST, c.classify(60_000L))
    }

    @Test
    fun `负数与零归为STABLE`() {
        assertEquals(LinkHealthLevel.STABLE, c.classify(-5L))
        assertEquals(LinkHealthLevel.STABLE, c.classify(0L))
    }
}
