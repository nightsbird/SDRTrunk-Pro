package io.github.dsheirer.dsp.fm;

import java.util.Arrays;

/**
 * IF Conditioner for narrowband FM demodulation.
 * Applies optional high-pass, low-pass, and de-emphasis filtering,
 * as well as narrowband noise reduction.
 */
public class IFConditioner
{
    // Toggleable features
    private boolean enableHPF = true;
    private boolean enableLPF = true;
    private boolean enableDeEmphasis = true;
    private boolean enableNoiseReduction = true;

    // HPF state
    private float hpfPrevOut = 0.0f;
    private float hpfPrevIn = 0.0f;

    // LPF state
    private float lpfPrevOut = 0.0f;
    private float alphaLPF = 0.1f; // LPF smoothing factor

    // De-emphasis
    private float deEmpPrev = 0.0f;
    private final float tau = 75e-6f; // 75 microseconds
    private float deEmpAlpha = 0.0f;
    private float sampleRate = 48000.0f; // default, can set dynamically

    // Noise reduction (simple threshold-based)
    private float noiseThreshold = 0.02f;

    /**
     * Constructor
     * @param sampleRate IF sample rate in Hz
     */
    public IFConditioner(float sampleRate)
    {
        this.sampleRate = sampleRate;
        this.deEmpAlpha = (float)Math.exp(-1.0 / (sampleRate * tau));
    }

    // Feature toggles
    public void setHPFEnabled(boolean enabled) { this.enableHPF = enabled; }
    public void setLPFEnabled(boolean enabled) { this.enableLPF = enabled; }
    public void setDeEmphasisEnabled(boolean enabled) { this.enableDeEmphasis = enabled; }
    public void setNoiseReductionEnabled(boolean enabled) { this.enableNoiseReduction = enabled; }

    public void setLPFAlpha(float alpha) { this.alphaLPF = alpha; }
    public void setNoiseThreshold(float threshold) { this.noiseThreshold = threshold; }

    /**
     * Process a single floating-point sample
     * @param input sample
     * @return processed sample
     */
    public float process(float input)
    {
        float sample = input;

        // High-pass filter (1st order)
        if (enableHPF)
        {
            float hpfOut = sample - hpfPrevIn + 0.995f * hpfPrevOut;
            hpfPrevIn = sample;
            hpfPrevOut = hpfOut;
            sample = hpfOut;
        }

        // Low-pass filter (simple 1st order exponential)
        if (enableLPF)
        {
            float lpfOut = alphaLPF * sample + (1.0f - alphaLPF) * lpfPrevOut;
            lpfPrevOut = lpfOut;
            sample = lpfOut;
        }

        // Noise reduction (threshold-based)
        if (enableNoiseReduction)
        {
            if (Math.abs(sample) < noiseThreshold)
            {
                sample = 0.0f;
            }
        }

        // 75us de-emphasis
        if (enableDeEmphasis)
        {
            float deEmpOut = deEmpAlpha * deEmpPrev + (1 - deEmpAlpha) * sample;
            deEmpPrev = deEmpOut;
            sample = deEmpOut;
        }

        return sample;
    }

    /**
     * Process a buffer of samples in-place
     * @param buffer floating point samples
     */
    public void process(float[] buffer)
    {
        for (int i = 0; i < buffer.length; i++)
        {
            buffer[i] = process(buffer[i]);
        }
    }

    /**
     * Reset all filter states
     */
    public void reset()
    {
        hpfPrevOut = 0.0f;
        hpfPrevIn = 0.0f;
        lpfPrevOut = 0.0f;
        deEmpPrev = 0.0f;
    }
}
