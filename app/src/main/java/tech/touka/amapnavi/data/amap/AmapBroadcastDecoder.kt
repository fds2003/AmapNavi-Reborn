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
            nowMs = System.currentTimeMillis(),
            previous = _naviStateFlow.value
        )
    }

    /** 主动清空导航状态（通信断开或退出导航时调用）。 */
    fun reset() {
        _naviStateFlow.value = NaviState()
    }
}
