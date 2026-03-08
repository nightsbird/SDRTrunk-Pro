/*
 * AudioActivityWatchdog.java
 *
 * Place in: src/main/java/io/github/dsheirer/audio/broadcast/
 *
 * Monitors decoded audio activity per channel independently.
 * Each channel registers with a configurable silence threshold.
 * If a channel exceeds its threshold with no audio, an alert email is sent.
 * A recovery email is sent when audio resumes on that channel.
 *
 * Channels are never silently unregistered — even intentionally stopped
 * channels continue to be monitored so unexpected drops are caught.
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.watchdog.AudioWatchdogPreference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AudioActivityWatchdog
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioActivityWatchdog.class);
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Check all channels every 5 minutes for prompt threshold detection
    private static final long CHECK_INTERVAL_SECONDS = 300;

    private final UserPreferences mUserPreferences;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    // Map of channelName -> ChannelWatchState
    private final Map<String, ChannelWatchState> mChannelStates = new ConcurrentHashMap<>();

    private ScheduledExecutorService mScheduler;
    private ScheduledFuture<?> mCheckTask;

    // -------------------------------------------------------------------------
    // Inner state class — one per monitored channel
    // -------------------------------------------------------------------------

    private static class ChannelWatchState
    {
        final String channelName;
        final AtomicLong lastActivityEpochMs = new AtomicLong(System.currentTimeMillis());
        final AtomicBoolean alertSent = new AtomicBoolean(false);
        final AtomicLong lastAlertEpochMs = new AtomicLong(0);
        volatile int thresholdMinutes;

        ChannelWatchState(String channelName, int thresholdMinutes)
        {
            this.channelName = channelName;
            this.thresholdMinutes = thresholdMinutes;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor / lifecycle
    // -------------------------------------------------------------------------

    public AudioActivityWatchdog(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    public void start()
    {
        if (mRunning.compareAndSet(false, true))
        {
            mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "audio-activity-watchdog");
                t.setDaemon(true);
                return t;
            });

            mCheckTask = mScheduler.scheduleAtFixedRate(
                    this::checkAllChannels,
                    CHECK_INTERVAL_SECONDS,
                    CHECK_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );

            LOGGER.info("AudioActivityWatchdog started (check interval: {}m)", CHECK_INTERVAL_SECONDS / 60);
        }
    }

    public void stop()
    {
        if (mRunning.compareAndSet(true, false))
        {
            if (mCheckTask != null) mCheckTask.cancel(false);
            if (mScheduler != null) mScheduler.shutdownNow();
            LOGGER.info("AudioActivityWatchdog stopped");
        }
    }

    // -------------------------------------------------------------------------
    // Channel registration
    // -------------------------------------------------------------------------

    public void registerChannel(String channelName, int thresholdMinutes)
    {
        ChannelWatchState existing = mChannelStates.get(channelName);

        if (existing == null)
        {
            mChannelStates.put(channelName, new ChannelWatchState(channelName, thresholdMinutes));
            LOGGER.info("AudioActivityWatchdog: registered channel '{}' (threshold: {}m)",
                    channelName, thresholdMinutes);
        }
        else
        {
            existing.thresholdMinutes = thresholdMinutes;
        }
    }

    public void removeChannel(String channelName)
    {
        mChannelStates.remove(channelName);
        LOGGER.info("AudioActivityWatchdog: removed channel '{}' from monitoring", channelName);
    }

    public Map<String, ChannelWatchState> getChannelStates()
    {
        return mChannelStates;
    }

    // -------------------------------------------------------------------------
    // Activity notification — accepts AudioSegment directly
    // -------------------------------------------------------------------------

    /**
     * Notify the watchdog that audio activity occurred on the channel associated
     * with the given AudioSegment. Extracts the channel name from the segment's
     * identifier collection. Auto-registers the channel if not already known.
     */
    public void notifyAudioActivity(AudioSegment audioSegment)
    {
        String channelName = extractChannelName(audioSegment);
        notifyAudioActivity(channelName);
    }

    /**
     * Notify the watchdog of activity on a channel by name directly.
     */
    public void notifyAudioActivity(String channelName)
    {
        ChannelWatchState state = mChannelStates.get(channelName);

        if (state == null)
        {
            AudioWatchdogPreference prefs = mUserPreferences.getAudioWatchdogPreference();
            registerChannel(channelName, prefs.getDefaultThresholdMinutes());
            state = mChannelStates.get(channelName);
        }

        long now = System.currentTimeMillis();
        state.lastActivityEpochMs.set(now);

        if (state.alertSent.compareAndSet(true, false))
        {
            state.lastAlertEpochMs.set(0);
            LOGGER.info("AudioActivityWatchdog: audio resumed on channel '{}'", channelName);
            final ChannelWatchState finalState = state;
            sendEmail(buildResumedSubject(channelName), buildResumedBody(channelName, now, finalState));
        }
    }

    /**
     * Extracts a human-readable channel name from an AudioSegment's identifier collection.
     * Falls back to a frequency or "Unknown" if no channel name identifier is present.
     */
    private String extractChannelName(AudioSegment audioSegment)
    {
        IdentifierCollection ids = audioSegment.getIdentifierCollection();

        if (ids != null)
        {
            // CHANNEL_NAME identifier carries the channel configuration name
            ids.getIdentifiers().stream()
               .filter(id -> id.getRole() == Role.BROADCAST && id instanceof StringIdentifier)
               .findFirst();

            // Primary: look for a string identifier tagged as the channel/system name
            for (var id : ids.getIdentifiers())
            {
                if (id instanceof StringIdentifier si && id.getRole() == Role.TO)
                {
                    String val = si.getValue();
                    if (val != null && !val.isBlank())
                    {
                        return val;
                    }
                }
            }

            // Fallback: use any non-blank string identifier value
            for (var id : ids.getIdentifiers())
            {
                if (id instanceof StringIdentifier si)
                {
                    String val = si.getValue();
                    if (val != null && !val.isBlank())
                    {
                        return val;
                    }
                }
            }
        }

        return "Unknown Channel";
    }

    // -------------------------------------------------------------------------
    // Background check
    // -------------------------------------------------------------------------

    private void checkAllChannels()
    {
        AudioWatchdogPreference prefs = mUserPreferences.getAudioWatchdogPreference();

        if (!prefs.isEnabled())
        {
            return;
        }

        for (ChannelWatchState state : mChannelStates.values())
        {
            long thresholdMs = (long) state.thresholdMinutes * 60_000L;
            long silenceMs = System.currentTimeMillis() - state.lastActivityEpochMs.get();

            if (silenceMs >= thresholdMs)
            {
                long now = System.currentTimeMillis();
                long timeSinceLastAlert = now - state.lastAlertEpochMs.get();

                // Alert on first breach, then re-alert every threshold period while silence continues
                if (!state.alertSent.get() || timeSinceLastAlert >= thresholdMs)
                {
                    state.alertSent.set(true);
                    state.lastAlertEpochMs.set(now);
                    LOGGER.warn("AudioActivityWatchdog: no audio on '{}' for {}ms — sending alert",
                            state.channelName, silenceMs);
                    sendEmail(
                        buildAlertSubject(state.channelName, silenceMs),
                        buildAlertBody(state.channelName, silenceMs, state)
                    );
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Email sending
    // -------------------------------------------------------------------------

    private void sendEmail(String subject, String body)
    {
        AudioWatchdogPreference prefs = mUserPreferences.getAudioWatchdogPreference();

        String from = prefs.getGmailAddress();
        String appPassword = prefs.getGmailAppPassword();
        String to = prefs.getRecipientAddress();

        if (from == null || from.isBlank() || appPassword == null || appPassword.isBlank()
                || to == null || to.isBlank())
        {
            LOGGER.warn("AudioActivityWatchdog: email not configured, skipping send");
            return;
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.ssl.trust", "smtp.gmail.com");

        Session session = Session.getInstance(props, new Authenticator()
        {
            @Override
            protected PasswordAuthentication getPasswordAuthentication()
            {
                return new PasswordAuthentication(from, appPassword);
            }
        });

        try
        {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setText(body);
            Transport.send(message);
            LOGGER.info("AudioActivityWatchdog: email sent → subject: '{}'", subject);
        }
        catch (MessagingException e)
        {
            LOGGER.error("AudioActivityWatchdog: failed to send email", e);
        }
    }

    public void sendTestEmail()
    {
        int channelCount = mChannelStates.size();
        StringBuilder channelList = new StringBuilder();
        for (ChannelWatchState state : mChannelStates.values())
        {
            channelList.append(String.format("  - %s (threshold: %d min)\n",
                    state.channelName, state.thresholdMinutes));
        }
        if (channelList.length() == 0)
        {
            channelList.append("  (no channels currently registered)\n");
        }

        sendEmail(
            "[SDRTrunk] Test email from Audio Watchdog",
            "This is a test message from the SDRTrunk Audio Activity Watchdog.\n\n" +
            "If you received this, your email configuration is working correctly.\n\n" +
            "Currently monitoring " + channelCount + " channel(s):\n" +
            channelList
        );
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private String buildAlertSubject(String channelName, long silenceMs)
    {
        long minutes = silenceMs / 60_000L;
        return String.format("[SDRTrunk] \u26a0 No audio on '%s' for %d minute%s",
                channelName, minutes, minutes == 1 ? "" : "s");
    }

    private String buildAlertBody(String channelName, long silenceMs, ChannelWatchState state)
    {
        long minutes = silenceMs / 60_000L;
        long seconds = (silenceMs % 60_000L) / 1000L;
        String lastSeen = FORMATTER.format(Instant.ofEpochMilli(state.lastActivityEpochMs.get()));
        String now = FORMATTER.format(Instant.now());

        return String.format(
            "SDRTrunk Audio Activity Watchdog — Channel Alert\n" +
            "=================================================\n\n" +
            "Channel             : %s\n" +
            "Configured threshold: %d minutes\n" +
            "Silence duration    : %d minutes %d seconds\n" +
            "Last audio activity : %s\n" +
            "Alert generated at  : %s\n\n" +
            "Possible causes:\n" +
            "  - SDR hardware failure or USB disconnect\n" +
            "  - SDRTrunk decoder stall on this channel\n" +
            "  - Genuine quiet period (low traffic)\n" +
            "  - Channel frequency drift / loss of lock\n\n" +
            "Please check your SDRTrunk instance.\n",
            channelName, state.thresholdMinutes, minutes, seconds, lastSeen, now
        );
    }

    private String buildResumedSubject(String channelName)
    {
        return String.format("[SDRTrunk] \u2713 Audio resumed on '%s'", channelName);
    }

    private String buildResumedBody(String channelName, long resumedEpochMs, ChannelWatchState state)
    {
        String resumed = FORMATTER.format(Instant.ofEpochMilli(resumedEpochMs));

        return String.format(
            "SDRTrunk Audio Activity Watchdog — Channel Recovery\n" +
            "===================================================\n\n" +
            "Channel    : %s\n" +
            "Resumed at : %s\n\n" +
            "Audio activity has resumed on this channel. No further action required.\n",
            channelName, resumed
        );
    }
}
