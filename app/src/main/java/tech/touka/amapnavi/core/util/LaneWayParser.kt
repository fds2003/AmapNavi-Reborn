package tech.touka.amapnavi.core.util

/**
 * EXTRA_DRIVE_WAY 车道字符串解析器（纯 Kotlin，无 Android 依赖）。
 *
 * 契约格式：
 *  - 高德 13012 JSON：drive_way_info[].drive_way_lane_Back_icon
 *  - 历史竖线/斜线串："s|s|S|X"
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

    // 高德 13012 的车道拓扑是 JSON 对象（不是文档 14 所述的竖线串）。
    // schema 固定且由高德生成，这里用轻量正则解析，保持本文件纯 Kotlin、可在 JVM 单测运行。
    private val JSON_ENABLED = Regex("\"drive_way_enabled\"\\s*:\\s*(true|false)")
    private val JSON_INFO = Regex("\"drive_way_info\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
    private val JSON_OBJ = Regex("\\{([^{}]*)}")
    private val JSON_ICON = Regex("\"drive_way_lane_Back_icon\"\\s*:\\s*\"?(-?\\d+)\"?")
    private val JSON_ADVISED = Regex("\"trafficLaneAdvised\"\\s*:\\s*(true|false)")

    /** 图标码 < 15 视为禁行，与原版 APK parseDriveWay 的判定一致。 */
    private const val ICON_DISABLED_MAX = 15

    fun parse(driveWayRaw: String?): ParsedLaneWay {
        if (driveWayRaw.isNullOrEmpty()) return ParsedLaneWay()

        // 13012 JSON 载荷
        if (driveWayRaw.trimStart().startsWith("{")) return parseJsonDriveWay(driveWayRaw)

        // 历史格式：高德文档示例用 "/"，抓包用 "|"；两种都接受，避免整串被当单车道
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

    /**
     * 解析 13012 的 EXTRA_DRIVE_WAY JSON。
     * 每条车道：icon < 15 → 禁行；trafficLaneAdvised=true → 推荐。
     */
    fun parseJsonDriveWay(raw: String): ParsedLaneWay {
        val enabled = JSON_ENABLED.find(raw)?.groupValues?.get(1)?.toBoolean() ?: false
        if (!enabled) return ParsedLaneWay(raw = raw)

        val info = JSON_INFO.find(raw)?.groupValues?.get(1) ?: return ParsedLaneWay(raw = raw)
        val tokens = ArrayList<String>()
        val suggest = ArrayList<Int>()
        val disable = ArrayList<Int>()

        JSON_OBJ.findAll(info).forEachIndexed { index, match ->
            val body = match.groupValues[1]
            val icon = JSON_ICON.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val advised = JSON_ADVISED.find(body)?.groupValues?.get(1)?.toBoolean() ?: false
            when {
                icon < ICON_DISABLED_MAX -> {
                    tokens.add("X")
                    disable.add(index)
                }
                advised -> {
                    tokens.add("S")
                    suggest.add(index)
                }
                else -> tokens.add("s")
            }
        }

        return ParsedLaneWay(raw = raw, tokens = tokens, suggest = suggest, disable = disable)
    }
}
