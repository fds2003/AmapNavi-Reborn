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

        assertTrue(t.calls.any { it.startsWith("tbt:70:") })
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
        assertTrue(t.calls.any { it.startsWith("tbt:67:800:0:0") })
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
}
