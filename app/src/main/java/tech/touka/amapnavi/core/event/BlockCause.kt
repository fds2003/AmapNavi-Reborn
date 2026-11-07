package tech.touka.amapnavi.core.event

/**
 * 决策阻断原因。任何非 NONE 值都代表本次变道意图被安全熔断。
 */
enum class BlockCause {
    NONE,
    TARGET_LANE_OCCUPIED,
    FRONT_BLIND,
    SOLID_LINE,
    BSD_ALARM,
    LIDAR_HAZARD,
    LIDAR_UNAVAILABLE,
    COOLDOWN,
    NO_TARGET_LANE,
    ROUTE_INVALID,
    NOT_ENGAGED,
    SPEED_TOO_LOW,
    NOT_HIGHWAY,
    DRIVER_OVERRIDE,
    TELEMETRY_LOST
}
