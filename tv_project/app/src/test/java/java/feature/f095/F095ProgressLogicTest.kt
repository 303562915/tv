package com.tv.ball.feature.f095

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F095 播放进度逻辑单元测试。
 * 覆盖进度换算边界、续播阈值判定、剩余时长非负。
 */
class F095ProgressLogicTest {

    private val duration = 100_000L

    // ---------- clamp ----------

    @Test
    fun clamp_keepsPositionInsideRange() {
        assertEquals(30_000L, F095ProgressMath.clamp(30_000L, duration))
    }

    @Test
    fun clamp_zeroPositionReturnsZero() {
        assertEquals(0L, F095ProgressMath.clamp(0L, duration))
    }

    @Test
    fun clamp_atDurationReturnsDuration() {
        assertEquals(duration, F095ProgressMath.clamp(duration, duration))
    }

    @Test
    fun clamp_negativePositionReturnsZero() {
        assertEquals(0L, F095ProgressMath.clamp(-500L, duration))
    }

    @Test
    fun clamp_beyondDurationReturnsDuration() {
        assertEquals(duration, F095ProgressMath.clamp(duration + 50_000L, duration))
    }

    @Test
    fun clamp_zeroDurationReturnsZero() {
        assertEquals(0L, F095ProgressMath.clamp(5_000L, 0L))
    }

    @Test
    fun clamp_negativeDurationReturnsZero() {
        assertEquals(0L, F095ProgressMath.clamp(5_000L, -1L))
    }

    // ---------- percent ----------

    @Test
    fun percent_zeroPositionIsZero() {
        assertEquals(0f, F095ProgressMath.percent(0L, duration), 0.0001f)
    }

    @Test
    fun percent_halfPositionIsHalf() {
        assertEquals(0.5f, F095ProgressMath.percent(50_000L, duration), 0.0001f)
    }

    @Test
    fun percent_atDurationIsOne() {
        assertEquals(1f, F095ProgressMath.percent(duration, duration), 0.0001f)
    }

    @Test
    fun percent_beyondDurationClampsToOne() {
        assertEquals(1f, F095ProgressMath.percent(duration * 2L, duration), 0.0001f)
    }

    @Test
    fun percent_negativePositionIsZero() {
        assertEquals(0f, F095ProgressMath.percent(-1_000L, duration), 0.0001f)
    }

    @Test
    fun percent_zeroDurationIsZero() {
        assertEquals(0f, F095ProgressMath.percent(10_000L, 0L), 0.0001f)
    }

    @Test
    fun percent_negativeDurationIsZero() {
        assertEquals(0f, F095ProgressMath.percent(10_000L, -10L), 0.0001f)
    }

    // ---------- shouldResume 阈值 ----------

    @Test
    fun shouldResume_atZeroIsFalse() {
        assertFalse(F095ProgressMath.shouldResume(0L, duration))
    }

    @Test
    fun shouldResume_atDurationIsFalse() {
        assertFalse(F095ProgressMath.shouldResume(duration, duration))
    }

    @Test
    fun shouldResume_atExactly5PercentIsTrue() {
        assertTrue(F095ProgressMath.shouldResume(5_000L, duration))
    }

    @Test
    fun shouldResume_justBelow5PercentIsFalse() {
        assertFalse(F095ProgressMath.shouldResume(4_999L, duration))
    }

    @Test
    fun shouldResume_atExactly95PercentIsTrue() {
        assertTrue(F095ProgressMath.shouldResume(95_000L, duration))
    }

    @Test
    fun shouldResume_justAbove95PercentIsFalse() {
        assertFalse(F095ProgressMath.shouldResume(95_001L, duration))
    }

    @Test
    fun shouldResume_midRangeIsTrue() {
        assertTrue(F095ProgressMath.shouldResume(50_000L, duration))
    }

    @Test
    fun shouldResume_negativePositionIsFalse() {
        assertFalse(F095ProgressMath.shouldResume(-1L, duration))
    }

    @Test
    fun shouldResume_nonPositiveDurationIsFalse() {
        assertFalse(F095ProgressMath.shouldResume(1_000L, 0L))
        assertFalse(F095ProgressMath.shouldResume(1_000L, -100L))
    }

    // ---------- resumeFrom ----------

    @Test
    fun resumeFrom_insideResumeWindowKeepsPosition() {
        assertEquals(42_000L, F095ProgressMath.resumeFrom(42_000L, duration))
    }

    @Test
    fun resumeFrom_below5PercentRestartsFromZero() {
        assertEquals(0L, F095ProgressMath.resumeFrom(1_000L, duration))
    }

    @Test
    fun resumeFrom_above95PercentRestartsFromZero() {
        assertEquals(0L, F095ProgressMath.resumeFrom(99_000L, duration))
    }

    @Test
    fun resumeFrom_positionBeyondDurationNormalizesToZero() {
        assertEquals(0L, F095ProgressMath.resumeFrom(duration + 10_000L, duration))
    }

    @Test
    fun resumeFrom_negativePositionNormalizesToZero() {
        assertEquals(0L, F095ProgressMath.resumeFrom(-10_000L, duration))
    }

    @Test
    fun resumeFrom_nonPositiveDurationReturnsZero() {
        assertEquals(0L, F095ProgressMath.resumeFrom(1_000L, 0L))
    }

    // ---------- estimateRemaining ----------

    @Test
    fun estimateRemaining_midPositionIsHalf() {
        assertEquals(50_000L, F095ProgressMath.estimateRemaining(50_000L, duration))
    }

    @Test
    fun estimateRemaining_atStartIsDuration() {
        assertEquals(duration, F095ProgressMath.estimateRemaining(0L, duration))
    }

    @Test
    fun estimateRemaining_atEndIsZero() {
        assertEquals(0L, F095ProgressMath.estimateRemaining(duration, duration))
    }

    @Test
    fun estimateRemaining_neverNegative() {
        assertTrue(F095ProgressMath.estimateRemaining(duration + 99_999L, duration) >= 0L)
        assertTrue(F095ProgressMath.estimateRemaining(-99_999L, duration) >= 0L)
        assertTrue(F095ProgressMath.estimateRemaining(1_000L, 0L) >= 0L)
        assertTrue(F095ProgressMath.estimateRemaining(1_000L, -1L) >= 0L)
    }
}
