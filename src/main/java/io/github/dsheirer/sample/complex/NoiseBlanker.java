/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.sample.complex;

/**
 * Adaptive Noise Blanker for IQ sample streams.
 *
 * Detects and suppresses short, high-amplitude impulse noise spikes that would
 * otherwise corrupt multiple symbols in the downstream P25 demodulator. Impulse
 * sources include switching power supplies, USB bus noise, inter-dongle
 * interference in multi-SDR setups, and electrical equipment interference.
 *
 * Operation:
 *   Each sample's instantaneous power P = I² + Q² is compared against a
 *   running average of recent signal power. If P exceeds the average by more
 *   than THRESHOLD_DB decibels, the sample is classified as an impulse and
 *   zeroed out. The running average is updated using only non-blanked samples
 *   so that the threshold tracks the true signal level without being biased
 *   upward by the impulses themselves.
 *
 * Warm-up phase:
 *   On startup and after reset(), the blanker enters a warm-up phase lasting
 *   WARMUP_SAMPLES samples. During warm-up, ALL samples pass through unblanked
 *   and are used to build an accurate initial power estimate. This prevents the
 *   bootstrap problem where a near-zero initial estimate causes every real
 *   signal sample to look like an impulse, resulting in 100% blanking.
 *
 * Pipeline position:
 *   Apply AFTER IQ imbalance correction, BEFORE decimation. At full sample
 *   rate, impulses are single-sample spikes and are easy to detect cleanly.
 *   After decimation the impulse energy is smeared across multiple samples
 *   by the decimation filter, making detection less reliable.
 *
 *   Recommended order:
 *     1. IQImbalanceCorrector.process()
 *     2. NoiseBlanker.process()           ← here
 *     3. Decimation
 *     4. Baseband filter
 *     5. Pulse shaping
 *     6. Demodulation
 *
 * Threshold tuning (THRESHOLD_DB):
 *   Lower = more aggressive blanking (catches smaller spikes).
 *   Higher = more conservative (only catches obvious large spikes).
 *
 *   Recommended starting points:
 *     Clean RF environment:           15–20 dB
 *     Typical USB/switching noise:    12 dB  (default)
 *     Dense urban / severe EMI:        8–10 dB
 *
 *   If getBlankedRatio() stays above ~0.05 (5%) in normal operation,
 *   the threshold may be too low, or EMI is genuinely severe.
 *
 * Hang time (HANG_SAMPLES):
 *   After an impulse trigger, blanking continues for HANG_SAMPLES additional
 *   samples to suppress filter ringing that would follow the spike downstream
 *   even if the raw spike is only one sample wide.
 */
public class NoiseBlanker
{
    /**
     * Impulse detection threshold in dB above the running average signal power.
     * Samples exceeding (averagePower * 10^(THRESHOLD_DB/10)) are blanked.
     */
    private static final float THRESHOLD_DB = 12.0f;

    /**
     * Threshold as a linear power ratio. Pre-computed to avoid per-sample math.
     * threshold_linear = 10^(THRESHOLD_DB/10)
     */
    private static final float THRESHOLD_LINEAR = (float)Math.pow(10.0, THRESHOLD_DB / 10.0);

    /**
     * Number of additional samples to blank after an impulse trigger.
     * Suppresses filter ringing following each spike.
     * At ~50 ksps channelized sample rate, 8 samples ≈ 160 µs per event.
     */
    private static final int HANG_SAMPLES = 8;

    /**
     * Number of samples to observe on startup/reset before enabling blanking.
     * During warm-up all samples pass through and are used to build an accurate
     * initial average power estimate. This prevents the bootstrap problem where
     * a near-zero initial estimate causes 100% blanking of real signal.
     *
     * At 1e-3 averaging rate, convergence is ~95% complete after 3000 samples.
     * 4000 samples gives a comfortable margin before blanking is activated.
     */
    private static final int WARMUP_SAMPLES = 4000;

    /**
     * Leaky integrator rate for the running average power estimate.
     * 1e-3 → ~1000-sample averaging time constant.
     */
    private static final float AVERAGE_MU = 1e-3f;
    private static final float ONE_MINUS_AVERAGE_MU = 1.0f - AVERAGE_MU;

    /** Leaky integrator rate for the blanked-ratio diagnostic. */
    private static final float DIAG_MU = 1e-3f;
    private static final float ONE_MINUS_DIAG_MU = 1.0f - DIAG_MU;

    /** Floor to prevent threshold collapse when no signal is present. */
    private static final float POWER_FLOOR = 1e-20f;

    // Running average of signal power — updated only on non-blanked samples
    private float mAveragePower = POWER_FLOOR;

    // Warm-up counter — blanking is suppressed until this reaches zero
    private int mWarmupRemaining = WARMUP_SAMPLES;

    // Hang counter: samples remaining to blank after an impulse trigger
    private int mHangCounter = 0;

    // Diagnostic: EMA fraction of samples blanked
    private float mBlankedRatio = 0.0f;

    // Diagnostic: total impulse events detected
    private long mImpulseCount = 0;

    /**
     * Applies noise blanking in-place to separate I and Q sample arrays.
     * Samples identified as impulses are zeroed on both channels.
     * Both arrays must be the same length.
     *
     * @param i I-channel sample array (modified in-place)
     * @param q Q-channel sample array (modified in-place)
     * @throws IllegalArgumentException if arrays are null or different lengths
     */
    public void process(float[] i, float[] q)
    {
        if(i == null || q == null)
        {
            throw new IllegalArgumentException("IQ sample arrays must not be null");
        }
        if(i.length != q.length)
        {
            throw new IllegalArgumentException("I and Q arrays must be the same length [i=" +
                    i.length + " q=" + q.length + "]");
        }

        for(int x = 0; x < i.length; x++)
        {
            float iSample = i[x];
            float qSample = q[x];
            float power   = iSample * iSample + qSample * qSample;

            // ------------------------------------------------------------------
            // Warm-up phase: pass all samples through and build power estimate.
            // No blanking until we have a reliable average to compare against.
            // ------------------------------------------------------------------
            if(mWarmupRemaining > 0)
            {
                mWarmupRemaining--;
                mAveragePower = ONE_MINUS_AVERAGE_MU * mAveragePower + AVERAGE_MU * power;
                mAveragePower = Math.max(mAveragePower, POWER_FLOOR);
                // Sample passes through unchanged — update diagnostic as "not blanked"
                mBlankedRatio = ONE_MINUS_DIAG_MU * mBlankedRatio;
                continue;
            }

            // ------------------------------------------------------------------
            // Normal operation: compare instantaneous power to threshold
            // ------------------------------------------------------------------
            float threshold = mAveragePower * THRESHOLD_LINEAR;
            boolean blank;

            if(mHangCounter > 0)
            {
                // Within hang window of a previous impulse — keep blanking
                blank = true;
                mHangCounter--;
            }
            else if(power > threshold)
            {
                // New impulse detected
                blank = true;
                mHangCounter = HANG_SAMPLES;
                mImpulseCount++;
            }
            else
            {
                blank = false;
            }

            if(blank)
            {
                i[x] = 0.0f;
                q[x] = 0.0f;
                // Do NOT update average power with blanked samples — keeps
                // the threshold anchored to the true signal level
                mBlankedRatio = ONE_MINUS_DIAG_MU * mBlankedRatio + DIAG_MU;
            }
            else
            {
                mAveragePower = ONE_MINUS_AVERAGE_MU * mAveragePower + AVERAGE_MU * power;
                mAveragePower = Math.max(mAveragePower, POWER_FLOOR);
                mBlankedRatio = ONE_MINUS_DIAG_MU * mBlankedRatio;
            }
        }
    }

    /**
     * Returns true if the blanker is still in the warm-up phase and blanking
     * has not yet been activated. Useful for diagnostic logging.
     * @return true if warming up
     */
    public boolean isWarmingUp()
    {
        return mWarmupRemaining > 0;
    }

    /**
     * Returns the exponential moving average fraction of samples blanked (0.0–1.0).
     *
     * Interpretation:
     *   < 0.001   — very clean RF environment
     *   0.001–0.01 — occasional impulses, normal for USB/switching supplies
     *   0.01–0.05  — moderate impulse noise
     *   > 0.05    — heavy impulse noise; consider ferrite chokes or USB isolator
     *
     * @return blanked sample fraction
     */
    public float getBlankedRatio()
    {
        return mBlankedRatio;
    }

    /**
     * Returns the blanked ratio as a percentage string for convenient logging.
     * @return e.g. "0.42%"
     */
    public String getBlankedPercent()
    {
        return String.format("%.2f%%", mBlankedRatio * 100.0f);
    }

    /**
     * Returns total impulse events detected since last reset.
     * @return impulse event count
     */
    public long getImpulseCount()
    {
        return mImpulseCount;
    }

    /**
     * Returns the current running average signal power estimate.
     * @return average power
     */
    public float getAveragePower()
    {
        return mAveragePower;
    }

    /**
     * Returns the current blanking threshold (absolute power level).
     * @return threshold power level
     */
    public float getThreshold()
    {
        return mAveragePower * THRESHOLD_LINEAR;
    }

    /**
     * Resets all internal state including the warm-up counter.
     * Call when retuning or starting a new channel.
     */
    public void reset()
    {
        mAveragePower    = POWER_FLOOR;
        mWarmupRemaining = WARMUP_SAMPLES;
        mHangCounter     = 0;
        mBlankedRatio    = 0.0f;
        mImpulseCount    = 0;
    }

    /**
     * Returns a diagnostic string showing current blanker state.
     * @return formatted diagnostic string
     */
    @Override
    public String toString()
    {
        return String.format(
            "NoiseBlanker[warmup=%s blanked=%s impulses=%d avgPwr=%.2e threshold=%.2e]",
            mWarmupRemaining > 0 ? mWarmupRemaining + " remaining" : "done",
            getBlankedPercent(), mImpulseCount, mAveragePower, getThreshold());
    }
}
