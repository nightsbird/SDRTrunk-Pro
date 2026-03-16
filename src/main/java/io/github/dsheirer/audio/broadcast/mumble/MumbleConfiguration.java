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

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Broadcast configuration for Mumble server streaming.
 *
 * Mumble uses the Mumble protocol over TCP (with optional TLS) to stream
 * Opus-encoded audio to a Mumble server channel. Audio is sent as Mumble
 * UDPTunnel voice packets carrying Opus frames.
 *
 * Configuration fields:
 * - Host:     Mumble server hostname or IP address
 * - Port:     Mumble server port (default: 64738)
 * - Username: The bot/client display name on the Mumble server
 * - Password: Optional server password (leave blank for open servers)
 * - Channel:  Target channel path (e.g. "Root/Scanner" — blank = root)
 */
public class MumbleConfiguration extends BroadcastConfiguration
{
    private static final int DEFAULT_MUMBLE_PORT = 64738;

    private StringProperty mChannel = new SimpleStringProperty();
    private StringProperty mUsername = new SimpleStringProperty();

    /**
     * Default constructor for Jackson XML deserialization.
     */
    public MumbleConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    /**
     * Public constructor.
     *
     * @param format audio format (audio will be re-encoded to Opus for Mumble)
     */
    public MumbleConfiguration(BroadcastFormat format)
    {
        super(format);
        mPort.set(DEFAULT_MUMBLE_PORT);

        // Rebind validity: host + username required; port > 0
        mValid.unbind();
        mValid.bind(Bindings.and(
            Bindings.isNotEmpty(mHost),
            Bindings.isNotEmpty(mUsername)
        ));
    }

    // ========================================================================
    // Channel path (optional – blank means root)
    // ========================================================================

    public StringProperty channelProperty()
    {
        return mChannel;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "channel")
    public String getChannel()
    {
        return mChannel.get();
    }

    public void setChannel(String channel)
    {
        mChannel.set(channel);
    }

    // ========================================================================
    // Username (display name on Mumble server)
    // ========================================================================

    public StringProperty usernameProperty()
    {
        return mUsername;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "username")
    public String getUsername()
    {
        return mUsername.get();
    }

    public void setUsername(String username)
    {
        mUsername.set(username);
    }

    // ========================================================================
    // BroadcastConfiguration overrides
    // ========================================================================

    @JacksonXmlProperty(isAttribute = true, localName = "type",
        namespace = "http://www.w3.org/2001/XMLSchema-instance")
    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.MUMBLE;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        MumbleConfiguration copy = new MumbleConfiguration();
        copy.setHost(getHost());
        copy.setPort(getPort());
        copy.setUsername(getUsername());
        copy.setPassword(getPassword());
        copy.setChannel(getChannel());
        copy.setName(getName());
        return copy;
    }
}
