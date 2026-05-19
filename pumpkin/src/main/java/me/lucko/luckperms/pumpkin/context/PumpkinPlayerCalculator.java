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

package me.lucko.luckperms.pumpkin.context;

import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.context.ImmutableContextSetImpl;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.ImmutableContextSet;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Set;

public class PumpkinPlayerCalculator implements ContextCalculator<Player>, Listener {
    private final LPPumpkinPlugin plugin;
    private final boolean gamemode;
    private final boolean world;

    public PumpkinPlayerCalculator(LPPumpkinPlugin plugin, Set<String> disabled) {
        this.plugin = plugin;
        this.gamemode = !disabled.contains(DefaultContextKeys.GAMEMODE_KEY);
        this.world = !disabled.contains(DefaultContextKeys.WORLD_KEY);
    }

    @Override
    public void calculate(@NonNull Player subject, @NonNull ContextConsumer consumer) {
        if (this.world) {
            try {
                consumer.accept(DefaultContextKeys.WORLD_KEY, subject.getWorld().getName());
            } catch (UnsupportedOperationException e) {
                // PatchBukkit may not implement getWorld()
            }
        }

        if (this.gamemode) {
            try {
                consumer.accept(DefaultContextKeys.GAMEMODE_KEY, subject.getGameMode().name().toLowerCase());
            } catch (UnsupportedOperationException e) {
                // PatchBukkit may not implement getGameMode()
            }
        }
    }

    @Override
    public @NonNull ContextSet estimatePotentialContexts() {
        ImmutableContextSet.Builder builder = new ImmutableContextSetImpl.BuilderImpl();

        if (this.gamemode) {
            for (org.bukkit.GameMode mode : org.bukkit.GameMode.values()) {
                builder.add(DefaultContextKeys.GAMEMODE_KEY, mode.name().toLowerCase());
            }
        }

        if (this.world) {
            try {
                for (org.bukkit.World world : this.plugin.getBootstrap().getServer().getWorlds()) {
                    builder.add(DefaultContextKeys.WORLD_KEY, world.getName());
                }
            } catch (UnsupportedOperationException e) {
                // PatchBukkit does not implement getWorlds() yet
            }
        }

        return builder.build();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        this.plugin.getContextManager().signalContextUpdate(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        if (this.gamemode) {
            this.plugin.getContextManager().signalContextUpdate(e.getPlayer());
        }
    }
}
