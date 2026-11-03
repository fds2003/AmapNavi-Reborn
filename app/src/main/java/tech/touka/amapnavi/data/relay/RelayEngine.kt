package tech.touka.amapnavi.data.relay

import android.content.Context
import android.content.IntentFilter
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.event.OvertakeStatus
import tech.touka.amapnavi.core.event.SoundEvent
import tech.touka.amapnavi.core.model.CarState
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.model.OvertakeSettings
import tech.touka.amapnavi.data.amap.AmapBroadcastDecoder
import tech.touka.amapnavi.data.amap.AmapContract
import tech.touka.amapnavi.data.prefs.UserSettingsRepository
import tech.touka.amapnavi.data.udp.C3Device
import tech.touka.amapnavi.data.udp.CarrotTelemetryParser
import tech.touka.amapnavi.data.udp.CarrotUdpClient
import tech.touka.amapnavi.data.udp.UdpDeviceDiscovery
import tech.touka.amapnavi.domain.decision.LeadVehicleEvaluator
import tech.touka.amapnavi.domain.decision.OvertakeStateMachine
import tech.touka.amapnavi.domain.decision.RampSpeedPlanner
import tech.touka.amapnavi.domain.decision.RouteLaneMarkingOracle
import tech.touka.amapnavi.domain.safety.LateralSafetyEvaluator
import tech.touka.amapnavi.ui.sound.SoundFeedbackEngine

/**
 * 车机端协同中继引擎（单进程单实例）。
 *
 * 负责把高德广播、C3 遥测、超车状态机、UDP 控制与音效编排成一条单向数据流：
 * AmapBroadcastDecoder / CarrotUdpClient -> StateFlow -> OvertakeStateMachine -> UI + C3。
 */
class RelayEngine private constructor(context: Context) {

    companion object {
        private const val SPEED_LIMIT_COMMAND = "SPEED_LIMIT"
        private const val LANE_CHANGE_COMMAND = "LANECHANGE"
        private const val CANCEL_COMMAND = "CANCEL_LANE_CHANGE"
        private const val TURN_TYPE_RIGHT = 3
        private const val RAMP_PLAN_START_DISTANCE_M = 500
        private const val TELEMETRY_TIMEOUT_MS = 1500L

        @Volatile
        private var instance: RelayEngine? = null

        fun get(context: Context): RelayEngine {
            return instance ?: synchronized(this) {
                instance ?: RelayEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsRepository = UserSettingsRepository.getInstance(appContext)
    private val soundEngine = SoundFeedbackEngine(appContext)
    private val decoder = AmapBroadcastDecoder()
    private val rampSpeedPlanner = RampSpeedPlanner()

    private val _carStateFlow = MutableStateFlow(CarState())
    val carStateFlow: StateFlow<CarState> = _carStateFlow

    val naviStateFlow: StateFlow<NaviState> = decoder.naviStateFlow

    private val _deviceFlow = MutableStateFlow<C3Device?>(null)
    val deviceFlow: StateFlow<C3Device?> = _deviceFlow

    private val _plannedRampSpeedFlow = MutableStateFlow(0)
    val plannedRampSpeedFlow: StateFlow<Int> = _plannedRampSpeedFlow

    private val udpClient = CarrotUdpClient(
        scope = scope,
        onTelemetryPayload = { payload -> onTelemetry(payload) }
    )

    private val deviceDiscovery = UdpDeviceDiscovery(
        scope = scope,
        onDeviceFound = { device ->
            _deviceFlow.value = device
            udpClient.updateTarget(device.ip)
        }
    )

    private val stateMachine = OvertakeStateMachine(
        safetyEvaluator = LateralSafetyEvaluator(),
        laneOracle = RouteLaneMarkingOracle(),
        leadEvaluator = LeadVehicleEvaluator(),
        onControlCommand = { action, vCruise ->
            udpClient.sendControlCommand(
                LANE_CHANGE_COMMAND, action, vCruise,
                settingsRepository.getInt(UserSettingsRepository.KEY_OVERTAKE_MODE, 1)
            )
        },
        onCancelCommand = { action ->
            udpClient.sendControlCommand(CANCEL_COMMAND, action, 0)
        },
        onSound = { event -> soundEngine.play(event) }
    )

    private val _overtakeStatusFlow = MutableStateFlow(stateMachine.status())
    val overtakeStatusFlow: StateFlow<OvertakeStatus> = _overtakeStatusFlow

    @Volatile
    private var running = false

    private var rampBaseCruise = 0
    private var rampLastSentCruise = 0

    private var trafficLightMonitorJob: Job? = null
    private var lastTrafficLightStatus = 0

    /** 遥测心跳：tick 只在收包时运行，断流必须由独立线程发现 */
    private val telemetryWatchdog = TelemetryWatchdog(TELEMETRY_TIMEOUT_MS)
    private var telemetryWatchdogJob: Job? = null

    /** 启动中继；重复调用幂等。 */
    fun start() {
        if (running) return
        running = true
        try {
            appContext.registerReceiver(
                decoder,
                IntentFilter(AmapContract.ACTION_AMAP_SEND)
            )
        } catch (e: Exception) {
            // 高德广播注册失败不阻断 UDP 链路
        }
        udpClient.start()
        deviceDiscovery.start()
        startTrafficLightMonitor()
        startTelemetryWatchdog()
        _overtakeStatusFlow.value = stateMachine.status()
    }

    /** 停止中继并清空所有决策状态。 */
    fun stop() {
        if (!running) return
        running = false
        try {
            appContext.unregisterReceiver(decoder)
        } catch (e: Exception) {
            // 未注册时忽略
        }
        decoder.reset()
        udpClient.stop()
        deviceDiscovery.stop()
        trafficLightMonitorJob?.cancel()
        trafficLightMonitorJob = null
        lastTrafficLightStatus = 0
        telemetryWatchdogJob?.cancel()
        telemetryWatchdogJob = null
        telemetryWatchdog.reset()
        stateMachine.reset()
        _carStateFlow.value = CarState()
        _overtakeStatusFlow.value = stateMachine.status()
        _plannedRampSpeedFlow.value = 0
        rampBaseCruise = 0
        rampLastSentCruise = 0
    }

    /** 未配置时收到任何遥测包即自动学习 C3 源地址，这里同时接受主动配网。 */
    fun updateDevice(device: C3Device) {
        _deviceFlow.value = device
        udpClient.updateTarget(device.ip)
    }
    fun updateTargetDevice(host: String) {
        udpClient.updateTarget(host)
    }

    /** 重新发起局域网设备发现。 */
    fun rescanDevices() {
        if (!running) return
        deviceDiscovery.stop()
        deviceDiscovery.start()
    }

    fun isOvertakeEnabled(): Boolean = settingsRepository.settings.enabled

    fun setOvertakeEnabled(enabled: Boolean) {
        settingsRepository.setOvertakeEnabled(enabled)
        if (!enabled) {
            stateMachine.abort()
        }
        _overtakeStatusFlow.value = stateMachine.status()
    }

    /** 巡航设定增减，通过 SPEED_LIMIT 指令同步至 C3。 */
    fun nudgeCruise(delta: Int): Int {
        val current = _carStateFlow.value.vCruise
        val next = if (current <= 0) 30 else (current + delta).coerceIn(30, 160)
        udpClient.sendControlCommand(SPEED_LIMIT_COMMAND, LaneAction.KEEP, next)
        return next
    }

    /** 人工复位决策引擎：执行中的变道会先被撤销。 */
    fun resetDecision() {
        stateMachine.abort()
        _overtakeStatusFlow.value = stateMachine.status()
    }

    private fun startTrafficLightMonitor() {
        if (trafficLightMonitorJob?.isActive == true) return
        trafficLightMonitorJob = scope.launch {
            decoder.naviStateFlow.collect { navi ->
                val previous = lastTrafficLightStatus
                if (previous == 1 && navi.trafficLightStatus == 2) {
                    soundEngine.play(SoundEvent.TRAFFIC_SIGN_GREEN)
                }
                lastTrafficLightStatus = navi.trafficLightStatus
            }
        }
    }

    private fun onTelemetry(payload: String) {
        telemetryWatchdog.onTelemetry(SystemClock.elapsedRealtime())
        val car = CarrotTelemetryParser.parse(payload)
        val navi = decoder.getFreshNaviState(2000L)
        _carStateFlow.value = car

        val settings: OvertakeSettings = settingsRepository.settings
        _overtakeStatusFlow.value = stateMachine.tick(car, navi, settings)

        updateRampSpeed(car, navi)
    }

    /**
     * 独立心跳看门狗：不依赖遥测包到达，每 100ms 检查一次。
     * 断流超过 1.5s 立即撤销执行中的变道并广播 TELEMETRY_LOST，避免状态机冻结在 EXECUTING。
     */
    private fun startTelemetryWatchdog() {
        if (telemetryWatchdogJob?.isActive == true) return
        telemetryWatchdogJob = scope.launch {
            while (isActive) {
                delay(100L)
                if (!running) continue
                if (telemetryWatchdog.shouldTrip(SystemClock.elapsedRealtime())) {
                    _overtakeStatusFlow.value = stateMachine.onTelemetryLost(TELEMETRY_TIMEOUT_MS)
                }
            }
        }
    }

    /**
     * 匝道平滑减速：仅在高速右转/出口匝道 500m 内生效，驶离后恢复基线巡航速度。
     */
    private fun updateRampSpeed(car: CarState, navi: NaviState) {
        val inRampZone = navi.navValid &&
            navi.isHighway &&
            navi.tbtTurnType == TURN_TYPE_RIGHT &&
            navi.tbtDistance in 0..RAMP_PLAN_START_DISTANCE_M

        if (!inRampZone) {
            if (rampLastSentCruise > 0 && rampBaseCruise > rampLastSentCruise) {
                udpClient.sendControlCommand(SPEED_LIMIT_COMMAND, LaneAction.KEEP, rampBaseCruise)
            }
            rampBaseCruise = 0
            rampLastSentCruise = 0
            _plannedRampSpeedFlow.value = 0
            return
        }

        if (rampBaseCruise <= 0) {
            rampBaseCruise = car.vCruise
        }
        if (rampBaseCruise <= 0) return

        val planned = rampSpeedPlanner.calculateCruiseSpeed(navi.tbtDistance, rampBaseCruise)
        if (planned < rampBaseCruise && planned != rampLastSentCruise) {
            rampLastSentCruise = planned
            udpClient.sendControlCommand(SPEED_LIMIT_COMMAND, LaneAction.KEEP, planned)
        }
        _plannedRampSpeedFlow.value = planned
    }
}
