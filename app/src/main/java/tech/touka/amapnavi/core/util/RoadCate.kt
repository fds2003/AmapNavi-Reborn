package tech.touka.amapnavi.core.util

/**
 * 原版 APK `com.carrot.amapnavi.AmapUtilsKt` 的道路等级语义等价实现。
 *
 * - `conv` 对应 `roadcate_conv`：0→1、6→0、1→2、其它→8
 * - `isHighway` 对应 `isHighway`：roadcate ∈ {0,1}
 *
 * 提取为纯 Kotlin 对象，便于 JVM 单测直接覆盖。
 */
object RoadCate {
    fun conv(rawRoadType: Int): Int = when (rawRoadType) {
        0 -> 1
        6 -> 0
        1 -> 2
        else -> 8
    }

    fun isHighway(roadcate: Int): Boolean = roadcate == 0 || roadcate == 1
}
