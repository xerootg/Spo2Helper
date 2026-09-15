package com.flyfish233.spo2helper

import com.flyfish233.spo2helper.shared.Spo2Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class Spo2EstimatorTest {

    private val fs = 100.0
    private val seconds = 20
    private val pulseHz = 1.2 // 72 bpm

    /**
     * Builds a 16-channel capture shaped like the Pixel Watch 3 sample:
     * 12 live slots, 4 zero slots. Channel roles are chosen so that
     * perfusion orders red < ir < green, with ambient slots carrying only noise.
     */
    private fun synthetic(redPi: Double, irPi: Double, drift: Boolean = true): Pair<List<FloatArray>, LongArray> {
        val n = (fs * seconds).toInt()
        val rnd = Random(42)
        val roles = mapOf(
            // index -> (dc, perfusion) ; null perfusion = ambient/noise only
            0 to (-20515.0 to null), 1 to (-6856.0 to null),
            2 to (379245.0 to redPi), 3 to (440528.0 to redPi * 1.1),
            4 to (-54054.0 to null), 5 to (-19662.0 to null),
            6 to (941196.0 to irPi), 7 to (1092931.0 to irPi * 0.95),
            8 to (8138.0 to null), 9 to (5071.0 to null),
            10 to (646421.0 to 0.06), 11 to (47391.0 to 0.055), // green-like
        )
        val samples = List(n) { i ->
            val t = i / fs
            FloatArray(16) { c ->
                val (dc, pi) = roles[c] ?: return@FloatArray 0f
                val pulse = if (pi != null) dc * pi / 2 * sin(2 * PI * pulseHz * t) else 0.0
                val slow = if (drift) dc * 0.01 * sin(2 * PI * 0.1 * t) else 0.0
                val noise = rnd.nextDouble(-1.0, 1.0) * 0.0005 * kotlin.math.abs(dc)
                (dc + pulse + slow + noise).toFloat()
            }
        }
        val ts = LongArray(n) { (it / fs * 1e9).toLong() }
        return samples to ts
    }

    @Test
    fun recoversKnownRatioWhenChannelsAreConfigured() {
        val (samples, ts) = synthetic(redPi = 0.01, irPi = 0.02)
        val result = Spo2Estimator.analyze(samples, ts, Spo2Config(redChannel = 2, irChannel = 6))
        assertNotNull(result.ratio)
        assertEquals(0.5, result.ratio!!, 0.03)
        assertEquals(97.5, result.spo2!!, 0.8)
        assertEquals(pulseHz, result.pulseHz!!, 0.05)
        assertEquals(fs, result.sampleRateHz, 0.5)
    }

    @Test
    fun autoModePicksRedBelowInfraredAndIgnoresAmbientAndGreen() {
        val (samples, ts) = synthetic(redPi = 0.01, irPi = 0.02)
        val result = Spo2Estimator.analyze(samples, ts, Spo2Config())
        assertTrue("red should be a red-like slot, was ${result.redChannel}", result.redChannel in setOf(2, 3))
        assertTrue("ir should be an ir-like slot, was ${result.irChannel}", result.irChannel in setOf(6, 7))
        assertEquals(97.5, result.spo2!!, 2.0)
        val ambient = result.channels.filter { it.index in setOf(0, 1, 4, 5, 8, 9, 12, 13, 14, 15) }
        assertTrue(ambient.none { it.pulsatile })
        assertTrue(result.channels.filter { it.index in setOf(2, 3, 6, 7, 10, 11) }.all { it.pulsatile })
    }

    @Test
    fun lowerSaturationGivesHigherRatio() {
        val (samples, ts) = synthetic(redPi = 0.02, irPi = 0.02) // R = 1 -> 85 %
        val result = Spo2Estimator.analyze(samples, ts, Spo2Config(redChannel = 2, irChannel = 6))
        assertEquals(1.0, result.ratio!!, 0.05)
        assertEquals(85.0, result.spo2!!, 1.5)
    }

    @Test
    fun calibrationConstantsAreApplied() {
        val (samples, ts) = synthetic(redPi = 0.01, irPi = 0.02)
        val result = Spo2Estimator.analyze(samples, ts, Spo2Config(redChannel = 2, irChannel = 6, a = 104.0, b = 17.0))
        assertEquals(104.0 - 17.0 * 0.5, result.spo2!!, 0.8)
    }

    @Test
    fun tooFewSamplesReportsNote() {
        val result = Spo2Estimator.analyze(List(5) { FloatArray(16) }, LongArray(5) { it * 10_000_000L }, Spo2Config())
        assertNotNull(result.note)
        assertFalse(result.spo2 != null)
    }
}
