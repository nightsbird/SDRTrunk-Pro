/*
 * *****************************************************************************
 * Copyright (C) 2026 Jeffrey Dunbar
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

package io.github.dsheirer.gui.playlist.streaming;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.mumble.MumbleConfiguration;
import io.github.dsheirer.gui.control.IntegerTextField;
import io.github.dsheirer.playlist.PlaylistManager;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import org.controlsfx.control.ToggleSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Mumble server streaming configuration editor.
 *
 * Fields:
 * - Format / Enabled toggle
 * - Name
 * - Host
 * - Port            (default 64738)
 * - Username        (bot display name on Mumble server)
 * - Password        (optional, blank for open servers)
 * - Channel         (e.g. Root/Scanner, blank = root)
 * - STT Enabled     (toggle to enable/disable Vosk speech-to-text)
 * - Vosk Model Path (path to model directory, Browse button included)
 */
public class MumbleEditor extends AbstractBroadcastEditor<MumbleConfiguration>
{
    private static final Logger mLog = LoggerFactory.getLogger(MumbleEditor.class);

    private TextField        mHostTextField;
    private IntegerTextField mPortTextField;
    private TextField        mUsernameTextField;
    private PasswordField    mPasswordField;
    private TextField        mChannelTextField;
    private ToggleSwitch     mVoskEnabledSwitch;
    private TextField        mVoskModelPathTextField;
    private Button           mVoskBrowseButton;
    private GridPane         mEditorPane;

    public MumbleEditor(PlaylistManager playlistManager)
    {
        super(playlistManager);
    }

    @Override
    public void setItem(MumbleConfiguration item)
    {
        super.setItem(item);

        boolean hasItem = item != null;

        getHostTextField().setDisable(!hasItem);
        getPortTextField().setDisable(!hasItem);
        getUsernameTextField().setDisable(!hasItem);
        getPasswordField().setDisable(!hasItem);
        getChannelTextField().setDisable(!hasItem);
        getVoskEnabledSwitch().setDisable(!hasItem);

        if(hasItem)
        {
            getHostTextField().setText(item.getHost());
            getPortTextField().set(item.getPort());
            getUsernameTextField().setText(item.getUsername());
            getPasswordField().setText(item.getPassword());
            getChannelTextField().setText(item.getChannel());
            getVoskEnabledSwitch().setSelected(item.isVoskEnabled());
            getVoskModelPathTextField().setText(item.getVoskModelPath());

            // Enable/disable path controls based on current toggle state
            updateVoskPathControls(item.isVoskEnabled());
        }
        else
        {
            getHostTextField().setText(null);
            getPortTextField().set(64738);
            getUsernameTextField().setText(null);
            getPasswordField().setText(null);
            getChannelTextField().setText(null);
            getVoskEnabledSwitch().setSelected(false);
            getVoskModelPathTextField().setText(null);
            updateVoskPathControls(false);
        }

        modifiedProperty().set(false);
    }

    /** Enable or disable the model path field and browse button based on the STT toggle. */
    private void updateVoskPathControls(boolean enabled)
    {
        getVoskModelPathTextField().setDisable(!enabled);
        getVoskBrowseButton().setDisable(!enabled);
    }

    @Override
    public void dispose() {}

    @Override
    public void save()
    {
        if(getItem() != null)
        {
            getItem().setHost(getHostTextField().getText());
            getItem().setPort(getPortTextField().get());
            getItem().setUsername(getUsernameTextField().getText());
            getItem().setPassword(getPasswordField().getText());
            getItem().setChannel(getChannelTextField().getText());
            getItem().setVoskEnabled(getVoskEnabledSwitch().isSelected());
            getItem().setVoskModelPath(getVoskModelPathTextField().getText());
        }
        super.save();
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.MUMBLE;
    }

    @Override
    protected GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10, 5, 10, 10));
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(5);

            int row = 0;

            // Row 0: Format + Enabled
            Label formatLabel = new Label("Format");
            GridPane.setHalignment(formatLabel, HPos.RIGHT);
            GridPane.setConstraints(formatLabel, 0, row);
            mEditorPane.getChildren().add(formatLabel);
            GridPane.setConstraints(getFormatField(), 1, row);
            mEditorPane.getChildren().add(getFormatField());

            Label enabledLabel = new Label("Enabled");
            GridPane.setHalignment(enabledLabel, HPos.RIGHT);
            GridPane.setConstraints(enabledLabel, 2, row);
            mEditorPane.getChildren().add(enabledLabel);
            GridPane.setConstraints(getEnabledSwitch(), 3, row);
            mEditorPane.getChildren().add(getEnabledSwitch());

            // Row 1: Name
            Label nameLabel = new Label("Name");
            GridPane.setHalignment(nameLabel, HPos.RIGHT);
            GridPane.setConstraints(nameLabel, 0, ++row);
            mEditorPane.getChildren().add(nameLabel);
            GridPane.setConstraints(getNameTextField(), 1, row);
            mEditorPane.getChildren().add(getNameTextField());

            // Row 2: Host
            Label hostLabel = new Label("Host");
            GridPane.setHalignment(hostLabel, HPos.RIGHT);
            GridPane.setConstraints(hostLabel, 0, ++row);
            mEditorPane.getChildren().add(hostLabel);
            GridPane.setConstraints(getHostTextField(), 1, row);
            mEditorPane.getChildren().add(getHostTextField());

            // Row 3: Port
            Label portLabel = new Label("Port");
            GridPane.setHalignment(portLabel, HPos.RIGHT);
            GridPane.setConstraints(portLabel, 0, ++row);
            mEditorPane.getChildren().add(portLabel);
            GridPane.setConstraints(getPortTextField(), 1, row);
            mEditorPane.getChildren().add(getPortTextField());

            // Row 4: Username
            Label usernameLabel = new Label("Username");
            GridPane.setHalignment(usernameLabel, HPos.RIGHT);
            GridPane.setConstraints(usernameLabel, 0, ++row);
            mEditorPane.getChildren().add(usernameLabel);
            GridPane.setConstraints(getUsernameTextField(), 1, row);
            mEditorPane.getChildren().add(getUsernameTextField());

            // Row 5: Password
            Label passwordLabel = new Label("Password");
            GridPane.setHalignment(passwordLabel, HPos.RIGHT);
            GridPane.setConstraints(passwordLabel, 0, ++row);
            mEditorPane.getChildren().add(passwordLabel);
            GridPane.setConstraints(getPasswordField(), 1, row);
            mEditorPane.getChildren().add(getPasswordField());
            Label passwordHint = new Label("(blank for open servers)");
            GridPane.setConstraints(passwordHint, 2, row);
            mEditorPane.getChildren().add(passwordHint);

            // Row 6: Channel
            Label channelLabel = new Label("Channel");
            GridPane.setHalignment(channelLabel, HPos.RIGHT);
            GridPane.setConstraints(channelLabel, 0, ++row);
            mEditorPane.getChildren().add(channelLabel);
            GridPane.setConstraints(getChannelTextField(), 1, row);
            mEditorPane.getChildren().add(getChannelTextField());
            Label channelHint = new Label("e.g. Root/Scanner (blank = root)");
            GridPane.setConstraints(channelHint, 2, row);
            mEditorPane.getChildren().add(channelHint);

            // Row 7: STT Enabled toggle
            Label voskEnabledLabel = new Label("Speech-to-Text");
            GridPane.setHalignment(voskEnabledLabel, HPos.RIGHT);
            GridPane.setConstraints(voskEnabledLabel, 0, ++row);
            mEditorPane.getChildren().add(voskEnabledLabel);
            GridPane.setConstraints(getVoskEnabledSwitch(), 1, row);
            mEditorPane.getChildren().add(getVoskEnabledSwitch());
            Label voskEnabledHint = new Label("Enable Vosk on-device STT");
            GridPane.setConstraints(voskEnabledHint, 2, row);
            mEditorPane.getChildren().add(voskEnabledHint);

            // Row 8: Vosk Model Path
            Label voskLabel = new Label("Vosk Model Path");
            GridPane.setHalignment(voskLabel, HPos.RIGHT);
            GridPane.setConstraints(voskLabel, 0, ++row);
            mEditorPane.getChildren().add(voskLabel);
            GridPane.setConstraints(getVoskModelPathTextField(), 1, row);
            mEditorPane.getChildren().add(getVoskModelPathTextField());
            GridPane.setConstraints(getVoskBrowseButton(), 2, row);
            mEditorPane.getChildren().add(getVoskBrowseButton());
        }
        return mEditorPane;
    }

    private TextField getHostTextField()
    {
        if(mHostTextField == null)
        {
            mHostTextField = new TextField();
            mHostTextField.setDisable(true);
            mHostTextField.setPromptText("hostname or IP address");
            mHostTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mHostTextField;
    }

    private IntegerTextField getPortTextField()
    {
        if(mPortTextField == null)
        {
            mPortTextField = new IntegerTextField();
            mPortTextField.setDisable(true);
            mPortTextField.set(64738);
            mPortTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mPortTextField;
    }

    private TextField getUsernameTextField()
    {
        if(mUsernameTextField == null)
        {
            mUsernameTextField = new TextField();
            mUsernameTextField.setDisable(true);
            mUsernameTextField.setPromptText("bot display name");
            mUsernameTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mUsernameTextField;
    }

    private PasswordField getPasswordField()
    {
        if(mPasswordField == null)
        {
            mPasswordField = new PasswordField();
            mPasswordField.setDisable(true);
            mPasswordField.textProperty().addListener(mEditorModificationListener);
        }
        return mPasswordField;
    }

    private TextField getChannelTextField()
    {
        if(mChannelTextField == null)
        {
            mChannelTextField = new TextField();
            mChannelTextField.setDisable(true);
            mChannelTextField.setPromptText("Root/ChannelName (blank = root)");
            mChannelTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mChannelTextField;
    }

    private ToggleSwitch getVoskEnabledSwitch()
    {
        if(mVoskEnabledSwitch == null)
        {
            mVoskEnabledSwitch = new ToggleSwitch();
            mVoskEnabledSwitch.setDisable(true);
            mVoskEnabledSwitch.selectedProperty().addListener((observable, oldValue, newValue) -> {
                // Enable/disable the path controls live as the toggle changes
                updateVoskPathControls(newValue);
                modifiedProperty().set(true);
            });
        }
        return mVoskEnabledSwitch;
    }

    private TextField getVoskModelPathTextField()
    {
        if(mVoskModelPathTextField == null)
        {
            mVoskModelPathTextField = new TextField();
            mVoskModelPathTextField.setDisable(true);
            mVoskModelPathTextField.setPromptText("path to vosk model folder");
            mVoskModelPathTextField.setPrefWidth(300);
            mVoskModelPathTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mVoskModelPathTextField;
    }

    private Button getVoskBrowseButton()
    {
        if(mVoskBrowseButton == null)
        {
            mVoskBrowseButton = new Button("Browse...");
            mVoskBrowseButton.setDisable(true);
            mVoskBrowseButton.setOnAction(event -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Select Vosk Model Directory");

                String current = getVoskModelPathTextField().getText();
                if(current != null && !current.isBlank())
                {
                    File currentDir = new File(current);
                    if(currentDir.exists()) chooser.setInitialDirectory(currentDir.getParentFile());
                }

                File selected = chooser.showDialog(mVoskBrowseButton.getScene().getWindow());
                if(selected != null)
                {
                    getVoskModelPathTextField().setText(selected.getAbsolutePath());
                    modifiedProperty().set(true);
                }
            });
        }
        return mVoskBrowseButton;
    }
}
