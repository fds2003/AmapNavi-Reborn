package tech.touka.amapnavi

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import tech.touka.amapnavi.cluster.BitWriter

/**
 * HSAE 8 字节位域写入器测试。
 *
 * 位序约定：bit 0 = 字节最低位（LSB-first），与原厂 AdasUtil::SetBitwideValue 一致。
 */
class BitWriterTest {

    @Test
    fun `同字节内LSB优先写入`() {
        val writer = BitWriter(2)
        writer.set(bitOffset = 0, width = 5, value = 0b10110)
        writer.set(bitOffset = 5, width = 3, value = 0b101)
        assertArrayEquals(byteArrayOf(0xB6.toByte(), 0x00), writer.toByteArray())
    }

    @Test
    fun `跨字节写入`() {
        val writer = BitWriter(2)
        writer.set(bitOffset = 6, width = 4, value = 0b1111)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x03), writer.toByteArray())
    }

    @Test
    fun `超出宽度的值按低位截断`() {
        val writer = BitWriter(1)
        writer.set(bitOffset = 0, width = 3, value = 0xFF)
        assertArrayEquals(byteArrayOf(0x07), writer.toByteArray())
    }

    @Test
    fun `越界写入被忽略`() {
        val writer = BitWriter(1)
        writer.set(bitOffset = 6, width = 4, value = 0xF)
        // 只写入落在缓冲区内的 bit 6/7
        assertArrayEquals(byteArrayOf(0xC0.toByte()), writer.toByteArray())
    }
}
