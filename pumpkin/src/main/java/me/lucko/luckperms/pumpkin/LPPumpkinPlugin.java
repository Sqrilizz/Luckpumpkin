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

package me.lucko.luckperms.pumpkin;

import me.lucko.luckperms.common.api.LuckPermsApiProvider;
import me.lucko.luckperms.common.calculator.CalculatorFactory;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.generic.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.dependencies.Dependency;
import me.lucko.luckperms.common.event.AbstractEventBus;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.StandardGroupManager;
import me.lucko.luckperms.common.model.manager.track.StandardTrackManager;
import me.lucko.luckperms.common.model.manager.user.StandardUserManager;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.plugin.util.AbstractConnectionListener;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.pumpkin.calculator.PumpkinCalculatorFactory;
import me.lucko.luckperms.pumpkin.context.PumpkinContextManager;
import me.lucko.luckperms.pumpkin.context.PumpkinPlayerCalculator;
import me.lucko.luckperms.pumpkin.inject.LuckPermsPermissible;
import me.lucko.luckperms.pumpkin.inject.PumpkinPermissibleInjector;
import me.lucko.luckperms.pumpkin.inject.server.PumpkinDefaultsMap;
import me.lucko.luckperms.pumpkin.inject.server.PumpkinPermissionMap;
import me.lucko.luckperms.pumpkin.inject.server.PumpkinServerMapInjector;
import me.lucko.luckperms.pumpkin.inject.server.PumpkinSubscriptionMap;
import me.lucko.luckperms.pumpkin.listeners.PumpkinConnectionListener;
import me.lucko.luckperms.pumpkin.listeners.PumpkinPlatformListener;
import me.lucko.luckperms.pumpkin.messaging.PumpkinMessagingFactory;
import me.lucko.luckperms.pumpkin.vault.PumpkinVaultHookManager;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * LuckPerms implementation for PumpkinMC via PatchBukkit.
 */
public class LPPumpkinPlugin extends AbstractLuckPermsPlugin {
    private final LPPumpkinBootstrap bootstrap;

    private PumpkinSenderFactory senderFactory;
    private PumpkinConnectionListener connectionListener;
    private PumpkinCommandExecutor commandManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private PumpkinContextManager contextManager;
    private PumpkinPermissionMap permissionMap;
    private PumpkinDefaultsMap defaultPermissionMap;
    private PumpkinSubscriptionMap subscriptionMap;
    private PumpkinVaultHookManager vaultHookManager = null;

    public LPPumpkinPlugin(LPPumpkinBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public LPPumpkinBootstrap getBootstrap() {
        return this.bootstrap;
    }

    public JavaPlugin getLoader() {
        return this.bootstrap.getLoader();
    }

    @Override
    protected void setupSenderFactory() {
        this.senderFactory = new PumpkinSenderFactory(this);
    }

    @Override
    protected Set<Dependency> getGlobalDependencies() {
        return super.getGlobalDependencies();
    }

    @Override
    protected ConfigurationAdapter provideConfigurationAdapter() {
        return new PumpkinConfigAdapter(this, resolveConfig("config.yml").toFile());
    }

    @Override
    protected void registerPlatformListeners() {
        this.connectionListener = new PumpkinConnectionListener(this);
        safeRegisterEvents(this.connectionListener);
        safeRegisterEvents(new PumpkinPlatformListener(this));
    }

    private void safeRegisterEvents(org.bukkit.event.Listener listener) {
        try {
            this.bootstrap.getServer().getPluginManager().registerEvents(listener, this.bootstrap.getLoader());
        } catch (UnsupportedOperationException e) {
            getLogger().warn("PatchBukkit gap during event registration: " + e.getMessage() + ". Some events may not fire.");
        }
    }

    @Override
    protected MessagingFactory<?> provideMessagingFactory() {
        return new PumpkinMessagingFactory(this);
    }

    @Override
    protected void registerCommands() {
        PluginCommand command = this.bootstrap.getLoader().getCommand("luckperms");
        if (command == null) {
            getLogger().severe("Unable to register /luckperms command with the server");
            return;
        }

        this.commandManager = new PumpkinCommandExecutor(this, command);
        this.commandManager.register();
    }

    @Override
    protected void setupManagers() {
        this.userManager = new StandardUserManager(this);
        this.groupManager = new StandardGroupManager(this);
        this.trackManager = new StandardTrackManager(this);
    }

    @Override
    protected CalculatorFactory provideCalculatorFactory() {
        return new PumpkinCalculatorFactory(this);
    }

    @Override
    protected void setupContextManager() {
        this.contextManager = new PumpkinContextManager(this);

        PumpkinPlayerCalculator playerCalculator = new PumpkinPlayerCalculator(this, getConfiguration().get(ConfigKeys.DISABLED_CONTEXTS));
        safeRegisterEvents(playerCalculator);
        this.contextManager.registerCalculator(playerCalculator);
    }

    @Override
    protected void setupPlatformHooks() {
        // Inject LP custom maps into PatchBukkit's PluginManager
        Runnable[] injectors = new Runnable[]{
                () -> { try { PumpkinServerMapInjector.inject(this); } catch (Exception e) { getLogger().warn("PatchBukkit gap during server map injection: " + e.getMessage()); } }
        };

        for (Runnable injector : injectors) {
            injector.run();
            this.bootstrap.getServer().getScheduler().runTaskLaterAsynchronously(this.bootstrap.getLoader(), injector, 1);
        }

        // Provide vault support if available
        tryVaultHook(false);
    }

    @Override
    protected AbstractEventBus<?> provideEventBus(LuckPermsApiProvider apiProvider) {
        return new PumpkinEventBus(this, apiProvider);
    }

    public void tryVaultHook(boolean force) {
        if (this.vaultHookManager != null) {
            return;
        }

        try {
            if (force || this.bootstrap.getServer().getPluginManager().isPluginEnabled("Vault")) {
                this.vaultHookManager = new PumpkinVaultHookManager(this);
                this.vaultHookManager.hook();
                getLogger().info("Registered Vault permission & chat hook.");
            }
        } catch (Exception e) {
            this.vaultHookManager = null;
            getLogger().severe("Error occurred whilst hooking into Vault.", e);
        }
    }

    @Override
    protected void registerApiOnPlatform(LuckPerms api) {
        this.bootstrap.getServer().getServicesManager().register(LuckPerms.class, api, this.bootstrap.getLoader(), ServicePriority.Normal);
    }

    @Override
    protected void performFinalSetup() {
        // register permissions
        try {
            PluginManager pluginManager = this.bootstrap.getServer().getPluginManager();
            PermissionDefault permDefault = getConfiguration().get(ConfigKeys.COMMANDS_ALLOW_OP) ? PermissionDefault.OP : PermissionDefault.FALSE;

            for (CommandPermission permission : CommandPermission.values()) {
                Permission perm = new Permission(permission.getPermission(), permDefault);
                pluginManager.removePermission(perm);
                pluginManager.addPermission(perm);
            }
        } catch (UnsupportedOperationException e) {
            getLogger().warn("PatchBukkit gap during permission registration: " + e.getMessage());
        }

        // remove all operators on startup if they're disabled
        if (!getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
            this.bootstrap.getServer().getScheduler().runTaskAsynchronously(this.bootstrap.getLoader(), () -> {
                try {
                    for (OfflinePlayer player : this.bootstrap.getServer().getOperators()) {
                        player.setOp(false);
                    }
                } catch (UnsupportedOperationException e) {
                    getLogger().warn("PatchBukkit gap: getOperators not implemented");
                }
            });
        }

        // register autoop listener
        if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
            getApiProvider().getEventBus().subscribe(new PumpkinAutoOpListener(this));
        }

        // Load any online users (in the case of a reload) and inject permissible
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            this.bootstrap.getScheduler().executeAsync(() -> {
                try {
                    User user = this.connectionListener.loadUser(player.getUniqueId(), player.getName());
                    if (user != null) {
                        this.bootstrap.getScheduler().executeSync(() -> {
                            try {
                                // Re-inject LP permissible for already-online players
                                if (PumpkinPermissibleInjector.get(player) == null) {
                                    LuckPermsPermissible lpPerm = new LuckPermsPermissible(player, user, this);
                                    PumpkinPermissibleInjector.inject(player, lpPerm, getLogger());
                                }
                                this.contextManager.signalContextUpdate(player);
                            } catch (Throwable t) {
                                getLogger().severe("Exception thrown when setting up permissions for " +
                                        player.getUniqueId() + " - " + player.getName(), t);
                            }
                        });
                    }
                } catch (Exception e) {
                    getLogger().severe("Exception occurred whilst loading data for " +
                            player.getUniqueId() + " - " + player.getName(), e);
                }
            });
        }
    }

    @Override
    protected void removePlatformHooks() {
        // Uninject permissible and cleanup for online players
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            try {
                PumpkinPermissibleInjector.uninject(player, false);
            } catch (Exception e) {
                getLogger().severe("Exception thrown when uninjecting LuckPermsPermissible for " +
                        player.getUniqueId() + " - " + player.getName(), e);
            }

            if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
                player.setOp(false);
            }

            final User user = getUserManager().getIfLoaded(player.getUniqueId());
            if (user != null) {
                user.getCachedData().invalidate();
                getUserManager().unload(user.getUniqueId());
            }
        }

        // Uninject custom server maps
        try {
            PumpkinServerMapInjector.uninject(this);
        } catch (Exception e) {
            getLogger().warn("PatchBukkit gap during server map uninjection: " + e.getMessage());
        }

        if (this.vaultHookManager != null) {
            this.vaultHookManager.unhook();
        }
    }

    @Override
    public Optional<QueryOptions> getQueryOptionsForUser(User user) {
        return this.bootstrap.getPlayer(user.getUniqueId()).map(player -> this.contextManager.getQueryOptions(player));
    }

    @Override
    public Stream<Sender> getOnlineSenders() {
        List<Player> players = new ArrayList<>(this.bootstrap.getServer().getOnlinePlayers());
        return Stream.concat(
                Stream.of(getConsoleSender()),
                players.stream().map(p -> getSenderFactory().wrap(p))
        );
    }

    @Override
    public Sender getConsoleSender() {
        return getSenderFactory().wrap(this.bootstrap.getServer().getConsoleSender());
    }

    public PumpkinSenderFactory getSenderFactory() {
        return this.senderFactory;
    }

    @Override
    public AbstractConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    @Override
    public PumpkinCommandExecutor getCommandManager() {
        return this.commandManager;
    }

    @Override
    public StandardUserManager getUserManager() {
        return this.userManager;
    }

    @Override
    public StandardGroupManager getGroupManager() {
        return this.groupManager;
    }

    @Override
    public StandardTrackManager getTrackManager() {
        return this.trackManager;
    }

    @Override
    public PumpkinContextManager getContextManager() {
        return this.contextManager;
    }

    public PumpkinPermissionMap getPermissionMap() {
        return this.permissionMap;
    }

    public void setPermissionMap(PumpkinPermissionMap permissionMap) {
        this.permissionMap = permissionMap;
    }

    public PumpkinDefaultsMap getDefaultPermissionMap() {
        return this.defaultPermissionMap;
    }

    public void setDefaultPermissionMap(PumpkinDefaultsMap defaultPermissionMap) {
        this.defaultPermissionMap = defaultPermissionMap;
    }

    public PumpkinSubscriptionMap getSubscriptionMap() {
        return this.subscriptionMap;
    }

    public void setSubscriptionMap(PumpkinSubscriptionMap subscriptionMap) {
        this.subscriptionMap = subscriptionMap;
    }
}
