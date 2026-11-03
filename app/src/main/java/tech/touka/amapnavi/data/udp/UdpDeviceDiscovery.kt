package tech.touka.amapnavi.data.udp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** 已发现的 C3 设备。 */
data class C3Device(
    val deviceId: String,
    val ip: String,
    val lastSeenMs: Long
)

/**
 * 局域网免配置设备发现。
 *
 * 绑定 5510，向 255.255.255.255:4210 周期广播探针，接收 C3 的 c3_pong 响应并锁定 IP。
 */
class UdpDeviceDiscovery(
    private val scope: CoroutineScope,
    private val onDeviceFound: (C3Device) -> Unit,
    private val localPort: Int = PORT_LOCAL,
    private val probePort: Int = PORT_PROBE,
    private val probeIntervalMs: Long = 5000L
) {

    companion object {
        const val PORT_LOCAL = 5510
        const val PORT_PROBE = 4210
    }

    private val _deviceFlow = MutableStateFlow<C3Device?>(null)
    val deviceFlow: StateFlow<C3Device?> = _deviceFlow

    private var discoveryJob: Job? = null

    @Volatile
    private var socket: DatagramSocket? = null

    fun start() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch(Dispatchers.IO) { runDiscoveryLoop() }
    }

    private suspend fun runDiscoveryLoop() {
        val localSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(localPort))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            return
        }
        socket = localSocket
        val probeBytes = "{\"cmd\":\"discover\",\"port\":$localPort}".toByteArray(Charsets.UTF_8)
        val pingBytes = "c3_ping".toByteArray(Charsets.UTF_8)
        val probeTarget = try {
            InetAddress.getByName("255.255.255.255")
        } catch (e: Exception) {
            null
        }
        val buffer = ByteArray(2048)
        var lastProbeMs = 0L
        var lastNotifiedIp: String? = null

        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (probeTarget != null && now - lastProbeMs >= probeIntervalMs) {
                    lastProbeMs = now
                    try {
                        localSocket.send(DatagramPacket(probeBytes, probeBytes.size, probeTarget, probePort))
                        localSocket.send(DatagramPacket(pingBytes, pingBytes.size, probeTarget, probePort))
                        localSocket.send(DatagramPacket(pingBytes, pingBytes.size, probeTarget, localPort))
                    } catch (e: Exception) {
                    }
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localSocket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    // 套接字被关闭或协程取消时平滑跳出循环，绝不向外抛出未捕获异常
                    break
                }

                val device = parseResponse(packet) ?: continue
                _deviceFlow.value = device
                if (device.ip != lastNotifiedIp) {
                    lastNotifiedIp = device.ip
                    onDeviceFound(device)
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

    private fun parseResponse(packet: DatagramPacket): C3Device? {
        val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
        if (payload.isEmpty()) return null
        return try {
            val json = JSONObject(payload)
            val resp = json.optString("resp")
            val status = json.optString("status")
            val device = json.optString("device")
            if (resp != "c3_pong" && status != "ready" && device != "comma3") return null
            if (!json.optBoolean("auth", true)) return null
            val packetSrcIp = packet.address?.hostAddress ?: ""
            val reportedIp = json.optString("ip")
            val ip = if (packetSrcIp.isNotEmpty() && packetSrcIp != "127.0.0.1") {
                packetSrcIp
            } else if (reportedIp.isNotEmpty() && reportedIp != "127.0.0.1") {
                reportedIp
            } else {
                ""
            }
            if (ip.isEmpty()) return null

            C3Device(
                deviceId = json.optString("device_id", "c3"),
                ip = ip,
                lastSeenMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // 关闭异常忽略
        }
        socket = null
    }

    fun restart() {
        stop()
        start()
    }
}
