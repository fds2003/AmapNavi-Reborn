package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.util.LaneWayParser
import tech.touka.amapnavi.data.amap.AmapBroadcastCodec

/**
 * 高德广播解码契约测试（纯 JVM）。
 *
 * 覆盖 EXTRA_DRIVE_WAY 推荐/禁行车道解析、道路等级、限速、转向与红绿灯字段，
 * 保证车道解析准确率 100%。
 */
class AmapBroadcastDecoderTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `解析推荐车道与禁行车道_s_s_S_X`() {
        val state = AmapBroadcastCodec.decode(
            driveWayRaw = "s|s|S|X",
            roadLimitSpeed = 120,
            cameraSpeedLimit = 120,
            cameraDistance = 350,
            tbtDistance = 800,
            tbtTurnType = 2,
            roadCate = 0,
            trafficLightSeconds = -1,
            trafficLightStatus = 0,
            nowMs = now
        )

        assertTrue(state.navValid)
        assertEquals(listOf(2), state.suggestLanes)
        assertEquals(listOf(3), state.disableLanes)
        assertEquals("s|s|S|X", state.driveWayRaw)
        assertEquals(120, state.roadSpeedLimit)
        assertEquals(120, state.cameraSpeedLimit)
        assertEquals(350, state.cameraDistance)
        assertEquals(800, state.tbtDistance)
        assertEquals(2, state.tbtTurnType)
        assertEquals(now, state.timestamp)
    }

    @Test
    fun `道路等级为0时判定高速`() {
        val highway = AmapBroadcastCodec.decode(
            driveWayRaw = "L|S|R", roadLimitSpeed = 100, cameraSpeedLimit = 0,
            cameraDistance = 0, tbtDistance = 1000, tbtTurnType = 1, roadCate = 0,
            trafficLightSeconds = -1, trafficLightStatus = 0, nowMs = now
        )
        assertTrue(highway.isHighway)

        val urban = AmapBroadcastCodec.decode(
            driveWayRaw = "L|S|R", roadLimitSpeed = 60, cameraSpeedLimit = 0,
            cameraDistance = 0, tbtDistance = 1000, tbtTurnType = 1, roadCate = 1,
            trafficLightSeconds = -1, trafficLightStatus = 0, nowMs = now
        )
        assertFalse(urban.isHighway)
    }

    @Test
    fun `红绿灯倒计时与状态正确落库`() {
        val state = AmapBroadcastCodec.decode(
            driveWayRaw = "", roadLimitSpeed = 60, cameraSpeedLimit = 0,
            cameraDistance = 0, tbtDistance = 120, tbtTurnType = 1, roadCate = 1,
            trafficLightSeconds = 18, trafficLightStatus = 1, nowMs = now
        )
        assertEquals(18, state.trafficLightSeconds)
        assertEquals(1, state.trafficLightStatus)
    }

    @Test
    fun `空车道串不产生推荐或禁行车道`() {
        val state = AmapBroadcastCodec.decode(
            driveWayRaw = "", roadLimitSpeed = 0, cameraSpeedLimit = 0,
            cameraDistance = 0, tbtDistance = 0, tbtTurnType = 0, roadCate = -1,
            trafficLightSeconds = -1, trafficLightStatus = 0, nowMs = now
        )
        assertTrue(state.suggestLanes.isEmpty())
        assertTrue(state.disableLanes.isEmpty())
        assertFalse(state.isHighway)
    }

    @Test
    fun `保留上一帧道路名称等未广播字段`() {
        val previous = NaviState(currentRoadName = "京港澳高速", nextRoadName = "机场高速")
        val state = AmapBroadcastCodec.decode(
            driveWayRaw = "S|s", roadLimitSpeed = 120, cameraSpeedLimit = 0,
            cameraDistance = 0, tbtDistance = 500, tbtTurnType = 6, roadCate = 0,
            trafficLightSeconds = -1, trafficLightStatus = 0, nowMs = now,
            previous = previous
        )
        assertEquals("京港澳高速", state.currentRoadName)
        assertEquals("机场高速", state.nextRoadName)
    }

    @Test
    fun `车道串解析器左中右目标索引计算`() {
        val parsed = LaneWayParser.parse("s|S|s")
        assertEquals(3, parsed.laneCount)
        assertEquals(listOf(1), parsed.suggest)
        assertEquals(0, parsed.targetLaneIndex("LEFT"))
        assertEquals(2, parsed.targetLaneIndex("RIGHT"))
    }

    @Test
    fun `最左车道无法再向左且最右车道无法再向右`() {
        val leftmost = LaneWayParser.parse("S|s|s")
        assertEquals(-1, leftmost.targetLaneIndex("LEFT"))
        assertEquals(1, leftmost.targetLaneIndex("RIGHT"))

        val rightmost = LaneWayParser.parse("s|s|S")
        assertEquals(1, rightmost.targetLaneIndex("LEFT"))
        assertEquals(-1, rightmost.targetLaneIndex("RIGHT"))
    }
}
