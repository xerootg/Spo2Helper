package com.flyfish233.spo2helper

import com.flyfish233.spo2helper.shared.ChannelStats
import com.flyfish233.spo2helper.shared.Spo2Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a raw multi-channel PPG capture into an SpO2 estimate using the
 * classic ratio-of-ratios method:
 *
 *   R    = (AC_red / DC_red) / (AC_ir / DC_ir)
 *   SpO2 = a - b * R          (a = 110, b = 25 when uncalibrated)
 *
 * Pure Kotlin, no Android dependencies, so it is unit-tested on the JVM.
 */
object Spo2Estimator {

    data class Result(
        val channels: List<ChannelStats>,
        val pulseHz: Double?,
        val redChannel: Int?,
        val irChannel: Int?,
        val ratio: Double?,
        val spo2: Double?,
        val sampleRateHz: Double,
        val note: String?,
    )

    private const val BAND_LO_HZ = 0.6
    private const val BAND_HI_HZ = 3.5
    private const val BAND_STEP_HZ = 0.02
    private const val MIN_SNR = 4.0
    private const val MIN_DC_FRACTION = 0.02
    private const val PULSE_TOLERANCE_HZ = 0.15

    /**
     * @param samples one FloatArray per sensor event, all the same length
     * @param timestampsNs event timestamps in nanoseconds
     */
    fun analyze(samples: List<FloatArray>, timestampsNs: LongArray, config: Spo2Config): Result {
        val n = samples.size
        if (n < 32 || timestampsNs.size != n) {
            return Result(emptyList(), null, null, null, null, null, 0.0, "Too few PPG samples ($n)")
        }
        val spanS = (timestampsNs.last() - timestampsNs.first()) / 1e9
        val fs = if (spanS > 0) (n - 1) / spanS else 0.0
        if (fs < 2 * BAND_HI_HZ) {
            return Result(emptyList(), null, null, null, null, null, fs, "PPG sample rate too low (${"%.1f".format(fs)} Hz)")
        }

        val channelCount = samples[0].size
        val raw = List(channelCount) { c -> DoubleArray(n) { i -> samples[i][c].toDouble() } }
        val maxAbsDc = raw.maxOf { abs(it.average()) }.coerceAtLeast(1e-9)

        // Per-channel spectrum in the pulse band.
        val prelim = raw.mapIndexed { c, x ->
            val dc = x.average()
            val ac = detrend(x, fs)
            val (peakHz, peakAmp, snr) = dominantFrequency(ac, fs)
            Prelim(c, dc, 2 * peakAmp, peakHz, snr, abs(dc) >= MIN_DC_FRACTION * maxAbsDc && snr >= MIN_SNR)
        }

        // Shared pulse frequency: median of the good channels.
        val candidates = prelim.filter { it.good }
        val pulseHz = candidates.map { it.hz }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val stats = prelim.map { p ->
            val pulsatile = p.good && pulseHz != null && abs(p.hz - pulseHz) <= PULSE_TOLERANCE_HZ
            ChannelStats(p.index, p.dc, p.ac, p.hz, p.snr, pulsatile)
        }
        val pulsatile = stats.filter { it.pulsatile }

        // Channel assignment: explicit from config, otherwise a heuristic guess.
        val notes = mutableListOf<String>()
        val red: Int?
        val ir: Int?
        if (config.redChannel != Spo2Config.AUTO && config.irChannel != Spo2Config.AUTO) {
            red = config.redChannel.takeIf { it in 0 until channelCount }
            ir = config.irChannel.takeIf { it in 0 until channelCount }
            if (red == null || ir == null) notes += "Configured channel out of range"
        } else {
            val guess = guessRedIr(pulsatile)
            red = guess?.first
            ir = guess?.second
            if (guess == null) notes += "Need at least two pulsatile channels to guess red/IR (found ${pulsatile.size})"
            else notes += "Red/IR channels guessed automatically; verify against a pulse oximeter"
        }

        var ratio: Double? = null
        var spo2: Double? = null
        if (red != null && ir != null) {
            val r = stats[red]
            val i = stats[ir]
            if (r.dc != 0.0 && i.dc != 0.0 && i.ac > 0) {
                ratio = r.perfusion / i.perfusion
                spo2 = (config.a - config.b * ratio).coerceIn(50.0, 100.0)
                if (!r.pulsatile || !i.pulsatile) notes += "Selected channel has a weak pulse; estimate unreliable"
            } else {
                notes += "Selected channels carry no signal"
            }
        }

        return Result(stats, pulseHz, red, ir, ratio, spo2, fs, notes.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    private data class Prelim(val index: Int, val dc: Double, val ac: Double, val hz: Double, val snr: Double, val good: Boolean)

    /**
     * Heuristic when nothing is configured. With three LED colours the perfusion
     * index normally orders red < infrared < green, so after sorting the
     * pulsatile channels by perfusion the lowest third is red and the middle
     * third is infrared; within each third prefer the cleanest signal.
     */
    internal fun guessRedIr(pulsatile: List<ChannelStats>): Pair<Int, Int>? {
        if (pulsatile.size < 2) return null
        val sorted = pulsatile.sortedBy { it.perfusion }
        if (sorted.size < 3) return sorted[0].index to sorted[1].index
        val third = sorted.size / 3.0
        val low = sorted.subList(0, third.roundToInt().coerceAtLeast(1))
        val mid = sorted.subList(low.size, (2 * third).roundToInt().coerceAtLeast(low.size + 1).coerceAtMost(sorted.size))
        val red = low.maxBy { it.snr }.index
        val ir = mid.maxBy { it.snr }.index
        return red to ir
    }

    /** Removes the slow baseline with a one-second centred moving average. */
    internal fun detrend(x: DoubleArray, fs: Double): DoubleArray {
        val half = (fs / 2).roundToInt().coerceAtLeast(1)
        val out = DoubleArray(x.size)
        var sum = 0.0
        var lo = 0
        var hi = -1
        for (i in x.indices) {
            val newLo = (i - half).coerceAtLeast(0)
            val newHi = (i + half).coerceAtMost(x.size - 1)
            while (hi < newHi) { hi++; sum += x[hi] }
            while (lo < newLo) { sum -= x[lo]; lo++ }
            out[i] = x[i] - sum / (hi - lo + 1)
        }
        return out
    }

    /**
     * Scans the pulse band with a plain DFT and returns
     * (frequency of the peak, sinusoid amplitude at the peak, peak power / median power).
     */
    internal fun dominantFrequency(x: DoubleArray, fs: Double): Triple<Double, Double, Double> {
        val n = x.size
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) } // Hann
        val windowGain = window.sum() / n
        var f = BAND_LO_HZ
        val freqs = ArrayList<Double>()
        val powers = ArrayList<Double>()
        while (f <= BAND_HI_HZ) {
            var re = 0.0
            var im = 0.0
            val w = 2 * PI * f / fs
            for (i in 0 until n) {
                val v = x[i] * window[i]
                re += v * cos(w * i)
                im -= v * sin(w * i)
            }
            freqs += f
            powers += re * re + im * im
            f += BAND_STEP_HZ
        }
        val peakIdx = powers.indices.maxBy { powers[it] }
        val peakPower = powers[peakIdx]
        val median = powers.sorted()[powers.size / 2].coerceAtLeast(1e-12)
        val amplitude = 2 * sqrt(peakPower) / (n * windowGain)
        return Triple(freqs[peakIdx], amplitude, peakPower / median)
    }
}
