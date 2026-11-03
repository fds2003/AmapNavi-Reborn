package tech.touka.amapnavi.core.model

/**
 * 高德车机版导航状态模型。
 *
 * 字段严格对应 AUTONAVI_STANDARD_BROADCAST_SEND 广播 Intent Extra 契约。
 */
data class NaviState(
    val navValid: Boolean = false,              // 当前是否处于规划导航状态
    val currentRoadName: String = "",           // 当前行驶道路名称
    val nextRoadName: String = "",              // 下一转向道路名称
    val roadSpeedLimit: Int = 0,                // 道路静态限速 (km/h)
    val cameraSpeedLimit: Int = 0,              // 电子眼限速 (km/h)
    val cameraDistance: Int = 0,                // 距电子眼剩余距离 (米)
    val tbtDistance: Int = 0,                   // 距下个路口/分岔路剩余距离 (米)
    val tbtTurnType: Int = 0,                   // 转向图标枚举
    val trafficLightSeconds: Int = -1,          // 红绿灯倒计时秒数 (-1 表示无信号灯)
    val trafficLightStatus: Int = 0,            // 信号灯状态 (1:红 2:绿 3:黄)
    val driveWayRaw: String = "",               // 车道原始字符串 (如 "s|s|S|X")
    val suggestLanes: List<Int> = emptyList(),  // 推荐车道索引 (0-based)
    val disableLanes: List<Int> = emptyList(),  // 禁行车道索引 (0-based)
    val isHighway: Boolean = false,             // 是否高速公路 (EXTRA_ROAD_CATE == 0)
    val timestamp: Long = 0L
)
