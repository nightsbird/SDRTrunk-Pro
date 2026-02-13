# SDRTrunk VoxSend Edition - Build Instructions

## What This Package Contains

This is a complete set of files to build SDRTrunk with VoxSend audio processing enhancements.

### Custom Audio Features
- **5-Stage VoxSend Audio Chain:**
  1. Input Gain - Amplify weak signals
  2. Low-Pass Filter - Remove high-frequency hiss/noise
  3. 75μs De-emphasis - Correct FM transmitter pre-emphasis
  4. Voice Enhancement - Boost speech clarity (2.8 kHz presence)
  5. Intelligent Squelch - Spectral analysis to detect FM carrier vs voice

### Files Included

**Build Configuration:**
- `build.gradle` - Java 22 build configuration
- `gradle.properties` - Java paths and version
- `build-old-style.bat` - Build script that creates self-contained package

**Source Code (place in sdrtrunk source):**
- `NBFMAudioFilters.java` → `src/main/java/io/github/dsheirer/dsp/filter/nbfm/`
- `NBFMConfigurationEditor.java` → `src/main/java/io/github/dsheirer/gui/playlist/channel/`
- `DecodeConfigNBFM.java` → `src/main/java/io/github/dsheirer/module/decode/nbfm/`

## Prerequisites

1. **Java Development Kit 22**
   - Download: https://bell-sw.com/pages/downloads/#jdk-22-lts
   - Select: JDK 22 LTS, Full JDK, Windows x64
   - Install to: `C:\Java\LibericaJDK-22-Full`

2. **JavaFX SDK 22**
   - Download: https://gluonhq.com/products/javafx/
   - Version: 22
   - Type: **SDK** (not jmods)
   - Extract to: `C:\Java\javafx-sdk-22`

3. **JavaFX jmods 22**
   - Download: https://gluonhq.com/products/javafx/
   - Version: 22
   - Type: **jmods** (not SDK)
   - Extract to: `C:\Java\javafx-jmods-22`

## Installation Steps

1. **Download SDRTrunk source code**
   ```
   Download from: https://github.com/DSheirer/sdrtrunk
   Extract to: C:\Users\YourName\Downloads\sdrtrunk-master
   ```

2. **Copy custom files**
   - Copy `build.gradle` → `sdrtrunk-master\build.gradle` (replace existing)
   - Copy `gradle.properties` → `sdrtrunk-master\gradle.properties` (replace existing)
   - Copy `build-old-style.bat` → `sdrtrunk-master\build-old-style.bat`
   - Copy `NBFMAudioFilters.java` → `sdrtrunk-master\src\main\java\io\github\dsheirer\dsp\filter\nbfm\NBFMAudioFilters.java` (replace)
   - Copy `NBFMConfigurationEditor.java` → `sdrtrunk-master\src\main\java\io\github\dsheirer\gui\playlist\channel\NBFMConfigurationEditor.java` (replace)
   - Copy `DecodeConfigNBFM.java` → `sdrtrunk-master\src\main\java\io\github\dsheirer\module\decode\nbfm\DecodeConfigNBFM.java` (replace)

3. **Build**
   ```
   cd C:\Users\YourName\Downloads\sdrtrunk-master
   build-old-style.bat
   ```

4. **Get your package**
   - Output: `build\sdr-trunk-windows-x86_64-v0.6.2-voxsend.zip`
   - This is a self-contained package - no Java installation needed on target PC!

## Using the VoxSend Audio Features

1. **Open SDRTrunk**
   - Extract the ZIP
   - Run `bin\sdr-trunk.bat`

2. **Create/Edit NBFM Channel**
   - Expand "Audio Filters (VoxSend Chain)" section
   - Configure each of the 5 stages:

**Recommended Settings:**
- Input Gain: 1.0x (adjust for weak signals)
- Low-Pass: ON, 3400 Hz
- De-emphasis: ON, 75 μs (North America)
- Voice Enhancement: ON, 30%
- Intelligent Squelch: OFF (use existing squelch first, enable if carrier noise persists)

## Troubleshooting

**"Graphics Device initialization failed"**
- The launcher includes `-Dprism.order=sw` for software rendering fallback
- Should work on all systems

**"UnsupportedClassVersionError"**
- Java version mismatch
- Make sure you're using Java 22 everywhere (build.gradle, gradle.properties, build script)

**Build fails**
- Check that all Java paths are correct
- Make sure JavaFX SDK and jmods are at the correct paths
- Run `gradlew.bat clean` and try again

## Technical Details

- **Java Version:** 22
- **JavaFX Version:** 22
- **Audio Sample Rate:** 8000 Hz (NBFM standard)
- **Filter Types:** Butterworth (low-pass), 1-pole IIR (de-emphasis), Peaking EQ (voice)
- **Squelch:** Spectral analysis with high-pass filter, energy ratios, zero-crossing detection

## Credits

- Original SDRTrunk: Dennis Sheirer (https://github.com/DSheirer/sdrtrunk)
- VoxSend Audio Processing: Custom implementation by Tim
- Build assistance: Claude (Anthropic)

## License

Same as SDRTrunk - GNU General Public License v3.0
