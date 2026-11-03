package tech.touka.amapnavi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.data.relay.TelemetryWatchdog

/**
 * 遥测心跳看门狗测试（纯 JVM，注入时间）。
 *
 * 契约：断流判定与 OvertakeSettings.telemetryTimeoutMs 同口径（<= 阈值视为新鲜），
 * 同一断流期只触发一次，恢复收包后可再次触发。
 */
class TelemetryWatchdogTest {

    private val timeoutMs = 1500L
    private val watchdog = TelemetryWatchdog(timeoutMs)

    @Test
    fun `尚未建立连接时不报警`() {
        assertFalse(watchdog.shouldTrip(10_000L))
    }

    @Test
    fun `断流未达阈值不报警`() {
        watchdog.onTelemetry(1_000L)
        assertFalse(watchdog.shouldTrip(2_000L))
    }

    @Test
    fun `恰好等于阈值视为新鲜`() {
        watchdog.onTelemetry(1_000L)
        assertFalse(watchdog.shouldTrip(1_000L + timeoutMs))
    }

    @Test
    fun `超过阈值触发且同一断流期只触发一次`() {
        watchdog.onTelemetry(1_000L)
        assertTrue(watchdog.shouldTrip(1_000L + timeoutMs + 1L))
        assertFalse(watchdog.shouldTrip(1_000L + timeoutMs + 600L))
    }

    @Test
    fun `恢复收包后再次断流可再次触发`() {
        watchdog.onTelemetry(1_000L)
        assertTrue(watchdog.shouldTrip(3_000L))
        watchdog.onTelemetry(4_000L)
        assertFalse(watchdog.shouldTrip(5_000L))
        assertTrue(watchdog.shouldTrip(5_600L))
    }

    @Test
    fun `reset 后回到未建立连接状态`() {
        watchdog.onTelemetry(1_000L)
        assertTrue(watchdog.shouldTrip(3_000L))
        watchdog.reset()
        assertFalse(watchdog.shouldTrip(9_000L))
    }
}
