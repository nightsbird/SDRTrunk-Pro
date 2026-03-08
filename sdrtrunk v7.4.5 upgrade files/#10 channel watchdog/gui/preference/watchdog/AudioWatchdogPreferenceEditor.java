/*
 * AudioWatchdogPreferenceEditor.java
 *
 * Place in: src/main/java/io/github/dsheirer/gui/preference/watchdog/
 *
 * JavaFX panel shown in the SDRTrunk Preferences dialog.
 * Follows the same pattern as other preference editors (e.g. DirectoryPreferenceEditor).
 */
package io.github.dsheirer.gui.preference.watchdog;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.watchdog.AudioWatchdogPreference;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioWatchdogPreferenceEditor extends VBox
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioWatchdogPreferenceEditor.class);

    private final UserPreferences mUserPreferences;
    private final AudioWatchdogPreference mPreference;

    // Controls
    private CheckBox mEnabledCheckBox;
    private Spinner<Integer> mThresholdSpinner;
    private TextField mGmailAddressField;
    private PasswordField mGmailPasswordField;
    private TextField mRecipientField;
    private Button mTestButton;
    private Label mStatusLabel;

    public AudioWatchdogPreferenceEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        mPreference = userPreferences.getAudioWatchdogPreference();
        init();
    }

    private void init()
    {
        setSpacing(10);
        setPadding(new Insets(10, 10, 10, 10));

        // --- Header ---
        Label header = new Label("Audio Activity Watchdog");
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        Label description = new Label(
                "Sends an email alert if no decoded audio is received within the configured period.\n" +
                "Requires a Gmail account with an App Password (not your regular Gmail password).");
        description.setWrapText(true);

        // --- Enable toggle ---
        mEnabledCheckBox = new CheckBox("Enable audio activity watchdog");
        mEnabledCheckBox.setSelected(mPreference.isEnabled());
        mEnabledCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            mPreference.setEnabled(newVal);
            updateControlStates();
        });

        // --- Settings grid ---
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(5, 0, 5, 20));

        int row = 0;

        // Threshold
        Label threshLabel = new Label("Alert after silence of:");
        mThresholdSpinner = new Spinner<>(1, 1440, mPreference.getThresholdMinutes(), 1);
        mThresholdSpinner.setEditable(true);
        mThresholdSpinner.setPrefWidth(80);
        mThresholdSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
                mPreference.setThresholdMinutes(newVal));
        Label minutesLabel = new Label("minutes");
        HBox threshBox = new HBox(5, mThresholdSpinner, minutesLabel);
        threshBox.setAlignment(Pos.CENTER_LEFT);

        grid.add(threshLabel, 0, row);
        grid.add(threshBox, 1, row++);

        // Gmail address
        Label gmailLabel = new Label("Gmail address:");
        mGmailAddressField = new TextField(mPreference.getGmailAddress());
        mGmailAddressField.setPromptText("yourname@gmail.com");
        mGmailAddressField.setPrefWidth(260);
        mGmailAddressField.focusedProperty().addListener((obs, oldVal, focused) -> {
            if (!focused) mPreference.setGmailAddress(mGmailAddressField.getText());
        });
        grid.add(gmailLabel, 0, row);
        grid.add(mGmailAddressField, 1, row++);

        // App password
        Label passLabel = new Label("Gmail App Password:");
        mGmailPasswordField = new PasswordField();
        mGmailPasswordField.setText(mPreference.getGmailAppPassword());
        mGmailPasswordField.setPromptText("16-character app password");
        mGmailPasswordField.setPrefWidth(260);
        mGmailPasswordField.focusedProperty().addListener((obs, oldVal, focused) -> {
            if (!focused) mPreference.setGmailAppPassword(mGmailPasswordField.getText());
        });
        Hyperlink appPassHelp = new Hyperlink("How to create an App Password");
        appPassHelp.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        new java.net.URI("https://support.google.com/accounts/answer/185833"));
            } catch (Exception ex) {
                LOGGER.warn("Could not open browser", ex);
            }
        });
        VBox passBox = new VBox(2, mGmailPasswordField, appPassHelp);
        grid.add(passLabel, 0, row);
        grid.add(passBox, 1, row++);

        // Recipient
        Label recipLabel = new Label("Send alerts to:");
        mRecipientField = new TextField(mPreference.getRecipientAddress());
        mRecipientField.setPromptText("recipient@example.com");
        mRecipientField.setPrefWidth(260);
        mRecipientField.focusedProperty().addListener((obs, oldVal, focused) -> {
            if (!focused) mPreference.setRecipientAddress(mRecipientField.getText());
        });
        grid.add(recipLabel, 0, row);
        grid.add(mRecipientField, 1, row++);

        // Make label column right-aligned
        for (javafx.scene.Node node : grid.getChildren())
        {
            if (GridPane.getColumnIndex(node) != null && GridPane.getColumnIndex(node) == 0)
            {
                GridPane.setHalignment(node, HPos.RIGHT);
            }
        }

        // --- Test button + status ---
        mTestButton = new Button("Send Test Email");
        mTestButton.setOnAction(e -> sendTestEmail());

        mStatusLabel = new Label("");
        mStatusLabel.setStyle("-fx-text-fill: grey;");

        HBox buttonRow = new HBox(10, mTestButton, mStatusLabel);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        buttonRow.setPadding(new Insets(0, 0, 0, 20));

        getChildren().addAll(header, new Separator(), description, mEnabledCheckBox, grid, buttonRow);

        updateControlStates();
    }

    private void updateControlStates()
    {
        boolean enabled = mEnabledCheckBox.isSelected();
        mThresholdSpinner.setDisable(!enabled);
        mGmailAddressField.setDisable(!enabled);
        mGmailPasswordField.setDisable(!enabled);
        mRecipientField.setDisable(!enabled);
        mTestButton.setDisable(!enabled);
    }

    private void sendTestEmail()
    {
        // Save current field values first
        mPreference.setGmailAddress(mGmailAddressField.getText());
        mPreference.setGmailAppPassword(mGmailPasswordField.getText());
        mPreference.setRecipientAddress(mRecipientField.getText());

        mStatusLabel.setText("Sending...");
        mStatusLabel.setStyle("-fx-text-fill: grey;");
        mTestButton.setDisable(true);

        // Send on background thread so UI doesn't freeze
        Thread t = new Thread(() -> {
            try
            {
                // Re-use the watchdog's send method via a temporary instance
                io.github.dsheirer.audio.broadcast.AudioActivityWatchdog watchdog =
                        mUserPreferences.getAudioActivityWatchdog();
                if(watchdog == null)
                {
                    throw new IllegalStateException("Watchdog not yet initialized");
                }
                watchdog.sendTestEmail();

                javafx.application.Platform.runLater(() -> {
                    mStatusLabel.setText("✓ Test email sent successfully!");
                    mStatusLabel.setStyle("-fx-text-fill: green;");
                    mTestButton.setDisable(false);
                });
            }
            catch (Exception ex)
            {
                LOGGER.error("Test email failed", ex);
                javafx.application.Platform.runLater(() -> {
                    mStatusLabel.setText("✗ Failed: " + ex.getMessage());
                    mStatusLabel.setStyle("-fx-text-fill: red;");
                    mTestButton.setDisable(false);
                });
            }
        }, "watchdog-test-email");
        t.setDaemon(true);
        t.start();
    }
}
