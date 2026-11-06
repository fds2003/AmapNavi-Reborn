package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.data.udp.CarrotTelemetryParser

/**
 * C3 遥测解析契约：目标侧前角雷达盲区字段。
 *
 * C3 `make_navi_message()` 实际下发 `l_front_blind` / `r_front_blind`
 * （见 amap_navi.py:1834/1836、1961/1963），车机必须解析并进入安全门禁。
 */
class CarrotTelemetryParserTest {

    @Test
    fun `解析左右前角盲区标志`() {
        val car = CarrotTelemetryParser.parse(
            """{"IsOnroad":true,"v_ego_kph":100,"l_front_blind":true,"r_front_blind":false}"""
        )
        assertTrue(car.leftFrontBlind)
        assertFalse(car.rightFrontBlind)
    }

    @Test
    fun `解析目标车道前车`() {
        val car = CarrotTelemetryParser.parse(
            """{"l_lead":true,"l_drel":18,"l_vrel":-4,"r_lead":false,"r_drel":0}"""
        )
        assertTrue(car.leftLead)
        assertEquals(18f, car.leftLeadDistance, 0.01f)
        assertEquals(-4f, car.leftLeadVrel, 0.01f)
        assertFalse(car.rightLead)
    }

    @Test
    fun `字段缺失时前角盲区默认关闭`() {
        val car = CarrotTelemetryParser.parse("""{"IsOnroad":true,"v_ego_kph":80}""")
        assertFalse(car.leftFrontBlind)
        assertFalse(car.rightFrontBlind)
    }
}
