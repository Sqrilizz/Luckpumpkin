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

package me.lucko.luckperms.pumpkin.listeners;

import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.locale.Message;
import me.lucko.luckperms.common.locale.TranslationManager;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import me.lucko.luckperms.pumpkin.inject.LuckPermsPermissible;
import me.lucko.luckperms.pumpkin.inject.PumpkinPermissibleInjector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PumpkinConnectionListener extends AbstractConnectionListener implements Listener {
    private final LPPumpkinPlugin plugin;

    private final Set<UUID> deniedAsyncLogin = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> deniedLogin = Collections.synchronizedSet(new HashSet<>());

    public PumpkinConnectionListener(LPPumpkinPlugin plugin) {
        super(plugin);
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent e) {
        try {
            this.plugin.getBootstrap().getEnableLatch().await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

        if (this.plugin.getConfiguration().get(ConfigKeys.DEBUG_LOGINS)) {
            this.plugin.getLogger().info("Processing pre-login for " + e.getUniqueId() + " - " + e.getName());
        }

        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            this.plugin.getLogger().info("Another plugin has cancelled the connection for " + e.getUniqueId() + " - " + e.getName() + ". No permissions data will be loaded.");
            this.deniedAsyncLogin.add(e.getUniqueId());
            return;
        }

        try {
            User user = loadUser(e.getUniqueId(), e.getName());
            recordConnection(e.getUniqueId());
            this.plugin.getEventDispatcher().dispatchPlayerLoginProcess(e.getUniqueId(), e.getName(), user);
        } catch (Exception ex) {
            this.plugin.getLogger().severe("Exception occurred whilst loading data for " + e.getUniqueId() + " - " + e.getName(), ex);

            this.deniedAsyncLogin.add(e.getUniqueId());

            Component reason = TranslationManager.render(Message.LOADING_DATABASE_ERROR.build());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, LegacyComponentSerializer.legacySection().serialize(reason));
            this.plugin.getEventDispatcher().dispatchPlayerLoginProcess(e.getUniqueId(), e.getName(), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPreLoginMonitor(AsyncPlayerPreLoginEvent e) {
        if (this.deniedAsyncLogin.remove(e.getUniqueId())) {
            if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                this.plugin.getLogger().severe("Player connection was re-allowed for " + e.getUniqueId());
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent e) {
        final Player player = e.getPlayer();

        if (this.plugin.getConfiguration().get(ConfigKeys.DEBUG_LOGINS)) {
            this.plugin.getLogger().info("Processing login for " + player.getUniqueId() + " - " + player.getName());
        }

        final User user = this.plugin.getUserManager().getIfLoaded(player.getUniqueId());

        if (user == null) {
            this.deniedLogin.add(player.getUniqueId());

            if (!getUniqueConnections().contains(player.getUniqueId())) {
                this.plugin.getLogger().warn("User " + player.getUniqueId() + " - " + player.getName() +
                        " doesn't have data pre-loaded, they have never been processed during pre-login in this session." +
                        " - denying login.");
            } else {
                this.plugin.getLogger().warn("User " + player.getUniqueId() + " - " + player.getName() +
                        " doesn't currently have data pre-loaded, but they have been processed before in this session." +
                        " - denying login.");
            }

            Component reason = TranslationManager.render(Message.LOADING_STATE_ERROR.build());
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, LegacyComponentSerializer.legacySection().serialize(reason));
            return;
        }

        // Inject LuckPerms permissible so player.hasPermission() goes through LP
        try {
            LuckPermsPermissible lpPermissible = new LuckPermsPermissible(player, user, this.plugin);
            PumpkinPermissibleInjector.inject(player, lpPermissible, this.plugin.getLogger());
        } catch (Exception ex) {
            this.plugin.getLogger().severe("Exception thrown when injecting LuckPermsPermissible for " +
                    player.getUniqueId() + " - " + player.getName(), ex);
        }

        this.plugin.getContextManager().signalContextUpdate(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLoginMonitor(PlayerLoginEvent e) {
        if (this.deniedLogin.remove(e.getPlayer().getUniqueId())) {
            if (e.getResult() == PlayerLoginEvent.Result.ALLOWED) {
                this.plugin.getLogger().severe("Player connection was re-allowed for " + e.getPlayer().getUniqueId());
                e.disallow(PlayerLoginEvent.Result.KICK_OTHER, "");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        final Player player = e.getPlayer();
        handleDisconnect(player.getUniqueId());

        // Uninject LuckPerms permissible (use dummy=true since player is quitting)
        try {
            PumpkinPermissibleInjector.uninject(player, true);
        } catch (Exception ex) {
            this.plugin.getLogger().severe("Exception thrown when uninjecting LuckPermsPermissible for " +
                    player.getUniqueId() + " - " + player.getName(), ex);
        }

        this.plugin.getContextManager().removeSupplier(player.getUniqueId());

        if (this.plugin.getConfiguration().get(ConfigKeys.AUTO_OP)) {
            player.setOp(false);
        }
    }

}
