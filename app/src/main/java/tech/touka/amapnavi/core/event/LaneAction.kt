package tech.touka.amapnavi.core.event

/**
 * 车道动作枚举，同时承载下发 C3 的转向灯码。
 */
enum class LaneAction(val code: String, val blinker: Int) {
    LEFT("LEFT", 1),
    RIGHT("RIGHT", 2),
    KEEP("KEEP", 0)
}
