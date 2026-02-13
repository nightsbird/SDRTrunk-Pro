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
package io.github.dsheirer.dsp.filter.nbfm;

/**
 * Audio filtering for NBFM decoder (Vox-Send processing chain)
 * 
 * Processing order (same as Vox-Send):
 * 1. Input Gain - Amplify quiet sources
 * 2. Low-Pass Filter - Remove high hiss/noise
 * 3. 75μs Deemphasis - Correct FM radio pre-emphasis
 * 4. Voice Enhancement - Boost speech clarity
 * 5. Intelligent Squelch - Gate out carrier noise
 */
public class NBFMAudioFilters 
{
    // Input gain
    private float mInputGain = 1.0f;
    
    // Low-pass filter state (2nd order Butterworth)
    private float mLpfX1 = 0, mLpfX2 = 0;
    private float mLpfY1 = 0, mLpfY2 = 0;
    private float mLpfB0, mLpfB1, mLpfB2;
    private float mLpfA1, mLpfA2;
    private double mLpfCutoff;
    
    // De-emphasis filter state (1-pole IIR)
    private float mDeemphasisPrevious = 0.0f;
    private float mDeemphasisAlpha;
    private double mDeemphasisTimeConstant;
    
    // Voice enhancement (presence boost around 2-4 kHz)
    private float mVoiceEnhX1 = 0, mVoiceEnhX2 = 0;
    private float mVoiceEnhY1 = 0, mVoiceEnhY2 = 0;
    private float mVoiceEnhB0, mVoiceEnhB1, mVoiceEnhB2;
    private float mVoiceEnhA1, mVoiceEnhA2;
    private float mVoiceEnhanceAmount = 1.0f; // 0.0 = off, 1.0 = full
    
    // Intelligent squelch (simple Vox-Send style gate)
    private float mSquelchThresholdPercent = 4.0f;  // 0-100% threshold
    private float mSquelchCurrentLevel = 0.0f;      // Current RMS level 0-100%
    private float mSquelchReduction = 0.8f;         // 0.0 to 1.0
    private float mSquelchCurrentGain = 1.0f;
    
    // Hold time - keep gate open after voice stops
    private int mHoldTimeMs = 500;        // milliseconds
    private int mHoldTimeSamples;         // converted to samples
    private int mHoldTimeCounter = 0;     // counts samples since voice stopped
    private boolean mGateOpen = false;    // current gate state
    
    // RMS calculation (running average for smooth level display)
    private float mRmsAlpha = 0.05f;      // Smoothing factor for level display
    private float mRmsSmoothed = 0.0f;    // Smoothed RMS for display
    
    // Attack/release for smooth gating
    private float mSquelchAttackAlpha;
    private float mSquelchReleaseAlpha;
    
    // Enable flags
    private boolean mLowPassEnabled = true;
    private boolean mDeemphasisEnabled = true;
    private boolean mVoiceEnhanceEnabled = true;
    private boolean mSquelchEnabled = false;  // Off by default - existing squelch handles this
    
    private double mSampleRate;
    
    /**
     * Constructor
     * @param sampleRate Audio sample rate (typically 8000 Hz for NBFM)
     */
    public NBFMAudioFilters(double sampleRate) 
    {
        mSampleRate = sampleRate;
        
        // Set defaults (Vox-Send values)
        setInputGain(1.0f);                   // No boost by default
        setLowPassCutoff(3400.0);             // 3400 Hz
        setDeemphasisTimeConstant(75.0);      // 75μs North America
        setVoiceEnhancement(0.3f);            // 30% boost
        setSquelchThreshold(4.0f);            // 4% threshold
        setSquelchReduction(0.8f);            // 80% reduction
        setHoldTime(500);                     // 500ms hold time
        
        mSquelchAttackAlpha = calculateTimeConstant(sampleRate, 10.0f);   // 10ms attack
        mSquelchReleaseAlpha = calculateTimeConstant(sampleRate, 100.0f); // 100ms release
    }
    
    /**
     * Process a single audio sample through the Vox-Send chain
     * Processing order: Low-Pass -> De-emphasis -> Voice Enhancement -> Squelch -> Input Gain (LAST!)
     */
    public float process(float sample) 
    {
        // 1. Low-Pass Filter - Remove high hiss/noise
        if (mLowPassEnabled) {
            sample = processLowPass(sample);
        }
        
        // 2. 75μs Deemphasis - Correct FM radio pre-emphasis
        if (mDeemphasisEnabled) {
            sample = processDeemphasis(sample);
        }
        
        // 3. Voice Enhancement - Boost speech clarity
        if (mVoiceEnhanceEnabled) {
            sample = processVoiceEnhancement(sample);
        }
        
        // 4. Intelligent Squelch - Gate out carrier noise
        if (mSquelchEnabled) {
            sample = processIntelligentSquelch(sample);
        }
        
        // 5. Input Gain LAST - Amplify clean signal only (don't amplify noise!)
        sample *= mInputGain;
        
        return sample;
    }
    
    /**
     * Process audio buffer in-place
     */
    public void process(float[] buffer) 
    {
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = process(buffer[i]);
        }
    }
    
    // ========== 1. INPUT GAIN ==========
    
    /**
     * Set input gain (amplification before processing)
     * @param gain Linear gain value (1.0 = unity, 2.0 = +6dB, etc.)
     */
    public void setInputGain(float gain) 
    {
        mInputGain = Math.max(0.1f, Math.min(10.0f, gain));
    }
    
    public float getInputGain() 
    {
        return mInputGain;
    }
    
    // ========== 2. LOW-PASS FILTER ==========
    
    /**
     * Set low-pass filter cutoff frequency
     * @param cutoffHz Cutoff frequency in Hz (Vox-Send uses 3400 Hz)
     */
    public void setLowPassCutoff(double cutoffHz) 
    {
        mLpfCutoff = cutoffHz;
        
        // Butterworth 2nd order low-pass filter design
        double w0 = 2.0 * Math.PI * cutoffHz / mSampleRate;
        double alpha = Math.sin(w0) / (2.0 * 0.7071);
        
        double b0 = (1.0 - Math.cos(w0)) / 2.0;
        double b1 = 1.0 - Math.cos(w0);
        double b2 = (1.0 - Math.cos(w0)) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * Math.cos(w0);
        double a2 = 1.0 - alpha;
        
        mLpfB0 = (float)(b0 / a0);
        mLpfB1 = (float)(b1 / a0);
        mLpfB2 = (float)(b2 / a0);
        mLpfA1 = (float)(a1 / a0);
        mLpfA2 = (float)(a2 / a0);
    }
    
    public double getLowPassCutoff() 
    {
        return mLpfCutoff;
    }
    
    private float processLowPass(float input) 
    {
        float output = mLpfB0 * input + mLpfB1 * mLpfX1 + mLpfB2 * mLpfX2
                     - mLpfA1 * mLpfY1 - mLpfA2 * mLpfY2;
        
        mLpfX2 = mLpfX1;
        mLpfX1 = input;
        mLpfY2 = mLpfY1;
        mLpfY1 = output;
        
        return output;
    }
    
    // ========== 3. FM DE-EMPHASIS ==========
    
    /**
     * Set de-emphasis time constant
     * @param timeConstantUs Time constant in microseconds (75 for North America, 50 for Europe)
     */
    public void setDeemphasisTimeConstant(double timeConstantUs) 
    {
        mDeemphasisTimeConstant = timeConstantUs;
        double tau = timeConstantUs * 1e-6;
        double dt = 1.0 / mSampleRate;
        mDeemphasisAlpha = (float)(dt / (tau + dt));
    }
    
    public double getDeemphasisTimeConstant() 
    {
        return mDeemphasisTimeConstant;
    }
    
    private float processDeemphasis(float input) 
    {
        float output = mDeemphasisAlpha * input + (1.0f - mDeemphasisAlpha) * mDeemphasisPrevious;
        mDeemphasisPrevious = output;
        return output;
    }
    
    // ========== 4. VOICE ENHANCEMENT ==========
    
    /**
     * Set voice enhancement amount
     * @param amount Enhancement level (0.0 = off, 1.0 = maximum boost)
     */
    public void setVoiceEnhancement(float amount) 
    {
        mVoiceEnhanceAmount = Math.max(0.0f, Math.min(1.0f, amount));
        
        // Design presence boost filter (peaking EQ around 2.8 kHz)
        double centerFreq = 2800.0;  // Center of speech presence
        double Q = 1.5;  // Bandwidth
        double dbGain = 6.0 * mVoiceEnhanceAmount;  // Up to +6dB boost
        
        double w0 = 2.0 * Math.PI * centerFreq / mSampleRate;
        double A = Math.pow(10.0, dbGain / 40.0);
        double alpha = Math.sin(w0) / (2.0 * Q);
        
        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * Math.cos(w0);
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * Math.cos(w0);
        double a2 = 1.0 - alpha / A;
        
        mVoiceEnhB0 = (float)(b0 / a0);
        mVoiceEnhB1 = (float)(b1 / a0);
        mVoiceEnhB2 = (float)(b2 / a0);
        mVoiceEnhA1 = (float)(a1 / a0);
        mVoiceEnhA2 = (float)(a2 / a0);
    }
    
    public float getVoiceEnhancement() 
    {
        return mVoiceEnhanceAmount;
    }
    
    private float processVoiceEnhancement(float input) 
    {
        if (mVoiceEnhanceAmount < 0.01f) {
            return input;  // Bypass if enhancement is off
        }
        
        float output = mVoiceEnhB0 * input + mVoiceEnhB1 * mVoiceEnhX1 + mVoiceEnhB2 * mVoiceEnhX2
                     - mVoiceEnhA1 * mVoiceEnhY1 - mVoiceEnhA2 * mVoiceEnhY2;
        
        mVoiceEnhX2 = mVoiceEnhX1;
        mVoiceEnhX1 = input;
        mVoiceEnhY2 = mVoiceEnhY1;
        mVoiceEnhY1 = output;
        
        return output;
    }
    
    // ========== 4. INTELLIGENT SQUELCH (SIMPLE VOX-SEND STYLE) ==========
    
    /**
     * Set squelch threshold percentage (0-100%)
     * @param percent Threshold percentage (0 = most sensitive, 100 = least sensitive)
     */
    public void setSquelchThreshold(float percent) 
    {
        mSquelchThresholdPercent = Math.max(0.0f, Math.min(100.0f, percent));
    }
    
    /**
     * Get current squelch threshold percentage
     */
    public float getSquelchThreshold()
    {
        return mSquelchThresholdPercent;
    }
    
    /**
     * Get current audio level percentage (0-100%) for display
     */
    public float getCurrentLevel()
    {
        return mSquelchCurrentLevel;
    }
    
    /**
     * Set squelch reduction amount
     * @param reduction Amount to reduce noise (0.0 = no reduction, 1.0 = full mute)
     */
    public void setSquelchReduction(float reduction) 
    {
        mSquelchReduction = Math.max(0.0f, Math.min(1.0f, reduction));
    }
    
    /**
     * Set hold time - how long to keep gate open after voice stops
     * @param timeMs Duration in milliseconds to hold gate open (prevents chopping between words)
     */
    public void setHoldTime(int timeMs)
    {
        mHoldTimeMs = Math.max(0, Math.min(1000, timeMs));
        mHoldTimeSamples = (int)(mSampleRate * mHoldTimeMs / 1000.0);
    }
    
    public int getHoldTime()
    {
        return mHoldTimeMs;
    }
    
    /**
     * Simple Vox-Send style squelch - gate based on RMS level percentage
     */
    private float processIntelligentSquelch(float sample) 
    {
        // Calculate instantaneous RMS (running average for smooth display)
        float sampleEnergy = sample * sample;
        mRmsSmoothed = mRmsAlpha * sampleEnergy + (1.0f - mRmsAlpha) * mRmsSmoothed;
        float rms = (float)Math.sqrt(mRmsSmoothed);
        
        // Convert to percentage (0-100%)
        // Scale RMS to percentage - typical carrier ~0.1-0.3 RMS, voice ~0.3-0.8 RMS
        // Use 1.0 RMS = 100% for better range
        mSquelchCurrentLevel = Math.min(100.0f, rms * 100.0f);
        
        // Simple gate logic: Is current level above threshold?
        boolean voiceDetected = (mSquelchCurrentLevel > mSquelchThresholdPercent);
        
        if (voiceDetected) {
            // Voice detected - open gate immediately
            mGateOpen = true;
            mHoldTimeCounter = 0;  // Reset hold timer
        } else {
            // No voice - apply hold time before closing
            if (mGateOpen) {
                mHoldTimeCounter++;
                
                if (mHoldTimeCounter >= mHoldTimeSamples) {
                    mGateOpen = false;  // Hold time expired - close gate
                    mHoldTimeCounter = 0;
                }
                // Otherwise keep gate open during hold period
            }
        }
        
        // Determine target gain based on gate state
        float targetGain = mGateOpen ? 1.0f : (1.0f - mSquelchReduction);
        
        // Smooth gain changes (attack/release)
        float alpha = (targetGain > mSquelchCurrentGain) ? 
                     mSquelchAttackAlpha : mSquelchReleaseAlpha;
        mSquelchCurrentGain = alpha * mSquelchCurrentGain + 
                             (1.0f - alpha) * targetGain;
        
        return sample * mSquelchCurrentGain;
    }
    
    // ========== ENABLE/DISABLE METHODS ==========
    
    public void setLowPassEnabled(boolean enabled) 
    {
        mLowPassEnabled = enabled;
        if (!enabled) {
            mLpfX1 = mLpfX2 = 0.0f;
            mLpfY1 = mLpfY2 = 0.0f;
        }
    }
    
    public boolean isLowPassEnabled() 
    {
        return mLowPassEnabled;
    }
    
    public void setDeemphasisEnabled(boolean enabled) 
    {
        mDeemphasisEnabled = enabled;
        if (!enabled) {
            mDeemphasisPrevious = 0.0f;
        }
    }
    
    public boolean isDeemphasisEnabled() 
    {
        return mDeemphasisEnabled;
    }
    
    public void setVoiceEnhanceEnabled(boolean enabled) 
    {
        mVoiceEnhanceEnabled = enabled;
        if (!enabled) {
            mVoiceEnhX1 = mVoiceEnhX2 = 0.0f;
            mVoiceEnhY1 = mVoiceEnhY2 = 0.0f;
        }
    }
    
    public boolean isVoiceEnhanceEnabled() 
    {
        return mVoiceEnhanceEnabled;
    }
    
    public void setNoiseGateEnabled(boolean enabled) 
    {
        mSquelchEnabled = enabled;
        if (!enabled) {
            mSquelchCurrentGain = 1.0f;
        }
    }
    
    public boolean isNoiseGateEnabled() 
    {
        return mSquelchEnabled;
    }
    
    // For backward compatibility with UI
    public void setNoiseGateThreshold(float thresholdDb) 
    {
        setSquelchThreshold(thresholdDb);
    }
    
    public void setNoiseGateReduction(float reduction) 
    {
        setSquelchReduction(reduction);
    }
    
    // AGC methods - kept for UI compatibility but map to input gain
    public void setAgcEnabled(boolean enabled) 
    {
        // AGC handled by input gain
    }
    
    public boolean isAgcEnabled() 
    {
        return true;  // Always on via input gain
    }
    
    public void setAgcParameters(float targetLevelDb, float maxGainDb) 
    {
        // Map max gain to input gain
        setInputGain(dbToLinear(maxGainDb / 2.0f));  // Half the max for reasonable default
    }
    
    public float getAGCGainDb() 
    {
        return linearToDb(mInputGain);
    }
    
    /**
     * Reset all filter states
     */
    public void reset() 
    {
        mLpfX1 = mLpfX2 = 0.0f;
        mLpfY1 = mLpfY2 = 0.0f;
        mDeemphasisPrevious = 0.0f;
        mVoiceEnhX1 = mVoiceEnhX2 = 0.0f;
        mVoiceEnhY1 = mVoiceEnhY2 = 0.0f;
        mSquelchCurrentGain = 1.0f;
        mSquelchCurrentLevel = 0.0f;
        mRmsSmoothed = 0.0f;
        mHoldTimeCounter = 0;
        mGateOpen = false;
    }
    
    // ========== UTILITY METHODS ==========
    
    private float calculateTimeConstant(double sampleRate, float timeMs) 
    {
        return (float)Math.exp(-1.0 / (sampleRate * timeMs / 1000.0));
    }
    
    private float dbToLinear(float db) 
    {
        return (float)Math.pow(10.0, db / 20.0);
    }
    
    private float linearToDb(float linear) 
    {
        return 20.0f * (float)Math.log10(Math.max(linear, 0.0001f));
    }
}
