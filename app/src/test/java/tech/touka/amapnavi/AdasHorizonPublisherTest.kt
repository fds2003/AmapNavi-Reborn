package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.cluster.AdasHorizonPublisher
import tech.touka.amapnavi.cluster.AdasPayloadCodec
import tech.touka.amapnavi.cluster.ClusterSettings
import tech.touka.amapnavi.cluster.ClusterTransport
import tech.touka.amapnavi.cluster.EstFrame
import tech.touka.amapnavi.cluster.ProbeResult
import tech.touka.amapnavi.cluster.SpeedFrame
import tech.touka.amapnavi.cluster.TbtFrame
import tech.touka.amapnavi.cluster.TextFrame
import tech.touka.amapnavi.core.model.NaviState

/**
 * ADAS 电子地平线发布器契约测试（纯 JVM）。
 *
 * 验证：开关默认关闭、真实数据填充（车道/航向）、Segment 去重、
 * 限速跟随、发送失败记录错误。
 *
 * 字节验证策略：直接用 [AdasPayloadCodec] 构造期望字节数组比较
 * （FakeTransport 记录完整字节，避免十进制负数解析歧义）。
 */
class AdasHorizonPublisherTest {

    private class FakeSettings(
        override var enabled: Boolean = true,
        override var tbtEnabled: Boolean = true,
        override var speedEnabled: Boolean = true,
        override var estEnabled: Boolean = true,
        override var textEnabled: Boolean = true,
        override var tlEnabled: Boolean = true,
        override var etaMode: Int = 1,
        override var experimentalIcons: Boolean = false,
        override var adasEnabled: Boolean = false
    ) : ClusterSettings

    private class FakeTransport : ClusterTransport {
        val adas = mutableListOf<Pair<Int, ByteArray>>()
        var fail = false

        override fun sendTbt(frame: TbtFrame): Boolean = !fail
        override fun sendSpeed(frame: SpeedFrame): Boolean = !fail
        override fun sendEst(frame: EstFrame): Boolean = !fail
        override fun sendText(frame: TextFrame): Boolean = !fail

        override fun sendAdas(payload: ByteArray, type: Int): Boolean {
            adas += (type to payload.copyOf())
            return !fail
        }

        override fun probe(): ProbeResult = ProbeResult.Ok
    }

    private fun navi(
        now: Long = 1_000L,
        valid: Boolean = true,
        speed: Int = 100,
        lanes: Int = 2,
        roadLimit: Int = 80,
        tbtDist: Int = 1_500,
        icon: Int = 3,
        direction: Int = 90
    ) = NaviState(
        navValid = valid,
        curSpeed = speed,
        suggestLanes = if (lanes > 0) List(lanes) { 0 } else emptyList(),
        roadSpeedLimit = roadLimit,
        tbtDistance = tbtDist,
        tbtTurnType = icon,
        carDirection = direction,
        timestamp = now
    )

    private fun adasCalls(t: FakeTransport, type: Int): List<ByteArray> =
        t.adas.mapNotNull { (type2, payload) -> if (type2 == type) payload else null }

    @Test
    fun `ADAS关闭时零发送`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = false))
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        assertTrue(t.adas.isEmpty())
    }

    @Test
    fun `ADAS开启时发送Position与Segment`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)

        assertEquals(1, adasCalls(t, AdasPayloadCodec.TYPE_POSITION).size)
        assertEquals(1, adasCalls(t, AdasPayloadCodec.TYPE_SEGMENT).size)
        assertEquals(1, adasCalls(t, AdasPayloadCodec.TYPE_META).size)
    }

    @Test
    fun `Meta前导帧序号与帧标志`() {
        val p = AdasHorizonPublisher(FakeTransport(), FakeSettings(adasEnabled = true))
        val first = p.buildMeta()
        // 首次：序号 0，帧类型标志 = 2（byte0[5..7]）
        assertEquals(0, first[0].toInt() and 0b00000011)
        assertEquals(2, first[0].toInt() ushr 5 and 0b111)
    }

    @Test
    fun `弯道TBT转向生成ProfileShort帧`() {
        val p = AdasHorizonPublisher(FakeTransport(), FakeSettings(adasEnabled = true))
        // 左转 (icon 3) → curveType 1 → byte0[0..4]=1
        val curve = p.buildCurve(navi(icon = 3))
        assertEquals(1, curve[0].toInt() and 0x1F)
        // 曲率码 0x10 → byte2[0..5]
        assertEquals(0x10, curve[2].toInt() and 0x3F)
    }

    @Test
    fun `直行不生成弯道帧`() {
        val p = AdasHorizonPublisher(FakeTransport(), FakeSettings(adasEnabled = true))
        val straight = p.buildCurve(navi(icon = 2))
        // 全零帧（非弯道）
        assertTrue(straight.all { it == 0.toByte() })
    }

    @Test
    fun `弯道帧随转向变化重发`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(icon = 2), 1_000L)
        p.tick(1_000L)
        val curveCount = adasCalls(t, AdasPayloadCodec.TYPE_PROFILE_SHORT).size

        p.onNaviState(navi(icon = 3), 1_200L)
        p.tick(1_200L)
        assertEquals(curveCount + 1, adasCalls(t, AdasPayloadCodec.TYPE_PROFILE_SHORT).size)
    }

    @Test
    fun `Position车道号与航向真实数据`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(lanes = 3, direction = 45), 1_000L)
        p.tick(1_000L)

        val payload = adasCalls(t, AdasPayloadCodec.TYPE_POSITION).single()
        // byte0[0..4] = 车道号 3
        assertEquals(3, payload[0].toInt() and 0x1F)
        // byte2[0..5] = 航向 45°/6 = 7
        assertEquals(7, payload[2].toInt() and 0x3F)
    }

    @Test
    fun `Position与codec手工构造一致`() {
        val p = AdasHorizonPublisher(FakeTransport(), FakeSettings(adasEnabled = true))
        val payload = p.buildPosition(navi(lanes = 3, direction = 45))
        val expected = AdasPayloadCodec.buildPosition(
            tech.touka.amapnavi.cluster.PositionFields(field0 = 3, field3 = 7)
        )
        assertEquals(expected.contentToString(), payload.contentToString())
    }

    @Test
    fun `Segment消息类型位为2`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(roadLimit = 100), 1_000L)
        p.tick(1_000L)

        val payload = adasCalls(t, AdasPayloadCodec.TYPE_SEGMENT).single()
        // byte0[5..7] = 消息类型 = 2
        assertEquals(2, payload[0].toInt() ushr 5 and 0b111)
    }

    @Test
    fun `导航无效时不发ADAS`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(valid = true), 1_000L)
        p.tick(1_000L)
        t.adas.clear()

        p.onNaviState(navi(valid = false), 1_100L)
        p.tick(1_100L)
        assertTrue(t.adas.isEmpty())
    }

    @Test
    fun `限速变化触发Segment重发`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(roadLimit = 80), 1_000L)
        p.tick(1_000L)
        val segCount = adasCalls(t, AdasPayloadCodec.TYPE_SEGMENT).size

        p.onNaviState(navi(roadLimit = 100), 1_200L)
        p.tick(1_200L)
        assertEquals(segCount + 1, adasCalls(t, AdasPayloadCodec.TYPE_SEGMENT).size)
    }

    @Test
    fun `TBT距离变化触发Segment重发`() {
        val t = FakeTransport()
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(tbtDist = 1_500), 1_000L)
        p.tick(1_000L)
        val segCount = adasCalls(t, AdasPayloadCodec.TYPE_SEGMENT).size

        p.onNaviState(navi(tbtDist = 900), 1_200L)
        p.tick(1_200L)
        assertEquals(segCount + 1, adasCalls(t, AdasPayloadCodec.TYPE_SEGMENT).size)
    }

    @Test
    fun `发送失败记录错误`() {
        val t = FakeTransport().apply { fail = true }
        val p = AdasHorizonPublisher(t, FakeSettings(adasEnabled = true))
        p.onNaviState(navi(), 1_000L)
        p.tick(1_000L)
        assertTrue(p.lastError != null)
    }
}
