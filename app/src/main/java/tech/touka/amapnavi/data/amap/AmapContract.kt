package tech.touka.amapnavi.data.amap

/**
 * 高德车机版广播契约常量字典。
 * 所有 Intent Action 与 Extra 键名严格取自官方开放广播接口。
 */
object AmapContract {
    const val ACTION_AMAP_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val ACTION_AMAP_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"

    const val KEY_TYPE = "KEY_TYPE"
    const val KEY_TYPE_NAVI_STATE = 10001
    const val KEY_TYPE_DRIVE_WAY = 13012   // 原版 APK 实际使用的车道拓扑广播
    const val KEY_TYPE_LANE = 10019        // 文档 14 声明值，保留兼容
    const val KEY_TYPE_TRAFFIC_LIGHT = 10020

    const val EXTRA_DRIVE_WAY = "EXTRA_DRIVE_WAY"
    const val EXTRA_ROAD_LIMIT_SPEED = "EXTRA_ROAD_LIMIT_SPEED"
    const val EXTRA_CAMERA_SPEED = "EXTRA_CAMERA_SPEED"
    const val EXTRA_CAMERA_DIST = "EXTRA_CAMERA_DIST"
    const val EXTRA_SEG_REMAIN_DIS = "EXTRA_SEG_REMAIN_DIS"
    const val EXTRA_TURN_TYPE = "EXTRA_TURN_TYPE"
    const val EXTRA_ROAD_CATE = "EXTRA_ROAD_CATE"
    const val EXTRA_ROAD_TYPE = "ROAD_TYPE"   // 原版 APK 实际读取的道路等级键
    const val EXTRA_TRAFFIC_LIGHT_SECONDS = "EXTRA_TRAFFIC_LIGHT_SECONDS"
    const val EXTRA_TRAFFIC_LIGHT_STATUS = "EXTRA_TRAFFIC_LIGHT_STATUS"
}
