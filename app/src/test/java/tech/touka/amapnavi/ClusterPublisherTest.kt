package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.cluster.ClusterPublisher
import tech.touka.amapnavi.cluster.ClusterSettings
import tech.touka.amapnavi.cluster.ClusterState
import tech.touka.amapnavi.cluster.ClusterTransport
import tech.touka.amapnavi.cluster.EstFrame
import tech.touka.amapnavi.cluster.ProbeResult
import tech.touka.amapnavi.cluster.SpeedFrame
import tech.touka.amapnavi.cluster.TbtFrame
import tech.touka.amapnavi.cluster.TextFrame
import tech.touka.amapnavi.core.model.NaviState

/**
 * 仪表发布器契约测试（纯 JVM）。
 *
 * 用假 Transport / 假时钟验证：开关、去重、节流、保活、超时交还、异常降级。
 */
class ClusterPublisherTest {

    private class FakeSettings(
        override var enabled: Boolean = true,
        override var tbtEnabled: Boolean = true,
        override var speedEnabled: Boolean = true,
        override var estEnabled: Boolean = true,
        override var textEnabled: Boolean = true,
        override var tlEnabled: Boolean = true,
        override var etaMode: Int = 2
    ) : ClusterSettings

    private class FakeTransport : ClusterTransport {
        val calls = mutableListOf<String>()
        var fail = false

        override fun sendTbt(frame: TbtFrame): Boolean {
            calls += "tbt:${frame.displayType}:${frame.distVal}:${frame.scale}:${frame.frac}"
            return !fail
        }

        override fun sendSpeed(frame: SpeedFrame): Boolean {
            calls += "speed:${frame.kph}"
            return !fail
        }

        override fun sendEst(frame: EstFrame): Boolean {
            calls += "est:${frame.distVal}:${frame.unit}:${frame.hour}:${frame.minute}:${frame.timeType}"
            return !fail
        }

        override fun sendText(frame: TextFrame): Boolean {
            calls += "text:${frame.text}"
            return !fail
        }

        override fun probe(): ProbeResult = ProbeResult.Ok
    }

    private fun navi(
        now: Long = 1_000L,
        valid: Boolean = true,
        icon: Int = 3,
        tbt: Int = 1_500,
        speed: Int = 100,
        routeDist: Int = 12_000,
        routeTime: Int = 900,
        road: String = "机场高速",
        tlSeconds: Int = -1,
        tlStatus: Int = 0
    ) = NaviState(
        navValid = valid,
        nextRoadName = road,
        roadSpeedLimit = speed,
        tbtDistance = tbt,
        tbtTurnType = icon,
        routeRemainDistance = routeDist,
        routeRemainTime = routeTime,
        trafficLightSeconds = tlSeconds,
        trafficLightStatus = tlStatus,
        timestamp = now
    )

    @Test
    fun `总开关关闭时零调用`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings(enabled = false)) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        assertTrue(t.calls.isEmpty())
        assertEquals(ClusterState.RELEASED, p.status.value.state)
    }

    @Test
    fun `导航无效时零调用`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(valid = false), 1_000L)
        p.tick(1_000L)
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun `首个有效帧发送四类通道`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)

        assertTrue(t.calls.any { it.startsWith("tbt:67:") })
        assertTrue(t.calls.any { it == "speed:100" })
        assertTrue(t.calls.any { it.startsWith("est:12:1:") })
        assertTrue(t.calls.any { it == "text:机场高速" })
        assertEquals(ClusterState.ACTIVE, p.status.value.state)
    }

    @Test
    fun `相同帧不重复发送`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        val first = t.calls.size

        p.tick(1_100L)
        assertEquals(first, t.calls.size)
    }

    @Test
    fun `TBT变化立即发送`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)

        p.onNaviState(navi(tbt = 800, icon = 4), 1_600L)
        p.tick(1_600L)
        assertTrue(t.calls.any { it.startsWith("tbt:70:800:0:0") })
    }

    @Test
    fun `TBT每2秒保活`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        val countAfterFirst = t.calls.count { it.startsWith("tbt:") }

        p.onNaviState(navi(), 3_000L)
        p.tick(3_000L)
        assertEquals(countAfterFirst + 1, t.calls.count { it.startsWith("tbt:") })
    }

    @Test
    fun `导航结束执行交还序列`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        t.calls.clear()

        p.onNaviState(navi(valid = false), 1_500L)
        p.tick(1_500L)

        assertEquals(
            listOf("tbt:3:0:0:0", "tbt:8:0:0:0", "speed:0", "est:0:0:0:0:1", "text:"),
            t.calls
        )
        assertEquals(ClusterState.RELEASED, p.status.value.state)
    }

    @Test
    fun `超过3秒无广播自动交还`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        t.calls.clear()

        p.tick(4_001L)
        assertTrue(t.calls.contains("tbt:3:0:0:0"))
        assertTrue(t.calls.contains("text:"))
        assertEquals(ClusterState.RELEASED, p.status.value.state)
    }

    @Test
    fun `限速按每秒20平滑逼近`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(speed = 80), 1_000L)
        p.tick(1_000L)

        p.onNaviState(navi(speed = 120), 2_000L)
        p.tick(2_000L)
        assertTrue(t.calls.contains("speed:100"))
    }

    @Test
    fun `红绿灯倒计时每秒发送`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(tlSeconds = 18, tlStatus = 1), 1_000L)
        p.tick(1_000L)
        assertTrue(t.calls.contains("text:[红灯 18s] 机场高速"))

        p.onNaviState(navi(tlSeconds = 17, tlStatus = 1), 2_000L)
        p.tick(2_000L)
        assertTrue(t.calls.contains("text:[红灯 17s] 机场高速"))
    }

    @Test
    fun `Transport失败不崩溃并记录错误`() {
        val t = FakeTransport().apply { fail = true }
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)

        assertNotNull(p.status.value.lastError)
    }

    @Test
    fun `R-2 快照读：tick 内 navi 与到达时间来自同一原子快照`() {
        // R-2：latestNavi 与 lastNavAt 是两个 @Volatile 字段，
        // 组合读取可能读到「新 navi + 旧时间戳」的撕裂帧，误判 3 秒超时提前 release。
        // 契约：onNaviState 与 tick 之间不插入任何 release 条件变化——
        // 写入 (navi, now) 后立即 tick(now) 必须走 ACTIVE 分支，
        // 不得因"lastNavAt 尚未同步"而误超时。
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }

        // 初始 lastNavAt=0：若 tick 读到撕裂的旧时间戳（0），
        // 4_000 - 0 > 3_000 会误判超时；正确实现读快照后 4_000 - 4_000 = 0。
        p.onNaviState(navi(), 4_000L)
        p.tick(4_000L)

        assertEquals(ClusterState.ACTIVE, p.status.value.state)
        assertTrue(t.calls.any { it.startsWith("tbt:") })
    }

    @Test
    fun `R-2 快照读：过期帧仍按快照内时间戳判超时`() {
        // 负向对照：快照内 receivedAt 真实过期（1_000 vs now 4_001 > 3_000），
        // tick 仍必须 release，防止快照化削弱超时保护。
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        t.calls.clear()

        p.tick(4_001L)  // 快照 receivedAt=1_000，4_001-1_000=3_001 > 3_000
        assertEquals(ClusterState.RELEASED, p.status.value.state)
    }

    @Test
    fun `分通道关闭时不调用对应Transport`() {
        val t = FakeTransport()
        val p = ClusterPublisher(
            t,
            FakeSettings(tbtEnabled = false, speedEnabled = false, estEnabled = false, textEnabled = false)
        ) { 1_000L }
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        assertTrue(t.calls.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 地图态罗盘（displayType=8）：实车定位"仪表圆圈每 2 秒闪一次"的根因修复
    // 高德未导航时只发 10001 的 CAR_DIRECTION，航向静止不变；
    // 罗盘帧若按 2 秒保活重发，HSAE 仪表每次重绘外圈 → 圆圈闪烁。
    // ---------------------------------------------------------------------

    private fun compassNavi(dir: Int, valid: Boolean = false, now: Long = 1_000L) = NaviState(
        navValid = valid,
        tbtDistance = 0,
        tbtTurnType = 0,
        carDirection = dir,
        roadSpeedLimit = 0,
        currentRoadName = "环馆路",
        timestamp = now
    )

    @Test
    fun `罗盘帧不保活重发，避免仪表圆圈闪烁`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(compassNavi(90), 1_000L)
        p.tick(1_000L)
        val first = t.calls.count { it.startsWith("tbt:8:") }
        assertEquals("首帧应发送一次罗盘", 1, first)

        // 2 秒、4 秒、10 秒后方向未变：不得重发罗盘帧
        p.tick(3_000L)
        p.tick(5_000L)
        p.tick(11_000L)
        assertEquals(
            "罗盘方向未变时不得保活重发（否则仪表圆圈闪烁）",
            1, t.calls.count { it.startsWith("tbt:8:") }
        )
    }

    @Test
    fun `罗盘方向变化时立即重发`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(compassNavi(90), 1_000L)
        p.tick(1_000L)

        p.onNaviState(compassNavi(180), 2_000L)
        p.tick(2_000L)
        assertEquals("方向变化应额外发送一帧", 2, t.calls.count { it.startsWith("tbt:8:") })
    }

    @Test
    fun `罗盘模式数据间歇时不交还，保持常显`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(compassNavi(90), 1_000L)
        p.tick(1_000L)
        t.calls.clear()

        // 高德地图态 10001 是事件驱动，十几秒才一条：不得因 3 秒超时交还仪表
        p.tick(12_000L)
        assertEquals(ClusterState.ACTIVE, p.status.value.state)
        assertTrue("罗盘模式不应触发交还序列", t.calls.none { it.startsWith("tbt:3:") || it.startsWith("tbt:8:0:0:0:0") })
    }

    @Test
    fun `无航向且未导航时才交还仪表`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(compassNavi(90), 1_000L)
        p.tick(1_000L)

        // 航向丢失（-1）且没有导航数据：交还
        p.onNaviState(compassNavi(-1), 1_100L)
        p.tick(1_100L)
        assertEquals(ClusterState.RELEASED, p.status.value.state)
    }

    @Test
    fun `普通TBT箭头仍按2秒保活`() {
        val t = FakeTransport()
        val p = ClusterPublisher(t, FakeSettings()) { 1_000L }
        p.onNaviState(navi(), 1_000L)   // tbtTurnType=3 → 普通箭头
        p.tick(1_000L)
        val first = t.calls.count { it.startsWith("tbt:") }

        p.onNaviState(navi(), 3_500L)   // 同样内容，仅刷新到达时间
        p.tick(3_500L)
        assertEquals("普通箭头仍需保活", first + 1, t.calls.count { it.startsWith("tbt:") })
    }
}
