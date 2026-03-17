/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast.mumble;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages a Vosk speech-to-text model and per-transmission recognizer.
 *
 * Audio arrives as 8 kHz float samples. Vosk needs 16-bit signed PCM
 * little-endian at the same sample rate. We convert on the fly.
 *
 * Usage:
 *   VoskTranscriber t = new VoskTranscriber("/path/to/model");
 *   t.startTransmission();
 *   t.acceptAudio(floatBuffer);   // called repeatedly
 *   String result = t.stopTransmission();  // returns final transcript
 *
 * The model path should point to a small Vosk model directory, e.g.
 * vosk-model-small-en-us-0.15. Download from https://alphacephei.com/vosk/models
 */
public class VoskTranscriber
{
    private static final Logger mLog = LoggerFactory.getLogger(VoskTranscriber.class);

    // Vosk works best at 16kHz but also supports 8kHz models
    // We use 8kHz to avoid resampling since that's what SDRTrunk provides
    private static final float VOSK_SAMPLE_RATE = 8000.0f;

    private final String mModelPath;
    private Model mModel;
    private Recognizer mRecognizer;
    private boolean mModelLoaded = false;
    private boolean mTransmissionActive = false;

    // Accumulate PCM bytes for the current transmission
    private final List<byte[]> mPcmChunks = new ArrayList<>();

    /**
     * Constructs a VoskTranscriber.
     *
     * @param modelPath path to the Vosk model directory
     *                  e.g. "C:/vosk-models/vosk-model-small-en-us-0.15"
     */
    public VoskTranscriber(String modelPath)
    {
        mModelPath = modelPath;
    }

    /**
     * Loads the Vosk model. Call once at startup. This is slow (1-5 seconds)
     * so it should be called on a background thread.
     *
     * @return true if model loaded successfully
     */
    public boolean loadModel()
    {
        if(mModelLoaded) return true;

        if(mModelPath == null || mModelPath.isBlank())
        {
            mLog.warn("Vosk model path not configured — STT disabled");
            return false;
        }

        try
        {
            mLog.info("Loading Vosk model from: {}", mModelPath);
            mModel = new Model(mModelPath);
            mModelLoaded = true;
            mLog.info("Vosk model loaded successfully");
            return true;
        }
        catch(Exception e)
        {
            mLog.error("Failed to load Vosk model from '{}': {}", mModelPath, e.getMessage());
            mModelLoaded = false;
            return false;
        }
    }

    /**
     * Returns true if the model is loaded and ready.
     */
    public boolean isReady()
    {
        return mModelLoaded && mModel != null;
    }

    /**
     * Call at the start of each transmission to open a fresh recognizer.
     */
    public void startTransmission()
    {
        if(!isReady()) return;

        closeRecognizer();
        mPcmChunks.clear();

        try
        {
            mRecognizer = new Recognizer(mModel, VOSK_SAMPLE_RATE);
            mRecognizer.setMaxAlternatives(0);
            mRecognizer.setWords(false);
            mTransmissionActive = true;
        }
        catch(Exception e)
        {
            mLog.error("Failed to create Vosk recognizer", e);
            mTransmissionActive = false;
        }
    }

    /**
     * Feed a buffer of 8 kHz float audio to the recognizer.
     * Convert float [-1,1] to signed 16-bit PCM little-endian as Vosk requires.
     *
     * @param audio8k float array at 8 kHz from SDRTrunk
     */
    public void acceptAudio(float[] audio8k)
    {
        if(!mTransmissionActive || mRecognizer == null) return;

        try
        {
            // Convert float to 16-bit signed PCM little-endian
            ByteBuffer pcm = ByteBuffer.allocate(audio8k.length * 2);
            pcm.order(ByteOrder.LITTLE_ENDIAN);
            for(float sample : audio8k)
            {
                // Clamp to [-1, 1] then scale to short range
                float clamped = Math.max(-1.0f, Math.min(1.0f, sample));
                pcm.putShort((short)(clamped * 32767.0f));
            }
            byte[] pcmBytes = pcm.array();
            mPcmChunks.add(pcmBytes);
            mRecognizer.acceptWaveForm(pcmBytes, pcmBytes.length);
        }
        catch(Exception e)
        {
            mLog.warn("Vosk acceptAudio error", e);
        }
    }

    /**
     * Call at the end of a transmission. Returns the final transcript text,
     * or an empty string if nothing was recognized.
     */
    public String stopTransmission()
    {
        if(!mTransmissionActive || mRecognizer == null)
        {
            mTransmissionActive = false;
            return "";
        }

        mTransmissionActive = false;

        try
        {
            String resultJson = mRecognizer.getFinalResult();
            closeRecognizer();
            return parseVoskResult(resultJson);
        }
        catch(Exception e)
        {
            mLog.warn("Vosk getFinalResult error", e);
            closeRecognizer();
            return "";
        }
    }

    /**
     * Parse Vosk JSON result to extract just the text value.
     * Vosk returns: {"text": "some words here"}
     * We do a simple parse without a JSON library dependency.
     */
    private String parseVoskResult(String json)
    {
        if(json == null || json.isBlank()) return "";

        // Simple extraction: find "text" : "..." pattern
        int textIdx = json.indexOf("\"text\"");
        if(textIdx < 0) return "";

        int colonIdx = json.indexOf(':', textIdx);
        if(colonIdx < 0) return "";

        int openQuote = json.indexOf('"', colonIdx + 1);
        if(openQuote < 0) return "";

        int closeQuote = json.indexOf('"', openQuote + 1);
        if(closeQuote < 0) return "";

        String text = json.substring(openQuote + 1, closeQuote).trim();
        return text;
    }

    /**
     * Close and release the current recognizer.
     */
    private void closeRecognizer()
    {
        if(mRecognizer != null)
        {
            try { mRecognizer.close(); }
            catch(Exception e) { /* ignore */ }
            mRecognizer = null;
        }
        mPcmChunks.clear();
    }

    /**
     * Fully release the model. Call on application shutdown.
     */
    public void dispose()
    {
        mTransmissionActive = false;
        closeRecognizer();
        if(mModel != null)
        {
            try { mModel.close(); }
            catch(Exception e) { /* ignore */ }
            mModel = null;
        }
        mModelLoaded = false;
        mLog.info("Vosk model disposed");
    }
}
