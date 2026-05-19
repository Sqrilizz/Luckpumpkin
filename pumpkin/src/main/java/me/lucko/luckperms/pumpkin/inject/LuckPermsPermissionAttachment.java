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

import com.google.common.base.Preconditions;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.factory.NodeBuilders;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.metadata.NodeMetadataKey;
import net.luckperms.api.query.Flag;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionRemovedExecutor;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A LuckPerms-backed {@link PermissionAttachment} that writes permissions as
 * transient LP nodes rather than into the Bukkit attachment list.
 */
public class LuckPermsPermissionAttachment extends PermissionAttachment {

    public static final NodeMetadataKey<LuckPermsPermissionAttachment> TRANSIENT_SOURCE_KEY =
            NodeMetadataKey.of("transientsource", LuckPermsPermissionAttachment.class);

    private static final Field PERMISSIONS_FIELD;

    static {
        try {
            PERMISSIONS_FIELD = PermissionAttachment.class.getDeclaredField("permissions");
            PERMISSIONS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final LuckPermsPermissible permissible;
    private final Plugin owner;
    private final Map<String, Boolean> perms = Collections.synchronizedMap(new HashMap<>());
    private boolean hooked = false;
    private PermissionRemovedExecutor removalCallback = null;
    private PermissionAttachment source;

    public LuckPermsPermissionAttachment(LuckPermsPermissible permissible, Plugin owner) {
        super(owner, null);
        this.permissible = permissible;
        this.owner = owner;
        injectFakeMap();
    }

    public LuckPermsPermissionAttachment(LuckPermsPermissible permissible, PermissionAttachment source) {
        super(source.getPlugin(), null);
        this.permissible = permissible;
        this.owner = source.getPlugin();
        this.perms.putAll(source.getPermissions());
        this.source = source;
        injectFakeMap();
    }

    private void injectFakeMap() {
        try {
            PERMISSIONS_FIELD.set(this, new FakeBackingMap());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public @NonNull LuckPermsPermissible getPermissible() { return this.permissible; }

    @Override
    public PermissionRemovedExecutor getRemovalCallback() { return this.removalCallback; }

    @Override
    public void setRemovalCallback(PermissionRemovedExecutor cb) { this.removalCallback = cb; }

    PermissionAttachment getSource() { return this.source; }

    public void hook() {
        this.hooked = true;
        this.permissible.hookedAttachments.add(this);
        for (Map.Entry<String, Boolean> entry : this.perms.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
            setPermissionInternal(entry.getKey(), entry.getValue());
        }
    }

    private void setPermissionInternal(String name, boolean value) {
        if (!this.permissible.getPlugin().getConfiguration().get(ConfigKeys.APPLY_BUKKIT_ATTACHMENT_PERMISSIONS)) return;

        NodeBuilder<?, ?> node = NodeBuilders.determineMostApplicable(name)
                .value(value)
                .withMetadata(TRANSIENT_SOURCE_KEY, this);

        QueryOptions globalOpts = this.permissible.getPlugin().getConfiguration().get(ConfigKeys.GLOBAL_QUERY_OPTIONS);
        if (!globalOpts.flag(Flag.INCLUDE_NODES_WITHOUT_SERVER_CONTEXT)) {
            node.withContext(this.permissible.getPlugin().getContextManager().getStaticContext());
        }

        User user = this.permissible.getUser();
        user.setNode(DataType.TRANSIENT, node.build(), true);
    }

    private void unsetPermissionInternal(String name) {
        if (!this.permissible.getPlugin().getConfiguration().get(ConfigKeys.APPLY_BUKKIT_ATTACHMENT_PERMISSIONS)) return;
        User user = this.permissible.getUser();
        user.removeIf(DataType.TRANSIENT, null,
                n -> n.getMetadata(TRANSIENT_SOURCE_KEY).orElse(null) == this && n.getKey().equals(name), false);
    }

    private void clearInternal() {
        User user = this.permissible.getUser();
        user.removeIf(DataType.TRANSIENT, null,
                n -> n.getMetadata(TRANSIENT_SOURCE_KEY).orElse(null) == this, false);
    }

    @Override
    public boolean remove() {
        if (!this.hooked) return false;
        clearInternal();
        if (this.removalCallback != null) this.removalCallback.attachmentRemoved(this);
        this.hooked = false;
        this.permissible.hookedAttachments.remove(this);
        return true;
    }

    @Override
    public void setPermission(@NonNull String name, boolean value) {
        Objects.requireNonNull(name, "name");
        Preconditions.checkArgument(!name.isEmpty(), "name is empty");
        String perm = name.toLowerCase(Locale.ROOT);
        Boolean prev = this.perms.put(perm, value);
        if (prev != null && prev == value) return;
        if (!this.hooked) return;
        if (prev != null) unsetPermissionInternal(perm);
        setPermissionInternal(perm, value);
    }

    @Override
    public void unsetPermission(@NonNull String name) {
        Objects.requireNonNull(name, "name");
        Preconditions.checkArgument(!name.isEmpty(), "name is empty");
        String perm = name.toLowerCase(Locale.ROOT);
        Boolean prev = this.perms.remove(perm);
        if (prev == null || !this.hooked) return;
        unsetPermissionInternal(perm);
    }

    @Override
    public @NonNull Map<String, Boolean> getPermissions() { return this.perms; }

    @Override
    public @NonNull Plugin getPlugin() {
        return this.owner != null ? this.owner : this.permissible.getPlugin().getLoader();
    }

    @Override public boolean equals(Object o) { return this == o; }
    @Override public int hashCode() { return System.identityHashCode(this); }

    private final class FakeBackingMap implements Map<String, Boolean> {
        @Override public Boolean put(String key, Boolean value) {
            Boolean prev = LuckPermsPermissionAttachment.this.perms.get(key);
            setPermission(key, value);
            return prev;
        }
        @Override public Boolean remove(Object key) {
            if (!(key instanceof String)) return null;
            String p = (String) key;
            Boolean prev = LuckPermsPermissionAttachment.this.perms.get(p);
            unsetPermission(p);
            return prev;
        }
        @Override public void putAll(Map<? extends String, ? extends Boolean> m) {
            m.forEach(this::put);
        }
        @Override public void clear() {
            if (LuckPermsPermissionAttachment.this.hooked) clearInternal();
            LuckPermsPermissionAttachment.this.perms.clear();
        }
        @Override public int size() { return LuckPermsPermissionAttachment.this.perms.size(); }
        @Override public boolean isEmpty() { return LuckPermsPermissionAttachment.this.perms.isEmpty(); }
        @Override public boolean containsKey(Object key) { return LuckPermsPermissionAttachment.this.perms.containsKey(key); }
        @Override public boolean containsValue(Object value) { return LuckPermsPermissionAttachment.this.perms.containsValue(value); }
        @Override public Boolean get(Object key) { return LuckPermsPermissionAttachment.this.perms.get(key); }
        @Override public Set<String> keySet() { return Collections.unmodifiableSet(LuckPermsPermissionAttachment.this.perms.keySet()); }
        @Override public Collection<Boolean> values() { return Collections.unmodifiableCollection(LuckPermsPermissionAttachment.this.perms.values()); }
        @Override public Set<Entry<String, Boolean>> entrySet() { return Collections.unmodifiableSet(LuckPermsPermissionAttachment.this.perms.entrySet()); }
        @Override public boolean equals(Object o) { return o instanceof Map<?,?> && LuckPermsPermissionAttachment.this.perms.equals(o); }
        @Override public int hashCode() { return LuckPermsPermissionAttachment.this.perms.hashCode(); }
    }
}
