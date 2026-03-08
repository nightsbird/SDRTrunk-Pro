# SDRTrunk VoxSend Edition v1.1 - Installation & Changes

## INSTALLATION INSTRUCTIONS

### Files to Replace (3 Total):

1. **NBFMAudioFilters_VoxSend.java**
   - Destination: `src/main/java/io/github/dsheirer/dsp/filter/nbfm/NBFMAudioFilters.java`
   - Action: Replace existing file

2. **NBFMConfigurationEditor_VoxSend.java**
   - Destination: `src/main/java/io/github/dsheirer/gui/playlist/channel/NBFMConfigurationEditor.java`
   - Action: Replace existing file

3. **DecodeConfigNBFM.java**
   - Destination: `src/main/java/io/github/dsheirer/module/decode/nbfm/DecodeConfigNBFM.java`
   - Action: Replace existing file

### Build Instructions:

```cmd
cd C:\Users\Tim's Surface\Downloads\sdrtrunk-master
build-old-style.bat
```

### Requirements:
- Java 23
- JavaFX 23
- Gradle (included in build script)

---

## COMPLETE CHANGELOG

### File 1: NBFMAudioFilters.java

#### NEW FEATURES ADDED:

**1. Complete 6-Stage Audio Processing Chain:**
```
Input → Low-Pass → Bass Boost → De-emphasis → Voice Enhancement → Squelch → Output Gain → Output
```

**2. Low-Pass Filter (Existing, Enhanced)**
- 2nd-order Butterworth filter
- Adjustable cutoff: 1000-8000 Hz (default 3400 Hz)
- Enable/disable toggle
- Removes high-frequency hiss/noise

**3. Bass Boost (NEW!)**
- Low-shelf filter boosting below 400 Hz
- Range: 0 to +12 dB
- Enable/disable toggle
- Cookbook biquad implementation
- Applied after low-pass for clean bass enhancement

**4. FM De-emphasis (Existing, Enhanced)**
- 1-pole IIR filter
- Selectable time constant: 75 μs or 50 μs
- Corrects FM radio pre-emphasis
- Enable/disable toggle

**5. Voice Enhancement (Existing, Enhanced)**
- Peaking EQ filter boosting 300-3000 Hz
- Amount: 0-100% (default 30%)
- Enhances speech clarity and presence
- Enable/disable toggle

**6. Squelch/Noise Gate (COMPLETELY REWRITTEN)**

**OLD (Complex):**
- Threshold in dB (-60 to -20 dB)
- Detection modes (Energy/Spectral/Hybrid)
- Spectral analysis (high-freq ratio, zero-crossings)
- Activation time threshold
- Hold time

**NEW (Simple Vox-Send Style):**
- Threshold: 0-100% (percentage, not dB!)
- RMS-based detection: `Level = RMS * 100%`
- Hold time: 0-1000ms (prevents word chopping)
- Reduction: 0-100% (how much to cut carrier)
- Smooth attack/release envelope
- Simple gate logic: if (Level > Threshold) → OPEN, else → CLOSED (after hold)

**7. Output Gain (Repositioned)**
- Moved from FIRST to LAST in chain
- Now amplifies clean signal, not noise
- Range: 0.1x to 4.0x

**8. Audio Level Analyzer (Backend Only)**
- `startAnalyzing()` - Begin collecting samples
- `stopAnalyzing()` - Return recommended threshold
- Analyzes 10 seconds of audio
- Separates carrier (bottom 30%) from voice (top 30%)
- Suggests optimal threshold
- NOTE: Not wired to UI/decoder

**9. Debug Logging**
- Console output every 1000 samples
- Shows: Level %, Threshold %, Gate state, Gain, Reduction %
- Format: `[SQUELCH DEBUG] Level: 3.2%, Threshold: 22.0%, Gate: CLOSED, Gain: 0.20, Reduction: 80%`

#### METHODS ADDED:

**Low-Pass:**
- `setLowPassEnabled(boolean)`
- `setLowPassCutoff(double)`
- `processLowPass(float)` - Filter implementation

**Bass Boost:**
- `setBassBoostEnabled(boolean)`
- `isBassBoostEnabled()`
- `setBassBoost(float)` - Set boost amount in dB
- `getBassBoost()`
- `calculateBassBoostCoefficients()` - Compute filter coefficients
- `processBassBoost(float)` - Filter implementation

**De-emphasis:**
- `setDeemphasisEnabled(boolean)`
- `setDeemphasisTimeConstant(double)`
- `processDeemphasis(float)` - Filter implementation

**Voice Enhancement:**
- `setVoiceEnhanceEnabled(boolean)`
- `setVoiceEnhancement(float)` - Set amount 0-1.0
- `getVoiceEnhancement()`
- `calculateVoiceEnhanceCoefficients()` - Compute filter coefficients
- `processVoiceEnhancement(float)` - Filter implementation

**Squelch:**
- `setSquelchEnabled(boolean)`
- `setSquelchThreshold(float)` - Set percentage 0-100
- `getSquelchThreshold()`
- `getCurrentLevel()` - Get current RMS level percentage
- `setSquelchReduction(float)` - Set reduction 0-1.0
- `setHoldTime(int)` - Set hold time in ms
- `getHoldTime()`
- `processIntelligentSquelch(float)` - Gate implementation

**Analyzer:**
- `startAnalyzing()`
- `stopAnalyzing()` - Returns [carrierMax, voiceMin, recommendedThreshold]
- `isAnalyzing()`

**Core:**
- `process(float)` - Process single sample through all stages
- `process(float[])` - Process buffer in-place
- `reset()` - Reset all filter states

#### MEMBER VARIABLES ADDED:

```java
// Low-pass filter
private float mLpfX1, mLpfX2, mLpfY1, mLpfY2;
private float mLpfB0, mLpfB1, mLpfB2, mLpfA1, mLpfA2;
private double mLpfCutoff;

// Bass boost
private boolean mBassBoostEnabled;
private float mBassBoostDb;
private float mBassBoostX1, mBassBoostX2, mBassBoostY1, mBassBoostY2;
private float mBassBoostB0, mBassBoostB1, mBassBoostB2;
private float mBassBoostA1, mBassBoostA2;

// De-emphasis
private float mDeemphasisPrevious;
private float mDeemphasisAlpha;
private double mDeemphasisTimeConstant;

// Voice enhancement
private float mVoiceEnhX1, mVoiceEnhX2, mVoiceEnhY1, mVoiceEnhY2;
private float mVoiceEnhB0, mVoiceEnhB1, mVoiceEnhB2;
private float mVoiceEnhA1, mVoiceEnhA2;
private float mVoiceEnhanceAmount;

// Squelch (simplified)
private float mSquelchThresholdPercent;  // 0-100%
private float mSquelchCurrentLevel;      // Current RMS 0-100%
private float mSquelchReduction;
private float mSquelchCurrentGain;
private int mHoldTimeMs, mHoldTimeSamples, mHoldTimeCounter;
private boolean mGateOpen;
private float mRmsAlpha, mRmsSmoothed;
private float mSquelchAttackAlpha, mSquelchReleaseAlpha;

// Analyzer
private boolean mAnalyzing;
private List<Float> mAnalyzedLevels;
private int mAnalyzeSampleCount, mAnalyzeMaxSamples;

// Debug
private int mDebugCounter;

// Enable flags
private boolean mLowPassEnabled, mDeemphasisEnabled;
private boolean mVoiceEnhanceEnabled, mSquelchEnabled;
```

#### ALGORITHMS CHANGED:

**Old Squelch Algorithm:**
```java
// Complex spectral analysis
highFreqRatio = highFreqEnergy / totalEnergy
zeroCrossingRate = zeroCrossings / bufferSize

// Mode-based detection
if (mode == HYBRID):
    energyTest = (rms > threshold_dB)
    spectralTest = (highFreqRatio > 0.10 && zcr > 0.08)
    isVoice = energyTest && spectralTest
```

**New Squelch Algorithm:**
```java
// Simple RMS percentage
rms = sqrt(smoothed_energy)
currentLevel = min(100%, rms * 100)

// Simple threshold comparison
if (currentLevel > thresholdPercent):
    gateOpen = true
    resetHoldTimer()
else:
    incrementHoldTimer()
    if (holdTimer > holdTime):
        gateOpen = false

// Apply gain
targetGain = gateOpen ? 1.0 : (1.0 - reduction)
currentGain = smooth(targetGain, attack/release)
```

---

### File 2: NBFMConfigurationEditor.java

#### UI SECTIONS ADDED/MODIFIED:

**Section 1: Low-Pass Filter**
- Enable/Disable toggle switch
- Cutoff frequency slider (1000-8000 Hz)
- Default: 3400 Hz

**Section 2: Bass Boost (NEW!)**
- Enable/Disable toggle switch
- Boost amount slider (0 to +12 dB)
- Label shows current boost (e.g., "+4.0 dB")
- Default: 0 dB (off)

**Section 3: FM De-emphasis**
- Enable/Disable toggle switch
- Time constant dropdown (75 μs or 50 μs)
- Default: 75 μs (North America)

**Section 4: Voice Enhancement**
- Enable/Disable toggle switch
- Amount slider (0-100%)
- Label shows percentage (e.g., "30%")
- Default: 30%

**Section 5: Squelch/Noise Gate (REDESIGNED)**
- Enable/Disable toggle switch
- **"Analyze Audio & Suggest Settings" button** (placeholder)
  - Status label shows analysis progress
  - Backend ready, not wired to decoder
- **Threshold slider:** 0-100% (not dB!)
  - Label shows percentage (e.g., "22.0%")
  - Default: 4%
- **Reduction slider:** 0-100%
  - Label shows percentage (e.g., "80%")
  - Default: 80%
- **Delay slider:** 0-1000ms
  - Label shows milliseconds (e.g., "500 ms")
  - Default: 500ms

**OLD Squelch UI (Removed):**
- Threshold in dB
- Detection mode dropdown (Energy/Spectral/Hybrid)
- Activation time slider

**Section 6: Output Gain**
- Gain slider (0.1x to 4.0x)
- Label shows multiplier (e.g., "1.0x")
- Default: 1.0x
- Note: "Applied Last"

#### UI ELEMENTS ADDED:

```java
// Low-pass
private ToggleSwitch mLowPassEnabledSwitch;
private Slider mLowPassCutoffSlider;
private Label mLowPassCutoffLabel;

// Bass boost
private ToggleSwitch mBassBoostEnabledSwitch;
private Slider mBassBoostSlider;
private Label mBassBoostLabel;

// De-emphasis
private ToggleSwitch mDeemphasisEnabledSwitch;
private ComboBox<String> mDeemphasisTimeConstantCombo;

// Voice enhancement
private ToggleSwitch mVoiceEnhanceEnabledSwitch;
private Slider mVoiceEnhanceSlider;
private Label mVoiceEnhanceLabel;

// Squelch
private ToggleSwitch mSquelchEnabledSwitch;
private Button mAnalyzeButton;
private Label mAnalyzeStatusLabel;
private Slider mSquelchThresholdSlider;
private Label mSquelchThresholdLabel;
private Slider mSquelchReductionSlider;
private Label mSquelchReductionLabel;
private Slider mHoldTimeSlider;
private Label mHoldTimeLabel;

// Output gain
private Slider mInputGainSlider;
private Label mInputGainLabel;
```

#### METHODS ADDED:

**UI Creation:**
- `createLowPassSection()` - Build low-pass UI
- `createBassBoostSection()` - Build bass boost UI (NEW!)
- `createDeemphasisSection()` - Build de-emphasis UI
- `createVoiceEnhanceSection()` - Build voice enhancement UI
- `createSquelchSection()` - Build squelch UI (redesigned)
- `createInputGainSection()` - Build output gain UI

**Configuration:**
- `loadAudioFilterConfiguration(DecodeConfigNBFM)` - Load all settings from config
- `saveAudioFilterConfiguration(DecodeConfigNBFM)` - Save all settings to config
- `disableAudioFilterControls()` - Disable all controls

**Event Handlers:**
- `handleAnalyzeClick()` - Handle analyze button (placeholder)
- Multiple lambda listeners for sliders/toggles

#### CONFIGURATION LOADING:

```java
private void loadAudioFilterConfiguration(DecodeConfigNBFM config) {
    // Input Gain (from AGC max gain)
    float maxGainDb = config.getAgcMaxGain();
    float inputGain = Math.pow(10.0, maxGainDb / 40.0);
    mInputGainSlider.setValue(inputGain);
    
    // Low-pass
    mLowPassEnabledSwitch.setSelected(config.isLowPassEnabled());
    mLowPassCutoffSlider.setValue(config.getLowPassCutoff());
    
    // Bass boost
    mBassBoostEnabledSwitch.setSelected(config.isBassBoostEnabled());
    mBassBoostSlider.setValue(config.getBassBoostDb());
    
    // De-emphasis
    mDeemphasisEnabledSwitch.setSelected(config.isDeemphasisEnabled());
    double tc = config.getDeemphasisTimeConstant();
    mDeemphasisTimeConstantCombo.setValue(tc == 75.0 ? "75 μs" : "50 μs");
    
    // Voice Enhancement (from AGC target level)
    mVoiceEnhanceEnabledSwitch.setSelected(config.isAgcEnabled());
    float targetLevel = config.getAgcTargetLevel();
    float voiceAmount = ((targetLevel + 30.0f) / 24.0f) * 100.0f;
    mVoiceEnhanceSlider.setValue(voiceAmount);
    
    // Squelch
    mSquelchEnabledSwitch.setSelected(config.isNoiseGateEnabled());
    mSquelchThresholdSlider.setValue(config.getNoiseGateThreshold());
    mSquelchReductionSlider.setValue(config.getNoiseGateReduction() * 100);
    mHoldTimeSlider.setValue(config.getNoiseGateHoldTime());
}
```

#### CONFIGURATION SAVING:

```java
private void saveAudioFilterConfiguration(DecodeConfigNBFM config) {
    // Input Gain
    float inputGain = (float)mInputGainSlider.getValue();
    float maxGainDb = (float)(40.0 * Math.log10(inputGain));
    config.setAgcMaxGain(maxGainDb);
    
    // Low-pass
    config.setLowPassEnabled(mLowPassEnabledSwitch.isSelected());
    config.setLowPassCutoff(mLowPassCutoffSlider.getValue());
    
    // Bass boost
    config.setBassBoostEnabled(mBassBoostEnabledSwitch.isSelected());
    config.setBassBoostDb((float)mBassBoostSlider.getValue());
    
    // De-emphasis
    config.setDeemphasisEnabled(mDeemphasisEnabledSwitch.isSelected());
    String selected = mDeemphasisTimeConstantCombo.getValue();
    double tc = (selected.startsWith("75")) ? 75.0 : 50.0;
    config.setDeemphasisTimeConstant(tc);
    
    // Voice Enhancement
    config.setAgcEnabled(mVoiceEnhanceEnabledSwitch.isSelected());
    float voiceAmount = (float)mVoiceEnhanceSlider.getValue();
    float targetLevel = -30.0f + (voiceAmount / 100.0f * 24.0f);
    config.setAgcTargetLevel(targetLevel);
    
    // Squelch
    config.setNoiseGateEnabled(mSquelchEnabledSwitch.isSelected());
    config.setNoiseGateThreshold((float)mSquelchThresholdSlider.getValue());
    config.setNoiseGateReduction((float)mSquelchReductionSlider.getValue() / 100);
    config.setNoiseGateHoldTime((int)mHoldTimeSlider.getValue());
}
```

---

### File 3: DecodeConfigNBFM.java

#### CONFIGURATION FIELDS ADDED:

```java
// Audio Filter Configuration (VoxSend)
private boolean mDeemphasisEnabled = true;
private double mDeemphasisTimeConstant = 75.0; // μs

private boolean mLowPassEnabled = true;
private double mLowPassCutoff = 3400.0; // Hz

private boolean mBassBoostEnabled = false;  // NEW!
private float mBassBoostDb = 0.0f;         // NEW! (0 to +12 dB)

private boolean mNoiseGateEnabled = false;
private float mNoiseGateThreshold = 4.0f;  // CHANGED: percentage 0-100 (was dB)
private float mNoiseGateReduction = 0.8f;  // 0.0 to 1.0
private int mNoiseGateHoldTime = 500;      // milliseconds

// Voice Enhancement (stored as AGC)
private boolean mAgcEnabled = true;
private float mAgcTargetLevel = -18.0f;    // dB (stores voice enhancement)
private float mAgcMaxGain = 24.0f;         // dB (stores input gain)
```

#### CONFIGURATION FIELDS REMOVED:

```java
// OLD (Complex Squelch - Removed):
private String mNoiseGateMode;           // REMOVED: "ENERGY_ONLY", "SPECTRAL_ONLY", "HYBRID"
private int mNoiseGateActivationTime;    // REMOVED: milliseconds
```

#### GETTERS/SETTERS ADDED:

**Bass Boost:**
```java
@JacksonXmlProperty(isAttribute = true, localName = "bassBoostEnabled")
public boolean isBassBoostEnabled()

public void setBassBoostEnabled(boolean enabled)

@JacksonXmlProperty(isAttribute = true, localName = "bassBoostDb")
public float getBassBoostDb()

public void setBassBoostDb(float boostDb)  // Range: 0-12 dB
```

**Low-Pass:**
```java
@JacksonXmlProperty(isAttribute = true, localName = "lowPassEnabled")
public boolean isLowPassEnabled()

public void setLowPassEnabled(boolean enabled)

@JacksonXmlProperty(isAttribute = true, localName = "lowPassCutoff")
public double getLowPassCutoff()

public void setLowPassCutoff(double cutoff)
```

**De-emphasis:**
```java
@JacksonXmlProperty(isAttribute = true, localName = "deemphasisEnabled")
public boolean isDeemphasisEnabled()

public void setDeemphasisEnabled(boolean enabled)

@JacksonXmlProperty(isAttribute = true, localName = "deemphasisTimeConstant")
public double getDeemphasisTimeConstant()

public void setDeemphasisTimeConstant(double timeConstant)
```

**Squelch (Modified):**
```java
@JacksonXmlProperty(isAttribute = true, localName = "noiseGateEnabled")
public boolean isNoiseGateEnabled()

public void setNoiseGateEnabled(boolean enabled)

@JacksonXmlProperty(isAttribute = true, localName = "noiseGateThreshold")
public float getNoiseGateThreshold()  // CHANGED: Now returns percentage 0-100 (was dB)

public void setNoiseGateThreshold(float threshold)  // CHANGED: Now accepts percentage

@JacksonXmlProperty(isAttribute = true, localName = "noiseGateReduction")
public float getNoiseGateReduction()

public void setNoiseGateReduction(float reduction)

@JacksonXmlProperty(isAttribute = true, localName = "noiseGateHoldTime")
public int getNoiseGateHoldTime()

public void setNoiseGateHoldTime(int timeMs)
```

#### GETTERS/SETTERS REMOVED:

```java
// OLD (Removed):
public String getNoiseGateMode()             // REMOVED
public void setNoiseGateMode(String mode)    // REMOVED
public int getNoiseGateActivationTime()      // REMOVED
public void setNoiseGateActivationTime(int)  // REMOVED
```

#### XML PERSISTENCE:

All fields marked with `@JacksonXmlProperty` for XML serialization:
```xml
<decode type="nbfm" 
    lowPassEnabled="true" 
    lowPassCutoff="3400.0"
    bassBoostEnabled="false"
    bassBoostDb="0.0"
    deemphasisEnabled="true" 
    deemphasisTimeConstant="75.0"
    noiseGateEnabled="true" 
    noiseGateThreshold="22.0" 
    noiseGateReduction="0.8" 
    noiseGateHoldTime="500"
    agcEnabled="true" 
    agcTargetLevel="-18.0" 
    agcMaxGain="24.0"/>
```

---

## RECOMMENDED DEFAULT SETTINGS

```java
// In NBFMAudioFilters constructor:
setInputGain(1.0f);                   // No boost by default
setLowPassCutoff(3400.0);             // 3400 Hz
setBassBoost(0.0f);                   // 0 dB (off)
setDeemphasisTimeConstant(75.0);      // 75 μs (North America)
setVoiceEnhancement(0.3f);            // 30% boost
setSquelchThreshold(4.0f);            // 4% (percentage)
setSquelchReduction(0.8f);            // 80% reduction
setHoldTime(500);                     // 500ms hold time
```

---

## USER-TESTED SETTINGS

Based on audio analysis and user testing:

**For User's Radio System:**
- Low-Pass: Enabled, 3400 Hz
- Bass Boost: +4 to +6 dB
- De-emphasis: Enabled, 75 μs
- Voice Enhancement: 30-50%
- Squelch Threshold: **22-28%** (audio analysis suggested 6%, user found 22-28% worked best)
- Squelch Reduction: **70-80%**
- Squelch Delay: **500-700ms**
- Output Gain: 1.0x

**Audio Analysis Results:**
- Carrier noise: 2-4%
- Voice levels: 20-60%
- Recommended threshold: 6% (3-5 dB margin above carrier max)
- User's optimal threshold: 22-28% (higher due to overlapping levels)

---

## DEBUGGING

### Console Output:
When squelch is enabled, debug messages print every 1000 samples:

```
[SQUELCH DEBUG] Level: 3.2%, Threshold: 22.0%, Gate: CLOSED, Gain: 0.20, Reduction: 80%
[SQUELCH DEBUG] Level: 45.6%, Threshold: 22.0%, Gate: OPEN, Gain: 0.95, Reduction: 80%
```

**What to look for:**
- **Level:** Current RMS level as percentage
- **Threshold:** User-set threshold
- **Gate:** OPEN (voice) or CLOSED (carrier)
- **Gain:** Current gain multiplier (1.0 = full, 0.2 = 80% reduction)
- **Reduction:** User-set reduction percentage

**Troubleshooting:**
- If carrier bleeds through: Increase Threshold
- If voice cuts out: Decrease Threshold
- If voice choppy: Increase Delay (hold time)
- If too much carrier audible: Increase Reduction

---

## BUILD REQUIREMENTS

- **Java:** 23
- **JavaFX:** 23
- **Gradle:** Included in build script
- **OS:** Windows (build-old-style.bat)

### Build Output:
- Self-contained package with bundled JRE
- No Java installation needed on target PC
- Includes all JavaFX native libraries

---

## VERSION HISTORY

**v1.0** (February 11, 2026)
- Initial release
- 5-stage audio processing
- Simple percentage-based squelch
- Complex spectral analysis removed

**v1.1** (February 15, 2026)
- Added bass boost (low-shelf filter)
- Moved bass boost to position 2 (after low-pass)
- Updated section numbering
- Fixed Platform.runLater syntax error
- Updated processing order documentation

---

## TECHNICAL DETAILS

### Filter Specifications:

**Low-Pass (Butterworth 2nd-Order):**
- Cutoff: 1000-8000 Hz (default 3400 Hz)
- Attenuation: -12 dB/octave
- Type: IIR biquad

**Bass Boost (Low-Shelf):**
- Cutoff: 400 Hz
- Boost: 0 to +12 dB
- Type: Cookbook biquad
- Applied after low-pass for clean bass

**De-emphasis (1-Pole IIR):**
- Time constant: 75 μs or 50 μs
- Compensates for FM pre-emphasis
- Type: Simple RC filter

**Voice Enhancement (Peaking EQ):**
- Center: ~2000 Hz
- Bandwidth: 300-3000 Hz
- Boost: 0-100% (maps to gain)
- Type: Biquad peaking filter

**Squelch (RMS Gate):**
- Detection: Running average RMS
- Smoothing: α = 0.05
- Attack: 10ms
- Release: 100ms
- Type: Envelope follower with gate

### Processing Flow:
```
Sample Input
    ↓
Low-Pass (3400 Hz) ← Remove high-freq noise
    ↓
Bass Boost (+4 dB @ <400 Hz) ← Enhance warmth
    ↓
De-emphasis (75 μs) ← Correct FM boost
    ↓
Voice Enhance (+30% @ 300-3000 Hz) ← Boost clarity
    ↓
Squelch (22% threshold, 80% reduction, 500ms hold) ← Gate carrier
    ↓
Output Gain (1.0x) ← Amplify clean signal
    ↓
Sample Output
```

---

## KNOWN LIMITATIONS

1. **Auto-Calibration Not Wired:**
   - Backend analyzer works
   - UI button present but shows "not wired" message
   - Requires decoder architecture changes to complete
   - Alternative: Manual audio analysis available

2. **Live Level Meter Removed:**
   - Would require decoder wiring
   - User adjusts threshold by ear (works well)

3. **Debug Logging Always On:**
   - Console output every 1000 samples
   - Can be disabled by removing debug code if desired

---

## SUPPORT NOTES

**If squelch not working:**
1. Check squelch is enabled
2. Watch console debug output
3. Verify Level % changes with audio
4. Adjust Threshold to sit between carrier and voice levels
5. Try different Reduction amounts (70-90%)

**If audio quality poor:**
1. Try disabling bass boost
2. Reduce voice enhancement (try 20%)
3. Adjust low-pass cutoff (try 3000 Hz)
4. Check output gain not too high

**If build fails:**
1. Verify all 3 files replaced
2. Check Java 23 is being used
3. Verify JavaFX 23 is installed
4. Check file paths are correct

---

## FILES SUMMARY

**Total Files Modified:** 3
**Total Lines Added:** ~1800
**Total Lines Removed:** ~400
**Net Change:** ~1400 lines

**Complexity:**
- NBFMAudioFilters.java: High (DSP algorithms)
- NBFMConfigurationEditor.java: Medium (UI/event handling)
- DecodeConfigNBFM.java: Low (data storage)

---

**End of Documentation**
**SDRTrunk VoxSend Edition v1.1**
**February 15, 2026**
