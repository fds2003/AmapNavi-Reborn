package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.touka.amapnavi.domain.decision.RampSpeedPlanner

/**
 * 匝道平滑减速曲线测试（纯 JVM）。
 *
 * 契约：v(s) = min(v_cruise, sqrt((v_target/3.6)^2 + 2*a*s) * 3.6)，
 * 500m 起算，目标 60km/h，减速度 1.0m/s^2。
 */
class RampSpeedPlannerTest {

    private val planner = RampSpeedPlanner()

    @Test
    fun `超过500米不减速`() {
        assertEquals(120, planner.calculateCruiseSpeed(600, 120))
        assertEquals(120, planner.calculateCruiseSpeed(5000, 120))
    }

    @Test
    fun `到达路口时降至目标速度`() {
        assertEquals(60, planner.calculateCruiseSpeed(0, 120))
    }

    @Test
    fun `100米处按曲线计算中间速度`() {
        // sqrt((60/3.6)^2 + 2*1*100) * 3.6 ≈ 78.7 => 78
        assertEquals(78, planner.calculateCruiseSpeed(100, 120))
    }

    @Test
    fun `500米处规划速度不得超过巡航`() {
        assertEquals(120, planner.calculateCruiseSpeed(500, 120))
    }

    @Test
    fun `巡航低于目标速度时原样返回`() {
        assertEquals(50, planner.calculateCruiseSpeed(100, 50))
        assertEquals(60, planner.calculateCruiseSpeed(0, 60))
    }

    @Test
    fun `非法距离原样返回巡航`() {
        assertEquals(120, planner.calculateCruiseSpeed(-1, 120))
    }
}
