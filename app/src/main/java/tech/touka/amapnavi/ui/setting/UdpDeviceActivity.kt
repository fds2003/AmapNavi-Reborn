package tech.touka.amapnavi.ui.setting

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import tech.touka.amapnavi.R
import tech.touka.amapnavi.data.relay.RelayEngine
import tech.touka.amapnavi.data.udp.C3Device
import java.text.SimpleDateFormat
import java.util.*

/**
 * 局域网 C3 设备管理与手动绑定（纯响应式观察者架构，零套接字生命周期冲突）
 */
class UdpDeviceActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val deviceList = ArrayList<C3Device>()
    private lateinit var adapter: ArrayAdapter<C3Device>

    private lateinit var tvStatus: TextView
    private lateinit var lvDevices: ListView
    private lateinit var btnScan: Button
    private var isScanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_udp_device)

        initViews()
        observeRelayEngine()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_device_scan_status)
        lvDevices = findViewById(R.id.lv_devices)
        btnScan = findViewById(R.id.btn_scan_toggle)

        findViewById<Button>(R.id.btn_device_back).setOnClickListener {
            finish()
        }

        btnScan.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            if (isScanning) {
                isScanning = false
                btnScan.text = "重新扫描"
                btnScan.setBackgroundColor(0xFF1976D2.toInt())
                tvStatus.text = "已暂停探测广播"
            } else {
                isScanning = true
                btnScan.text = "正在查找"
                btnScan.setBackgroundColor(0xFFD32F2F.toInt())
                tvStatus.text = "正在 5510 端口监听局域网 C3 遥测节点..."
                RelayEngine.get(this).rescanDevices()
            }
        }

        adapter = object : ArrayAdapter<C3Device>(this, android.R.layout.simple_list_item_2, android.R.id.text1, deviceList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: android.view.LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)

                if (item != null) {
                    text1?.text = "IP 地址: ${item.ip}   [点击连接]"
                    text1?.setTextColor(Color.WHITE)
                    text1?.textSize = 16f

                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.lastSeenMs))
                    text2?.text = "设备 ID: ${item.deviceId}    最近活跃: $timeStr"
                    text2?.setTextColor(Color.LTGRAY)
                    text2?.textSize = 13f
                }
                return view
            }
        }
        lvDevices.adapter = adapter

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position in deviceList.indices) {
                val dev = deviceList[position]
                RelayEngine.get(this).updateTargetDevice(dev.ip)
                Toast.makeText(this, "已锁定并连接 C3 设备: ${dev.ip}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun observeRelayEngine() {
        val engine = RelayEngine.get(this)
        
        // 1. 若引擎当前已有活跃设备，立即直接加入列表
        engine.deviceFlow.value?.let { currentDev ->
            addOrUpdateDevice(currentDev)
        }

        // 2. 持续收集发现的新设备
        scope.launch {
            engine.deviceFlow.collect { dev ->
                if (isFinishing || isDestroyed) return@collect
                if (dev != null) {
                    addOrUpdateDevice(dev)
                }
            }
        }
    }

    private fun addOrUpdateDevice(dev: C3Device) {
        val idx = deviceList.indexOfFirst { it.ip == dev.ip }
        if (idx == -1) {
            deviceList.add(dev)
        } else {
            deviceList[idx] = dev
        }
        adapter.notifyDataSetChanged()
        tvStatus.text = "已在线发现 ${deviceList.size} 台 C3 设备 (实时通信正常)"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
