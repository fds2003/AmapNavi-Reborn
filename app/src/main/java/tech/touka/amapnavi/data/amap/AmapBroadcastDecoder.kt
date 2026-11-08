package tech.touka.amapnavi.data.amap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.util.LaneWayParser
import tech.touka.amapnavi.core.util.RoadCate

/**
 * 高德车机版标准导航广播解码器。
 *
 * 动态注册监听 AUTONAVI_STANDARD_BROADCAST_SEND，解析 Bundle Extra 后写入 naviStateFlow。
 */
class AmapBroadcastDecoder : BroadcastReceiver() {

    private val _naviStateFlow = MutableStateFlow(NaviState())
    val naviStateFlow: StateFlow<NaviState> = _naviStateFlow

    /** 诊断：记录每条广播的 keyType 与关键字段（默认 null，零开销）。 */
    @JvmField
    var debugSink: ((String) -> Unit)? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || intent.action != AmapContract.ACTION_AMAP_SEND) return

        val keyType = intent.getIntExtra(AmapContract.KEY_TYPE, 0)
        debugSink?.let { sink ->
            val dump = intent.extras?.keySet()?.joinToString("; ") { k ->
                val v = try { intent.extras?.get(k)?.toString() } catch (e: Exception) { "<$e>" }
                "$k=$v"
            } ?: "null"
            sink("RX keyType=$keyType [$dump]")
        }
        val current = _naviStateFlow.value
        val nowMs = System.currentTimeMillis()

        when (keyType) {
            AmapContract.KEY_TYPE_NAVI_STATE -> {
                // 10001: 导航基础数据广播 (限速、测速、TBT、路况)
                val roadLimitSpeed = intent.getIntExtra("LIMITED_SPEED", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_ROAD_LIMIT_SPEED, 0)
                val cameraSpeedLimit = intent.getIntExtra("CAMERA_SPEED", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_CAMERA_SPEED, 0)
                val cameraDistance = intent.getIntExtra("CAMERA_DIST", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_CAMERA_DIST, 0)
                val tbtDistance = intent.getIntExtra("SEG_REMAIN_DIS", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_SEG_REMAIN_DIS, 0)
                val tbtTurnType = intent.getIntExtra("ICON", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_TURN_TYPE, 0)
                val roadCate = readRoadCate(intent)
                val routeRemainDistance = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_REMAIN_DIS, current.routeRemainDistance
                )
                val routeRemainTime = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_REMAIN_TIME, current.routeRemainTime
                )
                val nextRoadName = safeStringExtra(intent, AmapContract.EXTRA_NEXT_ROAD_NAME)
                    ?: current.nextRoadName
                val currentRoadName = safeStringExtra(intent, AmapContract.EXTRA_CUR_ROAD_NAME)
                    ?: current.currentRoadName
                val trafficLightSeconds = intent.getIntExtra(
                    "redLightCountDownSeconds", current.trafficLightSeconds
                )
                val trafficLightStatus = intent.getIntExtra(
                    "trafficLightStatus", current.trafficLightStatus
                )
                val curSpeed = intent.getIntExtra(AmapContract.EXTRA_CUR_SPEED, current.curSpeed)
                val routeTotalDistance = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_ALL_DIS, current.routeTotalDistance
                )
                val segRemainTime = intent.getIntExtra(
                    AmapContract.EXTRA_SEG_REMAIN_TIME, current.segRemainTime
                )
                val etaText = safeStringExtra(intent, AmapContract.EXTRA_ETA_TEXT) ?: current.etaText
                val carDirection = intent.getIntExtra(
                    AmapContract.EXTRA_CAR_DIRECTION, current.carDirection
                )
                val trafficLightNum = intent.getIntExtra(
                    AmapContract.EXTRA_TRAFFIC_LIGHT_NUM, current.trafficLightNum
                )
                val routeRemainTrafficLightNum = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_REMAIN_TRAFFIC_LIGHT_NUM, current.routeRemainTrafficLightNum
                )
                val cameraType = intent.getIntExtra(AmapContract.EXTRA_CAMERA_TYPE, current.cameraType)
                val cameraId = readCameraId(intent, current.cameraId)

                _naviStateFlow.value = current.copy(
                    navValid = readNavValid(intent, default = true),
                    currentRoadName = currentRoadName,
                    nextRoadName = nextRoadName,
                    segRemainTime = segRemainTime,
                    curSpeed = curSpeed,
                    routeTotalDistance = routeTotalDistance,
                    routeRemainDistance = routeRemainDistance,
                    routeRemainTime = routeRemainTime,
                    etaText = etaText,
                    carDirection = carDirection,
                    trafficLightNum = trafficLightNum,
                    routeRemainTrafficLightNum = routeRemainTrafficLightNum,
                    cameraType = cameraType,
                    cameraId = cameraId,
                    roadSpeedLimit = roadLimitSpeed,
                    cameraSpeedLimit = cameraSpeedLimit,
                    cameraDistance = cameraDistance,
                    tbtDistance = tbtDistance,
                    tbtTurnType = tbtTurnType,
                    trafficLightSeconds = trafficLightSeconds,
                    trafficLightStatus = trafficLightStatus,
                    isHighway = RoadCate.isHighway(roadCate),
                    timestamp = nowMs
                )
            }
            AmapContract.KEY_TYPE_DRIVE_WAY -> {
                // 13012: 车道级拓扑广播，EXTRA_DRIVE_WAY 为 JSON 对象
                val driveWayRaw = safeStringExtra(intent, AmapContract.EXTRA_DRIVE_WAY)
                    ?: safeStringExtra(intent, "driveWay") ?: current.driveWayRaw
                val parsed = LaneWayParser.parse(driveWayRaw)
                _naviStateFlow.value = current.copy(
                    driveWayRaw = driveWayRaw,
                    suggestLanes = parsed.suggest,
                    disableLanes = parsed.disable,
                    timestamp = nowMs
                )
            }
            AmapContract.KEY_TYPE_LANE, AmapContract.KEY_TYPE_TRAFFIC_LIGHT -> {
                // 10019 / 10020: 文档 14 声明值，保留兼容；载荷同样兼容 JSON 与竖线串
                val driveWayRaw = safeStringExtra(intent, AmapContract.EXTRA_DRIVE_WAY)
                    ?: safeStringExtra(intent, "driveWay") ?: current.driveWayRaw
                val trafficLightSeconds = intent.getIntExtra("redLightCountDownSeconds", -1).takeIf { it >= 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_TRAFFIC_LIGHT_SECONDS, current.trafficLightSeconds)
                val trafficLightStatus = intent.getIntExtra("trafficLightStatus", current.trafficLightStatus)

                val parsed = LaneWayParser.parse(driveWayRaw)
                _naviStateFlow.value = current.copy(
                    navValid = readNavValid(intent, default = current.navValid),
                    driveWayRaw = driveWayRaw,
                    suggestLanes = parsed.suggest,
                    disableLanes = parsed.disable,
                    trafficLightSeconds = trafficLightSeconds,
                    trafficLightStatus = trafficLightStatus,
                    timestamp = nowMs
                )
            }
            else -> {
                // 兼顾未声明 KEY_TYPE 的全量字段广播
                val driveWayRaw = safeStringExtra(intent, AmapContract.EXTRA_DRIVE_WAY)
                    ?: safeStringExtra(intent, "driveWay") ?: ""
                val roadLimitSpeed = intent.getIntExtra("LIMITED_SPEED", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_ROAD_LIMIT_SPEED, 0)
                val cameraSpeedLimit = intent.getIntExtra("CAMERA_SPEED", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_CAMERA_SPEED, 0)
                val cameraDistance = intent.getIntExtra("CAMERA_DIST", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_CAMERA_DIST, 0)
                val tbtDistance = intent.getIntExtra("SEG_REMAIN_DIS", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_SEG_REMAIN_DIS, 0)
                val tbtTurnType = intent.getIntExtra("ICON", 0).takeIf { it > 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_TURN_TYPE, 0)
                val roadCate = readRoadCate(intent)
                val trafficLightSeconds = intent.getIntExtra("redLightCountDownSeconds", -1).takeIf { it >= 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_TRAFFIC_LIGHT_SECONDS, -1)
                val trafficLightStatus = intent.getIntExtra("trafficLightStatus", 0)
                val routeRemainDistance = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_REMAIN_DIS, current.routeRemainDistance
                )
                val routeRemainTime = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_REMAIN_TIME, current.routeRemainTime
                )
                val nextRoadName = safeStringExtra(intent, AmapContract.EXTRA_NEXT_ROAD_NAME)
                    ?: current.nextRoadName
                val currentRoadName = safeStringExtra(intent, AmapContract.EXTRA_CUR_ROAD_NAME)
                    ?: current.currentRoadName
                val curSpeed = intent.getIntExtra(AmapContract.EXTRA_CUR_SPEED, current.curSpeed)
                val routeTotalDistance = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_ALL_DIS, current.routeTotalDistance
                )
                val segRemainTime = intent.getIntExtra(
                    AmapContract.EXTRA_SEG_REMAIN_TIME, current.segRemainTime
                )
                val etaText = safeStringExtra(intent, AmapContract.EXTRA_ETA_TEXT) ?: current.etaText
                val carDirection = intent.getIntExtra(
                    AmapContract.EXTRA_CAR_DIRECTION, current.carDirection
                )
                val trafficLightNum = intent.getIntExtra(
                    AmapContract.EXTRA_TRAFFIC_LIGHT_NUM, current.trafficLightNum
                )
                val routeRemainTrafficLightNum = intent.getIntExtra(
                    AmapContract.EXTRA_ROUTE_REMAIN_TRAFFIC_LIGHT_NUM, current.routeRemainTrafficLightNum
                )
                val cameraType = intent.getIntExtra(AmapContract.EXTRA_CAMERA_TYPE, current.cameraType)
                val cameraId = readCameraId(intent, current.cameraId)
                _naviStateFlow.value = AmapBroadcastCodec.decode(
                    driveWayRaw = driveWayRaw,
                    roadLimitSpeed = roadLimitSpeed,
                    cameraSpeedLimit = cameraSpeedLimit,
                    cameraDistance = cameraDistance,
                    tbtDistance = tbtDistance,
                    tbtTurnType = tbtTurnType,
                    roadCate = roadCate,
                    trafficLightSeconds = trafficLightSeconds,
                    trafficLightStatus = trafficLightStatus,
                    nowMs = nowMs,
                    previous = _naviStateFlow.value,
                    routeRemainDistance = routeRemainDistance,
                    routeRemainTime = routeRemainTime,
                    nextRoadName = nextRoadName,
                    currentRoadName = currentRoadName,
                    navValid = readNavValid(intent, default = current.navValid),
                    curSpeed = curSpeed,
                    routeTotalDistance = routeTotalDistance,
                    segRemainTime = segRemainTime,
                    etaText = etaText,
                    carDirection = carDirection,
                    trafficLightNum = trafficLightNum,
                    routeRemainTrafficLightNum = routeRemainTrafficLightNum,
                    cameraType = cameraType,
                    cameraId = cameraId
                )
            }
        }
    }

    /**
     * 原版 APK 读取的是 ROAD_TYPE（原始等级），再经 roadcate_conv 转换：
     * 0→1、6→0、1→2、其它→8；高速判定为 roadcate ∈ {0,1}。
     */
    private fun readRoadCate(intent: Intent): Int {
        val roadType = intent.getIntExtra("ROAD_TYPE", -1).takeIf { it != -1 }
            ?: intent.getIntExtra(AmapContract.EXTRA_ROAD_CATE, -1)
        return if (roadType >= 0) RoadCate.conv(roadType) else -1
    }

    /**
     * 高德 EXTRA_STATE：8=开始导航，9=结束导航，0=退出。
     * 未携带时使用调用方给的默认值。
     */
    private fun readNavValid(intent: Intent, default: Boolean): Boolean {
        return when (intent.getIntExtra(AmapContract.EXTRA_STATE, Int.MIN_VALUE)) {
            AmapContract.STATE_START_NAVI -> true
            AmapContract.STATE_END_NAVI, AmapContract.STATE_EXIT_APP -> false
            else -> default
        }
    }

    /**
     * 获取经过新鲜度看门狗过滤的导航状态。
     * 超过 maxAgeMs (默认 2.0 秒) 未收到地图广播，自动置 navValid = false，杜绝陈旧数据控车。
     */
    fun getFreshNaviState(maxAgeMs: Long = 2000L): NaviState {
        val current = _naviStateFlow.value
        if (!current.navValid) return current
        return if (System.currentTimeMillis() - current.timestamp > maxAgeMs) {
            current.copy(navValid = false)
        } else {
            current
        }
    }
    /** 主动清空导航状态（通信断开或退出导航时调用）。 */
    fun reset() {
        _naviStateFlow.value = NaviState()
    }
}

/**
 * 容错读取 String Extra：高德部分 key 实际可能是 Integer，
 * getStringExtra 会抛 ClassCastException 并中断整条广播解析，这里降级为 null。
 */
internal fun safeStringExtra(intent: Intent, key: String): String? =
    try {
        intent.getStringExtra(key)
    } catch (e: Exception) {
        null
    }

/**
 * 容错读取高德 cameraID：可能是 String 也可能是 Integer（实车 logcat 实锤）。
 * 优先按 String 读；类型不匹配时按 Int 读并转字符串；都失败保留上一帧。
 */
internal fun readCameraId(intent: Intent, fallback: String): String = readCameraId(
    getString = { key -> safeStringExtra(intent, key) },
    getInt = { key, default -> intent.getIntExtra(key, default) },
    fallback = fallback
)

/** 纯函数版本，便于 JVM 单测注入 fake 读取器。 */
internal fun readCameraId(
    getString: (String) -> String?,
    getInt: (String, Int) -> Int,
    fallback: String
): String {
    val asString = try {
        getString(AmapContract.EXTRA_CAMERA_ID)
    } catch (e: Exception) {
        null
    }
    if (!asString.isNullOrEmpty()) return asString

    val asInt = try {
        getInt(AmapContract.EXTRA_CAMERA_ID, -1)
    } catch (e: Exception) {
        -1
    }
    return if (asInt >= 0) asInt.toString() else fallback
}
