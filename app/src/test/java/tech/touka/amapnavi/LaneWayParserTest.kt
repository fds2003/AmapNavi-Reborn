package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.core.util.LaneWayParser
import tech.touka.amapnavi.core.util.ParsedLaneWay

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
    fun `多推荐车道取中间车道为自车估计位置（R-8）`() {
        // R-8：suggest = [1, 2]（车道 2/3 同时推荐）时，
        // 自车最近推荐车道取中位数（索引 size/2 = 1），而非 suggest[0]。
        val multi = ParsedLaneWay(tokens = listOf("s", "S", "S", "s"), suggest = listOf(1, 2))
        // 中位数 suggest[size/2=1]=2：向左 target=1，向右 target=3（均在 0..3 内）
        assertEquals("向左超车目标应为索引 1（车道 2）", 1, multi.targetLaneIndex("LEFT"))
        assertEquals("向右超车目标应为索引 3（车道 4）", 3, multi.targetLaneIndex("RIGHT"))
        // 单推荐时与旧行为一致（中位数 == 唯一值）
        val single = ParsedLaneWay(tokens = listOf("s", "S", "s"), suggest = listOf(1))
        assertEquals(0, single.targetLaneIndex("LEFT"))
        assertEquals(2, single.targetLaneIndex("RIGHT"))
    }

    @Test
    fun `无推荐车道时目标为-1`() {
        assertEquals(-1, LaneWayParser.parse("s|s|s").targetLaneIndex("LEFT"))
        assertEquals(-1, LaneWayParser.parse("s|s|s").targetLaneIndex("RIGHT"))
    }

    @Test
    fun `解析13012车道JSON图标与推荐`() {
        val raw = """{"drive_way_enabled":true,"drive_way_size":3,"drive_way_info":[
            {"drive_way_lane_Back_icon":"15","trafficLaneAdvised":false},
            {"drive_way_lane_Back_icon":"18","trafficLaneAdvised":true},
            {"drive_way_lane_Back_icon":"32","trafficLaneAdvised":false}]}"""
        val parsed = LaneWayParser.parse(raw)
        assertEquals(3, parsed.laneCount)
        assertEquals(listOf(1), parsed.suggest)
        assertTrue(parsed.disable.isEmpty())
        assertEquals("S", parsed.tokenAt(1))
    }

    @Test
    fun `车道JSON中小于15的图标判为禁行`() {
        val raw = """{"drive_way_enabled":true,"drive_way_size":3,"drive_way_info":[
            {"drive_way_lane_Back_icon":"0","trafficLaneAdvised":false},
            {"drive_way_lane_Back_icon":"15","trafficLaneAdvised":true},
            {"drive_way_lane_Back_icon":"14","trafficLaneAdvised":false}]}"""
        val parsed = LaneWayParser.parse(raw)
        assertEquals(listOf(0, 2), parsed.disable)
        assertEquals(listOf(1), parsed.suggest)
        assertEquals("X", parsed.tokenAt(0))
    }

    @Test
    fun `车道JSON关闭时无车道`() {
        val parsed = LaneWayParser.parse("""{"drive_way_enabled":false}""")
        assertEquals(0, parsed.laneCount)
        assertTrue(parsed.suggest.isEmpty())
    }
}
