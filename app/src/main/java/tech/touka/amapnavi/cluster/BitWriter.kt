package tech.touka.amapnavi.cluster

/**
 * 8 字节位域写入器（LSB-first）。
 *
 * bit 0 = byte0 的最低位；bit 8 = byte1 的最低位。
 * 与原厂 AdasUtil::SetBitwideValue 的位序假设一致，实车回放时再校准。
 */
class BitWriter(byteCount: Int) {

    private val bytes = ByteArray(byteCount)

    fun set(bitOffset: Int, width: Int, value: Int) {
        if (width <= 0) return
        val totalBits = bytes.size * 8
        for (i in 0 until width) {
            val absolute = bitOffset + i
            if (absolute < 0 || absolute >= totalBits) continue
            val bit = (value ushr i) and 1
            if (bit != 0) {
                val index = absolute / 8
                bytes[index] = (bytes[index].toInt() or (1 shl (absolute % 8))).toByte()
            }
        }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()
}
