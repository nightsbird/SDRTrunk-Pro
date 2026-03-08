# AudioActivityWatchdog — Integration Guide (Per-Channel)

## Files to create (new)
| File | Package/Location |
|------|-----------------|
| `AudioActivityWatchdog.java` | `io.github.dsheirer.audio.broadcast` |
| `AudioWatchdogPreference.java` | `io.github.dsheirer.preference.watchdog` |
| `AudioWatchdogPreferenceEditor.java` | `io.github.dsheirer.gui.preference.watchdog` |

---

## 1. Add `AUDIO_WATCHDOG` to `PreferenceType` enum

```java
AUDIO_WATCHDOG,
```

---

## 2. Wire into `UserPreferences`

```java
// Field:
private AudioWatchdogPreference mAudioWatchdogPreference;

// In constructor:
mAudioWatchdogPreference = new AudioWatchdogPreference(this::preferenceUpdated);

// Getter:
public AudioWatchdogPreference getAudioWatchdogPreference()
{
    return mAudioWatchdogPreference;
}
```

---

## 3. Wire into `AudioManager`

```java
// Field:
private AudioActivityWatchdog mAudioActivityWatchdog;

// In constructor:
mAudioActivityWatchdog = new AudioActivityWatchdog(userPreferences);

// In start():
mAudioActivityWatchdog.start();

// In stop():
mAudioActivityWatchdog.stop();
```

---

## 4. Register channels — hook point in channel startup

When a channel starts decoding, register it with its configured threshold.
The best place is wherever `Channel` or `ChannelProcessingManager` brings a
decoder online. Look for where `DecodeConfig` or `Channel` state changes to
`STARTED`/`ENABLED`:

```java
// When a channel starts:
String channelName = channel.getName();  // or channel.getChannelConfiguration().getName()
int threshold = userPreferences.getAudioWatchdogPreference()
                               .getChannelThresholdMinutes(channelName);
mAudioActivityWatchdog.registerChannel(channelName, threshold);
```

For permanently deleted channels (removed from config), call:
```java
mAudioActivityWatchdog.removeChannel(channelName);
```
Do NOT call `removeChannel` on normal stop/disable — monitoring intentionally
continues so unexpected failures are caught.

---

## 5. Notify on audio — hook point in AudioManager

Find where `AudioSegment` objects are dispatched to broadcaster threads —
the same place your Zello/ThinLine broadcasters receive audio. Add one line:

```java
// Alongside your existing broadcaster dispatch:
mAudioActivityWatchdog.notifyAudioActivity(audioSegment.getChannelName());
```

`audioSegment.getChannelName()` (or equivalent getter) must return the same
string used during `registerChannel()`. If `AudioSegment` doesn't carry a
channel name directly, use whatever identifier (`ChannelDescriptor`,
`ChannelMetadata`, etc.) is available at that dispatch point — just keep it
consistent between registration and notification.

---

## 6. Preferences UI — update `AudioWatchdogPreferenceEditor`

The existing editor covers global settings (enabled, Gmail, recipient, default
threshold). Add a per-channel section:

- A `TableView<ChannelRow>` with columns: Channel Name | Threshold (minutes)
- Populate from `mWatchdog.getChannelStates()` (call on FX thread after start)
- On cell edit, call `mPreference.setChannelThresholdMinutes(name, value)`
  and `mWatchdog.updateThreshold(name, value)`
- Add a "Reset to default" button per row

Example row class:
```java
public class ChannelRow
{
    private final SimpleStringProperty name;
    private final SimpleIntegerProperty thresholdMinutes;
    // ... constructor, getters, setters
}
```

---

## 7. Preferences dialog registration

```java
// In PreferenceEditorFactory switch:
case AUDIO_WATCHDOG:
    return new AudioWatchdogPreferenceEditor(userPreferences);
```

Add `PreferenceType.AUDIO_WATCHDOG` (label: "Audio Watchdog") to the
preferences tree alongside other categories.

---

## 8. Gradle dependency

```groovy
implementation 'com.sun.mail:jakarta.mail:2.0.1'
```

---

## How it works end-to-end

```
Channel A starts → registerChannel("Channel A", 30)
Channel B starts → registerChannel("Channel B", 60)

AudioSegment completes on Channel A
  └─▶ notifyAudioActivity("Channel A")  → resets Channel A timer only
      Channel B timer is unaffected

Every 5 minutes, checkAllChannels():
  Channel A: silenceMs < 30min threshold → OK
  Channel B: silenceMs ≥ 60min threshold → send alert email for Channel B only

Channel A goes silent for 30+ minutes → alert email for Channel A only
Channel A audio resumes → recovery email for Channel A only
Channel B still silent → no repeat alert (alertSent flag prevents spam)
```

---

## Alert email example

```
Subject: [SDRTrunk] ⚠ No audio on 'MARCS-IP Statewide' for 32 minutes

Channel             : MARCS-IP Statewide
Configured threshold: 30 minutes
Silence duration    : 32 minutes 14 seconds
Last audio activity : 2026-03-04 14:22:08
Alert generated at  : 2026-03-04 14:54:22

Possible causes:
  - SDR hardware failure or USB disconnect
  - SDRTrunk decoder stall on this channel
  - Genuine quiet period (low traffic)
  - Channel frequency drift / loss of lock
```

---

## Gmail App Password setup

1. Google Account → Security → Enable 2-Step Verification
2. Security → 2-Step Verification → App passwords
3. Create app password named "SDRTrunk"
4. Paste the 16-character password into SDRTrunk preferences
