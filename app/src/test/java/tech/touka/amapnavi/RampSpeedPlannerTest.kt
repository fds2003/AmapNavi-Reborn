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

    @Test
    fun `参数注入改变目标速度与起始距离行为`() {
        val custom = RampSpeedPlanner(rampTargetSpeedKmh = 80, decelStartDistanceM = 300)
        // 默认参数（60/500）：500m 边界内开始减速，到 0m 降至 60
        // 自定义参数（80/300）：300m 外不减速，300m 边界内开始减速，到 0m 降至 80
        // 400m：默认参数开始减速；自定义参数 300m 外 → 不减速，原样返回
        assertEquals(120, custom.calculateCruiseSpeed(400, 120))
        // 350m：默认参数内（<500m）开始减速 <120；自定义参数外（>300m）→ 原样 120
        assertEquals(120, custom.calculateCruiseSpeed(350, 120))
        // 0m：降至目标速度 80（默认参数下应为 60，行为不同）
        assertEquals(80, custom.calculateCruiseSpeed(0, 120))
        // 目标速度 80 为下限：巡航 50 < 80 → 原样 50（验证自定义目标速度作为下限的语义）
        assertEquals(50, custom.calculateCruiseSpeed(100, 50))
    }
}
