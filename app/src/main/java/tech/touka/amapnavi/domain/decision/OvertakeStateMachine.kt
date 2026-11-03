package tech.touka.amapnavi.domain.decision

import kotlin.math.abs
import tech.touka.amapnavi.core.event.BlockCause
import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.event.OvertakeState
import tech.touka.amapnavi.core.event.OvertakeStatus
import tech.touka.amapnavi.core.event.SoundEvent
import tech.touka.amapnavi.core.model.CarState
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.model.OvertakeSettings
import tech.touka.amapnavi.domain.safety.LateralSafetyEvaluator
import tech.touka.amapnavi.domain.safety.SafetyAuditResult

/**
 * 自主超车状态机（纯 Kotlin，可注入时钟以保证测试确定性）。
 *
 * 时钟周期驱动：每个 C3 遥测包到达触发一次 tick，依序执行
 * 看门狗 -> 冷却检查 -> 前车压速评估 -> 侧向安全审计 -> 指令下发/状态跃迁。
 *
 * 状态流转：
 * IDLE -> EVALUATING -> SIDE_CHECKING -> PREPARE -> EXECUTING -> FINISHING -> COOLDOWN -> IDLE
 */
class OvertakeStateMachine(
    private val safetyEvaluator: LateralSafetyEvaluator,
    private val laneOracle: LaneMarkingOracle,
    private val leadEvaluator: LeadVehicleEvaluator,
    private val onControlCommand: (LaneAction, Int) -> Unit,
    private val onCancelCommand: (LaneAction) -> Unit,
    private val onSound: (SoundEvent) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private var state: OvertakeState = OvertakeState.IDLE
    private var candidate: LaneAction = LaneAction.KEEP
    private var stateSinceMs: Long = 0L
    private var cooldownUntilMs: Long = 0L
    private var steerAtExecutionStart: Float = 0f
    private var blockCause: BlockCause = BlockCause.NONE
    private var message: String = "待机"

    fun currentState(): OvertakeState = state

    /** 只读快照，供 UI 初始化时读取。 */
    fun status(): OvertakeStatus = snapshot(clock())

    fun tick(car: CarState, navi: NaviState, settings: OvertakeSettings): OvertakeStatus {
        val now = clock()

        // 第一层防御：协议看门狗，遥测中断立即熔断
        if (!car.isTelemetryFresh(now, settings.telemetryTimeoutMs)) {
            return onTelemetryLost(settings.telemetryTimeoutMs)
        }

        // 反向变道防横跳冷却锁
        if (now < cooldownUntilMs) {
            state = OvertakeState.COOLDOWN
            val remainMs = cooldownUntilMs - now
            message = "反向变道冷却中，剩余 ${(remainMs / 1000L) + 1L} 秒"
            return snapshot(now)
        }
        if (state == OvertakeState.COOLDOWN && cooldownUntilMs > 0L) {
            cooldownUntilMs = 0L
            state = OvertakeState.IDLE
            candidate = LaneAction.KEEP
            blockCause = BlockCause.NONE
            message = "冷却结束，恢复待机"
            return snapshot(now)
        }

        when (state) {
            OvertakeState.IDLE -> handleIdle(car, navi, settings, now)
            OvertakeState.EVALUATING -> handleEvaluating(car, navi, settings, now)
            OvertakeState.SIDE_CHECKING -> handleSideChecking(car, navi, now)
            OvertakeState.PREPARE -> handlePrepare(car, navi, settings, now)
            OvertakeState.EXECUTING -> handleExecuting(car, settings, now)
            OvertakeState.FINISHING -> handleFinishing(settings, now)
            OvertakeState.COOLDOWN -> {
                // 冷却锁未命中时不可能进入该分支，保持防御性落回待机
                state = OvertakeState.IDLE
                blockCause = BlockCause.NONE
                message = "待机"
            }
        }

        return snapshot(now)
    }

    private fun handleIdle(car: CarState, navi: NaviState, settings: OvertakeSettings, now: Long) {
        candidate = LaneAction.KEEP
        blockCause = BlockCause.NONE
        when {
            !settings.enabled -> message = "自动超车已关闭"
            !car.engaged -> {
                blockCause = BlockCause.NOT_ENGAGED
                message = "智驾未接管，禁止发起超车"
            }
            car.vEgo < settings.minEngageSpeedKmh -> {
                blockCause = BlockCause.SPEED_TOO_LOW
                message = "车速 ${car.vEgo}km/h 低于准入 ${settings.minEngageSpeedKmh}km/h"
            }
            !navi.isHighway -> {
                blockCause = BlockCause.NOT_HIGHWAY
                message = "当前非高速路段，禁止自主超车"
            }
            !navi.navValid -> {
                blockCause = BlockCause.ROUTE_INVALID
                message = "无有效高德导航数据"
            }
            else -> {
                state = OvertakeState.EVALUATING
                stateSinceMs = now
                message = "开始评估前车压速条件"
            }
        }
    }

    private fun handleEvaluating(
        car: CarState,
        navi: NaviState,
        settings: OvertakeSettings,
        now: Long
    ) {
        if (!car.engaged || car.vEgo < settings.minEngageSpeedKmh || !navi.isHighway) {
            state = OvertakeState.IDLE
            blockCause = if (!car.engaged) BlockCause.NOT_ENGAGED else BlockCause.SPEED_TOO_LOW
            message = "运行条件不满足，退回待机"
            return
        }

        val evaluation = leadEvaluator.evaluate(car)
        if (!evaluation.triggered) {
            blockCause = BlockCause.NONE
            message = evaluation.reason
            return
        }

        val direction = chooseDirection(navi, settings)
        if (direction == LaneAction.KEEP) {
            state = OvertakeState.IDLE
            blockCause = BlockCause.NO_TARGET_LANE
            message = "${evaluation.reason}，但侧向无可用车道"
            return
        }

        candidate = direction
        state = OvertakeState.SIDE_CHECKING
        stateSinceMs = now
        blockCause = BlockCause.NONE
        message = "${evaluation.reason}，开始${directionName(direction)}侧向安全检查"
    }

    private fun handleSideChecking(car: CarState, navi: NaviState, now: Long) {
        val audit = audit(candidate, car, navi)
        if (audit.isSafe) {
            onSound(
                if (candidate == LaneAction.LEFT) {
                    SoundEvent.PRE_LANE_LEFT
                } else {
                    SoundEvent.PRE_LANE_RIGHT
                }
            )
            state = OvertakeState.PREPARE
            stateSinceMs = now
            blockCause = BlockCause.NONE
            message = "变道预备：转向灯预闪，等待二次安全校验"
            return
        }

        state = OvertakeState.IDLE
        blockCause = audit.blockCause
        message = audit.reasonDesc
        when {
            audit.isSolidBlocked -> onSound(SoundEvent.SOLID_BLOCKED)
            audit.isBsdBlocked || audit.isLidarBlocked -> onSound(SoundEvent.CAR_WATCHOUT)
        }
    }

    private fun handlePrepare(
        car: CarState,
        navi: NaviState,
        settings: OvertakeSettings,
        now: Long
    ) {
        if (now - stateSinceMs < settings.prepareBlinkMs) {
            message = "变道预备中，剩余预闪 ${(settings.prepareBlinkMs - (now - stateSinceMs))}ms"
            return
        }

        val audit = audit(candidate, car, navi)
        if (!audit.isSafe) {
            state = OvertakeState.IDLE
            blockCause = audit.blockCause
            message = audit.reasonDesc
            when {
                audit.isSolidBlocked -> onSound(SoundEvent.SOLID_BLOCKED)
                audit.isBsdBlocked || audit.isLidarBlocked -> onSound(SoundEvent.CAR_WATCHOUT)
            }
            return
        }

        onControlCommand(candidate, car.vCruise)
        onSound(SoundEvent.LANE_CHANGE)
        steerAtExecutionStart = car.steerAngle
        state = OvertakeState.EXECUTING
        stateSinceMs = now
        blockCause = BlockCause.NONE
        message = "已下发 ${candidate.code} 变道指令，等待执行反馈"
    }

    private fun handleExecuting(car: CarState, settings: OvertakeSettings, now: Long) {
        val steerDelta = abs(car.steerAngle - steerAtExecutionStart)
        val driverIntervened = !car.engaged || car.brakePress || car.gasPress ||
            steerDelta > settings.driverSteerThresholdDeg

        if (driverIntervened) {
            onCancelCommand(candidate)
            onSound(SoundEvent.LANE_CHANGE_CANCEL)
            val cause = if (!car.engaged) BlockCause.NOT_ENGAGED else BlockCause.DRIVER_OVERRIDE
            cooldownUntilMs = now + settings.interruptCooldownMs
            state = OvertakeState.COOLDOWN
            stateSinceMs = now
            blockCause = cause
            message = "驾驶员干预(Δsteer=${formatDeg(steerDelta)}°)，已撤销变道并锁定 " +
                "${settings.interruptCooldownMs / 1000L} 秒冷却"
            return
        }

        if (car.atcState == 3) {
            state = OvertakeState.FINISHING
            stateSinceMs = now
            message = "收到变道完成反馈(atc_state=3)"
            return
        }

        if (now - stateSinceMs > settings.executeTimeoutMs) {
            onCancelCommand(candidate)
            onSound(SoundEvent.LANE_CHANGE_CANCEL)
            cooldownUntilMs = now + settings.interruptCooldownMs
            state = OvertakeState.COOLDOWN
            stateSinceMs = now
            blockCause = BlockCause.TELEMETRY_LOST
            message = "变道执行超时 ${settings.executeTimeoutMs}ms，已撤销变道并锁定冷却"
            return
        }
    }
    private fun handleFinishing(settings: OvertakeSettings, now: Long) {
        onSound(SoundEvent.LANE_CHANGE_OK)
        cooldownUntilMs = now + settings.standardCooldownMs
        state = OvertakeState.COOLDOWN
        stateSinceMs = now
        blockCause = BlockCause.COOLDOWN
        message = "变道完成，进入 ${settings.standardCooldownMs / 1000L} 秒反向变道冷却"
    }

    private fun chooseDirection(navi: NaviState, settings: OvertakeSettings): LaneAction {
        if (laneOracle.hasTargetLane(navi, LaneAction.LEFT)) return LaneAction.LEFT
        if (settings.allowRightOvertake && laneOracle.hasTargetLane(navi, LaneAction.RIGHT)) {
            return LaneAction.RIGHT
        }
        return LaneAction.KEEP
    }

    private fun audit(action: LaneAction, car: CarState, navi: NaviState): SafetyAuditResult {
        if (action == LaneAction.KEEP) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.NO_TARGET_LANE,
                reasonDesc = "未指定目标车道"
            )
        }
        if (!laneOracle.hasTargetLane(navi, action)) {
            return SafetyAuditResult(
                isSafe = false,
                blockCause = BlockCause.NO_TARGET_LANE,
                reasonDesc = "目标${directionName(action)}侧无可用车道"
            )
        }
        return safetyEvaluator.evaluateSide(
            action = action,
            car = car,
            isTargetSolid = laneOracle.isTargetSolid(navi, action)
        )
    }

    private fun snapshot(now: Long): OvertakeStatus {
        val remain = if (cooldownUntilMs > now) cooldownUntilMs - now else 0L
        return OvertakeStatus(
            state = state,
            action = if (state == OvertakeState.IDLE) LaneAction.KEEP else candidate,
            blockCause = blockCause,
            message = message,
            cooldownRemainMs = remain,
            updatedAtMs = now
        )
    }

    private fun directionName(action: LaneAction): String {
        return if (action == LaneAction.LEFT) "左" else "右"
    }

    private fun formatDeg(value: Float): String {
        return String.format(java.util.Locale.US, "%.1f", value)
    }

    /** 纯复位：不发送撤销指令。 */
    fun reset() {
        state = OvertakeState.IDLE
        candidate = LaneAction.KEEP
        cooldownUntilMs = 0L
        steerAtExecutionStart = 0f
        blockCause = BlockCause.NONE
        message = "待机"
        stateSinceMs = clock()
    }

    /** 人工复位：若正在执行变道则先撤销，再回到待机。 */
    fun abort() {
        if (state == OvertakeState.EXECUTING) {
            onCancelCommand(candidate)
        }
        reset()
    }

    /**
     * 遥测中断熔断。
     * tick() 只在收到遥测包时运行，无法自行发现「断流」；需由 RelayEngine 的独立心跳线程调用。
     */
    fun onTelemetryLost(timeoutMs: Long): OvertakeStatus {
        val now = clock()
        if (state == OvertakeState.EXECUTING) {
            onCancelCommand(candidate)
            onSound(SoundEvent.LANE_CHANGE_CANCEL)
        }
        state = OvertakeState.IDLE
        candidate = LaneAction.KEEP
        blockCause = BlockCause.TELEMETRY_LOST
        message = "C3 遥测中断超过 ${timeoutMs}ms，已恢复安全静默"
        stateSinceMs = now
        return snapshot(now)
    }
}
