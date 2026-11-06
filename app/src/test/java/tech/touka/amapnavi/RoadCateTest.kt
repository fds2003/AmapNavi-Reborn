package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.core.util.RoadCate

/**
 * 原版 APK（com.carrot.amapnavi.AmapUtilsKt）道路等级语义的等价契约。
 *
 * 原版逻辑：
 *   roadcate_conv: 0 -> 1, 6 -> 0, 1 -> 2, 其它 -> 8
 *   isHighway:     roadcate == 0 || roadcate == 1
 */
class RoadCateTest {

    @Test
    fun `原始道路等级转换为内部等级`() {
        assertEquals(1, RoadCate.conv(0))
        assertEquals(2, RoadCate.conv(1))
        assertEquals(0, RoadCate.conv(6))
        assertEquals(8, RoadCate.conv(2))
        assertEquals(8, RoadCate.conv(8))
        assertEquals(8, RoadCate.conv(7))
    }

    @Test
    fun `内部等级0和1判定高速`() {
        assertTrue(RoadCate.isHighway(0))
        assertTrue(RoadCate.isHighway(1))
        assertFalse(RoadCate.isHighway(2))
        assertFalse(RoadCate.isHighway(8))
    }

    @Test
    fun `原始等级6和0都落到高速`() {
        // 原版：raw 6 -> 0(高速)，raw 0 -> 1(高速)
        assertTrue(RoadCate.isHighway(RoadCate.conv(6)))
        assertTrue(RoadCate.isHighway(RoadCate.conv(0)))
        // 原版：raw 1 -> 2(非高速)
        assertFalse(RoadCate.isHighway(RoadCate.conv(1)))
    }
}
