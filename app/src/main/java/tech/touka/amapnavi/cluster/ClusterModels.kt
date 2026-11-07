package tech.touka.amapnavi.cluster

/**
 * 一帧仪表数据。
 *
 * 字段顺序与 HSAE Parcel 布局无关，仅作为 Mapper 的输出模型；
 * 具体写 Parcel 的顺序由 ClusterTransport 实现负责。
 */
data class TbtFrame(
    val displayType: Int,
    val direction: Int = 0,
    val distVal: Int,
    val scale: Int,
    val frac: Int
)

data class SpeedFrame(val kph: Int)

data class EstFrame(
    val distF: Int,
    val unit: Int,
    val hour: Int,
    val minute: Int,
    val distVal: Int,
    val timeType: Int
)

data class TextFrame(val text: String)

data class ClusterFrame(
    val tbt: TbtFrame,
    val speed: SpeedFrame,
    val est: EstFrame,
    val text: TextFrame
)
