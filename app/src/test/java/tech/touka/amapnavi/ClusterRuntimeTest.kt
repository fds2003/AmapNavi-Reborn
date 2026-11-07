package tech.touka.amapnavi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.cluster.ClusterRuntime
import tech.touka.amapnavi.cluster.ClusterSettings
import tech.touka.amapnavi.cluster.ClusterTransport
import tech.touka.amapnavi.cluster.EstFrame
import tech.touka.amapnavi.cluster.ProbeResult
import tech.touka.amapnavi.cluster.SpeedFrame
import tech.touka.amapnavi.cluster.TbtFrame
import tech.touka.amapnavi.cluster.TextFrame
import tech.touka.amapnavi.core.model.NaviState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ClusterRuntime 契约测试（纯 JVM）。
 *
 * 用真实单线程调度 + 假 Transport，验证：
 * 启动后按 100ms 发送四类通道、导航结束/停止时交还、关闭时零调用。
 */
class ClusterRuntimeTest {

    private class FakeSettings(
        override var enabled: Boolean = true,
        override var tbtEnabled: Boolean = true,
        override var speedEnabled: Boolean = true,
        override var estEnabled: Boolean = true,
        override var textEnabled: Boolean = true,
        override var tlEnabled: Boolean = true,
        override var etaMode: Int = 2
    ) : ClusterSettings

    private class RecordingTransport : ClusterTransport {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val fourChannels = CountDownLatch(4)
        var adasPayload: ByteArray? = null
        var adasType: Int = -1

        override fun sendTbt(frame: TbtFrame): Boolean {
            calls += "tbt:${frame.displayType}:${frame.distVal}:${frame.scale}:${frame.frac}"
            fourChannels.countDown()
            return true
        }

        override fun sendSpeed(frame: SpeedFrame): Boolean {
            calls += "speed:${frame.kph}"
            fourChannels.countDown()
            return true
        }

        override fun sendEst(frame: EstFrame): Boolean {
            calls += "est:${frame.distVal}:${frame.unit}:${frame.hour}:${frame.minute}:${frame.timeType}"
            fourChannels.countDown()
            return true
        }

        override fun sendText(frame: TextFrame): Boolean {
            calls += "text:${frame.text}"
            fourChannels.countDown()
            return true
        }

        override fun sendAdas(payload: ByteArray, type: Int): Boolean {
            adasPayload = payload
            adasType = type
            return true
        }

        override fun probe(): ProbeResult = ProbeResult.Ok
    }

    private fun navi(valid: Boolean = true) = NaviState(
        navValid = valid,
        nextRoadName = "机场高速",
        roadSpeedLimit = 100,
        tbtDistance = 1_500,
        tbtTurnType = 3,
        routeRemainDistance = 12_000,
        routeRemainTime = 900,
        trafficLightSeconds = -1
    )

    @Test
    fun `启动后发送四类通道_停止时执行交还序列`() = runBlocking {
        val transport = RecordingTransport()
        val runtime = ClusterRuntime(transport, FakeSettings())
        val flow = MutableStateFlow(navi())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        runtime.start(scope, flow)
        assertTrue("四类通道未在 2s 内发送", transport.fourChannels.await(2, TimeUnit.SECONDS))
        delay(100)

        runtime.stop()
        scope.cancel()

        val calls = transport.calls.toList()
        assertTrue(calls.any { it.startsWith("tbt:70:") })
        assertTrue(calls.contains("speed:100"))
        assertTrue(calls.any { it.startsWith("est:12:") })
        assertTrue(calls.contains("text:机场高速"))

        // stop 的交还序列
        assertTrue(calls.contains("tbt:3:0:0:0"))
        assertTrue(calls.contains("tbt:8:0:0:0"))
        assertTrue(calls.contains("speed:0"))
        assertTrue(calls.contains("est:0:0:0:0:1"))
        assertTrue(calls.contains("text:"))
    }

    @Test
    fun `总开关关闭时零调用`() = runBlocking {
        val transport = RecordingTransport()
        val runtime = ClusterRuntime(transport, FakeSettings(enabled = false))
        val flow = MutableStateFlow(navi())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        runtime.start(scope, flow)
        delay(350)
        runtime.stop()
        scope.cancel()

        assertTrue("关闭总开关时不应调用 Transport", transport.calls.isEmpty())
    }

    @Test
    fun `导航无效时零调用`() = runBlocking {
        val transport = RecordingTransport()
        val runtime = ClusterRuntime(transport, FakeSettings())
        val flow = MutableStateFlow(navi(valid = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        runtime.start(scope, flow)
        delay(350)
        runtime.stop()
        scope.cancel()

        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `ADAS测试帧使用type458且8字节`() {
        val transport = RecordingTransport()
        val runtime = ClusterRuntime(transport, FakeSettings())

        runtime.sendAdasTestFrame()

        assertEquals(458, transport.adasType)
        assertEquals(8, transport.adasPayload?.size)
    }

    @Test
    fun `标定帧直接发送指定displayType`() {
        val transport = RecordingTransport()
        val runtime = ClusterRuntime(transport, FakeSettings())

        runtime.sendCalibrationFrame(displayType = 96, meters = 1_500)

        assertTrue(transport.calls.contains("tbt:96:1:1:5"))
    }
}
