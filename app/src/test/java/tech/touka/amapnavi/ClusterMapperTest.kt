package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.cluster.ClusterMapper
import tech.touka.amapnavi.cluster.EstFrame
import tech.touka.amapnavi.cluster.SpeedFrame
import tech.touka.amapnavi.cluster.TbtFrame
import tech.touka.amapnavi.core.model.NaviState

/**
 * 仪表映射契约测试（纯 JVM）。
 *
 * 覆盖：转向显示码、TBT 距离分档、行程分档、文本行、限速钳制。
 * 依据：文档 12/13/16。
 */
class ClusterMapperTest {

    // ---------- 转向显示码 ----------

    @Test
    fun `高德ICON映射到HSAE显示码`() {
        assertEquals(65, ClusterMapper.mapTurnType(2))
        assertEquals(65, ClusterMapper.mapTurnType(10))
        assertEquals(65, ClusterMapper.mapTurnType(18))
        assertEquals(65, ClusterMapper.mapTurnType(19))
        assertEquals(70, ClusterMapper.mapTurnType(3))
        assertEquals(67, ClusterMapper.mapTurnType(4))
        assertEquals(71, ClusterMapper.mapTurnType(5))
        assertEquals(71, ClusterMapper.mapTurnType(16))
        assertEquals(66, ClusterMapper.mapTurnType(6))
        assertEquals(69, ClusterMapper.mapTurnType(7))
        assertEquals(68, ClusterMapper.mapTurnType(8))
        assertEquals(72, ClusterMapper.mapTurnType(9))
    }

    @Test
    fun `未知ICON一律255`() {
        assertEquals(255, ClusterMapper.mapTurnType(0))
        assertEquals(255, ClusterMapper.mapTurnType(1))
        assertEquals(255, ClusterMapper.mapTurnType(11))
        assertEquals(255, ClusterMapper.mapTurnType(12))
        assertEquals(255, ClusterMapper.mapTurnType(14))
        assertEquals(255, ClusterMapper.mapTurnType(17))
        assertEquals(255, ClusterMapper.mapTurnType(20))
        assertEquals(255, ClusterMapper.mapTurnType(99))
    }

    @Test
    fun `实验性图标开关关闭时保持255`() {
        listOf(11, 12, 14, 15, 17, 20).forEach {
            assertEquals(255, ClusterMapper.mapTurnType(it, experimental = false))
        }
    }

    @Test
    fun `实验性图标开关开启后的候选映射`() {
        assertEquals(96, ClusterMapper.mapTurnType(11, experimental = true))   // 进入环岛
        assertEquals(97, ClusterMapper.mapTurnType(12, experimental = true))   // 离开环岛
        assertEquals(212, ClusterMapper.mapTurnType(14, experimental = true))  // 收费站
        assertEquals(178, ClusterMapper.mapTurnType(15, experimental = true))  // 驶出匝道
        assertEquals(66, ClusterMapper.mapTurnType(17, experimental = true))   // 靠右
        assertEquals(178, ClusterMapper.mapTurnType(20, experimental = true))  // 驶出高速
    }

    // ---------- TBT 距离 ----------

    @Test
    fun `小于1000米使用米`() {
        val d = ClusterMapper.formatDistance(800)
        assertEquals(800, d.distVal)
        assertEquals(0, d.scale)
        assertEquals(0, d.frac)
    }

    @Test
    fun `1000到100000米使用公里加一位小数`() {
        val d1500 = ClusterMapper.formatDistance(1500)
        assertEquals(1, d1500.distVal)
        assertEquals(1, d1500.scale)
        assertEquals(5, d1500.frac)

        val d999 = ClusterMapper.formatDistance(999)
        assertEquals(999, d999.distVal)
        assertEquals(0, d999.scale)

        val d1000 = ClusterMapper.formatDistance(1000)
        assertEquals(1, d1000.distVal)
        assertEquals(1, d1000.scale)
        assertEquals(0, d1000.frac)
    }

    @Test
    fun `超过100000米只取整数公里`() {
        val d = ClusterMapper.formatDistance(123_456)
        assertEquals(123, d.distVal)
        assertEquals(1, d.scale)
        assertEquals(0, d.frac)
    }

    @Test
    fun `TBT帧包含显示码和距离`() {
        val f = ClusterMapper.formatTbt(icon = 3, meters = 1500)
        assertEquals(70, f.displayType)
        assertEquals(1, f.distVal)
        assertEquals(1, f.scale)
        assertEquals(5, f.frac)
        assertEquals(0, f.direction)
    }

    // ---------- 行程 ----------

    @Test
    fun `行程到达时刻模式`() {
        val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        val f = ClusterMapper.formatEstimate(meters = 1500, seconds = 600, nowMs = now, etaMode = 1)
        assertEquals(1, f.distVal)
        assertEquals(1, f.unit)
        assertEquals(5, f.distF)
        assertEquals(1, f.timeType)
        // 只校验小时/分钟落在合法区间，避免依赖运行环境时区
        assertTrue(f.hour in 0..23)
        assertTrue(f.minute in 0..59)
    }

    @Test
    fun `行程剩余时长模式`() {
        val f = ClusterMapper.formatEstimate(meters = 800, seconds = 3_900, nowMs = 0L, etaMode = 2)
        assertEquals(800, f.distVal)
        assertEquals(0, f.unit)
        assertEquals(1, f.hour)
        assertEquals(5, f.minute)
        assertEquals(2, f.timeType)
    }

    // ---------- 文本行 ----------

    @Test
    fun `无红绿灯只显示路名`() {
        val navi = NaviState(nextRoadName = "机场高速", trafficLightSeconds = -1, trafficLightStatus = 0)
        assertEquals("机场高速", ClusterMapper.buildText(navi))
    }

    @Test
    fun `关闭红绿灯通道时只显示路名`() {
        val navi = NaviState(nextRoadName = "机场高速", trafficLightSeconds = 18, trafficLightStatus = 1)
        assertEquals("机场高速", ClusterMapper.buildText(navi, includeTrafficLight = false))
    }

    @Test
    fun `红灯文本带倒计时`() {
        val navi = NaviState(nextRoadName = "机场高速", trafficLightSeconds = 18, trafficLightStatus = 1)
        assertEquals("[红灯 18s] 机场高速", ClusterMapper.buildText(navi))
    }

    @Test
    fun `绿灯和黄灯文本`() {
        assertEquals(
            "[绿灯 12s] 机场高速",
            ClusterMapper.buildText(NaviState(nextRoadName = "机场高速", trafficLightSeconds = 12, trafficLightStatus = 2))
        )
        assertEquals(
            "[黄灯 3s] 机场高速",
            ClusterMapper.buildText(NaviState(nextRoadName = "机场高速", trafficLightSeconds = 3, trafficLightStatus = 3))
        )
    }

    @Test
    fun `下一道路为空时回退当前道路`() {
        val navi = NaviState(currentRoadName = "华南快速", nextRoadName = "", trafficLightSeconds = -1)
        assertEquals("华南快速", ClusterMapper.buildText(navi))
    }

    @Test
    fun `文本截断到24字符`() {
        val longRoad = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十"
        val text = ClusterMapper.buildText(NaviState(nextRoadName = longRoad, trafficLightSeconds = -1))
        assertEquals(24, text.length)
    }

    // ---------- 限速 ----------

    @Test
    fun `限速优先道路限速再回退电子眼`() {
        val frame = ClusterMapper.toFrame(
            NaviState(roadSpeedLimit = 100, cameraSpeedLimit = 80),
            nowMs = 0L,
            etaMode = 2
        )
        assertEquals(SpeedFrame(100), frame.speed)

        val fallback = ClusterMapper.toFrame(
            NaviState(roadSpeedLimit = 0, cameraSpeedLimit = 80),
            nowMs = 0L,
            etaMode = 2
        )
        assertEquals(SpeedFrame(80), fallback.speed)
    }

    @Test
    fun `限速钳制在20到150之间`() {
        assertEquals(20, ClusterMapper.clampSpeed(prev = 0, next = 10, elapsedMs = 1000))
        assertEquals(150, ClusterMapper.clampSpeed(prev = 0, next = 200, elapsedMs = 1000))
        assertEquals(0, ClusterMapper.clampSpeed(prev = 80, next = 0, elapsedMs = 1000))
    }

    @Test
    fun `限速每秒变化不超过20`() {
        assertEquals(100, ClusterMapper.clampSpeed(prev = 80, next = 120, elapsedMs = 1000))
        assertEquals(60, ClusterMapper.clampSpeed(prev = 80, next = 30, elapsedMs = 1000))
        assertEquals(90, ClusterMapper.clampSpeed(prev = 80, next = 120, elapsedMs = 500))
    }

    // ---------- 整帧 ----------

    @Test
    fun `整帧字段齐全`() {
        val navi = NaviState(
            navValid = true,
            nextRoadName = "机场高速",
            roadSpeedLimit = 100,
            tbtDistance = 1500,
            tbtTurnType = 3,
            routeRemainDistance = 12_000,
            routeRemainTime = 900,
            trafficLightSeconds = -1
        )
        val frame = ClusterMapper.toFrame(navi, nowMs = 0L, etaMode = 2)
        assertEquals(TbtFrame(70, 0, 1, 1, 5), frame.tbt)
        assertEquals(SpeedFrame(100), frame.speed)
        assertEquals(12, frame.est.distVal)
        assertEquals(1, frame.est.unit)
        assertEquals(0, frame.est.hour)
        assertEquals(15, frame.est.minute)
        assertEquals("机场高速", frame.text.text)
    }

    // ---------- 罗盘 / ETA 扩展 ----------

    @Test
    fun `航向角转罗盘显示码`() {
        assertEquals(0, ClusterMapper.formatCompass(0))      // 360/22.5=16 -> >47 归零
        assertEquals(36, ClusterMapper.formatCompass(90))
        assertEquals(24, ClusterMapper.formatCompass(180))
        assertEquals(12, ClusterMapper.formatCompass(270))
    }

    @Test
    fun `非法航向角返回零`() {
        assertEquals(0, ClusterMapper.formatCompass(-1))
        assertEquals(0, ClusterMapper.formatCompass(361))
    }

    @Test
    fun `无转向时输出罗盘帧`() {
        val navi = NaviState(
            navValid = true,
            tbtTurnType = 0,
            tbtDistance = 0,
            carDirection = 90,
            roadSpeedLimit = 100,
            nextRoadName = "机场高速"
        )
        val frame = ClusterMapper.toFrame(navi, nowMs = 0L, etaMode = 2)
        assertEquals(8, frame.tbt.displayType)
        assertEquals(36, frame.tbt.direction)
        assertEquals(0, frame.tbt.distVal)
        assertEquals(0, frame.tbt.scale)
    }

    @Test
    fun `高德ETA文本优先于本地计算`() {
        val frame = ClusterMapper.toFrame(
            NaviState(
                routeRemainDistance = 12_000,
                routeRemainTime = 900,
                etaText = "18:45"
            ),
            nowMs = 0L,
            etaMode = 1
        )
        assertEquals(18, frame.est.hour)
        assertEquals(45, frame.est.minute)
        assertEquals(1, frame.est.timeType)
        assertEquals(12, frame.est.distVal)
        assertEquals(1, frame.est.unit)
    }

    @Test
    fun `非法ETA文本回退剩余时长`() {
        val frame = ClusterMapper.toFrame(
            NaviState(
                routeRemainDistance = 12_000,
                routeRemainTime = 900,
                etaText = "bad"
            ),
            nowMs = 0L,
            etaMode = 2
        )
        assertEquals(0, frame.est.hour)
        assertEquals(15, frame.est.minute)
        assertEquals(2, frame.est.timeType)
    }

    // ---------- 目的地旗 / 文本增强 / 行程进度 ----------

    @Test
    fun `目的地近距离输出目的地旗`() {
        val navi = NaviState(
            navValid = true,
            tbtTurnType = 13,
            tbtDistance = 10,
            carDirection = 90
        )
        val frame = ClusterMapper.toFrame(navi, nowMs = 0L, etaMode = 2)
        assertEquals(2, frame.tbt.displayType)
        assertEquals(36, frame.tbt.direction)
        assertEquals(0, frame.tbt.distVal)
        assertEquals(0, frame.tbt.scale)
    }

    @Test
    fun `目的地远距离仍显示目的地图标112`() {
        val navi = NaviState(
            navValid = true,
            tbtTurnType = 13,
            tbtDistance = 1_500
        )
        val frame = ClusterMapper.toFrame(navi, nowMs = 0L, etaMode = 2)
        assertEquals(112, frame.tbt.displayType)
        assertEquals(1, frame.tbt.distVal)
        assertEquals(1, frame.tbt.scale)
        assertEquals(5, frame.tbt.frac)
    }

    @Test
    fun `行程进度百分比`() {
        assertEquals(60, ClusterMapper.progressPercent(NaviState(routeTotalDistance = 30_000, routeRemainDistance = 12_000)))
        assertEquals(0, ClusterMapper.progressPercent(NaviState(routeTotalDistance = 30_000, routeRemainDistance = 30_000)))
        assertEquals(100, ClusterMapper.progressPercent(NaviState(routeTotalDistance = 30_000, routeRemainDistance = 0)))
        assertEquals(-1, ClusterMapper.progressPercent(NaviState(routeTotalDistance = 0, routeRemainDistance = 12_000)))
    }

    @Test
    fun `文本行带行程进度`() {
        val navi = NaviState(
            nextRoadName = "机场高速",
            routeTotalDistance = 30_000,
            routeRemainDistance = 12_000,
            trafficLightSeconds = -1
        )
        assertEquals("机场高速 60%", ClusterMapper.buildText(navi))
    }

    @Test
    fun `文本行带测速提示`() {
        val navi = NaviState(
            nextRoadName = "机场高速",
            cameraType = 1,
            cameraDistance = 300,
            trafficLightSeconds = -1
        )
        assertEquals("[测速 300m] 机场高速", ClusterMapper.buildText(navi))
    }

    @Test
    fun `文本行带前方红绿灯数量`() {
        val navi = NaviState(
            nextRoadName = "机场高速",
            trafficLightNum = 3,
            trafficLightSeconds = -1
        )
        assertEquals("机场高速 ·3灯", ClusterMapper.buildText(navi))
    }
}
