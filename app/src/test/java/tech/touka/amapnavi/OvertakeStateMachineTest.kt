package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.core.event.BlockCause
import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.event.OvertakeState
import tech.touka.amapnavi.core.event.SoundEvent
import tech.touka.amapnavi.core.model.CarState
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.model.OvertakeSettings
import tech.touka.amapnavi.domain.decision.LeadVehicleEvaluator
import tech.touka.amapnavi.domain.decision.OvertakeStateMachine
import tech.touka.amapnavi.domain.decision.RouteLaneMarkingOracle
import tech.touka.amapnavi.domain.safety.LateralSafetyEvaluator

/**
 * 自主超车状态机全节拍测试（纯 JVM，注入假时钟）。
 *
 * 覆盖：完整 7 状态闭环、激光雷达逼近阻断、驾驶员反向打轮打断并锁定 60s、
 * 遥测超时熔断。
 */
class OvertakeStateMachineTest {

    private var now: Long = 1_000_000L

    private val commands = ArrayList<Pair<LaneAction, Int>>()
    private val cancels = ArrayList<LaneAction>()
    private val sounds = ArrayList<SoundEvent>()

    private val machine = OvertakeStateMachine(
        safetyEvaluator = LateralSafetyEvaluator(),
        laneOracle = RouteLaneMarkingOracle(),
        leadEvaluator = LeadVehicleEvaluator(),
        onControlCommand = { action, vCruise -> commands.add(action to vCruise) },
        onCancelCommand = { action -> cancels.add(action) },
        onSound = { event -> sounds.add(event) },
        clock = { now }
    )

    private val settings = OvertakeSettings()
    private val navi = NaviState(
        navValid = true,
        isHighway = true,
        driveWayRaw = "s|S|s",
        roadSpeedLimit = 120
    )

    private fun car(
        steerAngle: Float = 0f,
        atcState: Int = 0,
        lidarBehindVdistTime: Float = 99f,
        leftRearDistance: Float = 999f,
        timestamp: Long = now
    ): CarState = CarState(
        isOnroad = true,
        vEgo = 100,
        vCruise = 100,
        active = true,
        engaged = true,
        steerAngle = steerAngle,
        lead1 = true,
        drel = 30f,
        vrel = -0.5f,
        vLead = 80,
        lidarBehindVdistTime = lidarBehindVdistTime,
        leftRearDistance = leftRearDistance,
        atcState = atcState,
        timestamp = timestamp
    )

    private fun tick(carState: CarState) = machine.tick(carState, navi, settings)

    @Test
    fun `前车压速触发至变道完成进入20秒冷却并恢复待机`() {
        // 1. IDLE -> EVALUATING
        assertEquals(OvertakeState.EVALUATING, tick(car()).state)
        // 2. EVALUATING -> SIDE_CHECKING
        assertEquals(OvertakeState.SIDE_CHECKING, tick(car()).state)
        // 3. SIDE_CHECKING -> PREPARE（放行并预闪）
        assertEquals(OvertakeState.PREPARE, tick(car()).state)
        assertTrue(sounds.contains(SoundEvent.PRE_LANE_LEFT))
        // 4. 预闪 1.2s 后二次校验通过并下发指令 -> EXECUTING
        now += 1300L
        assertEquals(OvertakeState.EXECUTING, tick(car()).state)
        assertEquals(1, commands.size)
        assertEquals(LaneAction.LEFT to 100, commands.first())
        assertTrue(sounds.contains(SoundEvent.LANE_CHANGE))
        // 5. atc_state=3 反馈 -> FINISHING
        assertEquals(OvertakeState.FINISHING, tick(car(atcState = 3)).state)
        // 6. FINISHING -> COOLDOWN（20 秒）
        val cooling = tick(car(atcState = 3))
        assertEquals(OvertakeState.COOLDOWN, cooling.state)
        assertEquals(settings.standardCooldownMs, cooling.cooldownRemainMs)
        assertTrue(sounds.contains(SoundEvent.LANE_CHANGE_OK))
        // 7. 冷却结束后恢复 IDLE
        now += 20_001L
        assertEquals(OvertakeState.IDLE, tick(car(atcState = 3)).state)
        assertEquals(0L, machine.status().cooldownRemainMs)
        // 8. 待机下一拍重新进入评估
        assertEquals(OvertakeState.EVALUATING, tick(car(atcState = 3)).state)
    }

    @Test
    fun `激光雷达侧后逼近时阻断并停留在待机`() {
        assertEquals(OvertakeState.EVALUATING, tick(car()).state)
        assertEquals(OvertakeState.SIDE_CHECKING, tick(car()).state)
        // vEgo=100km/h 要求净空≈55.6m，实测仅 30m => 阻断
        val blocked = tick(car(leftRearDistance = 30f))
        assertEquals(OvertakeState.IDLE, blocked.state)
        assertEquals(BlockCause.LIDAR_HAZARD, blocked.blockCause)
        assertTrue(commands.isEmpty())
        assertTrue(sounds.contains(SoundEvent.CAR_WATCHOUT))
    }

    @Test
    fun `执行中驾驶员反向打轮立即撤销并锁定60秒`() {
        assertEquals(OvertakeState.EVALUATING, tick(car()).state)
        assertEquals(OvertakeState.SIDE_CHECKING, tick(car()).state)
        assertEquals(OvertakeState.PREPARE, tick(car()).state)
        now += 1300L
        assertEquals(OvertakeState.EXECUTING, tick(car()).state)

        // 方向盘突变 40° > 25° 阈值，判定驾驶员干预
        val interrupted = tick(car(steerAngle = 40f))
        assertEquals(OvertakeState.COOLDOWN, interrupted.state)
        assertEquals(1, cancels.size)
        assertEquals(LaneAction.LEFT, cancels.first())
        assertEquals(BlockCause.DRIVER_OVERRIDE, interrupted.blockCause)
        assertEquals(settings.interruptCooldownMs, interrupted.cooldownRemainMs)
        assertTrue(sounds.contains(SoundEvent.LANE_CHANGE_CANCEL))
    }

    @Test
    fun `遥测超时立即熔断回到待机`() {
        assertEquals(OvertakeState.EVALUATING, tick(car()).state)
        val lost = tick(car(timestamp = now - 1500L))
        assertEquals(OvertakeState.IDLE, lost.state)
        assertEquals(BlockCause.TELEMETRY_LOST, lost.blockCause)
    }

    // ---- UX-1：冷却倒计时显示用向上取整（旧写法 remainMs/1000+1 在恰整秒时多显 1 秒）----
    @Test
    fun `冷却倒计时恰整秒时不多显1秒`() {
        // 旧写法 remainMs=2000 → 2000/1000+1 = 3 秒（错）；正确 ceil(2.0s) = 2 秒
        // 用 2s 短冷却构造「恰剩 2000ms」的帧来区分。
        val short = OvertakeSettings(interruptCooldownMs = 3000L)
        val m2 = OvertakeStateMachine(
            safetyEvaluator = LateralSafetyEvaluator(),
            laneOracle = RouteLaneMarkingOracle(),
            leadEvaluator = LeadVehicleEvaluator(),
            onControlCommand = { _, _ -> }, onCancelCommand = { _ -> },
            onSound = { _ -> }, clock = { now },
        )
        fun t2(c: CarState, s: OvertakeSettings) = m2.tick(c, navi, s)
        t2(car(), short)            // IDLE -> EVALUATING
        t2(car(), short)           // -> SIDE_CHECKING
        t2(car(), short)           // -> PREPARE
        now += 1300L
        t2(car(), short)           // -> EXECUTING
        val cdStart = now
        t2(car(steerAngle = 40f), short)   // -> COOLDOWN，冷却至 cdStart+3000
        now = cdStart + 1000L              // 剩余恰 2000ms
        val st = t2(car(steerAngle = 40f), short)
        // 进入 COOLDOWN 后该帧走「反向变道冷却中，剩余 X 秒」分支，
        // remainMs 恰 2000ms 应向上取整显示 2 秒（旧 bug 写法会显示 3 秒）
        assertEquals("反向变道冷却中，剩余 2 秒", st.message)
    }

    @Test
    fun `执行中遥测中断由心跳撤单`() {
        assertEquals(OvertakeState.EVALUATING, tick(car()).state)
        assertEquals(OvertakeState.SIDE_CHECKING, tick(car()).state)
        assertEquals(OvertakeState.PREPARE, tick(car()).state)
        now += 1300L
        assertEquals(OvertakeState.EXECUTING, tick(car()).state)

        // 心跳看门狗在无遥测包时调用，必须撤销执行中的变道
        val lost = machine.onTelemetryLost(1500L)
        assertEquals(OvertakeState.IDLE, lost.state)
        assertEquals(LaneAction.KEEP, lost.action)
        assertEquals(BlockCause.TELEMETRY_LOST, lost.blockCause)
        assertEquals(1, cancels.size)
        assertEquals(LaneAction.LEFT, cancels.first())
        assertTrue(sounds.contains(SoundEvent.LANE_CHANGE_CANCEL))
    }

    @Test
    fun `待机时遥测中断不发送撤单`() {
        val lost = machine.onTelemetryLost(1500L)
        assertEquals(OvertakeState.IDLE, lost.state)
        assertEquals(BlockCause.TELEMETRY_LOST, lost.blockCause)
        assertTrue(cancels.isEmpty())
    }

    @Test
    fun `遥测恢复后重新进入评估`() {
        machine.onTelemetryLost(1500L)
        assertEquals(OvertakeState.EVALUATING, tick(car()).state)
        assertEquals(BlockCause.NONE, machine.status().blockCause)
    }

    @Test
    fun `前车距离过近时不触发超车`() {
        val nearCar = car().copy(drel = 5f)
        assertEquals(OvertakeState.EVALUATING, tick(nearCar).state)
        assertEquals(OvertakeState.EVALUATING, tick(nearCar).state)
        assertEquals(BlockCause.NONE, machine.status().blockCause)
    }
}
