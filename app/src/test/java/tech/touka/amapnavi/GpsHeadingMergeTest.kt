package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.touka.amapnavi.core.model.NaviState
import tech.touka.amapnavi.data.gps.withFallbackHeading

/**
 * 航向兜底合并契约：高德航向优先（导航时经地图匹配更准），
 * 高德缺省时用车机 GPS 航向；两者都无效则保持 -1（不进入罗盘模式）。
 */
class GpsHeadingMergeTest {

    @Test
    fun `高德有航向时优先使用高德`() {
        val navi = NaviState(carDirection = 135)
        assertEquals(135, navi.withFallbackHeading(270).carDirection)
    }

    @Test
    fun `高德无航向时回退到GPS`() {
        val navi = NaviState(carDirection = -1)
        assertEquals(90, navi.withFallbackHeading(90).carDirection)
    }

    @Test
    fun `两者都无航向时保持无效`() {
        val navi = NaviState(carDirection = -1)
        assertEquals(-1, navi.withFallbackHeading(-1).carDirection)
    }

    @Test
    fun `GPS航向归一化边界可取`() {
        assertEquals(0, NaviState(carDirection = -1).withFallbackHeading(0).carDirection)
        assertEquals(359, NaviState(carDirection = -1).withFallbackHeading(359).carDirection)
        // 越界值不采信
        assertEquals(-1, NaviState(carDirection = -1).withFallbackHeading(361).carDirection)
    }
}
