package io.github.dsheirer.dsp.fm;

import org.apache.commons.math3.util.FastMath;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.Properties;

/**
 * Scalar FM Demodulator with IF conditioning:
 * - Low-pass filter (12 kHz typical NBFM)
 * - De-Emphasis (75µs)
 * - Noise Reduction
 * - Retains existing High-Pass filter
 */
public class ScalarFMDemodulator implements IDemodulator
{
    protected float mPreviousI = 0.0f;
    protected float mPreviousQ = 0.0f;
    protected float mGain;

    // --- IF Conditioner toggles ---
    protected boolean hpfEnabled = true;
    protected boolean lpfEnabled = true;
    protected boolean deEmphasisEnabled = true;
    protected boolean noiseReductionEnabled = true;

    // --- LPF & De-emphasis state ---
    private float lpfPrev = 0.0f;
    private float deEmphPrev = 0.0f;
    private final float deEmphAlpha;
    private final float sampleRate;

    /**
     * Default constructor with gain 1.0 and default 30 kHz sample rate
     */
    public ScalarFMDemodulator()
    {
        this(1.0f, 30000f);
    }

    /**
     * Constructor with gain and sample rate
     * @param gain Gain factor
     * @param sampleRate Audio sample rate (Hz)
     */
    public ScalarFMDemodulator(float gain, float sampleRate)
    {
        this.mGain = gain;
        this.sampleRate = sampleRate;
        float dt = 1.0f / sampleRate;
        float tau = 75e-6f; // 75 µs de-emphasis
        deEmphAlpha = dt / (tau + dt);
    }

    public float[] demodulate(float[] i, float[] q)
    {
        float[] demodulated = new float[i.length];
        float demodI, demodQ;

        // First sample
        demodI = (i[0] * mPreviousI) - (q[0] * -mPreviousQ);
        demodQ = (q[0] * mPreviousI) + (i[0] * -mPreviousQ);
        demodulated[0] = (demodI != 0) ? (float)FastMath.atan(demodQ / demodI) : (float)FastMath.atan(demodQ / Float.MIN_VALUE);

        mPreviousI = i[i.length - 1];
        mPreviousQ = q[q.length - 1];

        // Remaining samples
        for(int x = 1; x < i.length; x++)
        {
            demodI = (i[x] * i[x - 1]) - (q[x] * -q[x - 1]);
            demodQ = (q[x] * i[x - 1]) + (i[x] * -q[x - 1]);
            demodulated[x] = (demodI != 0) ? (float)FastMath.atan(demodQ / demodI) : (float)FastMath.atan(demodQ / Float.MIN_VALUE);
        }

        // --- IF Conditioning ---
        for(int x = 0; x < demodulated.length; x++)
        {
            float sample = demodulated[x];

            // HPF (existing)
            if(hpfEnabled) { /* leave existing HPF in SDRTrunk */ }

            // LPF: first-order IIR with 12 kHz cutoff
            if(lpfEnabled)
            {
                float fCut = 12000f; // 12 kHz typical NBFM audio
                float lpfAlpha = (float)(2 * Math.PI * fCut / (2 * Math.PI * fCut + sampleRate));
                sample = lpfAlpha * sample + (1 - lpfAlpha) * lpfPrev;
                lpfPrev = sample;
            }

            // 75 µs De-Emphasis
            if(deEmphasisEnabled)
            {
                sample = deEmphAlpha * sample + (1 - deEmphAlpha) * deEmphPrev;
                deEmphPrev = sample;
            }

            // Simple Noise Reduction
            if(noiseReductionEnabled)
            {
                float threshold = 0.02f; // adjustable noise floor
                if(FastMath.abs(sample) < threshold) sample = 0.0f;
            }

            demodulated[x] = sample;
        }

        return demodulated;
    }

    public void dispose() { /* no-op */ }

    public void reset()
    {
        mPreviousI = 0.0f;
        mPreviousQ = 0.0f;
        lpfPrev = 0.0f;
        deEmphPrev = 0.0f;
    }

    public void setGain(float gain) { mGain = gain; }

    // --- IF Conditioner toggles ---
    public void setHPFEnabled(boolean enabled) { hpfEnabled = enabled; }
    public void
