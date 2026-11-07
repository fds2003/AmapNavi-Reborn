package tech.touka.amapnavi.cluster

/**
 * ADAS Position（HSAE type 458）的 8 字节位域。
 *
 * 位偏移来自 Ghidra 对 AdasManager::sendPosMsg 的还原。
 * - field0（byte0[0..4]）车道号
 * - field1（byte0[5..7]）路线编号
 */
data class PositionFields(
    val field0: Int = 0,
    val field1: Int = 0,
    val field2: Int = 0,
    val field3: Int = 0,
    val field4: Int = 0,
    val field5: Int = 0,
    val field6: Int = 0,
    val field7: Int = 0,
    val field8: Int = 0,
    val field9: Int = 0,
    val field10: Int = 0,
    val field11: Int = 0,
    val field12: Int = 0,
    val field13: Int = 0
)

/**
 * ADAS Segment（HSAE type 202）的 8 字节位域。
 *
 * 位偏移来自 Ghidra 对 AdasManager::sendSegMsg 的还原（LSB-first）：
 * - f0[0..4] f1[5..7]：byte0，f1 含消息类型（segment=2）
 * - f2[8..15]：段 ID / 偏移（updateLinkData 中 `local_9c & 0x1fff` 的低 8 位）
 * - f3[16..21]：功能道路等级（getFunctionalRoadClassValue）
 * - f4[22..23]：道路形态低位（getFormOfWayValue）
 * - f5[24]：限速编码低位
 * - f6[25..29]：限速编码高位（getSpeedLimitbyFormat，5 位）
 * - f7[30..31] f8[32..34] f9[35..39]：车道数 / 匝道属性 / 转向
 * - f10..f17：转向角度、区间长度等（未完全标定）
 */
data class SegmentFields(
    val field0: Int = 0,
    val field1: Int = 0,
    val field2: Int = 0,
    val field3: Int = 0,
    val field4: Int = 0,
    val field5: Int = 0,
    val field6: Int = 0,
    val field7: Int = 0,
    val field8: Int = 0,
    val field9: Int = 0,
    val field10: Int = 0,
    val field11: Int = 0,
    val field12: Int = 0,
    val field13: Int = 0,
    val field14: Int = 0,
    val field15: Int = 0,
    val field16: Int = 0,
    val field17: Int = 0,
    val field18: Int = 0,
    val field19: Int = 0
)

/**
 * ADAS Profile Short（HSAE type 204）的 8 字节位域。
 * 位偏移来自 Ghidra 对 AdasManager::sendProShortMsg 的还原：
 * - byte0[0..4]=field0, byte0[5..7]=field1（路线编号）;
 * - byte2[0..5]=曲率相关, byte3[0..5]=间隔, byte4[4..7]=分隔带值
 *   （getProfileShortDividerValue 结果经 getSpeedLimitbyFormat 换算）;
 * - byte5[0..1]=隧道/区间标志; byte7[3..7]=转向/区间类型。
 */
data class ProfileShortFields(
    val field0: Int = 0,
    val field1: Int = 0,
    val field2: Int = 0,
    val field3: Int = 0,
    val field4: Int = 0,
    val field5: Int = 0,
    val field6: Int = 0,
    val field7: Int = 0,
    val field8: Int = 0,
    val field9: Int = 0,
    val field10: Int = 0,
    val field11: Int = 0,
    val field12: Int = 0,
    val field13: Int = 0,
    val field14: Int = 0,
    val field15: Int = 0
)

/**
 * ADAS Profile Long（HSAE type 205）的 8 字节位域。
 * 位偏移来自 Ghidra 对 AdasManager::sendProLongMsg 的还原，
 * 与 Short 版共享 byte0..byte2 布局，byte5 增加一位（field11）。
 */
data class ProfileLongFields(
    val field0: Int = 0,
    val field1: Int = 0,
    val field2: Int = 0,
    val field3: Int = 0,
    val field4: Int = 0,
    val field5: Int = 0,
    val field6: Int = 0,
    val field7: Int = 0,
    val field8: Int = 0,
    val field9: Int = 0,
    val field10: Int = 0,
    val field11: Int = 0,
    val field12: Int = 0
)

/**
 * ADAS Meta（HSAE type 459）的 8 字节位域。
 *
 * 位偏移来自 Ghidra 对 AdasManager::sendMetaMsg（makeMetaMsg 填充）的还原。
 * Meta 是每条 ADAS 消息流的前导帧：携带消息序号、时间戳与状态标志。
 * 主要命名字段：
 * - [f0]（byte0[0..1]）消息序号
 * - [f2]（byte0[5..7]）帧类型标志
 * - [f3]（byte1）时间戳高 8 位
 * - [f4]（byte2）时间戳低 8 位 + 标志
 * - [f10]（byte5）状态/校验字节
 * - [f11]（byte6）状态/校验字节
 */
data class MetaFields(
    val field0: Int = 0,
    val field1: Int = 0,
    val field2: Int = 0,
    val field3: Int = 0,
    val field4: Int = 0,
    val field5: Int = 0,
    val field6: Int = 0,
    val field7: Int = 0,
    val field8: Int = 0,
    val field9: Int = 0,
    val field10: Int = 0,
    val field11: Int = 0,
    val field12: Int = 0
)

/**
 * ADAS 电子地平线载荷编码器。
 *
 * 实现 Position（458）、Segment（202）、ProfileShort（204）、ProfileLong（205）、
 * Meta（459）的 8 字节位布局。
 *
 * 注：Tunnel（type 207）在原厂并非独立 8 字节线格式，而是内部
 * `QList<_stTunnelInfo>` 列表，用于门控哪些隧道段被发射（makeTunnelEvent /
 * updateTunnelInfo），最终仍通过 Segment/Profile 通道发送，故此处不实现
 * buildTunnel。
 */
object AdasPayloadCodec {

    const val TYPE_SEGMENT = 202
    const val TYPE_STUB = 203
    const val TYPE_PROFILE_SHORT = 204
    const val TYPE_PROFILE_LONG = 205
    const val TYPE_TUNNEL = 207
    const val TYPE_CAMERA_ALERT = 407
    const val TYPE_POSITION = 458
    const val TYPE_META = 459

    fun buildPosition(fields: PositionFields): ByteArray {
        val writer = BitWriter(8)
        writer.set(bitOffset = 0, width = 5, value = fields.field0)
        writer.set(bitOffset = 5, width = 3, value = fields.field1)
        writer.set(bitOffset = 8, width = 8, value = fields.field2)
        writer.set(bitOffset = 16, width = 6, value = fields.field3)
        writer.set(bitOffset = 22, width = 2, value = fields.field4)
        writer.set(bitOffset = 24, width = 1, value = fields.field5)
        writer.set(bitOffset = 25, width = 5, value = fields.field6)
        writer.set(bitOffset = 30, width = 2, value = fields.field7)
        writer.set(bitOffset = 32, width = 8, value = fields.field8)
        writer.set(bitOffset = 40, width = 1, value = fields.field9)
        writer.set(bitOffset = 41, width = 3, value = fields.field10)
        writer.set(bitOffset = 44, width = 3, value = fields.field11)
        writer.set(bitOffset = 48, width = 8, value = fields.field12)
        writer.set(bitOffset = 56, width = 8, value = fields.field13)
        return writer.toByteArray()
    }

    fun buildSegment(fields: SegmentFields): ByteArray {
        val writer = BitWriter(8)
        writer.set(bitOffset = 0, width = 5, value = fields.field0)
        writer.set(bitOffset = 5, width = 3, value = fields.field1)
        writer.set(bitOffset = 8, width = 8, value = fields.field2)
        writer.set(bitOffset = 16, width = 6, value = fields.field3)
        writer.set(bitOffset = 22, width = 2, value = fields.field4)
        writer.set(bitOffset = 24, width = 1, value = fields.field5)
        writer.set(bitOffset = 25, width = 5, value = fields.field6)
        writer.set(bitOffset = 30, width = 2, value = fields.field7)
        writer.set(bitOffset = 32, width = 3, value = fields.field8)
        writer.set(bitOffset = 35, width = 5, value = fields.field9)
        writer.set(bitOffset = 40, width = 4, value = fields.field10)
        writer.set(bitOffset = 44, width = 2, value = fields.field11)
        writer.set(bitOffset = 46, width = 2, value = fields.field12)
        writer.set(bitOffset = 48, width = 3, value = fields.field13)
        writer.set(bitOffset = 51, width = 5, value = fields.field14)
        writer.set(bitOffset = 56, width = 3, value = fields.field15)
        writer.set(bitOffset = 59, width = 2, value = fields.field16)
        writer.set(bitOffset = 61, width = 2, value = fields.field17)
        return writer.toByteArray()
    }

    fun buildProfileShort(fields: ProfileShortFields): ByteArray {
        val writer = BitWriter(8)
        writer.set(bitOffset = 0, width = 5, value = fields.field0)
        writer.set(bitOffset = 5, width = 3, value = fields.field1)
        writer.set(bitOffset = 8, width = 8, value = fields.field2)
        writer.set(bitOffset = 16, width = 6, value = fields.field3)
        writer.set(bitOffset = 22, width = 2, value = fields.field4)
        writer.set(bitOffset = 24, width = 6, value = fields.field5)
        writer.set(bitOffset = 30, width = 2, value = fields.field6)
        writer.set(bitOffset = 32, width = 4, value = fields.field7)
        writer.set(bitOffset = 36, width = 4, value = fields.field8)
        writer.set(bitOffset = 40, width = 2, value = fields.field9)
        writer.set(bitOffset = 42, width = 6, value = fields.field10)
        writer.set(bitOffset = 48, width = 8, value = fields.field11)
        writer.set(bitOffset = 56, width = 1, value = fields.field12)
        writer.set(bitOffset = 57, width = 1, value = fields.field13)
        writer.set(bitOffset = 58, width = 1, value = fields.field14)
        writer.set(bitOffset = 59, width = 5, value = fields.field15)
        return writer.toByteArray()
    }

    /**
     * 弯道类 ProfileShort 帧（makeCurveMsg 还原）：word0 = type(curve) + 曲率码，
     * word1 = 曲率半径 + 区间距离码。
     *
     * @param curveType 曲线类型（byte0[0..1]，makeCurveMsg 中 `&0xf8|4` 的低 2 位）
     * @param curvature 曲率码（byte1[0..7]，`local_2c & 0x7f`，bit7=转向符号）
     * @param rangeDist 区间距离码（byte2[0..6]，`local_2c & 0x3ff` 的低 7 位）
     */
    fun buildProfileShortCurve(
        curveType: Int = 0,
        curvature: Int = 0,
        rangeDist: Int = 0,
        divider: Int = 0
    ): ByteArray {
        val fields = ProfileShortFields(
            field0 = curveType and 0x1F,
            field3 = curvature and 0x3F,
            field5 = rangeDist and 0x3F,
            field15 = divider and 0x1F
        )
        return buildProfileShort(fields)
    }

    fun buildProfileLong(fields: ProfileLongFields): ByteArray {
        val writer = BitWriter(8)
        writer.set(bitOffset = 0, width = 5, value = fields.field0)
        writer.set(bitOffset = 5, width = 3, value = fields.field1)
        writer.set(bitOffset = 8, width = 8, value = fields.field2)
        writer.set(bitOffset = 16, width = 6, value = fields.field3)
        writer.set(bitOffset = 22, width = 2, value = fields.field4)
        writer.set(bitOffset = 24, width = 1, value = fields.field5)
        writer.set(bitOffset = 25, width = 5, value = fields.field6)
        writer.set(bitOffset = 30, width = 2, value = fields.field7)
        writer.set(bitOffset = 32, width = 4, value = fields.field8)
        writer.set(bitOffset = 36, width = 4, value = fields.field9)
        writer.set(bitOffset = 40, width = 2, value = fields.field10)
        writer.set(bitOffset = 42, width = 1, value = fields.field11)
        writer.set(bitOffset = 43, width = 5, value = fields.field12)
        return writer.toByteArray()
    }

    fun buildMeta(fields: MetaFields): ByteArray {
        val writer = BitWriter(8)
        writer.set(bitOffset = 0, width = 2, value = fields.field0)
        writer.set(bitOffset = 2, width = 3, value = fields.field1)
        writer.set(bitOffset = 5, width = 3, value = fields.field2)
        writer.set(bitOffset = 8, width = 8, value = fields.field3)
        writer.set(bitOffset = 16, width = 1, value = fields.field4)
        writer.set(bitOffset = 17, width = 3, value = fields.field5)
        writer.set(bitOffset = 20, width = 2, value = fields.field6)
        writer.set(bitOffset = 22, width = 2, value = fields.field7)
        writer.set(bitOffset = 24, width = 8, value = fields.field8)
        writer.set(bitOffset = 32, width = 7, value = fields.field9)
        writer.set(bitOffset = 39, width = 1, value = fields.field10)
        writer.set(bitOffset = 40, width = 8, value = fields.field11)
        writer.set(bitOffset = 48, width = 7, value = fields.field12)
        return writer.toByteArray()
    }
}
