package tech.touka.amapnavi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.touka.amapnavi.cluster.AdasPayloadCodec
import tech.touka.amapnavi.cluster.MetaFields
import tech.touka.amapnavi.cluster.PositionFields
import tech.touka.amapnavi.cluster.ProfileLongFields
import tech.touka.amapnavi.cluster.ProfileShortFields
import tech.touka.amapnavi.cluster.SegmentFields

/**
 * ADAS 电子地平线 8 字节位域布局测试。
 *
 * 位偏移来自 Ghidra 对 AdasManager::sendPosMsg / sendSegMsg / sendProShortMsg /
 * sendProLongMsg 的还原。字段语义：
 *
 * Position(458)：byte0[0..4]=车道号, [5..7]=路线编号; byte1=偏航计数;
 *   byte2[0..5]=车向, [6..7]=保留; byte3[0]=静止, [1..5]=GPS质量, [6..7]=卫星数;
 *   byte4=高程; byte5[0]=隧道位, [1..3]=路线数, [4..6]=偏航数; byte6=北向?（未知保留）;
 *   byte7=北向。
 *
 * Segment(202)：word0 = type(2) + segId/偏移 + 道路属性；word1.. = 限速/车道/转向。
 * 语义映射见 AdasPayloadCodec 注释。
 */
class AdasPayloadCodecTest {

    @Test
    fun `Position字段位偏移正确`() {
        val payload = AdasPayloadCodec.buildPosition(
            PositionFields(
                laneId = 1,
                field2 = 0xAB,
                field13 = 0xCD
            )
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0xAB.toByte(), 0, 0, 0, 0, 0, 0xCD.toByte()),
            payload
        )
    }

    @Test
    fun `Position跨字段组合`() {
        val payload = AdasPayloadCodec.buildPosition(
            PositionFields(
                laneId = 0b10110,
                routeId = 0b101,
                field3 = 0b111111,
                field4 = 0b10,
                field5 = 1,
                field6 = 0b10101,
                field7 = 0b11,
                field9 = 1,
                field10 = 0b111,
                field11 = 0b101
            )
        )
        // byte0: f0(10110) | f1(101)<<5 = 1011 0110
        assertEquals(0xB6.toByte(), payload[0])
        // byte2: f3(111111) | f4(10)<<6 = 10 111111
        assertEquals(0xBF.toByte(), payload[2])
        // byte3: f5(1) | f6(10101)<<1 | f7(11)<<6 = 11 10101 1
        assertEquals(0xEB.toByte(), payload[3])
        // byte5: f9(1) | f10(111)<<1 | f11(101)<<4 = 101 111 1
        assertEquals(0x5F.toByte(), payload[5])
    }

    @Test
    fun `Position车道号与路线编号命名访问`() {
        // byte0[0..4]=field0（车道号），byte0[5..7]=field1（路线编号）
        // 车道 5 | 路线 2<<5 = 5 | 0x40 = 0x45
        val payload = AdasPayloadCodec.buildPosition(
            PositionFields(laneId = 5, routeId = 2)
        )
        assertEquals(0x45.toByte(), payload[0])
    }

    @Test
    fun `Position固定8字节`() {
        assertEquals(8, AdasPayloadCodec.buildPosition(PositionFields()).size)
    }

    @Test
    fun `Segment字段位偏移正确`() {
        val payload = AdasPayloadCodec.buildSegment(
            SegmentFields(
                field0 = 1,
                segId = 0xAB,
                field17 = 3
            )
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0xAB.toByte(), 0, 0, 0, 0, 0, 0x60),
            payload
        )
    }

    @Test
    fun `Segment固定8字节`() {
        assertEquals(8, AdasPayloadCodec.buildSegment(SegmentFields()).size)
    }

    // ---- Profile Short（204）----
    // 实际位偏移（Ghidra sendProShortMsg，LSB-first）：
    // f0[0..4] f1[5..7] f2[8..15] f3[16..21] f4[22..23] f5[24..29]
    // f6[30..31] f7[32..35] f8[36..39] f9[40..41] f10[42..47]
    // f11[48..55] f12[56] f13[57] f14[58] f15[59..63]。
    @Test
    fun `ProfileShort字段位偏移正确`() {
        val payload = AdasPayloadCodec.buildProfileShort(
            ProfileShortFields(
                field0 = 1,
                field1 = 1,
                field2 = 0xAB,
                field3 = 0x3F,
                field4 = 0b10,
                field5 = 0x5A,
                field6 = 0b01,
                field7 = 0b1010,
                field8 = 0b0101,
                field9 = 0b11,
                field10 = 0b101010,
                field11 = 0xCD,
                field12 = 1,
                field13 = 1,
                field14 = 0,
                field15 = 0b11111
            )
        )
        // 由 LSB-first 位域推导：
        // byte0 = f0(1) | f1(1)<<5 = 0x21
        // byte1 = f2 = 0xAB
        // byte2 = f3(0x3F) | f4(2)<<6 = 0xBF
        // byte3 = f5(0x5A)<<0 | f6(1)<<6 = 0x5A
        // byte4 = f7(10)<<0 | f8(5)<<4 | f9(3)<<8 = 0x5A
        // byte5 = f10(42)<<0 | f11(0xCD) 的高 2 位<<6 = 0xAB
        // byte6 = f11 = 0xCD
        // byte7 = f12(1)<<0 | f13(1)<<1 | f15(31)<<3 = 0xFB
        assertEquals(0x21.toByte(), payload[0])
        assertEquals(0xAB.toByte(), payload[1])
        assertEquals(0xBF.toByte(), payload[2])
        assertEquals(0x5A.toByte(), payload[3])
        assertEquals(0x5A.toByte(), payload[4])
        assertEquals(0xAB.toByte(), payload[5])
        assertEquals(0xCD.toByte(), payload[6])
        assertEquals(0xFB.toByte(), payload[7])
    }

    @Test
    fun `ProfileShort固定8字节`() {
        assertEquals(8, AdasPayloadCodec.buildProfileShort(ProfileShortFields()).size)
    }

    // ---- Profile Long（205）----
    // 实际位偏移（Ghidra sendProLongMsg，LSB-first）：
    // f0[0..4] f1[5..7] f2[8..15] f3[16..21] f4[22..23] f5[24]
    // f6[25..29] f7[30..31] f8[32..35] f9[36..39] f10[40..41]
    // f11[42] f12[43..47]。
    @Test
    fun `ProfileLong字段位偏移正确`() {
        val payload = AdasPayloadCodec.buildProfileLong(
            ProfileLongFields(
                field0 = 1,
                field1 = 1,
                field2 = 0xAB,
                field3 = 0x3F,
                field4 = 0b10,
                field5 = 1,
                field6 = 0b10101,
                field7 = 0b11,
                field8 = 0b1010,
                field9 = 0b0101,
                field10 = 0b11,
                field11 = 1,
                field12 = 0b11111
            )
        )
        assertEquals(0x21.toByte(), payload[0])
        assertEquals(0xAB.toByte(), payload[1])
        assertEquals(0xBF.toByte(), payload[2])  // f3(0x3F) | f4(2)<<6 | f5(1)<<7
        assertEquals(0xEB.toByte(), payload[3])  // f5(1)<<0 | f6(21)<<1 | f7(3)<<6
        assertEquals(0x5A.toByte(), payload[4])  // f8(10)<<0 | f9(5)<<4 | f10(3)<<8
        assertEquals(0xFF.toByte(), payload[5])  // f10(3)<<0 | f11(1)<<2 | f12(31)<<3
    }

    @Test
    fun `ProfileLong固定8字节`() {
        assertEquals(8, AdasPayloadCodec.buildProfileLong(ProfileLongFields()).size)
    }

    // ---- Meta（459）----
    // 实际位偏移（Ghidra sendMetaMsg，LSB-first）：
    // f0[0..1] f1[2..4] f2[5..7] f3[8..15] f4[16] f5[17..19]
    // f6[20..21] f7[22..23] f8[24..31] f9[32..38] f10[39] f11[40..47] f12[48..54]。
    @Test
    fun `Meta字段位偏移正确`() {
        val payload = AdasPayloadCodec.buildMeta(
            MetaFields(
                msgSeq = 1,
                field1 = 1,
                frameTypeFlag = 2,
                tsHigh = 0xAB,
                tsLow = 1,
                field5 = 1,
                field6 = 0b11,
                field7 = 0b10,
                field8 = 0xCD,
                field9 = 0b1010101,
                stateByte1 = 1,
                stateByte2 = 0x5F,
                field12 = 0b1100001
            )
        )
        assertEquals(0x45.toByte(), payload[0])  // f0(1) | f1(1)<<2 | f2(2)<<5
        assertEquals(0xAB.toByte(), payload[1])
        assertEquals(0xB3.toByte(), payload[2])  // f4(1) | f5(1)<<1 | f6(3)<<5 | f7(2)<<7
        assertEquals(0xCD.toByte(), payload[3])
        assertEquals(0xD5.toByte(), payload[4])  // f9(1010101) | f10(1)<<7
        assertEquals(0x5F.toByte(), payload[5])
        assertEquals(0x61.toByte(), payload[6])  // f12(1100001)<<0（低 5 位 00001 + 高 2 位 11）
        assertEquals(0.toByte(), payload[7])
    }

    @Test
    fun `Meta固定8字节`() {
        assertEquals(8, AdasPayloadCodec.buildMeta(MetaFields()).size)
    }

    @Test
    fun `ProfileShortCurve弯道子构造`() {
        // curveType=4 → byte0[0..4]; curvature=0x10 → byte2[0..5]; rangeDist 默认 0
        // divider=1 → byte7[3]
        val payload = AdasPayloadCodec.buildProfileShortCurve(
            curveType = 4,
            curvature = 0x10,
            rangeDist = 0,
            divider = 1
        )
        assertArrayEquals(
            byteArrayOf(0x04, 0, 0x10, 0, 0, 0, 0, 0x08),
            payload
        )
    }
}
