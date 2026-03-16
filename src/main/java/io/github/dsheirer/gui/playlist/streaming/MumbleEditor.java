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

package io.github.dsheirer.gui.playlist.streaming;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.mumble.MumbleConfiguration;
import io.github.dsheirer.gui.control.IntegerTextField;
import io.github.dsheirer.playlist.PlaylistManager;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mumble server streaming configuration editor.
 *
 * Fields:
 * - Format:   Read-only display of broadcast type
 * - Enabled:  Toggle to enable/disable this stream
 * - Name:     Display name for this stream configuration
 * - Host:     Mumble server hostname or IP address
 * - Port:     Mumble server port (default 64738)
 * - Username: Bot/client display name on the Mumble server
 * - Password: Optional server password
 * - Channel:  Target channel path (e.g. "Root/Scanner" — blank = root)
 */
public class MumbleEditor extends AbstractBroadcastEditor<MumbleConfiguration>
{
    private static final Logger mLog = LoggerFactory.getLogger(MumbleEditor.class);

    private TextField        mHostTextField;
    private IntegerTextField mPortTextField;
    private TextField        mUsernameTextField;
    private PasswordField    mPasswordField;
    private TextField        mChannelTextField;
    private GridPane         mEditorPane;

    /**
     * Constructs an instance.
     * @param playlistManager for accessing the broadcast model
     */
    public MumbleEditor(PlaylistManager playlistManager)
    {
        super(playlistManager);
    }

    @Override
    public void setItem(MumbleConfiguration item)
    {
        super.setItem(item);

        getHostTextField().setDisable(item == null);
        getPortTextField().setDisable(item == null);
        getUsernameTextField().setDisable(item == null);
        getPasswordField().setDisable(item == null);
        getChannelTextField().setDisable(item == null);

        if(item != null)
        {
            getHostTextField().setText(item.getHost());
            getPortTextField().set(item.getPort());
            getUsernameTextField().setText(item.getUsername());
            getPasswordField().setText(item.getPassword());
            getChannelTextField().setText(item.getChannel());
        }
        else
        {
            getHostTextField().setText(null);
            getPortTextField().set(64738);
            getUsernameTextField().setText(null);
            getPasswordField().setText(null);
            getChannelTextField().setText(null);
        }

        modifiedProperty().set(false);
    }

    @Override
    public void dispose()
    {
    }

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
            GridPane.setHalignment(passwordHint, HPos.LEFT);
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
            GridPane.setHalignment(channelHint, HPos.LEFT);
            GridPane.setConstraints(channelHint, 2, row);
            mEditorPane.getChildren().add(channelHint);
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
}
