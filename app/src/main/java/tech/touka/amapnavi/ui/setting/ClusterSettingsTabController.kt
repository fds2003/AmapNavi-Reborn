package tech.touka.amapnavi.ui.setting

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.touka.amapnavi.R
import tech.touka.amapnavi.cluster.ClusterState
import tech.touka.amapnavi.cluster.ClusterStatus
import tech.touka.amapnavi.cluster.ProbeResult
import tech.touka.amapnavi.data.prefs.UserSettingsRepository
import tech.touka.amapnavi.data.relay.RelayEngine

/**
 * “仪表投影”页面控制器。
 *
 * 既可用于 MainActivity 的 ViewStub 覆盖层（onClose 隐藏），
 * 也可用于独立 Activity（onClose 为空时 finish）。
 */
class ClusterSettingsTabController(
    private val activity: AppCompatActivity,
    private val root: View,
    private val repo: UserSettingsRepository,
    private val onClose: (() -> Unit)? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val statusView: TextView = root.findViewById(R.id.tv_cluster_status)

    private val calibrationCodes = listOf(
        65 to "65 直行",
        66 to "66 右前方",
        67 to "67 右转",
        68 to "68 右后方",
        69 to "69 左后方",
        70 to "70 左转",
        71 to "71 左前方",
        72 to "72 掉头",
        96 to "96 环岛",
        97 to "97 环岛出口",
        99 to "99 环岛档位",
        100 to "100 环岛档位",
        102 to "102 环岛档位",
        104 to "104 环岛档位",
        105 to "105 环岛档位",
        107 to "107 环岛档位",
        112 to "112 目的地",
        147 to "147 IC 变体",
        148 to "148 IC 变体",
        149 to "149 IC 变体",
        176 to "176 渡口",
        178 to "178 驶出渡口",
        212 to "212 收费站",
        2 to "2 目的地旗",
        3 to "3 导航结束",
        8 to "8 罗盘"
    )

    init {
        root.findViewById<CheckBox>(R.id.switch_cluster_enable).apply {
            isChecked = repo.getBoolean(UserSettingsRepository.KEY_CLUSTER_ENABLE, false)
            setOnCheckedChangeListener { _, checked ->
                repo.setBoolean(UserSettingsRepository.KEY_CLUSTER_ENABLE, checked)
            }
        }

        bindCheck(R.id.cb_cluster_tbt, UserSettingsRepository.KEY_CLUSTER_TBT_ENABLE, true)
        bindCheck(R.id.cb_cluster_speed, UserSettingsRepository.KEY_CLUSTER_SPEED_ENABLE, true)
        bindCheck(R.id.cb_cluster_est, UserSettingsRepository.KEY_CLUSTER_EST_ENABLE, true)
        bindCheck(R.id.cb_cluster_text, UserSettingsRepository.KEY_CLUSTER_TEXT_ENABLE, true)
        bindCheck(R.id.cb_cluster_tl, UserSettingsRepository.KEY_CLUSTER_TL_ENABLE, true)
        bindCheck(R.id.cb_cluster_icon_experimental, UserSettingsRepository.KEY_CLUSTER_ICON_EXPERIMENTAL, false)
        bindCheck(R.id.cb_cluster_adas, UserSettingsRepository.KEY_CLUSTER_ADA_ENABLE, false)

        val etaGroup = root.findViewById<RadioGroup>(R.id.rg_cluster_eta)
        when (repo.getInt(UserSettingsRepository.KEY_CLUSTER_ETA_MODE, 1)) {
            2 -> root.findViewById<android.widget.RadioButton>(R.id.rb_cluster_eta_duration).isChecked = true
            else -> root.findViewById<android.widget.RadioButton>(R.id.rb_cluster_eta_arrival).isChecked = true
        }
        etaGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_cluster_eta_duration) 2 else 1
            repo.setInt(UserSettingsRepository.KEY_CLUSTER_ETA_MODE, mode)
        }

        root.findViewById<Button>(R.id.btn_cluster_test).setOnClickListener {
            scope.launch {
                withContext(Dispatchers.IO) { RelayEngine.get(activity).sendClusterTestFrame() }
            }
        }

        root.findViewById<Button>(R.id.btn_cluster_adas_test).setOnClickListener {
            scope.launch {
                val sent = withContext(Dispatchers.IO) {
                    RelayEngine.get(activity).sendClusterAdasTestFrame()
                }
                Toast.makeText(
                    activity,
                    if (sent) "ADAS Position 测试帧已发送" else "ADAS 测试帧发送失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val codeSpinner = root.findViewById<Spinner>(R.id.spinner_cluster_code)
        codeSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            calibrationCodes.map { it.second }
        )
        root.findViewById<Button>(R.id.btn_cluster_calibrate).setOnClickListener {
            val index = codeSpinner.selectedItemPosition.coerceIn(0, calibrationCodes.lastIndex)
            val code = calibrationCodes[index].first
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    RelayEngine.get(activity).sendClusterCalibrationFrame(code)
                }
                Toast.makeText(
                    activity,
                    if (ok) "已发送 displayType=$code" else "标定帧发送失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        root.findViewById<Button>(R.id.btn_cluster_probe).setOnClickListener {
            scope.launch {
                val result = withContext(Dispatchers.IO) { RelayEngine.get(activity).probeCluster() }
                statusView.text = probeText(result)
            }
        }

        root.findViewById<Button>(R.id.btn_cluster_back)?.setOnClickListener {
            onClose?.invoke() ?: activity.finish()
        }

        scope.launch {
            RelayEngine.get(activity).clusterStatusFlow.collect { render(it) }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun bindCheck(id: Int, key: String, default: Boolean) {
        root.findViewById<CheckBox>(id).apply {
            isChecked = repo.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> repo.setBoolean(key, checked) }
        }
    }

    private fun render(status: ClusterStatus) {
        val state = when (status.state) {
            ClusterState.RELEASED -> "未接管"
            ClusterState.ACQUIRING -> "接管中"
            ClusterState.ACTIVE -> "已接管"
            ClusterState.RELEASING -> "交还中"
        }
        val error = status.lastError?.let { "\n最近错误：$it" } ?: ""
        statusView.text = "状态：$state" +
            (status.lastTbt.takeIf { it.isNotEmpty() }?.let { "\n最近 TBT：$it" } ?: "") +
            (status.lastText.takeIf { it.isNotEmpty() }?.let { "\n最近文本：$it" } ?: "") +
            error
    }

    private fun probeText(result: ProbeResult): String = when (result) {
        ProbeResult.Ok -> "HSAE 服务正常，可尝试发送测试帧"
        ProbeResult.ServiceMissing -> "未找到 HSAE 服务（非车机环境）"
        ProbeResult.PermissionDenied -> "无权限写入仪表（需要系统应用/平台签名）"
        is ProbeResult.Error -> "服务异常：${result.message}"
    }
}
