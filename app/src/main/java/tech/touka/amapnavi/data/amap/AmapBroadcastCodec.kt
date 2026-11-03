package tech.touka.amapnavi.data.amap

import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.util.LaneWayParser

/**
 * 高德广播载荷的纯 Kotlin 解码器。
 *
 * 该对象不引用任何 Android 类型，使车道解析逻辑可以在纯 JVM 单元测试中 100% 复现。
 */
object AmapBroadcastCodec {

    fun decode(
        driveWayRaw: String,
        roadLimitSpeed: Int,
        cameraSpeedLimit: Int,
        cameraDistance: Int,
        tbtDistance: Int,
        tbtTurnType: Int,
        roadCate: Int,
        trafficLightSeconds: Int,
        trafficLightStatus: Int,
        nowMs: Long,
        previous: NaviState = NaviState()
    ): NaviState {
        val parsed = LaneWayParser.parse(driveWayRaw)
        return previous.copy(
            navValid = true,
            roadSpeedLimit = roadLimitSpeed,
            cameraSpeedLimit = cameraSpeedLimit,
            cameraDistance = cameraDistance,
            tbtDistance = tbtDistance,
            tbtTurnType = tbtTurnType,
            trafficLightSeconds = trafficLightSeconds,
            trafficLightStatus = trafficLightStatus,
            driveWayRaw = driveWayRaw,
            suggestLanes = parsed.suggest,
            disableLanes = parsed.disable,
            isHighway = roadCate == 0,
            timestamp = nowMs
        )
    }
}
