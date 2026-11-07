package tech.touka.amapnavi.ui.main

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tech.touka.amapnavi.R
import tech.touka.amapnavi.core.event.BlockCause
import tech.touka.amapnavi.core.event.OvertakeState
import tech.touka.amapnavi.core.event.OvertakeStatus
import tech.touka.amapnavi.core.model.CarState
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.core.util.LaneWayParser
import tech.touka.amapnavi.data.relay.RelayEngine
import tech.touka.amapnavi.data.relay.LinkHealthLevel
import tech.touka.amapnavi.data.udp.C3Device
import tech.touka.amapnavi.service.KeepAliveService
import tech.touka.amapnavi.ui.setting.SettingActivity
import tech.touka.amapnavi.ui.setting.NavSettingActivity
import tech.touka.amapnavi.ui.setting.OvertakeSettingActivity
import tech.touka.amapnavi.ui.setting.LogManagerActivity
import tech.touka.amapnavi.ui.setting.UdpDeviceActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车载横屏 HUD 仪表：大字车速、高德车道指引、6 块雷达卡片、决策 Trace 与实体微调按钮。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: RelayEngine

    private lateinit var tvNaviStatus: TextView
    private lateinit var tvDeviceStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvCruise: TextView
    private lateinit var tvLimit: TextView
    private lateinit var tvTbt: TextView
    private lateinit var tvLight: TextView
    private lateinit var tvCruiseAdjust: TextView
    private lateinit var tvBsdLeft: TextView
    private lateinit var tvLead: TextView
    private lateinit var tvBsdRight: TextView
    private lateinit var tvSideLeft: TextView
    private lateinit var tvSideRight: TextView
    private lateinit var tvLidarLeft: TextView
    private lateinit var tvEgo: TextView
    private lateinit var tvLidarRight: TextView
    private lateinit var tvOvertakeState: TextView
    private lateinit var tvOvertakeMessage: TextView
    private lateinit var tvTrace: TextView
    private lateinit var laneWayContainer: LinearLayout
    private lateinit var btnOvertake: Button
    private var layoutSettingsOverlay: View? = null
    private var settingController: tech.touka.amapnavi.ui.setting.SettingViewController? = null
    private var layoutClusterOverlay: View? = null
    private var clusterController: tech.touka.amapnavi.ui.setting.ClusterSettingsTabController? = null
    private var settingsOverlayRequested = false
    private var clusterOverlayRequested = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val traceLines = ArrayList<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var currentCar = CarState()
    private var currentNavi = NaviState()
    private var plannedRampSpeed = 0
    private var lastStatus: OvertakeStatus? = null
    private var lastLaneRaw = ""
    private var lastSuggest: List<Int> = emptyList()
    private var lastDisable: List<Int> = emptyList()
    private var wasNaviConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        engine = RelayEngine.get(this)
        bindViews()
        setupListeners()
        observeState()
        updateOvertakeButton()
        startKeepAliveService()
        engine.start()

        // 首帧后再预加载设置/仪表覆盖层，避免拖慢启动，同时消除首次点击卡顿
        window.decorView.post { preloadOverlays() }

        val showTab = intent.getIntExtra("show_tab", -1)
        if (showTab != -1) openSettingTab(showTab)
    }
    override fun onResume() {
        super.onResume()
        engine.start()
        updateOvertakeButton()
    }

    override fun onDestroy() {
        clusterController?.dispose()
        clusterController = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        tvNaviStatus = findViewById(R.id.tv_navi_status)
        tvDeviceStatus = findViewById(R.id.tv_device_status)
        tvSpeed = findViewById(R.id.tv_speed)
        tvCruise = findViewById(R.id.tv_cruise)
        tvLimit = findViewById(R.id.tv_limit)
        tvTbt = findViewById(R.id.tv_tbt)
        tvLight = findViewById(R.id.tv_light)
        tvCruiseAdjust = findViewById(R.id.tv_cruise_adjust)
        tvBsdLeft = findViewById(R.id.tv_bsd_left)
        tvLead = findViewById(R.id.tv_lead)
        tvBsdRight = findViewById(R.id.tv_bsd_right)
        tvSideLeft = findViewById(R.id.tv_side_left)
        tvSideRight = findViewById(R.id.tv_side_right)
        tvLidarLeft = findViewById(R.id.tv_lidar_left)
        tvEgo = findViewById(R.id.tv_ego)
        tvLidarRight = findViewById(R.id.tv_lidar_right)
        tvOvertakeState = findViewById(R.id.tv_overtake_state)
        tvOvertakeMessage = findViewById(R.id.tv_overtake_message)
        tvTrace = findViewById(R.id.tv_trace)
        laneWayContainer = findViewById(R.id.lane_way_container)
        btnOvertake = findViewById(R.id.btn_overtake)
    }

    private fun setupListeners() {
        btnOvertake.setOnClickListener {
            val enabled = !engine.isOvertakeEnabled()
            engine.setOvertakeEnabled(enabled)
            updateOvertakeButton()
            Toast.makeText(
                this,
                if (enabled) "自动超车已开启" else "自动超车已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btn_open_setting)?.setOnClickListener { openSettingTab(0) }
        findViewById<Button>(R.id.btn_open_nav_setting)?.setOnClickListener { openSettingTab(1) }
        findViewById<Button>(R.id.btn_open_overtake_setting)?.setOnClickListener { openSettingTab(2) }
        findViewById<Button>(R.id.btn_open_cluster_setting)?.setOnClickListener {
            openClusterOverlay()
        }
        findViewById<Button>(R.id.btn_open_devices)?.setOnClickListener {
            startActivity(Intent(this, UdpDeviceActivity::class.java))
            overridePendingTransition(0, 0)
        }
        findViewById<Button>(R.id.btn_open_logs)?.setOnClickListener {
            startActivity(Intent(this, LogManagerActivity::class.java))
            overridePendingTransition(0, 0)
        }
        findViewById<Button>(R.id.btn_reset)?.setOnClickListener {
            engine.resetDecision()
            Toast.makeText(this, "决策引擎已复位", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_cruise_inc).setOnClickListener {
            notifyCruiseRequest(engine.nudgeCruise(5))
        }
        findViewById<Button>(R.id.btn_cruise_dec).setOnClickListener {
            notifyCruiseRequest(engine.nudgeCruise(-5))
        }
    }

    private fun ensureSettingsInitialized(): Boolean {
        if (layoutSettingsOverlay == null) {
            val stub = findViewById<android.view.ViewStub>(R.id.stub_settings_overlay) ?: return false
            val inflated = stub.inflate()
            layoutSettingsOverlay = inflated
            settingController = tech.touka.amapnavi.ui.setting.SettingViewController(
                activity = this,
                rootView = inflated,
                repo = tech.touka.amapnavi.data.prefs.UserSettingsRepository.getInstance(this),
                onCloseRequested = { inflated.visibility = View.GONE }
            )
        }
        return true
    }

    /**
     * 首帧后预加载覆盖层：布局 inflate + 通用参数控件绑定只做一次，
     * 首次点击“通用设置”/“仪表投影”只切 visibility。
     */
    private fun preloadOverlays() {
        if (ensureSettingsInitialized()) {
            settingController?.preloadGeneralTab()
            if (!settingsOverlayRequested) {
                layoutSettingsOverlay?.visibility = View.GONE
            }
        }
        if (ensureClusterInitialized()) {
            if (!clusterOverlayRequested) {
                layoutClusterOverlay?.visibility = View.GONE
            }
        }
    }

    private fun openSettingTab(tabIndex: Int) {
        settingsOverlayRequested = true
        if (ensureSettingsInitialized()) {
            layoutSettingsOverlay?.visibility = View.VISIBLE
            settingController?.showTab(tabIndex)
        }
    }

    private fun ensureClusterInitialized(): Boolean {
        if (layoutClusterOverlay == null) {
            val stub = findViewById<android.view.ViewStub>(R.id.stub_cluster_overlay) ?: return false
            val inflated = stub.inflate()
            layoutClusterOverlay = inflated
            clusterController = tech.touka.amapnavi.ui.setting.ClusterSettingsTabController(
                activity = this,
                root = inflated,
                repo = tech.touka.amapnavi.data.prefs.UserSettingsRepository.getInstance(this),
                onClose = { inflated.visibility = View.GONE }
            )
        }
        return true
    }

    private fun openClusterOverlay() {
        clusterOverlayRequested = true
        if (ensureClusterInitialized()) {
            layoutClusterOverlay?.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (layoutClusterOverlay?.visibility == View.VISIBLE) {
            layoutClusterOverlay?.visibility = View.GONE
        } else if (layoutSettingsOverlay?.visibility == View.VISIBLE) {
            layoutSettingsOverlay?.visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val showTab = intent?.getIntExtra("show_tab", -1) ?: -1
        if (showTab != -1) openSettingTab(showTab)
    }

    private fun observeState() {
        uiScope.launch {
            engine.carStateFlow.collect { car -> renderCar(car) }
        }
        uiScope.launch {
            engine.naviStateFlow.collect { navi -> renderNavi(navi) }
        }
        uiScope.launch {
            engine.overtakeStatusFlow.collect { status -> renderOvertake(status) }
        }
        uiScope.launch {
            engine.deviceFlow.collect { device -> renderDevice(device) }
        }
        uiScope.launch {
            engine.linkHealthFlow.collect { level -> renderLinkHealth(level) }
        }
        uiScope.launch {
            engine.plannedRampSpeedFlow.collect { speed ->
                plannedRampSpeed = speed
                refreshTbtLine()
            }
        }
        appendTrace("系统就绪，等待高德广播与 C3 遥测")
    }

    private fun renderCar(car: CarState) {
        currentCar = car

        tvSpeed.text = car.vEgo.toString()
        tvCruise.text = "巡航 ${car.vCruise} km/h"
        tvCruiseAdjust.text = "定速微调 ${car.vCruise}"

        tvBsdLeft.text = if (car.leftBlindspot) "有车报警" else "正常"
        tvBsdLeft.setTextColor(
            color(if (car.leftBlindspot) R.color.danger_red else R.color.success_green)
        )

        tvBsdRight.text = if (car.rightBlindspot) "有车报警" else "正常"
        tvBsdRight.setTextColor(
            color(if (car.rightBlindspot) R.color.danger_red else R.color.success_green)
        )

        renderSideReminder(car)

        tvLead.text = if (car.lead1) {
            String.format(Locale.US, "%.1fm\n%dkm/h", car.drel, car.vLead)
        } else {
            "无目标"
        }
        tvLead.setTextColor(
            color(if (car.lead1) R.color.text_primary else R.color.text_muted)
        )

        // C3 无侧后雷达时 ttc 恒为 0（fail-closed），需显式告知，避免误判为软件故障
        val rearRadarMissing = car.isOnroad && car.lidarBehindVdistTime <= 0f
        tvLidarLeft.text = if (rearRadarMissing) "未检测到雷达" else lidarText(car.leftRearDistance, car.lidarLeftRearBlind)
        tvLidarLeft.setTextColor(
            color(if (car.lidarLeftRearBlind) R.color.danger_red else R.color.text_primary)
        )

        tvLidarRight.text = if (rearRadarMissing) "未检测到雷达" else lidarText(car.rightRearDistance, car.lidarRightRearBlind)
        tvLidarRight.setTextColor(
            color(if (car.lidarRightRearBlind) R.color.danger_red else R.color.text_primary)
        )

        val driveMode = when {
            car.engaged -> "智驾接管"
            car.active -> "ACC 激活"
            else -> "人工驾驶"
        }
        tvEgo.text = "$driveMode\nΔsteer ${String.format(Locale.US, "%.1f", car.steerAngle)}°"
    }

    private fun renderNavi(navi: NaviState) {
        currentNavi = navi

        val justConnected = !wasNaviConnected && navi.navValid
        wasNaviConnected = navi.navValid
        if (justConnected) {
            val speedInfo = if (navi.roadSpeedLimit > 0) "限速 ${navi.roadSpeedLimit}km/h" else "自适应巡航"
            appendTrace("高德广播已连接 ($speedInfo)")
        }

        tvNaviStatus.text = if (navi.navValid) "高德已连接" else "高德未连接"
        tvNaviStatus.setTextColor(
            color(if (navi.navValid) R.color.navi_blue else R.color.text_muted)
        )

        tvLimit.text = if (navi.roadSpeedLimit > 0) {
            "限速 ${navi.roadSpeedLimit}"
        } else {
            "限速 --"
        }

        // 显眼矢量信号灯徽章渲染
        if (navi.trafficLightStatus == 1 && navi.trafficLightSeconds > 0) {
            tvLight.text = "🚦 红灯 ${navi.trafficLightSeconds}s"
            tvLight.setTextColor(0xFFFFFFFF.toInt())
            tvLight.setBackgroundColor(0xFFD32F2F.toInt())
        } else if (navi.trafficLightStatus == 2 && navi.trafficLightSeconds > 0) {
            tvLight.text = "🟢 绿灯 ${navi.trafficLightSeconds}s"
            tvLight.setTextColor(0xFFFFFFFF.toInt())
            tvLight.setBackgroundColor(0xFF388E3C.toInt())
        } else {
            tvLight.text = "路口无信号灯"
            tvLight.setTextColor(0xFF888888.toInt())
            tvLight.setBackgroundColor(0xFF222222.toInt())
        }
        refreshTbtLine()
        renderLaneWay(navi)
    }

    private fun refreshTbtLine() {
        val distanceText = if (currentNavi.tbtDistance > 0) {
            "路口 ${currentNavi.tbtDistance} m"
        } else {
            "路口 -- m"
        }
        val rampText = if (plannedRampSpeed > 0) {
            " · 匝道规划 ${plannedRampSpeed} km/h"
        } else {
            ""
        }
        tvTbt.text = distanceText + rampText
        tvTbt.setTextColor(
            color(if (plannedRampSpeed > 0) R.color.warning_amber else R.color.text_secondary)
        )
    }

    private fun renderLaneWay(navi: NaviState) {
        if (navi.driveWayRaw == lastLaneRaw &&
            navi.suggestLanes == lastSuggest &&
            navi.disableLanes == lastDisable
        ) {
            return
        }
        lastLaneRaw = navi.driveWayRaw
        lastSuggest = navi.suggestLanes
        lastDisable = navi.disableLanes

        laneWayContainer.removeAllViews()
        val parsed = LaneWayParser.parse(navi.driveWayRaw)

        if (parsed.laneCount == 0) {
            addLaneCell("无车道数据", color(R.color.text_muted), false, dp(140))
            return
        }

        parsed.tokens.forEachIndexed { index, token ->
            val isSuggest = navi.suggestLanes.contains(index)
            val isDisable = navi.disableLanes.contains(index)
            val cellColor = when {
                isSuggest -> color(R.color.success_green)
                isDisable -> color(R.color.danger_red)
                else -> color(R.color.text_secondary)
            }
            addLaneCell(laneGlyph(token), cellColor, isSuggest, dp(48))
        }
    }

    private fun addLaneCell(text: String, textColor: Int, bold: Boolean, widthPx: Int) {
        val cell = TextView(this)
        cell.text = text
        cell.textSize = 26f
        cell.gravity = Gravity.CENTER
        cell.setTextColor(textColor)
        cell.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        cell.setBackgroundResource(R.drawable.shape_lane_cell)

        val params = LinearLayout.LayoutParams(widthPx, dp(56))
        params.setMargins(dp(4), 0, dp(4), 0)
        cell.layoutParams = params
        laneWayContainer.addView(cell)
    }

    private fun laneGlyph(token: String): String {
        if (token.isEmpty()) return "•"
        return when (token[0].uppercaseChar()) {
            'L' -> "↰"
            'R' -> "↱"
            'S' -> "↑"
            'X' -> "✕"
            else -> "•"
        }
    }

    private fun renderOvertake(status: OvertakeStatus) {
        tvOvertakeState.text = stateName(status.state)
        tvOvertakeState.setTextColor(stateColor(status.state))

        val causeText = if (status.blockCause != BlockCause.NONE) {
            " [${status.blockCause.name}]"
        } else {
            ""
        }
        tvOvertakeMessage.text = status.message + causeText

        if (lastStatus == null ||
            lastStatus?.state != status.state ||
            lastStatus?.message != status.message
        ) {
            appendTrace("${stateName(status.state)} · ${status.message}")
        }
        lastStatus = status
    }

    private fun renderDevice(device: C3Device?) {
        if (device == null) {
            tvDeviceStatus.text = "C3 未连接"
            tvDeviceStatus.setTextColor(color(R.color.text_muted))
        } else {
            tvDeviceStatus.text = "C3 ${device.ip}"
            tvDeviceStatus.setTextColor(color(R.color.success_green))
        }
        renderLinkHealth(LinkHealthLevel.STABLE)
    }

    /** E4：三档链路健康着色。LOST 红常亮、WEAK 黄、STABLE 绿（不覆盖 deviceFlow 的 IP 文本）。 */
    private fun renderLinkHealth(level: LinkHealthLevel) {
        when (level) {
            LinkHealthLevel.LOST -> {
                tvDeviceStatus.setTextColor(color(R.color.danger_red))
                if (!tvDeviceStatus.text.startsWith("C3 断联")) {
                    tvDeviceStatus.text = "C3 断联！请接管"
                }
            }
            LinkHealthLevel.WEAK -> tvDeviceStatus.setTextColor(color(R.color.warning_amber))
            LinkHealthLevel.STABLE -> {
                tvDeviceStatus.setTextColor(color(R.color.success_green))
                if (tvDeviceStatus.text == "C3 断联！请接管") tvDeviceStatus.text = "C3 "
            }
        }
    }

    /**
     * 侧后盲区/侧向来车提醒（库斯途顶配电子后视镜式，方案 A）。
     * 左侧 = 毫米波 BSD 盲区 OR 激光左后盲区；右侧同理。报警时红字高亮，
     * 无车时灰字「无来车」。声音由 RelayEngine 在状态上升沿触发（不在此处）。
     */
    private fun renderSideReminder(car: CarState) {
        val leftActive = car.leftBlindspot || car.lidarLeftRearBlind
        val rightActive = car.rightBlindspot || car.lidarRightRearBlind
        tvSideLeft.text = if (leftActive) "左侧来车！" else "左侧 无来车"
        tvSideLeft.setTextColor(color(if (leftActive) R.color.danger_red else R.color.text_muted))
        tvSideRight.text = if (rightActive) "右侧来车！" else "右侧 无来车"
        tvSideRight.setTextColor(color(if (rightActive) R.color.danger_red else R.color.text_muted))
    }

    private fun appendTrace(text: String) {
        val time = timeFormat.format(Date())
        traceLines.add("$time  $text")
        while (traceLines.size > 6) {
            traceLines.removeAt(0)
        }
        tvTrace.text = traceLines.joinToString("\n")
    }

    private fun updateOvertakeButton() {
        val enabled = engine.isOvertakeEnabled()
        btnOvertake.text = getString(
            if (enabled) R.string.btn_overtake_on else R.string.btn_overtake_off
        )
        btnOvertake.setTextColor(
            color(if (enabled) R.color.success_green else R.color.text_muted)
        )
    }

    private fun notifyCruiseRequest(targetSpeed: Int) {
        Toast.makeText(this, "已请求巡航 $targetSpeed km/h", Toast.LENGTH_SHORT).show()
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stateName(state: OvertakeState): String {
        return when (state) {
            OvertakeState.IDLE -> "待机"
            OvertakeState.EVALUATING -> "评估前车"
            OvertakeState.SIDE_CHECKING -> "侧向安全检查"
            OvertakeState.PREPARE -> "变道预备"
            OvertakeState.EXECUTING -> "变道执行"
            OvertakeState.FINISHING -> "完成结算"
            OvertakeState.COOLDOWN -> "冷却锁定"
        }
    }

    private fun stateColor(state: OvertakeState): Int {
        return when (state) {
            OvertakeState.IDLE -> color(R.color.text_secondary)
            OvertakeState.EVALUATING -> color(R.color.navi_blue)
            OvertakeState.SIDE_CHECKING -> color(R.color.warning_amber)
            OvertakeState.PREPARE -> color(R.color.warning_amber)
            OvertakeState.EXECUTING -> color(R.color.success_green)
            OvertakeState.FINISHING -> color(R.color.success_green)
            OvertakeState.COOLDOWN -> color(R.color.danger_red)
        }
    }

    private fun lightText(navi: NaviState): String {
        if (navi.trafficLightSeconds < 0) return "无信号灯"
        val status = when (navi.trafficLightStatus) {
            1 -> "红灯"
            2 -> "绿灯"
            3 -> "黄灯"
            else -> "信号灯"
        }
        return "$status ${navi.trafficLightSeconds}s"
    }

    private fun lidarText(distance: Float, blind: Boolean): String {
        val distanceText = if (distance <= 0f) {
            "无目标"
        } else {
            String.format(Locale.US, "%.1fm", distance)
        }
        return if (blind) "$distanceText\n盲区报警" else distanceText
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
