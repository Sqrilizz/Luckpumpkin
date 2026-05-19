/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.pumpkin.messaging;

import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import net.luckperms.api.messenger.IncomingMessageConsumer;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.messenger.message.OutgoingMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;

public class PumpkinPluginMessageMessenger implements Messenger, PluginMessageListener {
    private static final String CHANNEL = "luckperms:update";

    private final LPPumpkinPlugin plugin;
    private final IncomingMessageConsumer consumer;

    public PumpkinPluginMessageMessenger(LPPumpkinPlugin plugin, IncomingMessageConsumer consumer) {
        this.plugin = plugin;
        this.consumer = consumer;
    }

    public void init() {
        try {
            this.plugin.getBootstrap().getServer().getMessenger().registerOutgoingPluginChannel(this.plugin.getLoader(), CHANNEL);
            this.plugin.getBootstrap().getServer().getMessenger().registerIncomingPluginChannel(this.plugin.getLoader(), CHANNEL, this);
        } catch (UnsupportedOperationException e) {
            this.plugin.getLogger().warn("Plugin messaging is not supported by PatchBukkit yet. Cross-server sync disabled.");
        }
    }

    @Override
    public void sendOutgoingMessage(@NonNull OutgoingMessage outgoingMessage) {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(byteOut);

        try {
            out.writeUTF(outgoingMessage.asEncodedString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] data = byteOut.toByteArray();

        try {
            Collection<? extends Player> players = this.plugin.getBootstrap().getServer().getOnlinePlayers();
            Player first = players.isEmpty() ? null : players.iterator().next();
            if (first != null) {
                first.sendPluginMessage(this.plugin.getLoader(), CHANNEL, data);
            }
        } catch (UnsupportedOperationException e) {
            // PatchBukkit does not yet support sendPluginMessage
        }
    }

    @Override
    public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte @NonNull [] message) {
        if (!channel.equals(CHANNEL)) {
            return;
        }

        ByteArrayInputStream byteIn = new ByteArrayInputStream(message);
        DataInputStream in = new DataInputStream(byteIn);

        String msg;
        try {
            msg = in.readUTF();
        } catch (IOException e) {
            return;
        }

        this.consumer.consumeIncomingMessageAsString(msg);
    }

    @Override
    public void close() {
        try {
            this.plugin.getBootstrap().getServer().getMessenger().unregisterOutgoingPluginChannel(this.plugin.getLoader(), CHANNEL);
            this.plugin.getBootstrap().getServer().getMessenger().unregisterIncomingPluginChannel(this.plugin.getLoader(), CHANNEL, this);
        } catch (UnsupportedOperationException e) {
            // PatchBukkit does not yet support plugin messaging
        }
    }
}
