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

package me.lucko.luckperms.pumpkin.vault;

import com.google.common.base.Preconditions;
import me.lucko.luckperms.bukkit.vault.AbstractVaultPermission;
import me.lucko.luckperms.common.cacheddata.result.TristateResult;
import me.lucko.luckperms.common.cacheddata.type.MonitoredMetaCache;
import me.lucko.luckperms.common.cacheddata.type.PermissionCache;
import me.lucko.luckperms.common.calculator.processor.DirectProcessor;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.LuckPermsConfiguration;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.HolderType;
import me.lucko.luckperms.common.model.PermissionHolder;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.GroupManager;
import me.lucko.luckperms.common.node.factory.NodeBuilders;
import me.lucko.luckperms.common.node.types.Inheritance;
import me.lucko.luckperms.common.query.QueryOptionsImpl;
import me.lucko.luckperms.common.util.UniqueIdType;
import me.lucko.luckperms.common.util.Uuids;
import me.lucko.luckperms.common.verbose.event.CheckOrigin;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import me.lucko.luckperms.pumpkin.context.PumpkinContextManager;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.query.Flag;
import net.luckperms.api.query.QueryOptions;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class PumpkinVaultPermission extends AbstractVaultPermission {

    private final LPPumpkinPlugin plugin;

    public PumpkinVaultPermission(LPPumpkinPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "LuckPerms";
    }

    @Override
    protected String convertWorld(String world) {
        return isIgnoreWorld() ? null : super.convertWorld(world);
    }

    @Override
    public UUID lookupUuid(String player) {
        Objects.requireNonNull(player, "player");

        Player onlinePlayer = this.plugin.getBootstrap().getServer().getPlayerExact(player);
        if (onlinePlayer != null) {
            return onlinePlayer.getUniqueId();
        }

        UUID uuid = Uuids.parse(player);
        if (uuid != null) {
            return uuid;
        }

        if (!this.plugin.getBootstrap().isServerStarting() && this.plugin.getBootstrap().getServer().isPrimaryThread() && !this.plugin.getConfiguration().get(ConfigKeys.VAULT_UNSAFE_LOOKUPS)) {
            throw new RuntimeException("Vault lookups by username from the main thread are unsafe. Player '" + player + "' is not online.");
        }

        uuid = this.plugin.lookupUniqueId(player).orElse(null);

        if (uuid == null) {
            throw new IllegalArgumentException("Unable to find a UUID for player '" + player + "'.");
        }

        return uuid;
    }

    public PermissionHolder lookupUser(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");

        User user = this.plugin.getUserManager().getIfLoaded(uuid);
        if (user != null) {
            return user;
        }

        if (UniqueIdType.determineType(uuid, this.plugin).getType().equals("npc")) {
            String npcGroupName = this.plugin.getConfiguration().get(ConfigKeys.VAULT_NPC_GROUP);
            Group npcGroup = this.plugin.getGroupManager().getIfLoaded(npcGroupName);
            if (npcGroup == null) {
                npcGroup = this.plugin.getGroupManager().getIfLoaded(GroupManager.DEFAULT_GROUP_NAME);
                if (npcGroup == null) {
                    throw new IllegalStateException("unable to get default group");
                }
            }
            return npcGroup;
        }

        if (!this.plugin.getBootstrap().isServerStarting() && this.plugin.getBootstrap().getServer().isPrimaryThread() && !this.plugin.getConfiguration().get(ConfigKeys.VAULT_UNSAFE_LOOKUPS)) {
            throw new RuntimeException("Vault lookups by UUID from the main thread are unsafe. UUID '" + uuid + "' is not loaded.");
        }

        return this.plugin.getStorage().loadUser(uuid, null).join();
    }

    @Override
    public String[] getGroups() {
        return this.plugin.getGroupManager().getAll().values().stream()
                .map(this::groupName)
                .toArray(String[]::new);
    }

    @Override
    public boolean userHasPermission(String world, UUID uuid, String permission) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(permission, "permission");

        PermissionHolder user = lookupUser(uuid);
        QueryOptions queryOptions = getQueryOptions(uuid, world);
        PermissionCache permissionData = user.getCachedData().getPermissionData(queryOptions);
        return permissionData.checkPermission(permission, CheckOrigin.THIRD_PARTY_API).result().asBoolean();
    }

    @Override
    public boolean userAddPermission(String world, UUID uuid, String permission) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(permission, "permission");

        PermissionHolder user = lookupUser(uuid);
        if (user instanceof Group) {
            throw new UnsupportedOperationException("Unable to modify the permissions of NPC players");
        }
        return holderAddPermission(user, permission, world, DataType.NORMAL);
    }

    @Override
    public boolean userRemovePermission(String world, UUID uuid, String permission) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(permission, "permission");

        PermissionHolder user = lookupUser(uuid);
        if (user instanceof Group) {
            throw new UnsupportedOperationException("Unable to modify the permissions of NPC players");
        }
        return holderRemovePermission(user, permission, world, DataType.NORMAL);
    }

    @Override
    public boolean userAddTransient(String world, UUID uuid, String permission) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(permission, "permission");

        PermissionHolder user = lookupUser(uuid);
        if (user instanceof Group) {
            throw new UnsupportedOperationException("Unable to modify the permissions of NPC players");
        }
        return holderAddPermission(user, permission, world, DataType.TRANSIENT);
    }

    @Override
    public boolean userRemoveTransient(String world, UUID uuid, String permission) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(permission, "permission");

        PermissionHolder user = lookupUser(uuid);
        if (user instanceof Group) {
            throw new UnsupportedOperationException("Unable to modify the permissions of NPC players");
        }
        return holderRemovePermission(user, permission, world, DataType.TRANSIENT);
    }

    @Override
    public boolean userInGroup(String world, UUID uuid, String group) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(group, "group");

        PermissionHolder user = lookupUser(uuid);
        QueryOptions queryOptions = getQueryOptions(uuid, world);
        PermissionCache permissionData = user.getCachedData().getPermissionData(queryOptions);

        TristateResult result = permissionData.checkPermission(Inheritance.key(rewriteGroupName(group)), CheckOrigin.THIRD_PARTY_API);
        return result.processorClass() == DirectProcessor.class && result.result().asBoolean();
    }

    @Override
    public boolean userAddGroup(String world, UUID uuid, String group) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(group, "group");
        return checkGroupExists(group) && userAddPermission(world, uuid, Inheritance.key(rewriteGroupName(group)));
    }

    @Override
    public boolean userRemoveGroup(String world, UUID uuid, String group) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(group, "group");
        return checkGroupExists(group) && userRemovePermission(world, uuid, Inheritance.key(rewriteGroupName(group)));
    }

    @Override
    public String[] userGetGroups(String world, UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");

        PermissionHolder user = lookupUser(uuid);
        QueryOptions queryOptions = getQueryOptions(uuid, world);

        return user.getOwnNodes(NodeType.INHERITANCE, queryOptions).stream()
                .map(n -> {
                    Group group = this.plugin.getGroupManager().getIfLoaded(n.getGroupName());
                    return group != null ? groupName(group) : n.getGroupName();
                })
                .toArray(String[]::new);
    }

    @Override
    public String userGetPrimaryGroup(String world, UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");

        PermissionHolder user = lookupUser(uuid);
        if (user instanceof Group) {
            return this.plugin.getConfiguration().get(ConfigKeys.VAULT_NPC_GROUP);
        }

        QueryOptions queryOptions = getQueryOptions(uuid, world);
        MonitoredMetaCache metaData = user.getCachedData().getMetaData(queryOptions);
        String value = metaData.getPrimaryGroup(CheckOrigin.THIRD_PARTY_API);
        if (value == null) {
            return null;
        }

        Group group = getGroup(value);
        return group != null ? groupName(group) : value;
    }

    @Override
    public boolean groupHasPermission(String world, String name, String permission) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permission, "permission");

        Group group = getGroup(name);
        if (group == null) {
            return false;
        }

        QueryOptions queryOptions = getQueryOptions(null, world);
        PermissionCache permissionData = group.getCachedData().getPermissionData(queryOptions);
        return permissionData.checkPermission(permission, CheckOrigin.THIRD_PARTY_API).result().asBoolean();
    }

    @Override
    public boolean groupAddPermission(String world, String name, String permission) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permission, "permission");

        Group group = getGroup(name);
        if (group == null) {
            return false;
        }

        return holderAddPermission(group, permission, world, DataType.NORMAL);
    }

    @Override
    public boolean groupRemovePermission(String world, String name, String permission) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permission, "permission");

        Group group = getGroup(name);
        if (group == null) {
            return false;
        }

        return holderRemovePermission(group, permission, world, DataType.NORMAL);
    }

    private Group getGroup(String name) {
        return this.plugin.getGroupManager().getByDisplayName(name);
    }

    private String groupName(Group group) {
        if (this.plugin.getConfiguration().get(ConfigKeys.VAULT_GROUP_USE_DISPLAYNAMES)) {
            return group.getPlainDisplayName();
        } else {
            return group.getName();
        }
    }

    private boolean checkGroupExists(String group) {
        return this.plugin.getGroupManager().getByDisplayName(group) != null;
    }

    private String rewriteGroupName(String name) {
        Group group = this.plugin.getGroupManager().getByDisplayName(name);
        if (group != null) {
            return group.getName();
        }
        return name;
    }

    QueryOptions getQueryOptions(@Nullable UUID uuid, @Nullable String world) {
        ContextSet context;

        Player player = Optional.ofNullable(uuid).flatMap(u -> this.plugin.getBootstrap().getPlayer(u)).orElse(null);
        if (player != null) {
            context = this.plugin.getContextManager().getContext(player);
        } else {
            context = this.plugin.getContextManager().getStaticContext();
        }

        String playerWorld = player == null ? null : player.getWorld().getName();

        if (world != null && !world.isEmpty() && !world.equalsIgnoreCase(playerWorld)) {
            MutableContextSet mutContext = context.mutableCopy();
            context = mutContext;
            mutContext.removeAll(DefaultContextKeys.WORLD_KEY);
            mutContext.add(DefaultContextKeys.WORLD_KEY, world.toLowerCase(Locale.ROOT));
        }

        if (useVaultServer()) {
            MutableContextSet mutContext = context instanceof MutableContextSet ? (MutableContextSet) context : context.mutableCopy();
            context = mutContext;
            mutContext.remove(DefaultContextKeys.SERVER_KEY, getServer());
            if (!getVaultServer().equals("global")) {
                mutContext.add(DefaultContextKeys.SERVER_KEY, getVaultServer());
            }
        }

        boolean op = false;
        if (player != null) {
            op = player.isOp();
        } else if (uuid != null && UniqueIdType.determineType(uuid, this.plugin).getType().equals("npc")) {
            op = this.plugin.getConfiguration().get(ConfigKeys.VAULT_NPC_OP_STATUS);
        }

        QueryOptions.Builder builder = QueryOptionsImpl.DEFAULT_CONTEXTUAL.toBuilder();
        builder.context(context);
        builder.flag(Flag.INCLUDE_NODES_WITHOUT_SERVER_CONTEXT, isIncludeGlobal());
        if (op) {
            builder.option(PumpkinContextManager.OP_OPTION, true);
        }
        return builder.build();
    }

    private boolean holderAddPermission(PermissionHolder holder, String permission, String world, DataType type) {
        Objects.requireNonNull(permission, "permission is null");
        Preconditions.checkArgument(!permission.isEmpty(), "permission is an empty string");

        Node node = NodeBuilders.determineMostApplicable(permission)
                .withContext(DefaultContextKeys.SERVER_KEY, getVaultServer())
                .withContext(DefaultContextKeys.WORLD_KEY, world == null ? "global" : world)
                .build();

        if (holder.setNode(type, node, true).wasSuccessful()) {
            return holderSave(holder);
        }
        return false;
    }

    private boolean holderRemovePermission(PermissionHolder holder, String permission, String world, DataType type) {
        Objects.requireNonNull(permission, "permission is null");
        Preconditions.checkArgument(!permission.isEmpty(), "permission is an empty string");

        Node node = NodeBuilders.determineMostApplicable(permission)
                .withContext(DefaultContextKeys.SERVER_KEY, getVaultServer())
                .withContext(DefaultContextKeys.WORLD_KEY, world == null ? "global" : world)
                .build();

        if (holder.unsetNode(type, node).wasSuccessful()) {
            return holderSave(holder);
        }
        return false;
    }

    boolean holderSave(PermissionHolder holder) {
        if (holder.getType() == HolderType.USER) {
            User u = (User) holder;
            this.plugin.getStorage().saveUser(u);
        } else if (holder.getType() == HolderType.GROUP) {
            Group g = (Group) holder;
            this.plugin.getGroupManager().invalidateAllGroupCaches();
            this.plugin.getUserManager().invalidateAllUserCaches();
            this.plugin.getStorage().saveGroup(g);
        }
        return true;
    }

    String getServer() {
        return this.plugin.getConfiguration().get(ConfigKeys.SERVER);
    }

    String getVaultServer() {
        LuckPermsConfiguration configuration = this.plugin.getConfiguration();
        if (configuration.get(ConfigKeys.USE_VAULT_SERVER)) {
            return configuration.get(ConfigKeys.VAULT_SERVER);
        } else {
            return configuration.get(ConfigKeys.SERVER);
        }
    }

    boolean isIncludeGlobal() {
        return this.plugin.getConfiguration().get(ConfigKeys.VAULT_INCLUDING_GLOBAL);
    }

    boolean isIgnoreWorld() {
        return this.plugin.getConfiguration().get(ConfigKeys.VAULT_IGNORE_WORLD);
    }

    private boolean useVaultServer() {
        return this.plugin.getConfiguration().get(ConfigKeys.USE_VAULT_SERVER);
    }
}
