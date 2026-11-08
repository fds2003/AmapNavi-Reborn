package tech.touka.amapnavi.cluster

import tech.touka.amapnavi.core.model.NaviState
import java.util.Calendar

/**
 * NaviState -> ClusterFrame 的纯 Kotlin 映射。
 *
 * 不依赖 Android，保证 100% 可在 JVM 单测中覆盖。
 * 显示码与距离格式依据文档 12/13/16 的实车定案。
 */
object ClusterMapper {

    private const val ICON_DESTINATION = 13
    private const val DEST_FLAG_DISTANCE_M = 15

    fun toFrame(
        navi: NaviState,
        nowMs: Long,
        etaMode: Int,
        includeTrafficLight: Boolean = true,
        experimentalIcons: Boolean = false
    ): ClusterFrame {
        val destFlag = navi.tbtTurnType == ICON_DESTINATION &&
            navi.tbtDistance <= DEST_FLAG_DISTANCE_M
        // 罗盘只在"未导航"时出现：导航中高德部分 10001 帧不带 ICON，
        // 若按"无转向即罗盘"判定，仪表会在箭头与罗盘之间来回切换。
        val compassMode = !navi.navValid && !destFlag &&
            navi.tbtTurnType == 0 && navi.carDirection in 0..360
        val tbt = when {
            destFlag -> TbtFrame(
                displayType = 2,
                direction = formatCompass(navi.carDirection),
                distVal = 0,
                scale = 0,
                frac = 0
            )
            compassMode -> TbtFrame(
                displayType = 8,
                direction = formatCompass(navi.carDirection),
                distVal = 0,
                scale = 0,
                frac = 0
            )
            else -> formatTbt(navi.tbtTurnType, navi.tbtDistance, experimentalIcons)
        }
        val speed = SpeedFrame(
            when {
                navi.roadSpeedLimit > 0 -> navi.roadSpeedLimit
                navi.cameraSpeedLimit > 0 -> navi.cameraSpeedLimit
                else -> 0
            }
        )
        val est = formatEstimate(
            meters = navi.routeRemainDistance,
            seconds = navi.routeRemainTime,
            nowMs = nowMs,
            etaMode = etaMode,
            etaText = navi.etaText
        )
        return ClusterFrame(tbt, speed, est, TextFrame(buildText(navi, includeTrafficLight)))
    }

    /**
     * 航向角 -> HSAE 罗盘方向码（22.5° 一档，步长 3，0..47）。
     * 与原厂 getClusterDirection 一致：超出 47 归零。
     */
    fun formatCompass(bearing: Int): Int {
        if (bearing !in 0..360) return 0
        val code = ((360 - bearing) / 22.5f).toInt() * 3
        return if (code > 47) 0 else code
    }

    fun formatTbt(icon: Int, meters: Int, experimental: Boolean = false): TbtFrame {
        val d = formatDistance(meters)
        return TbtFrame(
            displayType = mapTurnType(icon, experimental),
            direction = 0,
            distVal = d.distVal,
            scale = d.scale,
            frac = d.frac
        )
    }

    /**
     * 高德广播 ICON -> HSAE displayType。
     *
     * 已标定项直接启用；环岛/收费站/匝道等未标定项只有打开 experimental 才发候选码，
     * 默认一律 255，避免盲发错误箭头。
     *
     * 左右镜像：实车（HSAE 车机）观察发现仪表箭头左右与高德地图相反，
     * 故全部左右成对码位互换（直行/掉头/目的地不受影响）：
     *   ICON=3 <-> 4（主要左右转）、5/16 <-> 6（左前/右前）、7 <-> 8（左后/右后）
     */
    fun mapTurnType(icon: Int, experimental: Boolean = false): Int = when (icon) {
        2, 10, 18, 19 -> 65
        3 -> 67
        4 -> 70
        5, 16 -> 66
        6 -> 71
        7 -> 68
        8 -> 69
        9 -> 72
        13 -> 112
        11 -> if (experimental) 96 else 255     // 进入环岛
        12 -> if (experimental) 97 else 255     // 离开环岛
        14 -> if (experimental) 212 else 255    // 收费站
        15 -> if (experimental) 178 else 255    // 驶出匝道
        17 -> if (experimental) 66 else 255     // 靠右
        20 -> if (experimental) 178 else 255    // 驶出高速
        else -> 255
    }

    data class Distance(val distVal: Int, val scale: Int, val frac: Int)

    /**
     * 原厂算法：
     * >=100km 取整公里；1–100km 四舍五入到 0.1km；<1km 用米。
     */
    fun formatDistance(meters: Int): Distance = when {
        meters >= 100_000 -> Distance(meters / 1000, 1, 0)
        meters >= 1_000 -> {
            val r = meters + 50
            Distance(r / 1000, 1, (r / 100) % 10)
        }
        else -> Distance(meters, 0, 0)
    }

    fun formatEstimate(
        meters: Int,
        seconds: Int,
        nowMs: Long,
        etaMode: Int,
        etaText: String = ""
    ): EstFrame {
        val d = formatDistance(meters)
        val total = seconds.coerceAtLeast(0)
        val hour: Int
        val minute: Int
        val timeType: Int
        val parsedEta = parseEtaText(etaText)
        if (etaMode == 1 && parsedEta != null) {
            hour = parsedEta.first
            minute = parsedEta.second
            timeType = 1
        } else if (etaMode == 1) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMs + total * 1000L
            hour = cal.get(Calendar.HOUR_OF_DAY)
            minute = cal.get(Calendar.MINUTE)
            timeType = 1
        } else {
            hour = total / 3600
            minute = (total % 3600) / 60
            timeType = 2
        }
        return EstFrame(
            distF = d.frac,
            unit = d.scale,
            hour = hour,
            minute = minute,
            distVal = d.distVal,
            timeType = timeType
        )
    }

    /** 已完成行程百分比；无总里程返回 -1。 */
    fun progressPercent(navi: NaviState): Int {
        val total = navi.routeTotalDistance
        if (total <= 0) return -1
        val remain = navi.routeRemainDistance.coerceIn(0, total)
        return ((total - remain) * 100 / total).coerceIn(0, 100)
    }

    fun buildText(navi: NaviState, includeTrafficLight: Boolean = true): String {
        val road = navi.nextRoadName.ifBlank { navi.currentRoadName }.ifBlank { "" }

        val prefix = when {
            includeTrafficLight && navi.trafficLightStatus != 0 && navi.trafficLightSeconds >= 0 -> {
                when (navi.trafficLightStatus) {
                    1 -> "[红灯 ${navi.trafficLightSeconds}s] "
                    2, 4 -> "[绿灯 ${navi.trafficLightSeconds}s] "
                    3 -> "[黄灯 ${navi.trafficLightSeconds}s] "
                    else -> ""
                }
            }
            navi.cameraType >= 0 && navi.cameraDistance in 1..500 -> "[测速 ${navi.cameraDistance}m] "
            else -> ""
        }

        val suffix = if (prefix.isEmpty()) {
            val progress = progressPercent(navi)
            when {
                progress >= 0 -> " ${progress}%"
                navi.trafficLightNum > 0 -> " ·${navi.trafficLightNum}灯"
                else -> ""
            }
        } else {
            ""
        }

        val roadBudget = (24 - prefix.length - suffix.length).coerceAtLeast(1)
        return (prefix + road.take(roadBudget) + suffix).take(24)
    }

    /**
     * 限速钳制：20–150，且每秒变化不超过 20。
     * next <= 0 表示清除圆牌。
     */
    fun clampSpeed(prev: Int, next: Int, elapsedMs: Long): Int {
        if (next <= 0) return 0
        val target = next.coerceIn(20, 150)
        if (prev <= 0) return target
        val maxDelta = ((elapsedMs / 1000.0) * 20.0).toInt().coerceAtLeast(1)
        val base = prev.coerceIn(20, 150)
        return (base + (target - base).coerceIn(-maxDelta, maxDelta)).coerceIn(20, 150)
    }

    /** 解析高德 ETA_TEXT（"HH:mm"）；非法或越界返回 null。 */
    private fun parseEtaText(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
