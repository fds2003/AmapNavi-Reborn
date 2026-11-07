package tech.touka.amapnavi.cluster

/**
 * 仪表 Parcel 字段写入抽象。
 *
 * 生产实现包装 android.os.Parcel；单测用 RecordingSink 断言字段顺序。
 */
interface ParcelSink {
    fun byte(value: Int)
    fun int(value: Int)
    fun string(value: String)
    fun byteArray(value: ByteArray)
}

/**
 * HSAE 四类帧的权威 Parcel 布局。
 *
 * 与 adbridge-custin / 原厂 ADAS 的写入顺序逐字段一致，
 * 详细依据见文档 12 §4。
 */
object HsaeFrameCodec {

    fun writeTbt(sink: ParcelSink, frame: TbtFrame) {
        sink.byte(frame.displayType)
        sink.int(15)                  // sideStreet
        sink.byte(frame.direction)
        sink.int(frame.distVal)
        sink.byte(15)                 // combinedSideStreet
        sink.byte(frame.scale)
        sink.byte(frame.frac)
    }

    fun writeSpeed(sink: ParcelSink, frame: SpeedFrame) {
        repeat(6) { sink.byte(0) }    // overSpdAlarm/nonSpdTrap/3×warningZone/curveWarning
        sink.byte(1)                  // spdLimitType
        sink.byte(0)                  // spdLimitUnit
        sink.byte(1)                  // spdInfoType
        sink.byte(frame.kph)
    }

    fun writeEst(sink: ParcelSink, frame: EstFrame) {
        sink.byte(frame.distF)
        sink.byte(frame.unit)
        sink.byte(frame.hour)
        sink.byte(frame.minute)
        sink.byte(0)                  // azimuth
        sink.int(frame.distVal)
        sink.byte(1)                  // estimTimeFormat
        sink.byte(frame.timeType)
        sink.byte(0)                  // estPointType
    }

    fun writeText(sink: ParcelSink, frame: TextFrame) {
        sink.byte(6)
        sink.string(frame.text)
    }

    /** INavigation transact 14：writeByteArray(payload) + writeInt(type)。 */
    fun writeAdas(sink: ParcelSink, payload: ByteArray, type: Int) {
        sink.byteArray(payload)
        sink.int(type)
    }
}
