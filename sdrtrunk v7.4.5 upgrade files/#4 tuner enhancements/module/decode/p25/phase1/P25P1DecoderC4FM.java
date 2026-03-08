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

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.alias.AliasList;
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
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.dmr.message.DMRBurst;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessageWithLinkControl;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.FullLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ShortLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferProvider;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.IComplexSamplesListener;
import io.github.dsheirer.sample.complex.IQImbalanceCorrector;
import io.github.dsheirer.sample.complex.NoiseBlanker;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.ISourceEventProvider;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APCO25 Phase 1 decoder.  Decimates incoming sample buffers to as close as possible to 25 kHz for ~5 samples per
 * symbol.  Employs baseband and pulse shaping filters.  Demodulates the complex baseband (I/Q) sample stream using
 * SIMD differential demodulation of the entire sample stream.  The C4FM demodulator (mSymbolProcessor) performs sync
 * detection, timing correction and PLL signal mistune correction.  Message framer performs message framing and message
 * creation.  A registered message listener receives the detected and framed messages.
 *
 * As a child of the FeedbackDecoder, this decoder provides periodic PLL measurements to the tuner for automatic PPM
 * correction.  It also provides a stream of demodulated soft symbols (in radians) for display to the user.
 *
 * IQ Imbalance Correction and Noise Blanking:
 * An adaptive LMS IQ imbalance corrector runs at the top of the receive pipeline, before decimation
 * and filtering, correcting gain and phase mismatches inherent in RTL-SDR hardware. Immediately after,
 * an adaptive noise blanker detects and zeros short high-amplitude impulse spikes (USB noise, switching
 * supplies, inter-dongle interference) before the decimation filters can smear them downstream.
 * Both components reset on frequency change events to allow rapid re-convergence on the new channel.
 * Diagnostic logging of both components is available at DEBUG level.
 */
public class P25P1DecoderC4FM extends FeedbackDecoder implements IByteBufferProvider, IComplexSamplesListener,
        ISourceEventListener, ISourceEventProvider, Listener<ComplexSamples>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25P1DecoderC4FM.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##");
    private static final int SYMBOL_RATE = 4800;
    private static final Map<Double,float[]> BASEBAND_FILTERS = new HashMap<>();

    // How often to log IQ corrector diagnostics (every N sample buffers). Set to 0 to disable.
    private static final int IQ_DIAGNOSTIC_LOG_INTERVAL = 1000;
    private int mIQDiagnosticCounter = 0;

    // -------------------------------------------------------------------------
    // Idle/stuck watchdog — detects RSP1B (or any tuner) locking up and
    // delivering a frozen/blanked signal without recovering on its own.
    //
    // Detection: at each diagnostic interval we check all three conditions:
    //   1. NoiseBlanker blankedRatio > WATCHDOG_BLANK_THRESHOLD  (near 100% blanking)
    //   2. NoiseBlanker avgPower has not changed by more than WATCHDOG_POWER_DELTA_RATIO
    //      since the last check  (frozen power estimate = dead signal)
    //   3. Zero valid P25 messages decoded since the last check
    //
    // If all three conditions persist for WATCHDOG_SOFT_RESET_INTERVALS consecutive
    // intervals, a Stage 1 soft reset is performed (reset IQ corrector, noise blanker,
    // and PLL).  If conditions persist for WATCHDOG_HARD_RESET_INTERVALS additional
    // intervals after that, a Stage 2 hard reset is performed (full decoder reinit
    // via setSampleRate(), which also re-creates all filters and decimators).
    // -------------------------------------------------------------------------
    private static final float WATCHDOG_BLANK_THRESHOLD      = 0.95f;  // 95% blanking = stuck
    private static final float WATCHDOG_POWER_DELTA_RATIO    = 0.10f;  // <10% change = frozen
    private static final int   WATCHDOG_SOFT_RESET_INTERVALS = 3;      // ~3 min at default log rate (non-LSM)
    private static final int   WATCHDOG_HARD_RESET_INTERVALS = 2;      // ~2 min after soft reset (non-LSM)
    private static final int   WATCHDOG_LSM_SOFT_RESET_INTERVALS = 8;  // ~8 min — LSM nulls need time to recover
    private static final int   WATCHDOG_LSM_HARD_RESET_INTERVALS = 4;  // ~4 min after soft reset (LSM)

    /**
     * True if this decoder is operating on an LSM (Linear Simulcast Modulation) system.
     * LSM simulcast nulls can cause extended sync loss and zero-message periods that are
     * not indicative of a stuck tuner, so the watchdog uses longer intervals before acting.
     */
    private final boolean mIsLSM;

    /** Soft reset interval threshold for this channel — longer for LSM to avoid false positives */
    private final int mWatchdogSoftResetThreshold;

    /** Hard reset interval threshold for this channel — longer for LSM */
    private final int mWatchdogHardResetThreshold;

    /** Current sample rate — stored so the hard reset can call setSampleRate() */
    private double mCurrentSampleRate = 0.0;

    /** Consecutive diagnostic intervals where all stuck conditions have been met */
    private int mWatchdogStuckIntervals = 0;

    /** Whether we have already attempted a Stage 1 soft reset this stuck episode */
    private boolean mWatchdogSoftResetDone = false;

    /** avgPower reading from the previous diagnostic interval — used to detect frozen power */
    private float mWatchdogLastAvgPower = 0.0f;

    /** Valid P25 messages decoded since the last watchdog check — reset each interval */
    private volatile int mWatchdogMessageCount = 0;

    private final P25P1DemodulatorC4FM mSymbolProcessor;
    private final P25P1MessageFramer mMessageFramer = new P25P1MessageFramer();
    private final P25P1MessageProcessor mMessageProcessor = new P25P1MessageProcessor();

    /**
     * Sets the allowed NACs for filtering at the message framer level.
     * @param allowedNACs set of allowed NAC values, or null to accept all
     */
    public void setAllowedNACs(java.util.Set<Integer> allowedNACs)
    {
        mMessageFramer.setAllowedNACs(allowedNACs);
    }

    private final PowerMonitor mPowerMonitor = new PowerMonitor();

    /**
     * Adaptive IQ imbalance corrector.
     * One instance per decoder — corrects the per-channel I/Q path mismatches introduced
     * by the RTL-SDR tuner hardware before any decimation or demodulation occurs.
     * Reset on frequency/correction change events to allow rapid re-convergence.
     */
    private final IQImbalanceCorrector mIQImbalanceCorrector = new IQImbalanceCorrector();

    /**
     * Adaptive noise blanker.
     * Detects and zeros short high-amplitude impulse spikes (USB noise, switching
     * supplies, inter-dongle interference) before they reach the decimation filters
     * and demodulator. Runs after IQ correction at full channelized sample rate
     * where impulses are sharpest and most reliably detected.
     */
    private final NoiseBlanker mNoiseBlanker = new NoiseBlanker();

    private DifferentialDemodulatorFloat mDemodulator;
    private IRealDecimationFilter mDecimationFilterI;
    private IRealDecimationFilter mDecimationFilterQ;
    private IRealFilter mBasebandFilterI;
    private IRealFilter mBasebandFilterQ;
    private IRealFilter mPulseShapingFilterI;
    private IRealFilter mPulseShapingFilterQ;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    public P25P1DecoderC4FM()
    {
        this(false);
    }

    public P25P1DecoderC4FM(boolean isLSM)
    {
        mIsLSM = isLSM;
        mWatchdogSoftResetThreshold = isLSM ? WATCHDOG_LSM_SOFT_RESET_INTERVALS : WATCHDOG_SOFT_RESET_INTERVALS;
        mWatchdogHardResetThreshold = isLSM ? WATCHDOG_LSM_HARD_RESET_INTERVALS : WATCHDOG_HARD_RESET_INTERVALS;

        // Wrap the message listener so every dispatched message increments the watchdog counter.
        // The watchdog uses this to distinguish "no signal" from a stuck blanker/tuner state.
        mMessageProcessor.setMessageListener(message -> {
            if(message.isValid())
            {
                notifyValidMessage();
            }
            Listener<IMessage> downstream = getMessageListener();
            if(downstream != null)
            {
                downstream.receive(message);
            }
        });
        mSymbolProcessor = new P25P1DemodulatorC4FM(mMessageFramer, this);
    }

    @Override
    public String getProtocolDescription()
    {
        return "P25 Phase 1 C4FM";
    }

    /**
     * Sets the sample rate and configures internal decoder components.
     * @param sampleRate of the channel to decode
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

        //Identify decimation that gets us as close to 4.0 Samples Per Symbol as possible (19.2 kHz)
        while((sampleRate / decimation) >= 38400)
        {
            decimation *= 2;
        }

        mDecimationFilterI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        mDecimationFilterQ = DecimationFilterFactory.getRealDecimationFilter(decimation);

        float decimatedSampleRate = (float)sampleRate / decimation;
        int symbolLength = 16;
        float rrcAlpha = 0.2f;

        float[] taps = FilterFactory.getRootRaisedCosine(decimatedSampleRate / SYMBOL_RATE,
                symbolLength, rrcAlpha);
        mPulseShapingFilterI = new RealFIRFilter(taps);
        mPulseShapingFilterQ = new RealFIRFilter(taps);

        mBasebandFilterI = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mBasebandFilterQ = FilterFactory.getRealFilter(getBasebandFilter(decimatedSampleRate));
        mDemodulator = DifferentialDemodulatorFactory.getFloatDemodulator(decimatedSampleRate, SYMBOL_RATE);
        mSymbolProcessor.setSamplesPerSymbol(mDemodulator.getSamplesPerSymbol());
        mMessageFramer.setListener(mMessageProcessor);
        mMessageProcessor.setMessageListener(getMessageListener());

        // Reset the IQ corrector when sample rate changes so it re-converges cleanly
        mIQImbalanceCorrector.reset();
        mNoiseBlanker.reset();
    }

    /**
     * Primary method for processing incoming complex sample buffers.
     *
     * Pipeline order:
     *   1. IQ imbalance correction  (corrects hardware I/Q path mismatches — before everything else)
     *   2. Decimation               (reduces sample rate toward target ~19.2 kHz)
     *   3. Power monitoring         (measures channel power on decimated samples)
     *   4. Baseband filter          (low-pass, removes out-of-band energy)
     *   5. Pulse shaping filter     (RRC matched filter)
     *   6. Differential demodulation
     *   7. Symbol processing / message framing
     *
     * @param samples containing channelized complex samples
     */
    @Override
    public void receive(ComplexSamples samples)
    {
        // Update the message framer with the timestamp from the incoming sample buffer.
        mMessageFramer.setTimestamp(samples.timestamp());

        // Step 1: IQ imbalance correction — applied first, before decimation, so all downstream
        // processing benefits from the corrected samples. Modifies I and Q arrays in-place.
        samples.correct(mIQImbalanceCorrector);

        // Step 2: Noise blanking — detect and zero impulse spikes before decimation filters
        // can smear them across multiple samples. Operates on corrected full-rate samples.
        mNoiseBlanker.process(samples.i(), samples.q());

        // Periodically log corrector and blanker diagnostics at DEBUG level
        if(LOGGER.isDebugEnabled() && IQ_DIAGNOSTIC_LOG_INTERVAL > 0)
        {
            if(++mIQDiagnosticCounter >= IQ_DIAGNOSTIC_LOG_INTERVAL)
            {
                mIQDiagnosticCounter = 0;
                LOGGER.debug("IQ Correction: {}", mIQImbalanceCorrector);
                LOGGER.debug("Noise Blanker: {}", mNoiseBlanker);
                checkWatchdog();
            }
        }

        // Step 3: Decimation
        float[] i = mDecimationFilterI.decimateReal(samples.i());
        float[] q = mDecimationFilterQ.decimateReal(samples.q());

        // Step 4: Channel power measurement
        mPowerMonitor.process(i, q);

        // Step 5: Baseband low-pass filter
        i = mBasebandFilterI.filter(i);
        q = mBasebandFilterQ.filter(q);

        // Step 6: Pulse shaping (RRC matched filter)
        i = mPulseShapingFilterI.filter(i);
        q = mPulseShapingFilterQ.filter(q);

        // Step 7: PI/4 DQPSK differential demodulation
        float[] demodulated = mDemodulator.demodulate(i, q);

        // Step 8: Process demodulated samples into symbols and apply message sync detection and framing.
        mSymbolProcessor.process(demodulated);
    }

    /**
     * Called whenever a valid P25 message is decoded.
     * Increments the watchdog message counter so the idle watchdog can distinguish
     * genuine signal loss from a stuck tuner/noise-blanker state.
     */
    public void notifyValidMessage()
    {
        mWatchdogMessageCount++;
    }

    /**
     * Idle/stuck watchdog — called at each diagnostic log interval.
     *
     * Evaluates three conditions that together indicate the tuner or noise blanker has
     * entered a stuck state (most commonly seen with the RSP1B under AGC instability):
     *   1. Nearly all samples are being blanked  (blankedRatio > 95%)
     *   2. The noise blanker avgPower estimate is frozen  (< 10% change since last check)
     *   3. No valid P25 messages decoded since the last check
     *
     * Stage 1 (soft reset): after WATCHDOG_SOFT_RESET_INTERVALS consecutive stuck intervals,
     *   resets noise blanker, IQ corrector, and PLL.
     * Stage 2 (hard reset): if still stuck WATCHDOG_HARD_RESET_INTERVALS intervals after
     *   Stage 1, performs a full decoder reinit via setSampleRate().
     */
    private void checkWatchdog()
    {
        float currentAvgPower = mNoiseBlanker.getAveragePower();
        float blankRatio      = mNoiseBlanker.getBlankedRatio();
        int   msgCount        = mWatchdogMessageCount;
        mWatchdogMessageCount = 0;

        // Fractional change in avgPower since last interval — frozen = stuck tuner
        float powerDelta = (mWatchdogLastAvgPower > 0.0f)
            ? Math.abs(currentAvgPower - mWatchdogLastAvgPower) / mWatchdogLastAvgPower
            : 1.0f;   // first call: treat as "not stuck"
        mWatchdogLastAvgPower = currentAvgPower;

        boolean blankStuck  = blankRatio  > WATCHDOG_BLANK_THRESHOLD;
        boolean powerFrozen = powerDelta  < WATCHDOG_POWER_DELTA_RATIO;
        boolean noMessages  = msgCount   == 0;

        if(blankStuck && powerFrozen && noMessages)
        {
            mWatchdogStuckIntervals++;
            int threshold = mWatchdogSoftResetDone ? mWatchdogHardResetThreshold : mWatchdogSoftResetThreshold;
            LOGGER.debug("Watchdog{}: stuck [{}/{}] blanked={} powerDelta={}% msgs=0",
                mIsLSM ? "(LSM)" : "",
                mWatchdogStuckIntervals, threshold,
                mNoiseBlanker.getBlankedPercent(),
                String.format("%.1f", powerDelta * 100.0f));

            if(!mWatchdogSoftResetDone && mWatchdogStuckIntervals >= mWatchdogSoftResetThreshold)
            {
                LOGGER.warn("Watchdog{} Stage 1: soft reset after {} stuck intervals — resetting noise blanker, IQ corrector, PLL (blanked={} avgPwr={})",
                    mIsLSM ? "(LSM)" : "",
                    mWatchdogStuckIntervals,
                    mNoiseBlanker.getBlankedPercent(),
                    String.format("%.2e", currentAvgPower));
                mNoiseBlanker.reset();
                mIQImbalanceCorrector.reset();
                mSymbolProcessor.resetPLL();
                mWatchdogSoftResetDone  = true;
                mWatchdogStuckIntervals = 0;
            }
            else if(mWatchdogSoftResetDone && mWatchdogStuckIntervals >= mWatchdogHardResetThreshold)
            {
                LOGGER.warn("Watchdog{} Stage 2: hard reset after soft reset failed — reinitialising full decoder pipeline (blanked={} avgPwr={} sampleRate={})",
                    mIsLSM ? "(LSM)" : "",
                    mNoiseBlanker.getBlankedPercent(),
                    String.format("%.2e", currentAvgPower),
                    mCurrentSampleRate);
                if(mCurrentSampleRate > 0.0)
                {
                    setSampleRate(mCurrentSampleRate);
                }
                mWatchdogSoftResetDone  = false;
                mWatchdogStuckIntervals = 0;
            }
        }
        else
        {
            if(mWatchdogStuckIntervals > 0)
            {
                LOGGER.debug("Watchdog{}: cleared after {} stuck intervals (blanked={} powerDelta={}% msgs={})",
                    mIsLSM ? "(LSM)" : "",
                    mWatchdogStuckIntervals,
                    mNoiseBlanker.getBlankedPercent(),
                    String.format("%.1f", powerDelta * 100.0f),
                    msgCount);
                mWatchdogStuckIntervals = 0;
                mWatchdogSoftResetDone  = false;
            }
        }
    }

    /**
     * Constructs a baseband filter for this decoder using the current sample rate
     */
    private float[] getBasebandFilter(double sampleRate)
    {
        if(BASEBAND_FILTERS.containsKey(sampleRate))
        {
            return BASEBAND_FILTERS.get(sampleRate);
        }

        FIRFilterSpecification specification = FIRFilterSpecification
                .lowPassBuilder()
                .sampleRate(sampleRate)
                .passBandCutoff(5200)
                .passBandAmplitude(1.0).passBandRipple(0.01) //.01
                .stopBandAmplitude(0.0).stopBandStart(7200) //Was 6500; widened transition band reduces filter order/ringing on noise
                .stopBandRipple(0.01).build();

        float[] coefficients = null;

        try
        {
            coefficients = FilterFactory.getTaps(specification);
            BASEBAND_FILTERS.put(sampleRate, coefficients);
        }
        catch(Exception fde) //FilterDesignException
        {
            System.out.println("Error");
        }

        if(coefficients == null)
        {
            throw new IllegalStateException("Unable to design low pass filter for sample rate [" + sampleRate + "]");
        }

        return coefficients;
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void setBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(listener);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
    @Override
    public void removeBufferListener(Listener<ByteBuffer> listener)
    {
        mSymbolProcessor.setBufferListener(null);
    }

    /**
     * Implements the IByteBufferProvider interface - delegates to the symbol processor
     */
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

    /**
     * Sets the source event listener to receive source events from this decoder.
     */
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
                // Reset IQ corrector and noise blanker on retune — new frequency means new
                // imbalance and noise characteristics; let both re-converge cleanly.
                mIQImbalanceCorrector.reset();
                mNoiseBlanker.reset();
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

    public static void main(String[] args)
    {
        LOGGER.info("Starting ...");

//        String directory = "D:\\DQPSK Equalizer Research - P25\\"; //Windows
        String directory = "/media/denny/T9/DQPSK Equalizer Research - P25/"; //Linux

        String file = directory + "P25-S1-Conventional-repeater-20241115_212221_469325000_QPS_Digital_Kynoch_Kynoch_Digital_59_baseband.wav";
//        String file = directory + "P25-S3-C4FM-20241225_040459_152517500_NYSEG_Onondaga_Control_30_baseband.wav";

        boolean autoReplay = false;

        P25P1DecoderC4FM decoder = new P25P1DecoderC4FM();
        decoder.start();

        UserPreferences userPreferences = new UserPreferences();
        P25P1AudioModule audio1 = new P25P1AudioModule(userPreferences, new AliasList(""));

        decoder.setMessageListener(new Listener<>()
        {
            private long mBitCounter = 1;
            private int mBitErrorCounter;
            private int mValidMessageCounter;
            private int mTotalMessageCounter;

            @Override
            public void receive(IMessage iMessage)
            {
                int errors = 0;

                audio1.receive(iMessage);

                if(iMessage instanceof P25P1Message message)
                {
                    mBitCounter += 288;

                    if(message.getMessage() != null)
                    {
                        errors = message.getMessage().getCorrectedBitCount();
                    }

                    mTotalMessageCounter++;

                    if(mTotalMessageCounter == 492)
                    {
                        int a = 0;
                    }
                    if(message.isValid())
                    {
                        mBitErrorCounter += Math.max(errors, 0);
                        mValidMessageCounter++;
                    }
                }
                else if(iMessage instanceof LCMessage lcw)
                {
                    mTotalMessageCounter++;
                    errors = lcw.getMessage().getCorrectedBitCount();
                    if(lcw.isValid())
                    {
                        mBitErrorCounter += errors;
                        mValidMessageCounter++;
                    }
                }

                double bitErrorRate = (double)mBitErrorCounter / (double)mBitCounter * 100.0;

                boolean logEverything = true;
                boolean logFLC = true;
                boolean logSLC = true;
                boolean logCACH = true;
                boolean logIdles = true;

                if(logEverything)
                {
                    if(iMessage.toString().contains("PLACEHOLDER"))
                    {
                        int a = 0;
                    }
                    System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                }
                else
                {
                    if (logFLC)
                    {
                        if(iMessage instanceof FullLCMessage)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                        else if(iMessage instanceof Terminator terminator)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                        else if(iMessage instanceof DataMessageWithLinkControl)
                        {
                            System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                        }
                    }

                    if(logCACH && iMessage instanceof DMRBurst burst && burst.hasCACH())
                    {
                        System.out.println("CACH:" + burst.getCACH());
                    }

                    if(logSLC && iMessage instanceof ShortLCMessage)
                    {
                        System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                    }

                    if(logIdles && iMessage instanceof SyncLossMessage)
                    {
                        System.out.println(">>MESSAGE: TS" + iMessage.getTimeslot() + " " + iMessage + " \t\t[" + errors + " | " + mBitErrorCounter + " | Valid:" + mValidMessageCounter + " Total:" + mTotalMessageCounter + " Msgs] Rate [" + DECIMAL_FORMAT.format(bitErrorRate) + " %]");
                    }
                }
            }
        });

        try(ComplexWaveSource source = new ComplexWaveSource(new File(file), autoReplay))
        {
            source.setListener(iNativeBuffer -> {
                Iterator<ComplexSamples> it = iNativeBuffer.iterator();

                while(it.hasNext())
                {
                    decoder.receive(it.next());
                }
            });
            source.start();
            decoder.setSampleRate(source.getSampleRate());

            while(true)
            {
                source.next(2048, true);
            }
        }
        catch(IOException ioe)
        {
            LOGGER.error("Error", ioe);
        }

        LOGGER.info("Finished");
    }
}
