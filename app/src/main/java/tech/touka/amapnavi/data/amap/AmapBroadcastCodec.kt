package tech.touka.amapnavi.data.amap

import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.util.RoadCate
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
        previous: NaviState = NaviState(),
        routeRemainDistance: Int = previous.routeRemainDistance,
        routeRemainTime: Int = previous.routeRemainTime,
        nextRoadName: String = previous.nextRoadName,
        currentRoadName: String = previous.currentRoadName,
        navValid: Boolean = true,
        curSpeed: Int = previous.curSpeed,
        routeTotalDistance: Int = previous.routeTotalDistance,
        segRemainTime: Int = previous.segRemainTime,
        etaText: String = previous.etaText,
        carDirection: Int = previous.carDirection,
        trafficLightNum: Int = previous.trafficLightNum,
        routeRemainTrafficLightNum: Int = previous.routeRemainTrafficLightNum,
        cameraType: Int = previous.cameraType,
        cameraId: String = previous.cameraId
    ): NaviState {
        val parsed = LaneWayParser.parse(driveWayRaw)
        return previous.copy(
            navValid = navValid,
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
            driveWayRaw = driveWayRaw,
            suggestLanes = parsed.suggest,
            disableLanes = parsed.disable,
            isHighway = RoadCate.isHighway(roadCate),
            timestamp = nowMs
        )
    }
}
