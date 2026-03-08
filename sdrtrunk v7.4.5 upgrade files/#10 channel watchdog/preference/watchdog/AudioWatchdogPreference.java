/*
 * AudioWatchdogPreference.java
 *
 * Place in: src/main/java/io/github/dsheirer/preference/watchdog/
 *
 * Persists watchdog settings to the existing SDRTrunk preferences system.
 *
 * Global settings (enabled, Gmail credentials, recipient) are stored once.
 * Per-channel thresholds are stored individually keyed by channel name.
 */
package io.github.dsheirer.preference.watchdog;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.preference.PreferenceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.Preferences;

public class AudioWatchdogPreference extends Preference
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioWatchdogPreference.class);

    private static final String PREF_NODE               = "io/github/dsheirer/watchdog";
    private static final String KEY_ENABLED             = "enabled";
    private static final String KEY_DEFAULT_THRESHOLD   = "threshold.default.minutes";
    private static final String KEY_GMAIL_ADDRESS       = "gmail.address";
    private static final String KEY_GMAIL_APP_PASSWORD  = "gmail.app.password";
    private static final String KEY_RECIPIENT_ADDRESS   = "recipient.address";

    // Per-channel threshold keys are prefixed: "threshold.channel.<channelName>"
    private static final String KEY_CHANNEL_THRESHOLD_PREFIX = "threshold.channel.";

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_THRESHOLD_MINUTES = 30;

    private final Preferences mPrefs = Preferences.userRoot().node(PREF_NODE);

    public AudioWatchdogPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.AUDIO_WATCHDOG;
    }

    // -------------------------------------------------------------------------
    // Enabled
    // -------------------------------------------------------------------------

    public boolean isEnabled()
    {
        return mPrefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled)
    {
        mPrefs.putBoolean(KEY_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Default threshold (used for auto-registered channels and as UI default)
    // -------------------------------------------------------------------------

    public int getThresholdMinutes()
    {
        return getDefaultThresholdMinutes();
    }

    public void setThresholdMinutes(int minutes)
    {
        setDefaultThresholdMinutes(minutes);
    }

    public int getDefaultThresholdMinutes()
    {
        return mPrefs.getInt(KEY_DEFAULT_THRESHOLD, DEFAULT_THRESHOLD_MINUTES);
    }

    public void setDefaultThresholdMinutes(int minutes)
    {
        mPrefs.putInt(KEY_DEFAULT_THRESHOLD, Math.max(1, minutes));
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Per-channel thresholds
    // -------------------------------------------------------------------------

    /**
     * Get the silence threshold for a specific channel.
     * Falls back to the default threshold if none has been set for this channel.
     */
    public int getChannelThresholdMinutes(String channelName)
    {
        return mPrefs.getInt(channelKey(channelName), getDefaultThresholdMinutes());
    }

    /**
     * Set the silence threshold for a specific channel.
     */
    public void setChannelThresholdMinutes(String channelName, int minutes)
    {
        mPrefs.putInt(channelKey(channelName), Math.max(1, minutes));
        notifyPreferenceUpdated();
    }

    /**
     * Remove a channel's custom threshold (reverts to default).
     */
    public void removeChannelThreshold(String channelName)
    {
        mPrefs.remove(channelKey(channelName));
        notifyPreferenceUpdated();
    }

    private String channelKey(String channelName)
    {
        // Sanitize channel name for use as a prefs key (strip chars that could
        // conflict with the Preferences node path separator)
        return KEY_CHANNEL_THRESHOLD_PREFIX + channelName.replace("/", "_").replace("\\", "_");
    }

    // -------------------------------------------------------------------------
    // Gmail credentials
    // -------------------------------------------------------------------------

    public String getGmailAddress()
    {
        return mPrefs.get(KEY_GMAIL_ADDRESS, "");
    }

    public void setGmailAddress(String address)
    {
        mPrefs.put(KEY_GMAIL_ADDRESS, address != null ? address.trim() : "");
        notifyPreferenceUpdated();
    }

    /**
     * App password stored in Java Preferences (registry-backed on Windows).
     * For higher security consider integrating the OS credential store.
     */
    public String getGmailAppPassword()
    {
        return mPrefs.get(KEY_GMAIL_APP_PASSWORD, "");
    }

    public void setGmailAppPassword(String password)
    {
        mPrefs.put(KEY_GMAIL_APP_PASSWORD, password != null ? password : "");
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Recipient
    // -------------------------------------------------------------------------

    public String getRecipientAddress()
    {
        return mPrefs.get(KEY_RECIPIENT_ADDRESS, "");
    }

    public void setRecipientAddress(String address)
    {
        mPrefs.put(KEY_RECIPIENT_ADDRESS, address != null ? address.trim() : "");
        notifyPreferenceUpdated();
    }
}
