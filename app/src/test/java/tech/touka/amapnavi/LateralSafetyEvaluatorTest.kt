package tech.touka.amapnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.touka.amapnavi.core.event.BlockCause
import tech.touka.amapnavi.core.event.LaneAction
import tech.touka.amapnavi.core.model.CarState
import tech.touka.amapnavi.domain.safety.LateralSafetyEvaluator

/**
 * 侧向安全熔断测试（纯 JVM）。
 *
 * 验证优先级：实线标线 > BSD 盲区 > 激光盲区标志 > 后向 TTC > 动态制动净空。
 */
class LateralSafetyEvaluatorTest {

    private val evaluator = LateralSafetyEvaluator()

    private fun car(
        vEgo: Int = 100,
        leftFrontBlind: Boolean = false,
        rightFrontBlind: Boolean = false,
        leftLead: Boolean = false,
        leftLeadDistance: Float = 0f,
        rightLead: Boolean = false,
        rightLeadDistance: Float = 0f,
        leftBlindspot: Boolean = false,
        rightBlindspot: Boolean = false,
        lidarLeftRearBlind: Boolean = false,
        lidarRightRearBlind: Boolean = false,
        lidarBehindVdistTime: Float = 99f,
        leftRearDistance: Float = 999f,
        rightRearDistance: Float = 999f
    ): CarState = CarState(
        vEgo = vEgo,
        leftFrontBlind = leftFrontBlind,
        rightFrontBlind = rightFrontBlind,
        leftLead = leftLead,
        leftLeadDistance = leftLeadDistance,
        rightLead = rightLead,
        rightLeadDistance = rightLeadDistance,
        leftBlindspot = leftBlindspot,
        rightBlindspot = rightBlindspot,
        lidarLeftRearBlind = lidarLeftRearBlind,
        lidarRightRearBlind = lidarRightRearBlind,
        lidarBehindVdistTime = lidarBehindVdistTime,
        leftRearDistance = leftRearDistance,
        rightRearDistance = rightRearDistance
    )

    @Test
    fun `实线绝对阻断_即使传感器全部净空`() {
        val result = evaluator.evaluateSide(LaneAction.LEFT, car(), isTargetSolid = true)
        assertFalse(result.isSafe)
        assertEquals(BlockCause.SOLID_LINE, result.blockCause)
        assertTrue(result.isSolidBlocked)
    }

    @Test
    fun `目标侧BSD报警阻断`() {
        val left = evaluator.evaluateSide(
            LaneAction.LEFT, car(leftBlindspot = true), isTargetSolid = false
        )
        assertFalse(left.isSafe)
        assertEquals(BlockCause.BSD_ALARM, left.blockCause)
        assertTrue(left.isBsdBlocked)

        val right = evaluator.evaluateSide(
            LaneAction.RIGHT, car(rightBlindspot = true), isTargetSolid = false
        )
        assertFalse(right.isSafe)
        assertEquals(BlockCause.BSD_ALARM, right.blockCause)
        assertTrue(right.isBsdBlocked)
    }

    @Test
    fun `激光雷达盲区标志阻断`() {
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(lidarLeftRearBlind = true), isTargetSolid = false
        )
        assertFalse(result.isSafe)
        assertEquals(BlockCause.LIDAR_HAZARD, result.blockCause)
        assertTrue(result.isLidarBlocked)
    }

    @Test
    fun `高速时动态净空不足阻断`() {
        // vEgo=100km/h => 要求净空 max(10, 100/3.6*2) ≈ 55.6m
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(vEgo = 100, leftRearDistance = 30f), isTargetSolid = false
        )
        assertFalse(result.isSafe)
        assertEquals(BlockCause.LIDAR_HAZARD, result.blockCause)
        assertTrue(result.isLidarBlocked)
    }

    @Test
    fun `高速时净空充足放行`() {
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(vEgo = 100, leftRearDistance = 80f), isTargetSolid = false
        )
        assertTrue(result.isSafe)
        assertEquals(BlockCause.NONE, result.blockCause)
    }

    @Test
    fun `低速时固定10米净空放行`() {
        val result = evaluator.evaluateSide(
            LaneAction.RIGHT, car(vEgo = 0, rightRearDistance = 12f), isTargetSolid = false
        )
        assertTrue(result.isSafe)
    }

    @Test
    fun `低速时低于10米固定净空阻断`() {
        val result = evaluator.evaluateSide(
            LaneAction.RIGHT, car(vEgo = 0, rightRearDistance = 8f), isTargetSolid = false
        )
        assertFalse(result.isSafe)
        assertTrue(result.isLidarBlocked)
    }

    @Test
    fun `KEEP动作不参与评估且视为不安全`() {
        val result = evaluator.evaluateSide(LaneAction.KEEP, car(), isTargetSolid = false)
        assertFalse(result.isSafe)
        assertEquals(BlockCause.NO_TARGET_LANE, result.blockCause)
    }

    @Test
    fun `后向TTC失效时_即使距离充足也阻断`() {
        // C3 语义：0.0 表示无有效回波，必须 fail-closed
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(lidarBehindVdistTime = 0f, leftRearDistance = 80f), isTargetSolid = false
        )
        assertFalse(result.isSafe)
        assertEquals(BlockCause.LIDAR_HAZARD, result.blockCause)
        assertTrue(result.isLidarBlocked)
    }

    @Test
    fun `后向TTC低于门限时阻断`() {
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(lidarBehindVdistTime = 1.5f, leftRearDistance = 80f), isTargetSolid = false
        )
        assertFalse(result.isSafe)
        assertTrue(result.isLidarBlocked)
    }

    @Test
    fun `后向TTC充足时按距离门限放行`() {
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(lidarBehindVdistTime = 5f, leftRearDistance = 80f), isTargetSolid = false
        )
        assertTrue(result.isSafe)
    }

    @Test
    fun `目标侧前角雷达盲区阻断`() {
        val left = evaluator.evaluateSide(
            LaneAction.LEFT, car(leftFrontBlind = true), isTargetSolid = false
        )
        assertFalse(left.isSafe)
        assertEquals(BlockCause.FRONT_BLIND, left.blockCause)

        val right = evaluator.evaluateSide(
            LaneAction.RIGHT, car(rightFrontBlind = true), isTargetSolid = false
        )
        assertFalse(right.isSafe)
        assertEquals(BlockCause.FRONT_BLIND, right.blockCause)
    }

    @Test
    fun `前角盲区优先级高于实线`() {
        // 原版 APK LaneChangeSafetyCheckRaw：先判 l/r_front_blind(-2)，再判实线(-1)
        val result = evaluator.evaluateSide(
            LaneAction.LEFT, car(leftFrontBlind = true), isTargetSolid = true
        )
        assertFalse(result.isSafe)
        assertEquals(BlockCause.FRONT_BLIND, result.blockCause)
    }

    @Test
    fun `目标车道前车过近阻断`() {
        val left = evaluator.evaluateSide(
            LaneAction.LEFT,
            car(leftLead = true, leftLeadDistance = 10f),
            isTargetSolid = false
        )
        assertFalse(left.isSafe)
        assertEquals(BlockCause.TARGET_LANE_OCCUPIED, left.blockCause)

        val right = evaluator.evaluateSide(
            LaneAction.RIGHT,
            car(rightLead = true, rightLeadDistance = 15f),
            isTargetSolid = false
        )
        assertFalse(right.isSafe)
        assertEquals(BlockCause.TARGET_LANE_OCCUPIED, right.blockCause)
    }

    @Test
    fun `目标车道有车但距离缺失时阻断`() {
        val result = evaluator.evaluateSide(
            LaneAction.LEFT,
            car(leftLead = true, leftLeadDistance = 0f),
            isTargetSolid = false
        )
        assertFalse(result.isSafe)
        assertEquals(BlockCause.TARGET_LANE_OCCUPIED, result.blockCause)
    }

    @Test
    fun `目标车道前车净空足够时放行`() {
        val result = evaluator.evaluateSide(
            LaneAction.LEFT,
            car(leftLead = true, leftLeadDistance = 60f),
            isTargetSolid = false
        )
        assertTrue(result.isSafe)
    }

    @Test
    fun `非目标侧前车不影响判定`() {
        // 左侧有车，但目标是向右变道 -> 不应阻断
        val result = evaluator.evaluateSide(
            LaneAction.RIGHT,
            car(leftLead = true, leftLeadDistance = 5f),
            isTargetSolid = false
        )
        assertTrue(result.isSafe)
    }
}
