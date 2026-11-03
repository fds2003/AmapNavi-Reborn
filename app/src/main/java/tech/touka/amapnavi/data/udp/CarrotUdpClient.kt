package tech.touka.amapnavi.data.udp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tech.touka.amapnavi.core.event.LaneAction
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * CarrotMan / OpenPilot 高速双向 UDP 通信引擎。
 *
 *  - 本地绑定 7706 接收 C3 下发的约 10Hz 遥测 JSON；
 *  - 向 C3:7705 下发控制指令，index 采用 AtomicInteger 单调自增防丢包序号。
 */
class CarrotUdpClient(
    private val scope: CoroutineScope,
    private val onTelemetryPayload: (String) -> Unit,
    private val receivePort: Int = PORT_TELEMETRY,
    private val sendPort: Int = PORT_CONTROL
) {

    companion object {
        const val PORT_TELEMETRY = 7706 // 车机本地绑定监听端口 (接收 C3 遥测)
        const val PORT_CONTROL = 7705   // 对端 C3 监听端口 (车机下发指令)
    }

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var targetAddress: InetAddress? = null

    private val sequenceId = AtomicInteger(1)
    private var receiveJob: Job? = null

    /** 启动非阻塞接收循环；重复调用幂等。 */
    fun start() {
        if (receiveJob?.isActive == true) return
        receiveJob = scope.launch(Dispatchers.IO) {
            val localSocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(receivePort))
                    soTimeout = 1000
                }
            } catch (e: Exception) {
                return@launch
            }
            socket = localSocket

            val buffer = ByteArray(4096)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        localSocket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        // 外部关闭 Socket 时平滑退出，严禁抛向协程外部
                        break
                    }
                    if (targetAddress == null) {
                        // 未配网时自动学习遥测包源地址，保证免配置直连
                        targetAddress = packet.address
                    }

                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (payload.isNotEmpty()) {
                        try {
                            onTelemetryPayload(payload)
                        } catch (e: Exception) {
                            // 单个畸形包直接丢弃，不中断监听循环
                        }
                    }
                }
            } finally {
                try {
                    localSocket.close()
                } catch (e: Exception) {
                    // 关闭异常忽略
                }
                socket = null
            }
        }
    }

    /** 锁定 C3 目标 IP（设备发现握手成功后调用）。 */
    fun updateTarget(host: String) {
        try {
            targetAddress = InetAddress.getByName(host)
        } catch (e: Exception) {
            // 非法 IP 保持原目标，等待下一次发现
        }
    }

    /**
     * 下发控制指令。cmd 可选 "LANECHANGE" / "SPEED_LIMIT" / "CANCEL_LANE_CHANGE"。
     */
    fun sendControlCommand(cmd: String, action: LaneAction, vCruise: Int, overtakeMode: Int = 1) {
        val seq = sequenceId.getAndIncrement()
        val json = JSONObject().apply {
            put("cmd", cmd)
            if (cmd == "SPEED_LIMIT") {
                // 限速是数值指令：arg 必须是十进制速度值，绝不能复用车道码（否则 C3 int("KEEP") 抛异常）
                put("direction", "KEEP")
                put("arg", vCruise)
            } else {
                put("direction", action.code)
                put("arg", action.code)
            }
            put("param", overtakeMode)
            put("seq", seq)
            put("index", seq)
            put("v_cruise", vCruise)
            put("blinker", action.blinker)
            put("timestamp", System.currentTimeMillis())
        }
        val payload = json.toString().toByteArray(Charsets.UTF_8)

        scope.launch(Dispatchers.IO) {
            val address = targetAddress ?: return@launch
            val localSocket = socket ?: return@launch
            val repeatCount = if (cmd.contains("CANCEL")) 3 else 1
            for (i in 0 until repeatCount) {
                try {
                    localSocket.send(DatagramPacket(payload, payload.size, address, sendPort))
                } catch (e: Exception) {
                    // 网络抖动重试
                }
                if (repeatCount > 1 && i < repeatCount - 1) {
                    kotlinx.coroutines.delay(20)
                }
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // 关闭异常忽略
        }
        socket = null
    }
}
