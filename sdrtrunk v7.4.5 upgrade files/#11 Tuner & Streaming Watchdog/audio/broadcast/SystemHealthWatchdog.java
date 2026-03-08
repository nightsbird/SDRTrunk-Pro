/*
 * SystemHealthWatchdog.java
 *
 * Place in: src/main/java/io/github/dsheirer/audio/broadcast/
 *
 * Monitors tuner and streaming broadcaster health, sending email alerts via the
 * same Gmail SMTP configuration used by AudioActivityWatchdog.
 *
 * Tuner alerts  : fires on TunerStatus.ERROR or TunerStatus.REMOVED
 * Streamer alerts: fires on BroadcastState.isErrorState() or DISCONNECTED
 *
 * Wire-up:
 *   1. Instantiate in SDRTrunk main app after UserPreferences is available
 *   2. Call watchdog.registerWithTunerManager(tunerManager)
 *   3. Call watchdog.registerWithBroadcastModel(broadcastModel)
 *   4. Store on UserPreferences via setSystemHealthWatchdog() so the prefs UI can reach it
 */
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.watchdog.AudioWatchdogPreference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.IDiscoveredTunerStatusListener;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SystemHealthWatchdog implements IDiscoveredTunerStatusListener, Listener<BroadcastEvent>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemHealthWatchdog.class);
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final UserPreferences mUserPreferences;

    // Track already-alerted tuners/streamers to avoid repeat emails for the same event
    private final Set<String> mAlertedTuners = ConcurrentHashMap.newKeySet();
    private final Set<String> mAlertedStreamers = ConcurrentHashMap.newKeySet();

    public SystemHealthWatchdog(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Register with TunerManager to receive status updates for all current and future tuners.
     * Call once after TunerManager has finished initial tuner discovery.
     */
    public void registerWithTunerManager(TunerManager tunerManager)
    {
        // Register for future tuner additions via the model's discovered tuner list
        for (DiscoveredTuner tuner : tunerManager.getAvailableTuners())
        {
            tuner.addTunerStatusListener(this);
            LOGGER.info("SystemHealthWatchdog: registered with tuner '{}'", tuner.getId());
        }
    }

    /**
     * Register with BroadcastModel to receive broadcaster state change events.
     */
    public void registerWithBroadcastModel(BroadcastModel broadcastModel)
    {
        broadcastModel.addListener(this);
        LOGGER.info("SystemHealthWatchdog: registered with BroadcastModel");
    }

    // -------------------------------------------------------------------------
    // IDiscoveredTunerStatusListener — tuner health
    // -------------------------------------------------------------------------

    @Override
    public void tunerStatusUpdated(DiscoveredTuner discoveredTuner, TunerStatus previous, TunerStatus current)
    {
        if (!mUserPreferences.getAudioWatchdogPreference().isEnabled())
        {
            return;
        }

        String tunerId = discoveredTuner.getId();

        if (current == TunerStatus.ERROR)
        {
            if (mAlertedTuners.add(tunerId + ":ERROR"))
            {
                String errorMsg = discoveredTuner.hasErrorMessage() ? discoveredTuner.getErrorMessage() : "No details available";
                LOGGER.warn("SystemHealthWatchdog: tuner '{}' entered ERROR state — sending alert", tunerId);
                sendEmail(
                    buildTunerAlertSubject(tunerId, "Error"),
                    buildTunerAlertBody(tunerId, "ERROR", errorMsg)
                );
            }
        }
        else if (current == TunerStatus.REMOVED)
        {
            if (mAlertedTuners.add(tunerId + ":REMOVED"))
            {
                LOGGER.warn("SystemHealthWatchdog: tuner '{}' was REMOVED — sending alert", tunerId);
                sendEmail(
                    buildTunerAlertSubject(tunerId, "Disconnected"),
                    buildTunerAlertBody(tunerId, "REMOVED/DISCONNECTED", "Tuner was physically removed or lost USB connection.")
                );
            }
        }
        else if (current == TunerStatus.ENABLED && previous != TunerStatus.ENABLED)
        {
            // Tuner recovered — clear alert state and send recovery email
            boolean hadError = mAlertedTuners.remove(tunerId + ":ERROR");
            boolean hadRemoved = mAlertedTuners.remove(tunerId + ":REMOVED");

            if (hadError || hadRemoved)
            {
                LOGGER.info("SystemHealthWatchdog: tuner '{}' recovered to ENABLED", tunerId);
                sendEmail(
                    String.format("[SDRTrunk] \u2713 Tuner '%s' recovered", tunerId),
                    String.format(
                        "SDRTrunk System Health Watchdog — Tuner Recovery\n" +
                        "================================================\n\n" +
                        "Tuner     : %s\n" +
                        "Status    : ENABLED (recovered)\n" +
                        "Timestamp : %s\n\n" +
                        "No further action required.\n",
                        tunerId, FORMATTER.format(Instant.now())
                    )
                );
            }
        }
    }

    // -------------------------------------------------------------------------
    // Listener<BroadcastEvent> — streaming health
    // -------------------------------------------------------------------------

    @Override
    public void receive(BroadcastEvent broadcastEvent)
    {
        if (!mUserPreferences.getAudioWatchdogPreference().isEnabled())
        {
            return;
        }

        if (broadcastEvent.getEvent() != BroadcastEvent.Event.BROADCASTER_STATE_CHANGE)
        {
            return;
        }

        AbstractAudioBroadcaster broadcaster = broadcastEvent.getAudioBroadcaster();
        if (broadcaster == null)
        {
            return;
        }

        BroadcastState state = broadcaster.getBroadcastState();
        String name = broadcaster.getBroadcastConfiguration().getName();

        if (state == null || name == null)
        {
            return;
        }

        if (state.isErrorState() || state == BroadcastState.DISCONNECTED)
        {
            String alertKey = name + ":" + state.name();
            if (mAlertedStreamers.add(alertKey))
            {
                LOGGER.warn("SystemHealthWatchdog: streamer '{}' entered state '{}' — sending alert", name, state);
                sendEmail(
                    buildStreamerAlertSubject(name, state),
                    buildStreamerAlertBody(name, state)
                );
            }
        }
        else if (state == BroadcastState.CONNECTED)
        {
            // Clear any prior alert keys for this streamer on reconnect
            boolean hadAlert = mAlertedStreamers.removeIf(key -> key.startsWith(name + ":"));

            if (hadAlert)
            {
                LOGGER.info("SystemHealthWatchdog: streamer '{}' reconnected", name);
                sendEmail(
                    String.format("[SDRTrunk] \u2713 Streamer '%s' reconnected", name),
                    String.format(
                        "SDRTrunk System Health Watchdog — Streamer Recovery\n" +
                        "===================================================\n\n" +
                        "Stream    : %s\n" +
                        "Status    : CONNECTED (recovered)\n" +
                        "Timestamp : %s\n\n" +
                        "No further action required.\n",
                        name, FORMATTER.format(Instant.now())
                    )
                );
            }
        }
    }

    // -------------------------------------------------------------------------
    // Email
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
            LOGGER.warn("SystemHealthWatchdog: email not configured, skipping send");
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

        // Send on a background thread so we never block the calling thread
        final String finalSubject = subject;
        final String finalBody = body;
        Thread t = new Thread(() -> {
            try
            {
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(from));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                message.setSubject(finalSubject);
                message.setText(finalBody);
                Transport.send(message);
                LOGGER.info("SystemHealthWatchdog: email sent → '{}'", finalSubject);
            }
            catch (MessagingException e)
            {
                LOGGER.error("SystemHealthWatchdog: failed to send email", e);
            }
        }, "system-health-watchdog-email");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private String buildTunerAlertSubject(String tunerId, String condition)
    {
        return String.format("[SDRTrunk] \u26a0 Tuner '%s' %s", tunerId, condition);
    }

    private String buildTunerAlertBody(String tunerId, String status, String detail)
    {
        return String.format(
            "SDRTrunk System Health Watchdog — Tuner Alert\n" +
            "=============================================\n\n" +
            "Tuner     : %s\n" +
            "Status    : %s\n" +
            "Detail    : %s\n" +
            "Timestamp : %s\n\n" +
            "Possible causes:\n" +
            "  - USB cable disconnected or loose\n" +
            "  - USB controller error or power issue\n" +
            "  - Driver or firmware failure\n" +
            "  - Noise blanker lockup (RSP1B)\n\n" +
            "Please check your SDRTrunk instance and hardware.\n",
            tunerId, status, detail, FORMATTER.format(Instant.now())
        );
    }

    private String buildStreamerAlertSubject(String name, BroadcastState state)
    {
        return String.format("[SDRTrunk] \u26a0 Streamer '%s' — %s", name, state);
    }

    private String buildStreamerAlertBody(String name, BroadcastState state)
    {
        return String.format(
            "SDRTrunk System Health Watchdog — Streamer Alert\n" +
            "================================================\n\n" +
            "Stream    : %s\n" +
            "State     : %s\n" +
            "Timestamp : %s\n\n" +
            "Possible causes:\n" +
            "  - Network connectivity loss\n" +
            "  - Remote server down or restarting\n" +
            "  - Invalid credentials or configuration\n" +
            "  - Server rejected connection (max sources, mount point in use)\n\n" +
            "Please check your SDRTrunk streaming configuration.\n",
            name, state, FORMATTER.format(Instant.now())
        );
    }
}
