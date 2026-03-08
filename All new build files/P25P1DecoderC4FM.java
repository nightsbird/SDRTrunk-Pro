/*
 * *****************************************************************************
 * Enhanced P25 Phase 1 C4FM Decoder - Weak Signal Improvements
 * 
 * Key Enhancements:
 * 1. Optimized filter chain (tighter passband/stopband)
 * 2. Enhanced pulse shaping filter
 * 3. Quality monitoring
 * 4. Integration with enhanced demodulator
 * 5. Diagnostic logging
 * 
 * Copyright (C) 2014-2025 Dennis Sheirer
 * Modified by Tim for weak signal performance
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.FIRFilterSpecification;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFactory;
import io.github.dsheirer.dsp.psk.demod.DifferentialDemodulatorFloat;
import io.github.dsheirer.dsp.squelch.PowerMonitor;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.ISourceEventProvider;
import io.github.dsheirer.source.SourceEvent;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced APCO25 Phase 1 C4FM decoder with improved weak signal performance.
 * 
 * Decimates incoming samples to ~4-5 samples per symbol, applies enhanced baseband 
 * and pulse shaping filters, performs PI/4 DQPSK differential demodulation, and 
 * processes with enhanced sync detection and timing correction.
 */
public class P25P1DecoderC4FM_Enhanced extends FeedbackDecoder implements IByteBufferProvider, 
        IComplexSamplesListener, ISourceEventListener, ISourceEventProvider, Listener<ComplexSamples>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25P1DecoderC4FM_Enhanced.class);
    private static final int SYMBOL_RATE = 4800;
    private static final Map<Double,float[]> BASEBAND_FILTERS = new HashMap<>();
    
    // Enhanced filter parameters for weak signals
    private static final double BASEBAND_PASSBAND = 5200;       // Unchanged - matches symbol rate
    private static final double BASEBAND_STOPBAND = 6000;       // Tighter than 6500 original
    private static final double BASEBAND_PASSBAND_RIPPLE = 0.005;  // Reduced from 0.01
    private static final double BASEBAND_STOPBAND_RIPPLE = 0.005;  // Reduced from 0.01
    
    // Enhanced pulse shaping parameters
    private static final int PULSE_SHAPING_SYMBOL_LENGTH = 16;  // Can increase to 20 for weaker signals
    private static final float PULSE_SHAPING_ROLLOFF = 0.2f;    // Standard for C4FM
    
    private final P25P1DemodulatorC4FM_Enhanced mSymbolProcessor;
    private final P25P1MessageFramer_Enhanced mMessageFramer = new P25P1MessageFramer_Enhanced();
    private final P25P1MessageProcessor mMessageProcessor = new P25P1MessageProcessor();
    private final PowerMonitor mPowerMonitor = new PowerMonitor();
    private final C4FMQualityMonitor mQualityMonitor = new C4FMQualityMonitor();
    
    private DifferentialDemodulatorFloat mDemodulator;
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private IRealFilter mBasebandFilterI;
    private IRealFilter mBasebandFilterQ;
    private IRealFilter mPulseShapingFilterI;
    private IRealFilter mPulseShapingFilterQ;
    
    private double mCurrentSampleRate = 0;
    private boolean mEnhancedFiltering = true;
    private int mSymbolLength = PULSE_SHAPING_SYMBOL_LENGTH;
    private float mRolloff = PULSE_SHAPING_ROLLOFF;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    public P25P1DecoderC4FM_Enhanced()
    {
        mMessageProcessor.setMessageListener(getMessageListener());
        mSymbolProcessor = new P25P1DemodulatorC4FM_Enhanced(mMessageFramer, this);
    }

    @Override
    public String getProtocolDescription()
    {
        return "P25 Phase 1 C4FM (Enhanced)";
    }

    /**
     * Sets the sample rate and configures internal decoder components with enhanced filtering.
     */
    public void setSampleRate(double sampleRate)
    {
        if(sampleRate <= SYMBOL_RATE * 2)
        {
            throw new IllegalArgumentException("Sample rate [" + sampleRate + "] must be >9600 (2 * " +
                    SYMBOL_RATE + " symbol rate)");
        }

        mCurrentSampleRate = sampleRate;
        mPowerMonitor.setSampleRate((int)sampleRate);

        int decimation = 1;

        // Identify decimation that gets close to 4.0 Samples Per Symbol (19.2 kHz)
        while((sampleRate / decimation) >= 38400)
        {
            decimation *= 2;
        }

        mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);

        float decimatedSampleRate = (float)sampleRate / decimation;
        
        // Enhanced pulse shaping filter
        float[] pulseTaps = createEnhancedPulseShapingFilter(decimatedSampleRate);
        mPulseShapingFilterI = new RealFIRFilter(pulseTaps);
        mPulseShapingFilterQ = new RealFIRFilter(pulseTaps);

        // Enhanced baseband filter with tighter specifications
        mBasebandFilterI = FilterFactory.getRealFilter(getEnhancedBasebandFilter(decimatedSampleRate));
        mBasebandFilterQ = FilterFactory.getRealFilter(getEnhancedBasebandFilter(decimatedSampleRate));
        
        // SIMD differential demodulator
        mDemodulator = DifferentialDemodulatorFactory.getFloatDemodulator(decimatedSampleRate, SYMBOL_RATE);
        mSymbolProcessor.setSamplesPerSymbol(mDemodulator.getSamplesPerSymbol());
        
        mMessageFramer.setListener(mMessageProcessor);
        mMessageProcessor.setMessageListener(getMessageListener());
        
        LOGGER.info("C4FM Enhanced Decoder initialized: Sample Rate={} Hz, Decimation={}, " +
                   "Decimated Rate={} Hz, Samples/Symbol={}, Pulse Filter Taps={}", 
                   sampleRate, decimation, decimatedSampleRate, 
                   mDemodulator.getSamplesPerSymbol(), pulseTaps.length);
    }
    
    /**
     * Create enhanced pulse shaping filter
     */
    private float[] createEnhancedPulseShapingFilter(float decimatedSampleRate)
    {
        float samplesPerSymbol = decimatedSampleRate / SYMBOL_RATE;
        
        // For weak signals, can use longer filter
        int symbolLength = mEnhancedFiltering ? 20 : PULSE_SHAPING_SYMBOL_LENGTH;
        float rolloff = mEnhancedFiltering ? 0.18f : PULSE_SHAPING_ROLLOFF;
        
        // Store for reference
        mSymbolLength = symbolLength;
        mRolloff = rolloff;
        
        return FilterFactory.getRootRaisedCosine(samplesPerSymbol, symbolLength, rolloff);
    }

    /**
     * Enhanced baseband filter with tighter specifications for better weak signal performance.
     */
    private float[] getEnhancedBasebandFilter(double sampleRate)
    {
        // Check cache first
        if(BASEBAND_FILTERS.containsKey(sampleRate))
        {
            return BASEBAND_FILTERS.get(sampleRate);
        }

        FIRFilterSpecification.Builder builder = FIRFilterSpecification.lowPassBuilder()
                .sampleRate(sampleRate)
                .passBandCutoff(BASEBAND_PASSBAND)
                .passBandAmplitude(1.0)
                .passBandRipple(BASEBAND_PASSBAND_RIPPLE)  // Reduced for flatter passband
                .stopBandAmplitude(0.0)
                .stopBandStart(BASEBAND_STOPBAND)  // Tighter than original 6500
                .stopBandRipple(BASEBAND_STOPBAND_RIPPLE);  // Reduced for better rejection

        FIRFilterSpecification specification = builder.build();
        float[] coefficients = null;

        try
        {
            coefficients = FilterFactory.getTaps(specification);
            BASEBAND_FILTERS.put(sampleRate, coefficients);
            
            LOGGER.debug("Enhanced baseband filter created: {} taps for sample rate {} Hz", 
                        coefficients.length, sampleRate);
        }
        catch(Exception fde)
        {
            LOGGER.error("Error designing enhanced baseband filter for sample rate [" + 
                        sampleRate + "]", fde);
        }

        if(coefficients == null)
        {
            throw new IllegalStateException("Unable to design enhanced baseband filter for sample rate [" + 
                                           sampleRate + "]");
        }

        return coefficients;
    }

    /**
     * Primary method for processing incoming complex sample buffers
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        // Update the message framer with the timestamp
        mMessageFramer.setTimestamp(samples.timestamp());

        // Decimation
        float[] i = mDecimationFilterI.decimateReal(samples.i());
        float[] q = mDecimationFilterQ.decimateReal(samples.q());

        // Process buffer for power measurements
        mPowerMonitor.process(i, q);

        // Baseband filtering - removes adjacent channel interference
        i = mBasebandFilterI.filter(i);
        q = mBasebandFilterQ.filter(q);

        // Pulse shaping - critical for ISI reduction
        i = mPulseShapingFilterI.filter(i);
        q = mPulseShapingFilterQ.filter(q);

        // PI/4 DQPSK differential demodulation
        float[] demodulated = mDemodulator.demodulate(i, q);

        // Process demodulated samples with enhanced symbol processor
        mSymbolProcessor.process(demodulated);
    }
    
    /**
     * Get current decode quality metrics
     */
    public C4FMQualityMonitor getQualityMonitor()
    {
        return mQualityMonitor;
    }
    
    /**
     * Get current SNR estimate from demodulator
     */
    public float getCurrentSNR()
    {
        return mSymbolProcessor.getCurrentSNR();
    }
    
    /**
     * Enable or disable enhanced filtering
     */
    public void setEnhancedFiltering(boolean enabled)
    {
        if(mEnhancedFiltering != enabled)
        {
            mEnhancedFiltering = enabled;
            
            // Re-initialize filters if sample rate is set
            if(mCurrentSampleRate > 0)
            {
                setSampleRate(mCurrentSampleRate);
            }
        }
    }
    
    /**
     * Get enhanced demodulator for advanced features
     */
    public P25P1DemodulatorC4FM_Enhanced getEnhancedDemodulator()
    {
        return mSymbolProcessor;
    }

    @Override
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(listener);
    }

    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(null);
    }

    @Override
    public boolean hasBufferListeners()
    {
        return mSymbolProcessor.hasBufferListener();
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return this::process;
    }

    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        super.setSourceEventListener(listener);
        mPowerMonitor.setSourceEventListener(listener);
    }

    @Override
    public void removeSourceEventListener()
    {
        super.removeSourceEventListener();
        mPowerMonitor.setSourceEventListener(null);
    }

    @Override
    public void start()
    {
        super.start();
        mMessageFramer.start();
        mQualityMonitor.reset();
    }

    @Override
    public void stop()
    {
        super.stop();
        mMessageFramer.stop();
    }

    /**
     * Process source events
     */
    private void process(SourceEvent sourceEvent)
    {
        switch(sourceEvent.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
            case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                mSymbolProcessor.resetPLL();
                mQualityMonitor.recordFrequencyChange();
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                setSampleRate(sourceEvent.getValue().doubleValue());
                break;
        }
    }

    @Override
    public Listener<ComplexSamples> getComplexSamplesListener()
    {
        return this;
    }
    
    /**
     * Quality monitor for C4FM decoder diagnostics
     */
    public static class C4FMQualityMonitor
    {
        private int mTotalSyncs = 0;
        private int mValidNIDs = 0;
        private int mFrequencyChanges = 0;
        private float mAverageEqualizerGain = 1.0f;
        private float mAveragePLLError = 0f;
        private int mSyncsSinceLastLog = 0;
        private long mLastSyncTime = 0;
        
        public void recordSync(boolean validNID, float pllError, float equalizerGain)
        {
            mTotalSyncs++;
            mSyncsSinceLastLog++;
            
            if(validNID)
            {
                mValidNIDs++;
            }
            
            // Update running averages
            mAveragePLLError = (mAveragePLLError * (mTotalSyncs - 1) + Math.abs(pllError)) / mTotalSyncs;
            mAverageEqualizerGain = (mAverageEqualizerGain * (mTotalSyncs - 1) + equalizerGain) / mTotalSyncs;
            
            mLastSyncTime = System.currentTimeMillis();
        }
        
        public void recordFrequencyChange()
        {
            mFrequencyChanges++;
        }
        
        public float getNIDSuccessRate()
        {
            return mTotalSyncs > 0 ? (float)mValidNIDs / mTotalSyncs : 0f;
        }
        
        public String getQualityReport()
        {
            return String.format(
                "C4FM Quality: Syncs=%d | Valid NIDs=%d (%.1f%%) | " +
                "Avg Equalizer Gain=%.3f | Avg PLL Error=%.3f rad | Freq Changes=%d",
                mTotalSyncs,
                mValidNIDs,
                getNIDSuccessRate() * 100f,
                mAverageEqualizerGain,
                mAveragePLLError,
                mFrequencyChanges
            );
        }
        
        public boolean shouldLogQuality()
        {
            if(mSyncsSinceLastLog >= 100)  // Log every 100 syncs
            {
                mSyncsSinceLastLog = 0;
                return true;
            }
            return false;
        }
        
        public void reset()
        {
            mTotalSyncs = 0;
            mValidNIDs = 0;
            mFrequencyChanges = 0;
            mAverageEqualizerGain = 1.0f;
            mAveragePLLError = 0f;
            mSyncsSinceLastLog = 0;
            mLastSyncTime = 0;
        }
    }
}
