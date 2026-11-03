package tech.touka.amapnavi.core.util

/**
 * EXTRA_DRIVE_WAY 车道字符串解析器（纯 Kotlin，无 Android 依赖）。
 *
 * 契约格式："s|s|S|X"
 *  - 'X'       ：禁行车道
 *  - 大写字母   ：推荐车道（'S'/'L'/'R'）
 *  - 小写字母   ：普通可用车道
 */
data class ParsedLaneWay(
    val raw: String = "",
    val tokens: List<String> = emptyList(),
    val suggest: List<Int> = emptyList(),
    val disable: List<Int> = emptyList()
) {
    val laneCount: Int
        get() = tokens.size

    /** 取指定索引车道字符（越界返回空串）。 */
    fun tokenAt(index: Int): String {
        return if (index in tokens.indices) tokens[index] else ""
    }

    /**
     * 假定自车沿高德推荐车道行驶，推算相邻目标车道索引。
     * 无推荐车道或无导航数据时返回 -1（故障安全：视为无车道）。
     */
    fun targetLaneIndex(direction: String): Int {
        if (suggest.isEmpty() || laneCount == 0) return -1
        val current = suggest[0]
        val target = when (direction) {
            "LEFT" -> current - 1
            "RIGHT" -> current + 1
            else -> return -1
        }
        return if (target in 0 until laneCount) target else -1
    }
}

object LaneWayParser {

    fun parse(driveWayRaw: String?): ParsedLaneWay {
        if (driveWayRaw.isNullOrEmpty()) return ParsedLaneWay()

        // 高德文档示例用 "/" 分隔，历史抓包用 "|"；两种都接受，避免整串被当单车道
        val tokens = driveWayRaw.split("|", "/")
        val suggest = ArrayList<Int>()
        val disable = ArrayList<Int>()

        tokens.forEachIndexed { index, token ->
            if (token.isNotEmpty()) {
                val c = token[0]
                // 文档中的 x 大小写混用，统一按禁行处理（fail-closed）
                if (c == 'X' || c == 'x') {
                    disable.add(index)
                } else if (c.isUpperCase()) {
                    suggest.add(index)
                }
            }
        }

        return ParsedLaneWay(
            raw = driveWayRaw,
            tokens = tokens,
            suggest = suggest,
            disable = disable
        )
    }
}
