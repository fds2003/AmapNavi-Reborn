package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.touka.amapnavi.cluster.EstFrame
import tech.touka.amapnavi.cluster.HsaeFrameCodec
import tech.touka.amapnavi.cluster.ParcelSink
import tech.touka.amapnavi.cluster.SpeedFrame
import tech.touka.amapnavi.cluster.TbtFrame
import tech.touka.amapnavi.cluster.TextFrame

/**
 * HSAE Parcel 布局契约测试（纯 JVM）。
 *
 * 用 RecordingSink 精确断言每类写入的字段顺序/类型/值，
 * 防止字段顺序回退（Android Binder 本身无法在 JVM 单测）。
 */
class HsaeFrameCodecTest {

    private class RecordingSink : ParcelSink {
        val ops = mutableListOf<String>()
        override fun byte(value: Int) { ops += "b:$value" }
        override fun int(value: Int) { ops += "i:$value" }
        override fun string(value: String) { ops += "s:$value" }
        override fun byteArray(value: ByteArray) { ops += "ba:${value.joinToString(",")}" }
    }

    @Test
    fun `TBT写入顺序`() {
        val sink = RecordingSink()
        HsaeFrameCodec.writeTbt(sink, TbtFrame(displayType = 70, direction = 0, distVal = 1, scale = 1, frac = 5))
        assertEquals(
            listOf("b:70", "i:15", "b:0", "i:1", "b:15", "b:1", "b:5"),
            sink.ops
        )
    }

    @Test
    fun `限速写入顺序`() {
        val sink = RecordingSink()
        HsaeFrameCodec.writeSpeed(sink, SpeedFrame(100))
        assertEquals(
            listOf("b:0", "b:0", "b:0", "b:0", "b:0", "b:0", "b:1", "b:0", "b:1", "b:100"),
            sink.ops
        )
    }

    @Test
    fun `行程写入顺序`() {
        val sink = RecordingSink()
        HsaeFrameCodec.writeEst(sink, EstFrame(distF = 5, unit = 1, hour = 0, minute = 30, distVal = 1, timeType = 2))
        assertEquals(
            listOf("b:5", "b:1", "b:0", "b:30", "b:0", "i:1", "b:1", "b:2", "b:0"),
            sink.ops
        )
    }

    @Test
    fun `文本写入顺序`() {
        val sink = RecordingSink()
        HsaeFrameCodec.writeText(sink, TextFrame("机场高速"))
        assertEquals(listOf("b:6", "s:机场高速"), sink.ops)
    }

    @Test
    fun `ADAS载荷写入顺序`() {
        val sink = RecordingSink()
        HsaeFrameCodec.writeAdas(sink, byteArrayOf(1, 2), 458)
        assertEquals(listOf("ba:1,2", "i:458"), sink.ops)
    }
}
