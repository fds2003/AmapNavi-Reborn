package tech.touka.amapnavi.data.prefs

import android.content.Context
import android.content.SharedPreferences
import tech.touka.amapnavi.core.model.OvertakeSettings

/**
 * 完整车载参数持久化仓库。
 *
 * 涵盖：通用参数、领航参数、超车参数。
 * 内存缓存避免 10Hz 决策周期内反复读取 SharedPreferences 造成主线程 I/O。
 */
class UserSettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "amapnavi_settings"

        @Volatile
        private var instance: UserSettingsRepository? = null

        @JvmStatic
        fun getInstance(context: Context): UserSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: UserSettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        // 通用参数 Keys
        const val KEY_CP_TRAFFIC_ASSIST = "cbCpTrafficAssist"
        const val KEY_NAVI_TRAFFIC_ASSIST = "cbNaviTrafficAssist"
        const val KEY_RED_LIGHT_TRAFFIC_ASSIST = "cbRedLightTrafficAssist"
        const val KEY_CP_MIN_SPEED_LIMIT = "etCpMinSpeedLimt"
        const val KEY_RED_LIGHT_MIN_SPEED_LIMIT = "etRedLightMinSpeedLimt"
        const val KEY_ASSIST_HOLD_TIME = "etNaviTrafficAssistTime"
        const val KEY_LANE_DETECT = "lane_detect"
        const val KEY_VIDEO_SHOW = "video_show"
        const val KEY_LANE_SHOW = "lane_show"
        const val KEY_LANE_TYPE_PUBLISH = "lane_type_publish_en"
        const val KEY_LANE_DETECT_INTERVAL = "lane_detect_interval"
        const val KEY_APP_LOG = "app_log"
        const val KEY_APP_SPEAK = "app_speak"
        const val KEY_TUNNEL_DEC_SPEED = "tunnel_dec_speed"
        const val KEY_SMOOTH_SPEED_LIMIT = "smooth_speed_limit"
        const val KEY_VISION_LANE_MODE = "vision_lane_mode" // 0: 快速, 1: 标准, 2: 保守
        const val KEY_STATION_SPEED = "station_speed"
        const val KEY_STATION_DIST_OFFSET = "station_dist_offset"
        const val KEY_STATION_KEEP_TIME = "station_keep_time"
        const val KEY_MIN_SPEED_LIMIT = "min_speed_limit"
        const val KEY_MAX_SPEED_LIMIT = "max_speed_limit"
        const val KEY_ROAD_SPEED_LIMIT_PERCENT = "road_speed_limit_percent"
        const val KEY_ROAD_SPEED_LIMIT_KPH = "road_speed_limit_kph"
        const val KEY_OFFSET_HIGHWAY = "offset_highway"
        const val KEY_OFFSET_ROAD = "offset_road"
        const val KEY_TOP_PAD_DP = "top_pad_dp"

        // 原厂仪表 Keys
        const val KEY_CLUSTER_ENABLE = "cluster_enable"
        const val KEY_CLUSTER_TBT_ENABLE = "cluster_tbt_enable"
        const val KEY_CLUSTER_SPEED_ENABLE = "cluster_speed_enable"
        const val KEY_CLUSTER_EST_ENABLE = "cluster_est_enable"
        const val KEY_CLUSTER_TEXT_ENABLE = "cluster_text_enable"
        const val KEY_CLUSTER_TL_ENABLE = "cluster_tl_enable"
        const val KEY_CLUSTER_ETA_MODE = "cluster_eta_mode"
        const val KEY_CLUSTER_ICON_EXPERIMENTAL = "cluster_icon_experimental"
        const val KEY_CLUSTER_ADA_ENABLE = "cluster_adas_enable"

        // 领航参数 Keys
        const val KEY_APP_NAVI = "app_navi"
        const val KEY_APP_ROAD_NAVI = "app_road_navi"
        const val KEY_SUGGEST_LANE_EN = "suggest_lane_en"
        const val KEY_DIS_LANE_EN = "dis_lane_en"
        const val KEY_KEEP_LEFT_EN = "keep_left_en"
        const val KEY_KEEP_RIGHT_EN = "keep_right_en"
        const val KEY_VISION_KEEP_RIGHT = "vision_keep_right"
        const val KEY_SAFETY_LEGACY_MODE = "safety_legacy_mode"
        const val KEY_LAST_LANE_SUPPRESS_EN = "last_lane_suppress_en"
        const val KEY_DRIVE_LOG_EN = "drive_log_en"
        const val KEY_NAVI_SPEAK = "navi_speak"
        const val KEY_NAVI_SPEAK_FIRST = "navi_speak_first"
        const val KEY_NAVI_BLIND_DEC_SPEED = "navi_blind_dec_speed"
        const val KEY_ROAD_MEDIAN = "road_median"
        const val KEY_ROAD_RIGHT_FIRST = "road_right_first"
        const val KEY_NAV_TURN = "nav_turn"
        const val KEY_LANE_PROB_ENABLE = "lane_prob_enable"
        const val KEY_NAV_MODE = "nav_mode" // 0: 激进, 1: 标准, 2: 保守
        const val KEY_NAVI_MIN_SPEED = "navi_min_speed"
        const val KEY_ATC_DIST_H = "atc_dist_h"
        const val KEY_ATC_DIST_L = "atc_dist_l"
        const val KEY_FORK_DIST_H = "fork_dist_h"
        const val KEY_FORK_DIST_L = "fork_dist_l"
        const val KEY_RELAX_OFFSET_H = "solid_relax_offset_h"
        const val KEY_RELAX_OFFSET_L = "solid_relax_offset_l"
        const val KEY_RAMP_SLOWDOWN_EN = "ramp_slowdown_en"
        const val KEY_RAMP_SLOWDOWN_DIST_H = "ramp_slowdown_dist_h"
        const val KEY_RAMP_SLOWDOWN_DIST_L = "ramp_slowdown_dist_l"
        const val KEY_RAMP_SLOWDOWN_SPEED_H = "ramp_slowdown_speed_h"
        const val KEY_RAMP_SLOWDOWN_SPEED_L = "ramp_slowdown_speed_l"
        const val KEY_RAMP_HOLD_TIME = "ramp_hold_time"

        // 超车参数 Keys
        const val KEY_OVERTAKE_ENABLED = "app_overtake"
        const val KEY_OVERTAKE_ROAD_ENABLED = "app_road_overtake"
        const val KEY_OVERTAKE_MODE = "overtake_mode" // 0: 激进, 1: 常规, 2: 保守
        const val KEY_LIDAR_SELF_CHECK = "app_lidar_self_check"
        const val KEY_THRESH_FOLLOW_CP = "app_thresh_follow_cp"
        const val KEY_BLOCK_ON_BLOCKED_LANE = "overtake_block_on_blocked_lane"
        const val KEY_BLOCK_ON_SUGGEST_LANE = "overtake_block_on_suggest_lane"
        const val KEY_FRONT_THRESH_MODE = "lidar_front_dist_mode" // 0: 固定距离, 1: 速度时距
        const val KEY_FRONT_THRESH_VAL = "lidar_front_dist_m"
        const val KEY_REAR_THRESH_MODE = "lidar_rear_dist_mode"
        const val KEY_REAR_THRESH_VAL = "lidar_rear_dist_m"
        const val KEY_FRONT_VREL_TIME = "lidar_front_vrel_time_s"
        const val KEY_REAR_VREL_TIME = "lidar_rear_vrel_time_s"
        const val KEY_REVERSE_COOLDOWN_S = "overtake_reverse_cooldown_s"
    }

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedOvertakeSettings: OvertakeSettings = loadOvertakeSettings()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_OVERTAKE_ENABLED || key == KEY_REVERSE_COOLDOWN_S || key == "allow_right_overtake") {
            cachedOvertakeSettings = loadOvertakeSettings()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    val settings: OvertakeSettings
        get() = cachedOvertakeSettings

    fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        if (key == KEY_OVERTAKE_ENABLED) {
            cachedOvertakeSettings = cachedOvertakeSettings.copy(enabled = value)
        }
    }

    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        if (key == KEY_REVERSE_COOLDOWN_S) {
            cachedOvertakeSettings = cachedOvertakeSettings.copy(standardCooldownMs = value * 1000L)
        }
    }

    fun getFloat(key: String, def: Float): Float = prefs.getFloat(key, def)
    fun setFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun setOvertakeEnabled(enabled: Boolean) = setBoolean(KEY_OVERTAKE_ENABLED, enabled)
    fun setAllowRightOvertake(allow: Boolean) = setBoolean("allow_right_overtake", allow)

    private fun loadOvertakeSettings(): OvertakeSettings {
        return OvertakeSettings(
            enabled = prefs.getBoolean(KEY_OVERTAKE_ENABLED, true),
            standardCooldownMs = prefs.getInt(KEY_REVERSE_COOLDOWN_S, 20) * 1000L,
            allowRightOvertake = prefs.getBoolean("allow_right_overtake", false)
        )
    }
}
