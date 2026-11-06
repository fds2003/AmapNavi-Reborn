package tech.touka.amapnavi.core.model

/**
 * 自车与传感器底盘状态模型。
 *
 * 全部字段来源于 C3 端约 10Hz 上报的遥测 JSON，保持不可变数据类以保障跨线程并发安全。
 */
data class CarState(
    // 自车基础动力学
    val isOnroad: Boolean = false,
    val vEgo: Int = 0,                      // 真实车速 (km/h)
    val vCruise: Int = 0,                   // 巡航设定车速 (km/h)
    val active: Boolean = false,            // 纵向控制是否激活
    val engaged: Boolean = false,           // 自动驾驶是否接管
    val steerAngle: Float = 0f,             // 方向盘转角 (度)
    val isDriverSteering: Boolean = false,  // 驾驶员是否正在反抗转向
    val gasPress: Boolean = false,          // 是否踩油门
    val brakePress: Boolean = false,        // 是否踩刹车

    // 正前车雷达 (lead1)
    val lead1: Boolean = false,             // 前方是否有车
    val drel: Float = 0f,                   // 前车纵向相对距离 (米)
    val vrel: Float = 0f,                   // 前车相对速度 (km/h，C3 实发单位，负值表示逼近)
    val vLead: Int = 0,                     // 前车绝对车速 (km/h)

    // 目标侧前角雷达盲区 (C3 l_front_blind / r_front_blind)
    val leftFrontBlind: Boolean = false,    // 左前角雷达盲区报警
    val rightFrontBlind: Boolean = false,   // 右前角雷达盲区报警

    // 目标车道前车 (C3 l_lead / r_lead)
    val leftLead: Boolean = false,          // 左侧目标车道有前车
    val leftLeadDistance: Float = 0f,       // 左侧前车距离 (米)，0 = 无/失效
    val leftLeadVrel: Float = 0f,           // 左侧前车相对速度 (km/h)
    val rightLead: Boolean = false,         // 右侧目标车道有前车
    val rightLeadDistance: Float = 0f,      // 右侧前车距离 (米)
    val rightLeadVrel: Float = 0f,          // 右侧前车相对速度 (km/h)

    // 盲区与毫米波雷达 (BSD)
    val leftBlindspot: Boolean = false,     // 左侧盲区毫米波是否报警
    val rightBlindspot: Boolean = false,    // 右侧盲区毫米波是否报警

    // 侧向激光雷达 (Lidar)
    val lidarFrontVdistTime: Float = 0f,    // 激光雷达前向时距 (秒)
    val lidarFrontVrelTime: Float = 0f,     // 激光雷达前向逼近时距 (秒)
    val lidarBehindVdistTime: Float = 0f,   // 激光雷达后向时距 (秒)
    val lidarBehindVrelTime: Float = 0f,    // 激光雷达后向逼近时距 (秒)
    val lidarLeftRearBlind: Boolean = false,    // 左后激光雷达盲区报警
    val lidarRightRearBlind: Boolean = false,   // 右后激光雷达盲区报警
    val leftRearDistance: Float = 0f,       // 激光雷达测得左后方车辆距离 (米)；0 = 无数据/失效，门禁阻断
    val rightRearDistance: Float = 0f,      // 激光雷达测得右后方车辆距离 (米)；0 = 无数据/失效，门禁阻断

    // 状态反馈
    val atcState: Int = 0,                  // LaneChangeState.raw（1=preLaneChange, 2=laneChangeStarting, 3=laneChangeFinishing）
    val timestamp: Long = 0L                // 遥测到达本地的时间戳
) {

    /** 协议看门狗：判断遥测包是否仍在有效期内。 */
    fun isTelemetryFresh(nowMs: Long, timeoutMs: Long): Boolean {
        return timestamp > 0L && nowMs - timestamp <= timeoutMs
    }
}
