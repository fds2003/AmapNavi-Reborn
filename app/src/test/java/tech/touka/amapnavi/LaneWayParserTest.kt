package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.core.util.LaneWayParser

/**
 * EXTRA_DRIVE_WAY 车道串解析契约测试（纯 JVM）。
 *
 * 现场数据存在两种分隔符：高德文档示例用 "/"，历史抓包用 "|"；
 * 禁行标记在文档与抓包里大小写混用。
 */
class LaneWayParserTest {

    @Test
    fun `竖线分隔解析为三车道`() {
        val parsed = LaneWayParser.parse("s|S|s")
        assertEquals(3, parsed.laneCount)
        assertEquals(listOf(1), parsed.suggest)
        assertTrue(parsed.disable.isEmpty())
        assertEquals("S", parsed.tokenAt(1))
    }

    @Test
    fun `斜杠分隔解析为三车道`() {
        val parsed = LaneWayParser.parse("s/S/s")
        assertEquals(3, parsed.laneCount)
        assertEquals(listOf(1), parsed.suggest)
        assertTrue(parsed.disable.isEmpty())
    }

    @Test
    fun `大小写X均判为禁行`() {
        val parsed = LaneWayParser.parse("s|X|x")
        assertEquals(3, parsed.laneCount)
        assertEquals(listOf(1, 2), parsed.disable)
        assertTrue(parsed.suggest.isEmpty())
    }

    @Test
    fun `空输入视为无车道`() {
        assertEquals(0, LaneWayParser.parse(null).laneCount)
        assertEquals(0, LaneWayParser.parse("").laneCount)
        assertEquals(-1, LaneWayParser.parse("").targetLaneIndex("LEFT"))
    }

    @Test
    fun `推荐车道推算相邻目标索引`() {
        val three = LaneWayParser.parse("s|S|s")
        assertEquals(0, three.targetLaneIndex("LEFT"))
        assertEquals(2, three.targetLaneIndex("RIGHT"))
        // 最右车道再向右越界，故障安全返回 -1
        assertEquals(-1, LaneWayParser.parse("s|S").targetLaneIndex("RIGHT"))
    }

    @Test
    fun `无推荐车道时目标为-1`() {
        assertEquals(-1, LaneWayParser.parse("s|s|s").targetLaneIndex("LEFT"))
        assertEquals(-1, LaneWayParser.parse("s|s|s").targetLaneIndex("RIGHT"))
    }
}
