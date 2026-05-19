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

package me.lucko.luckperms.pumpkin.inject;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import me.lucko.luckperms.common.cacheddata.result.TristateResult;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.context.manager.QueryOptionsSupplier;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.verbose.event.CheckOrigin;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import me.lucko.luckperms.pumpkin.calculator.OpProcessor;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A LuckPerms-backed {@link PermissibleBase} for PumpkinMC/PatchBukkit players.
 *
 * This replaces the stock {@code PermissibleBase} stored in
 * {@code PatchBukkitHumanEntity.perm} so that every {@code player.hasPermission()}
 * call goes through LP's permission calculator instead of Bukkit's default system.
 *
 * Thread-safe: permission checks may arrive from any thread on PumpkinMC.
 */
public class LuckPermsPermissible extends PermissibleBase {

    private static final Field ATTACHMENTS_FIELD;

    static {
        try {
            ATTACHMENTS_FIELD = PermissibleBase.class.getDeclaredField("attachments");
            ATTACHMENTS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final User user;
    private final Player player;
    private final LPPumpkinPlugin plugin;
    private final QueryOptionsSupplier queryOptionsSupplier;

    private PermissibleBase oldPermissible = null;
    private final AtomicBoolean active = new AtomicBoolean(false);

    final Set<LuckPermsPermissionAttachment> hookedAttachments = ConcurrentHashMap.newKeySet();

    public LuckPermsPermissible(Player player, User user, LPPumpkinPlugin plugin) {
        super(player);
        this.user = Objects.requireNonNull(user, "user");
        this.player = Objects.requireNonNull(player, "player");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.queryOptionsSupplier = plugin.getContextManager().createQueryOptionsSupplier(player);
        injectFakeAttachmentsList();
    }

    private void injectFakeAttachmentsList() {
        try {
            ATTACHMENTS_FIELD.set(this, new FakeAttachmentList());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isPermissionSet(@NonNull String permission) {
        if (permission == null) throw new NullPointerException("permission");
        QueryOptions queryOptions = this.queryOptionsSupplier.getQueryOptions();
        TristateResult result = this.user.getCachedData().getPermissionData(queryOptions)
                .checkPermission(permission, CheckOrigin.PLATFORM_API_HAS_PERMISSION_SET);
        if (result.result() == Tristate.UNDEFINED) return false;
        if (result.processorClass() == OpProcessor.class) return false;
        return true;
    }

    @Override
    public boolean isPermissionSet(@NonNull Permission permission) {
        if (permission == null) throw new NullPointerException("permission");
        return isPermissionSet(permission.getName());
    }

    @Override
    public boolean hasPermission(@NonNull String permission) {
        if (permission == null) throw new NullPointerException("permission");
        QueryOptions queryOptions = this.queryOptionsSupplier.getQueryOptions();
        return this.user.getCachedData().getPermissionData(queryOptions)
                .checkPermission(permission, CheckOrigin.PLATFORM_API_HAS_PERMISSION)
                .result().asBoolean();
    }

    @Override
    public boolean hasPermission(@NonNull Permission permission) {
        if (permission == null) throw new NullPointerException("permission");
        QueryOptions queryOptions = this.queryOptionsSupplier.getQueryOptions();
        TristateResult result = this.user.getCachedData().getPermissionData(queryOptions)
                .checkPermission(permission.getName(), CheckOrigin.PLATFORM_API_HAS_PERMISSION);

        if (result.processorClass() == OpProcessor.class
                && this.plugin.getConfiguration().get(ConfigKeys.APPLY_BUKKIT_DEFAULT_PERMISSIONS)) {
            return permission.getDefault().getValue(true);
        }
        return result.result().asBoolean();
    }

    void convertAndAddAttachments(Collection<PermissionAttachment> attachments) {
        for (PermissionAttachment attachment : attachments) {
            new LuckPermsPermissionAttachment(this, attachment).hook();
        }
    }

    @Override
    public void setOp(boolean value) {
        this.player.setOp(value);
    }

    @Override
    public @NonNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Map<String, Boolean> permissionMap = this.user.getCachedData()
                .getPermissionData(this.queryOptionsSupplier.getQueryOptions()).getPermissionMap();
        ImmutableSet.Builder<PermissionAttachmentInfo> builder = ImmutableSet.builder();
        permissionMap.forEach((key, value) ->
                builder.add(new PermissionAttachmentInfo(this.player, key, null, value)));
        return builder.build();
    }

    @Override
    public @NonNull LuckPermsPermissionAttachment addAttachment(@NonNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        LuckPermsPermissionAttachment attachment = new LuckPermsPermissionAttachment(this, plugin);
        attachment.hook();
        return attachment;
    }

    @Override
    public @NonNull PermissionAttachment addAttachment(@NonNull Plugin plugin, @NonNull String permission, boolean value) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(permission, "permission");
        PermissionAttachment attachment = addAttachment(plugin);
        attachment.setPermission(permission, value);
        return attachment;
    }

    @Override
    public LuckPermsPermissionAttachment addAttachment(@NonNull Plugin plugin, int ticks) {
        Objects.requireNonNull(plugin, "plugin");
        if (!plugin.isEnabled()) {
            throw new IllegalArgumentException("Plugin " + plugin.getDescription().getFullName() + " is not enabled");
        }
        LuckPermsPermissionAttachment attachment = addAttachment(plugin);
        // PatchBukkit scheduler is unimplemented — run removal async via LP's pool
        this.plugin.getBootstrap().getScheduler().asyncLater(attachment::remove, ticks * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
        return attachment;
    }

    @Override
    public LuckPermsPermissionAttachment addAttachment(@NonNull Plugin plugin, @NonNull String permission, boolean value, int ticks) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(permission, "permission");
        LuckPermsPermissionAttachment attachment = addAttachment(plugin, ticks);
        attachment.setPermission(permission, value);
        return attachment;
    }

    @Override
    public void removeAttachment(@NonNull PermissionAttachment attachment) {
        Objects.requireNonNull(attachment, "attachment");
        LuckPermsPermissionAttachment lpa;
        if (attachment instanceof LuckPermsPermissionAttachment) {
            lpa = (LuckPermsPermissionAttachment) attachment;
        } else {
            lpa = this.hookedAttachments.stream()
                    .filter(a -> a.getSource() == attachment)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Given attachment is not tracked by LuckPerms."));
        }
        if (lpa.getPermissible() != this) {
            throw new IllegalArgumentException("Attachment does not belong to this permissible.");
        }
        lpa.remove();
    }

    @Override
    public void recalculatePermissions() {
        if (this.queryOptionsSupplier != null) {
            this.queryOptionsSupplier.invalidateCache();
        }
    }

    @Override
    public void clearPermissions() {
        this.hookedAttachments.forEach(LuckPermsPermissionAttachment::remove);
    }

    public User getUser() { return this.user; }
    public Player getPlayer() { return this.player; }
    public LPPumpkinPlugin getPlugin() { return this.plugin; }
    public QueryOptionsSupplier getQueryOptionsSupplier() { return this.queryOptionsSupplier; }
    PermissibleBase getOldPermissible() { return this.oldPermissible; }
    AtomicBoolean getActive() { return this.active; }
    void setOldPermissible(PermissibleBase oldPermissible) { this.oldPermissible = oldPermissible; }

    private final class FakeAttachmentList implements List<PermissionAttachment> {
        @Override
        public boolean add(PermissionAttachment a) {
            if (LuckPermsPermissible.this.hookedAttachments.stream().anyMatch(h -> h.getSource() == a)) return false;
            new LuckPermsPermissionAttachment(LuckPermsPermissible.this, a).hook();
            return true;
        }
        @Override
        public boolean remove(Object o) { removeAttachment((PermissionAttachment) o); return true; }
        @Override
        public void clear() { clearPermissions(); }
        @Override
        public boolean addAll(@NonNull Collection<? extends PermissionAttachment> c) {
            boolean mod = false;
            for (PermissionAttachment e : c) if (add(e)) mod = true;
            return mod;
        }
        @Override
        public boolean contains(Object o) {
            PermissionAttachment a = (PermissionAttachment) o;
            return LuckPermsPermissible.this.hookedAttachments.stream().anyMatch(h -> h.getSource() == a);
        }
        @Override
        public Iterator<PermissionAttachment> iterator() {
            return ImmutableList.<PermissionAttachment>copyOf(LuckPermsPermissible.this.hookedAttachments).iterator();
        }
        @Override
        public ListIterator<PermissionAttachment> listIterator() {
            return ImmutableList.<PermissionAttachment>copyOf(LuckPermsPermissible.this.hookedAttachments).listIterator();
        }
        @Override public @NonNull Object[] toArray() {
            return ImmutableList.copyOf(LuckPermsPermissible.this.hookedAttachments).toArray();
        }
        @Override public <T> @NonNull T[] toArray(@NonNull T[] a) {
            return ImmutableList.<PermissionAttachment>copyOf(LuckPermsPermissible.this.hookedAttachments).toArray(a);
        }
        @Override public int size() { throw new UnsupportedOperationException(); }
        @Override public boolean isEmpty() { throw new UnsupportedOperationException(); }
        @Override public boolean containsAll(@NonNull Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(int i, @NonNull Collection<? extends PermissionAttachment> c) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(@NonNull Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean retainAll(@NonNull Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment get(int i) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment set(int i, PermissionAttachment e) { throw new UnsupportedOperationException(); }
        @Override public void add(int i, PermissionAttachment e) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment remove(int i) { throw new UnsupportedOperationException(); }
        @Override public int indexOf(Object o) { throw new UnsupportedOperationException(); }
        @Override public int lastIndexOf(Object o) { throw new UnsupportedOperationException(); }
        @Override public @NonNull ListIterator<PermissionAttachment> listIterator(int i) { throw new UnsupportedOperationException(); }
        @Override public @NonNull List<PermissionAttachment> subList(int f, int t) { throw new UnsupportedOperationException(); }
    }
}
