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
 * Adaptive IQ Imbalance Corrector using Gram-Schmidt Orthogonalization.
 *
 * Corrects gain and phase imbalance between the I and Q channels caused by
 * imperfections in RTL-SDR and similar tuner hardware. Uncorrected imbalance
 * creates a mirror image of every signal at the negative frequency, manifesting
 * in P25 C4FM/LSM demodulation as constellation distortion and elevated BER.
 *
 * Algorithm — Gram-Schmidt Orthogonalization:
 *
 *   The ideal I channel is used as-is (reference).
 *   The Q channel is corrected in two steps:
 *
 *     Step 1 — Phase correction:
 *       Remove the I-component leaking into Q by subtracting the
 *       cross-correlation projection:
 *           Q' = Q - theta * I
 *
 *     Step 2 — Gain normalization:
 *       Scale Q' so its RMS power matches I:
 *           Q_out = alpha * Q'    where alpha = sqrt(iPower / qPower)
 *
 *   Running estimates of iPower, qPower, and I/Q cross-correlation (iqCorr)
 *   are updated each sample with a leaky integrator (rate MU). Correction
 *   coefficients follow directly:
 *
 *       theta = iqCorr / iPower          (phase correction)
 *       alpha = sqrt(iPower / qPower)    (gain correction, applied after phase)
 *
 * Image Rejection Ratio (IRR):
 *   Estimated from the residual imbalance coefficients using the standard
 *   approximation valid in the high-IRR regime:
 *
 *       IRR_dB = 10 * log10(4 / (epsilon_gain² + epsilon_phase²))
 *
 *   where epsilon_gain = alpha - 1.0 and epsilon_phase = theta.
 *   This gives a meaningful reading while signal is present, unlike
 *   power-ratio methods that require separating signal from mirror.
 *
 * Typical performance:
 *   Uncorrected RTL-SDR: 25–35 dB IRR
 *   After convergence:   50–60 dB IRR
 *
 * Usage:
 *   IQImbalanceCorrector corrector = new IQImbalanceCorrector();
 *   corrector.process(iSamples, qSamples);  // modifies arrays in-place
 *
 * Call reset() when retuning or changing channels to allow re-convergence.
 */
public class IQImbalanceCorrector
{
    /**
     * Leaky integrator rate for power/correlation estimates.
     *
     * Controls tracking speed vs. coefficient smoothness.
     * Convergence time ≈ 1/MU samples.
     *
     *   MU=1e-3 → ~1,000 samples   (fast, strong signals)
     *   MU=1e-4 → ~10,000 samples  (default, general purpose)
     *   MU=5e-5 → ~20,000 samples  (simulcast / weak signals)
     */
    private static final float MU = 1e-4f;

    // Leaky integrator complement
    private static final float ONE_MINUS_MU = 1.0f - MU;

    // Running power/correlation estimates updated each sample
    private float mIPower = 1.0f;    // E[I²]
    private float mQPower = 1.0f;    // E[Q'²]  — measured after phase correction
    private float mIQCorr = 0.0f;    // E[I * Q_raw] — cross-correlation

    // Derived correction coefficients
    private float mTheta = 0.0f;     // phase:  Q' = Q - theta*I
    private float mAlpha = 1.0f;     // gain:   Q_out = alpha * Q'

    // Small floor to prevent divide-by-zero when no signal is present
    private static final float POWER_FLOOR = 1e-20f;

    /**
     * Applies IQ imbalance correction in-place to separate I and Q sample arrays.
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
            float iIn = i[x];
            float qIn = q[x];

            // ------------------------------------------------------------------
            // Update estimates using raw (uncorrected) samples this iteration.
            // We update before applying correction so the estimates reflect the
            // true hardware imbalance, not the already-corrected output.
            // ------------------------------------------------------------------

            // I power — I is the reference channel, never modified
            mIPower = ONE_MINUS_MU * mIPower + MU * (iIn * iIn);
            mIPower = Math.max(mIPower, POWER_FLOOR);

            // I/Q cross-correlation using raw Q — this is what theta minimizes
            mIQCorr = ONE_MINUS_MU * mIQCorr + MU * (iIn * qIn);

            // Derive phase coefficient: how much I leaks into Q
            mTheta = mIQCorr / mIPower;
            mTheta = Math.max(-0.5f, Math.min(0.5f, mTheta));

            // ------------------------------------------------------------------
            // Apply phase correction: remove I-leakage from Q
            // ------------------------------------------------------------------
            float qPhase = qIn - mTheta * iIn;

            // Corrected Q power — updated after phase correction so it tracks
            // the power of the phase-corrected Q channel
            mQPower = ONE_MINUS_MU * mQPower + MU * (qPhase * qPhase);
            mQPower = Math.max(mQPower, POWER_FLOOR);

            // Derive gain coefficient: normalize Q' power to match I power
            mAlpha = (float)Math.sqrt(mIPower / mQPower);
            mAlpha = Math.max(0.5f, Math.min(2.0f, mAlpha));

            // ------------------------------------------------------------------
            // Apply gain correction
            // ------------------------------------------------------------------
            q[x] = mAlpha * qPhase;
            // i[x] unchanged — I is the reference
        }
    }

    /**
     * Returns the estimated image rejection ratio in dB.
     *
     * Uses the standard small-imbalance approximation:
     *   IRR = 10 * log10(4 / (epsilon_gain² + epsilon_phase²))
     *
     * where epsilon_gain = (alpha - 1) and epsilon_phase = theta.
     *
     * Returns 99.9 dB when imbalance is negligibly small.
     *
     * @return estimated image rejection ratio in dB
     */
    public float getImageRejectionDB()
    {
        float eg  = mAlpha - 1.0f;
        float ep  = mTheta;
        float esq = eg * eg + ep * ep;

        if(esq < 1e-10f)
        {
            return 99.9f;
        }

        return 10.0f * (float)Math.log10(4.0f / esq);
    }

    /**
     * Returns the current Q gain correction coefficient (alpha).
     * Converges toward 1.0 for balanced I/Q gain.
     * @return alpha
     */
    public float getAlpha()
    {
        return mAlpha;
    }

    /**
     * Returns the current I/Q phase correction coefficient (theta).
     * Converges toward 0.0 for ideal 90° phase split.
     * @return theta
     */
    public float getTheta()
    {
        return mTheta;
    }

    /**
     * Returns the running I-channel power estimate E[I²].
     * Useful for confirming the corrector is seeing signal (should be >> 1e-20).
     * @return I power estimate
     */
    public float getIPower()
    {
        return mIPower;
    }

    /**
     * Returns the running corrected Q-channel power estimate E[Q'²].
     * At convergence this should closely match getIPower().
     * @return corrected Q power estimate
     */
    public float getQPower()
    {
        return mQPower;
    }

    /**
     * Resets all internal state to initial values.
     * Call when retuning or starting a new channel to allow re-convergence.
     */
    public void reset()
    {
        mIPower = 1.0f;
        mQPower = 1.0f;
        mIQCorr = 0.0f;
        mTheta  = 0.0f;
        mAlpha  = 1.0f;
    }

    /**
     * Returns a diagnostic string showing current correction state.
     * @return formatted diagnostic string
     */
    @Override
    public String toString()
    {
        return String.format(
            "IQImbalanceCorrector[alpha=%.4f theta=%.4f IRR=%.1f dB iPwr=%.2e qPwr=%.2e]",
            mAlpha, mTheta, getImageRejectionDB(), mIPower, mQPower);
    }
}
