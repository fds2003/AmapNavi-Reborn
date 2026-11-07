package tech.touka.amapnavi.core.event

/**
 * 8 大核心音效事件，文件名与 res/raw 中的 WAV 资源严格对应。
 */
enum class SoundEvent(val fileName: String) {
    PRE_LANE_LEFT("audio_pre_lane_left.wav"),
    PRE_LANE_RIGHT("audio_pre_lane_right.wav"),
    LANE_CHANGE("audio_lane_change.wav"),
    LANE_CHANGE_OK("audio_lane_change_ok.wav"),
    LANE_CHANGE_CANCEL("audio_lane_change_cancel.wav"),
    SOLID_BLOCKED("audio_solid_blocked.wav"),
    CAR_WATCHOUT("audio_car_watchout.wav"),
    TRAFFIC_SIGN_GREEN("traffic_sign_green.wav"),
    LINK_WEAK("warning_soft.wav"),
    LINK_LOST("warning_immediate.wav")
}
