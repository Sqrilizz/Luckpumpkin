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

package me.lucko.luckperms.pumpkin.inject.server;

import com.google.common.collect.ForwardingSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import me.lucko.luckperms.common.cache.Cache;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import net.luckperms.api.util.Tristate;
import org.bukkit.permissions.Permission;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A replacement for the default-permissions registry inside PatchBukkit's PluginManager.
 *
 * Tracks which permissions are "default true for ops" and "default true for non-ops",
 * resolves them (including children) and exposes them to
 * {@link me.lucko.luckperms.pumpkin.calculator.DefaultPermissionMapProcessor}.
 *
 * Injected by {@link PumpkinServerMapInjector}.
 */
public final class PumpkinDefaultsMap implements Map<Boolean, Set<Permission>> {

    private static final Set<Boolean> KEY_SET = ImmutableSet.of(Boolean.TRUE, Boolean.FALSE);

    final LPPumpkinPlugin plugin;

    private final Set<Permission> opSet = new DefaultPermissionSet(true);
    private final Set<Permission> nonOpSet = new DefaultPermissionSet(false);

    private final DefaultsCache opCache = new DefaultsCache(true);
    private final DefaultsCache nonOpCache = new DefaultsCache(false);

    private final Collection<Set<Permission>> values = ImmutableList.of(this.opSet, this.nonOpSet);
    private final Set<Entry<Boolean, Set<Permission>>> entrySet = ImmutableSet.of(
            Maps.immutableEntry(Boolean.TRUE, this.opSet),
            Maps.immutableEntry(Boolean.FALSE, this.nonOpSet)
    );

    public PumpkinDefaultsMap(LPPumpkinPlugin plugin, Map<Boolean, Set<Permission>> existingData) {
        this.plugin = plugin;
        this.opSet.addAll(existingData.getOrDefault(Boolean.TRUE, Collections.emptySet()));
        this.nonOpSet.addAll(existingData.getOrDefault(Boolean.FALSE, Collections.emptySet()));
    }

    @Override
    public Set<Permission> get(Object key) {
        boolean b = (boolean) key;
        return b ? this.opSet : this.nonOpSet;
    }

    private DefaultsCache getCache(boolean op) {
        return op ? this.opCache : this.nonOpCache;
    }

    private void invalidate(boolean op) {
        getCache(op).invalidate();
        this.plugin.getUserManager().invalidateAllPermissionCalculators();
        this.plugin.getGroupManager().invalidateAllPermissionCalculators();
    }

    /**
     * Queries whether the given permission should be granted by default.
     *
     * @param permission the permission node
     * @param isOp       whether the subject is op
     * @return tristate result
     */
    public Tristate lookupDefaultPermission(String permission, boolean isOp) {
        Map<String, Boolean> map = getCache(isOp).get();
        return Tristate.of(map.get(permission));
    }

    @Override public @NonNull Collection<Set<Permission>> values() { return this.values; }
    @Override public @NonNull Set<Entry<Boolean, Set<Permission>>> entrySet() { return this.entrySet; }
    @Override public @NonNull Set<Boolean> keySet() { return KEY_SET; }
    @Override public int size() { return 2; }
    @Override public boolean isEmpty() { return false; }
    @Override public boolean containsKey(Object key) { return key instanceof Boolean; }
    @Override public boolean containsValue(Object value) { return value == this.opSet || value == this.nonOpSet; }
    @Override public Set<Permission> put(Boolean key, Set<Permission> value) { throw new UnsupportedOperationException(); }
    @Override public Set<Permission> remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(@NonNull Map<? extends Boolean, ? extends Set<Permission>> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    private final class DefaultsCache extends Cache<Map<String, Boolean>> {
        private final boolean op;

        DefaultsCache(boolean op) { this.op = op; }

        @Override
        protected @NonNull Map<String, Boolean> supply() {
            Map<String, Boolean> builder = new HashMap<>();
            PumpkinPermissionMap permMap = PumpkinDefaultsMap.this.plugin.getPermissionMap();
            for (Permission perm : PumpkinDefaultsMap.this.get(this.op)) {
                String name = perm.getName().toLowerCase(Locale.ROOT);
                builder.put(name, true);
                if (permMap != null) {
                    for (Map.Entry<String, Boolean> child : permMap.getChildPermissions(name, true).entrySet()) {
                        builder.putIfAbsent(child.getKey(), child.getValue());
                    }
                }
            }
            return ImmutableMap.copyOf(builder);
        }
    }

    private final class DefaultPermissionSet extends ForwardingSet<Permission> {
        private final Set<Permission> delegate = ConcurrentHashMap.newKeySet();
        private final boolean op;

        DefaultPermissionSet(boolean op) { this.op = op; }

        @Override protected Set<Permission> delegate() { return this.delegate; }

        @Override
        public boolean add(@NonNull Permission element) {
            boolean ret = super.add(element);
            invalidate(this.op);
            return ret;
        }

        @Override
        public boolean addAll(@NonNull Collection<? extends Permission> collection) {
            boolean ret = super.addAll(collection);
            invalidate(this.op);
            return ret;
        }

        @Override
        public boolean remove(@NonNull Object object) {
            boolean ret = super.remove(object);
            invalidate(this.op);
            return ret;
        }
    }
}
