package tech.touka.amapnavi.data.amap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.touka.amapnavi.core.model.NaviState

/**
 * 高德车机版标准导航广播解码器。
 *
 * 动态注册监听 AUTONAVI_STANDARD_BROADCAST_SEND，解析 Bundle Extra 后写入 naviStateFlow。
 */
class AmapBroadcastDecoder : BroadcastReceiver() {

    private val _naviStateFlow = MutableStateFlow(NaviState())
    val naviStateFlow: StateFlow<NaviState> = _naviStateFlow

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || intent.action != AmapContract.ACTION_AMAP_SEND) return

        val keyType = intent.getIntExtra(AmapContract.KEY_TYPE, 0)
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
                val roadCate = intent.getIntExtra("ROAD_CATE", -1).takeIf { it != -1 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_ROAD_CATE, -1)

                _naviStateFlow.value = current.copy(
                    navValid = true,
                    roadSpeedLimit = roadLimitSpeed,
                    cameraSpeedLimit = cameraSpeedLimit,
                    cameraDistance = cameraDistance,
                    tbtDistance = tbtDistance,
                    tbtTurnType = tbtTurnType,
                    isHighway = (roadCate == 0),
                    timestamp = nowMs
                )
            }
            AmapContract.KEY_TYPE_LANE, AmapContract.KEY_TYPE_TRAFFIC_LIGHT -> {
                // 10019 / 10020: 车道拓扑与红绿灯倒计时广播
                val driveWayRaw = intent.getStringExtra(AmapContract.EXTRA_DRIVE_WAY)
                    ?: intent.getStringExtra("driveWay") ?: current.driveWayRaw
                val trafficLightSeconds = intent.getIntExtra("redLightCountDownSeconds", -1).takeIf { it >= 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_TRAFFIC_LIGHT_SECONDS, current.trafficLightSeconds)
                val trafficLightStatus = intent.getIntExtra("trafficLightStatus", current.trafficLightStatus)

                val parsed = if (driveWayRaw.isNotEmpty()) tech.touka.amapnavi.core.util.LaneWayParser.parse(driveWayRaw) else null
                _naviStateFlow.value = current.copy(
                    driveWayRaw = driveWayRaw,
                    suggestLanes = parsed?.suggest ?: current.suggestLanes,
                    disableLanes = parsed?.disable ?: current.disableLanes,
                    trafficLightSeconds = trafficLightSeconds,
                    trafficLightStatus = trafficLightStatus,
                    timestamp = nowMs
                )
            }
            else -> {
                // 兼顾未声明 KEY_TYPE 的全量字段广播
                val driveWayRaw = intent.getStringExtra(AmapContract.EXTRA_DRIVE_WAY)
                    ?: intent.getStringExtra("driveWay") ?: ""
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
                val roadCate = intent.getIntExtra("ROAD_CATE", -1).takeIf { it != -1 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_ROAD_CATE, -1)
                val trafficLightSeconds = intent.getIntExtra("redLightCountDownSeconds", -1).takeIf { it >= 0 }
                    ?: intent.getIntExtra(AmapContract.EXTRA_TRAFFIC_LIGHT_SECONDS, -1)
                val trafficLightStatus = intent.getIntExtra("trafficLightStatus", 0)
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
                    previous = _naviStateFlow.value
                )
            }
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
