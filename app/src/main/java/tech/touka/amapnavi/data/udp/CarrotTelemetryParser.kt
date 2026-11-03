package tech.touka.amapnavi.data.udp

import org.json.JSONObject
import tech.touka.amapnavi.core.model.CarState

/**
 * C3 遥测 JSON (Port 7706) 快速解析器。
 *
 * 编译期字符串字面量 key + 逐字符数值扫描：不再为每个字段构造 "\"$key\":" 与
 * substring()/toXxx()，从而消除 Dalvik 上高频 UDP 循环里的 GC_FOR_ALLOC 停顿。
 * parse(payload) 路径每次调用仅产生 1 个 CarState（UDP 层另有 1 次 String 解码）。
 */
object CarrotTelemetryParser {

    fun parse(payload: String): CarState {
        return CarState(
            isOnroad = scanBool(payload, "\"IsOnroad\":", false),
            vEgo = scanInt(payload, "\"v_ego_kph\":", scanInt(payload, "\"v_ego\":", 0)),
            vCruise = scanInt(payload, "\"v_cruise_kph\":", 0),
            active = scanBool(payload, "\"active\":", false),
            engaged = scanBool(payload, "\"engaged\":", false),
            steerAngle = scanFloat(payload, "\"steer_angle\":", 0.0f),
            isDriverSteering = scanBool(payload, "\"driver_steering\":", false),
            gasPress = scanBool(payload, "\"gas_press\":", false),
            brakePress = scanBool(payload, "\"brake_press\":", false),

            lead1 = scanBool(payload, "\"lead1\":", false),
            drel = scanFloat(payload, "\"drel\":", 0.0f),
            vrel = scanFloat(payload, "\"vrel\":", 0.0f),
            vLead = scanFloat(payload, "\"vlead\":", 0.0f).toInt(),

            leftBlindspot = scanBool(payload, "\"left_blindspot\":", false),
            rightBlindspot = scanBool(payload, "\"right_blindspot\":", false),

            lidarFrontVdistTime = scanFloat(payload, "\"lidar_front_vdist_time\":", 0.0f),
            lidarFrontVrelTime = scanFloat(payload, "\"lidar_front_vrel_time\":", 0.0f),
            lidarBehindVdistTime = scanFloat(payload, "\"lidar_behind_vdist_time\":", 0.0f),
            lidarBehindVrelTime = scanFloat(payload, "\"lidar_behind_vrel_time\":", 0.0f),
            lidarLeftRearBlind = scanBool(payload, "\"lidar_lbblind\":", false),
            lidarRightRearBlind = scanBool(payload, "\"lidar_rbblind\":", false),
            leftRearDistance = parseDistanceMeter(scanFloat(payload, "\"lb_drel\":", -999999.0f)),
            rightRearDistance = parseDistanceMeter(scanFloat(payload, "\"rb_drel\":", -999999.0f)),
            atcState = scanInt(payload, "\"atc_state\":", 0),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun parseDistanceMeter(rawVal: Float): Float {
        // fail-closed：缺键(-999999)、无效值与真实 0m 一律视为“无安全净空”，
        // 交由 LateralSafetyEvaluator 阻断变道；不得再用 999.0f 放行。
        if (rawVal <= -999000.0f || rawVal == 0.0f) return 0.0f
        // M-13: C3 端已把 lb_drel/rb_drel 固定为米制，直接采信。
        return Math.abs(rawVal)
    }
    private fun scanBool(s: String, key: String, defVal: Boolean): Boolean {
        val i = s.indexOf(key)
        if (i < 0) return defVal
        var p = i + key.length
        while (p < s.length && (s[p] == ' ' || s[p] == '\t')) p++
        if (s.startsWith("true", p)) return true
        if (s.startsWith("false", p)) return false
        if (p < s.length && s[p] == '1') return true
        if (p < s.length && s[p] == '0') return false
        return defVal
    }

    /** 逐字符累加，不使用 substring / toXxx，零中间对象分配 */
    private fun scanFloat(s: String, key: String, defVal: Float): Float {
        val i = s.indexOf(key)
        if (i < 0) return defVal
        var p = i + key.length
        while (p < s.length && (s[p] == ' ' || s[p] == '\t')) p++
        var neg = false
        if (p < s.length && s[p] == '-') { neg = true; p++ }
        var v = 0f
        var div = 1f
        var dot = false
        var any = false
        while (p < s.length) {
            val c = s[p]
            if (c in '0'..'9') {
                any = true
                val digit = (c - '0').toFloat()
                if (dot) { div *= 10f; v += digit / div }
                else v = v * 10f + digit
            } else if (c == '.' && !dot) {
                dot = true
            } else break
            p++
        }
        return if (any) (if (neg) -v else v) else defVal
    }

    private fun scanInt(s: String, key: String, defVal: Int): Int =
        scanFloat(s, key, defVal.toFloat()).toInt()

    fun parse(json: JSONObject): CarState {
        return CarState(
            isOnroad = json.optInt("IsOnroad", 0) == 1,
            vEgo = json.optInt("v_ego_kph", 0),
            vCruise = json.optInt("v_cruise_kph", 0),
            active = json.optBoolean("active", false),
            engaged = json.optBoolean("engaged", false),
            steerAngle = json.optDouble("steer_angle", 0.0).toFloat(),
            isDriverSteering = json.optBoolean("driver_steering", false),
            gasPress = json.optBoolean("gas_press", false),
            brakePress = json.optBoolean("brake_press", false),

            lead1 = json.optBoolean("lead1", false),
            drel = json.optDouble("drel", 0.0).toFloat(),
            vrel = json.optDouble("vrel", 0.0).toFloat(),
            vLead = json.optDouble("vlead", 0.0).toInt(),

            leftBlindspot = json.optBoolean("left_blindspot", false),
            rightBlindspot = json.optBoolean("right_blindspot", false),

            lidarFrontVdistTime = json.optDouble("lidar_front_vdist_time", 0.0).toFloat(),
            lidarFrontVrelTime = json.optDouble("lidar_front_vrel_time", 0.0).toFloat(),
            lidarBehindVdistTime = json.optDouble("lidar_behind_vdist_time", 0.0).toFloat(),
            lidarBehindVrelTime = json.optDouble("lidar_behind_vrel_time", 0.0).toFloat(),
            lidarLeftRearBlind = json.optBoolean("lidar_lbblind", false),
            lidarRightRearBlind = json.optBoolean("lidar_rbblind", false),
            leftRearDistance = parseDistanceMeter(json.optDouble("lb_drel", -999999.0).toFloat()),
            rightRearDistance = parseDistanceMeter(json.optDouble("rb_drel", -999999.0).toFloat()),

            atcState = json.optInt("atc_state", 0),
            timestamp = System.currentTimeMillis()
        )
    }
}
