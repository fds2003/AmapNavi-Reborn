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
    val segRemainTime: Int = 0,                 // 距下一转向预计耗时 (秒)
    val curSpeed: Int = 0,                      // 高德 GPS 车速 (km/h)
    val routeTotalDistance: Int = 0,            // 路线总里程 (米)
    val routeRemainDistance: Int = 0,           // 行程剩余距离 (米)
    val routeRemainTime: Int = 0,               // 行程剩余时间 (秒)
    val etaText: String = "",                   // 预计到达时间文本（HH:mm）
    val carDirection: Int = -1,                 // 自车航向角 0..360，-1 无效
    val trafficLightNum: Int = 0,               // 视距内红绿灯总数
    val routeRemainTrafficLightNum: Int = 0,    // 全程剩余红绿灯数
    val cameraType: Int = -1,                   // 电子眼类型，-1 无效
    val cameraId: String = "",                  // 电子眼编号
    val trafficLightSeconds: Int = -1,          // 红绿灯倒计时秒数 (-1 表示无信号灯)
    val trafficLightStatus: Int = 0,            // 信号灯状态 (1:红 2:绿 3:黄)
    val driveWayRaw: String = "",               // 车道原始字符串 (如 "s|s|S|X")
    val suggestLanes: List<Int> = emptyList(),  // 推荐车道索引 (0-based)
    val disableLanes: List<Int> = emptyList(),  // 禁行车道索引 (0-based)
    val isHighway: Boolean = false,             // 是否高速公路 (roadcate ∈ {0,1}，由 ROAD_TYPE 转换)
    val timestamp: Long = 0L
)
