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

import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A replacement for the permission-subscriptions map inside PatchBukkit's PluginManager.
 *
 * Bukkit uses subscriptions in some places (e.g. {@code Server#broadcast}) to find all
 * {@link Permissible}s that have a given permission, instead of calling
 * {@link Permissible#hasPermission(String)} directly.
 *
 * This implementation proxies those lookups back through LP so broadcast/subscribe
 * behaviour is correct without registering subscriptions per-player.
 *
 * Injected by {@link PumpkinServerMapInjector}.
 */
public final class PumpkinSubscriptionMap implements Map<String, Map<Permissible, Boolean>> {

    final LPPumpkinPlugin plugin;

    private final Map<Permissible, Set<String>> subscriptions =
            Collections.synchronizedMap(new WeakHashMap<>());

    public PumpkinSubscriptionMap(LPPumpkinPlugin plugin, Map<String, Map<Permissible, Boolean>> existingData) {
        this.plugin = plugin;
        for (Entry<String, Map<Permissible, Boolean>> entry : existingData.entrySet()) {
            entry.getValue().keySet().forEach(p -> subscribe(p, entry.getKey()));
        }
    }

    @Override
    public Map<Permissible, Boolean> get(Object key) {
        return new ValueMap((String) key);
    }

    public void subscribe(Permissible permissible, String permission) {
        if (permissible instanceof Player) return;
        Set<String> perms = this.subscriptions.computeIfAbsent(permissible,
                x -> Collections.synchronizedSet(new HashSet<>()));
        perms.add(permission);
    }

    public boolean unsubscribe(Permissible permissible, String permission) {
        if (permissible instanceof Player) return false;
        Set<String> perms = this.subscriptions.get(permissible);
        return perms != null && perms.remove(permission);
    }

    public @NonNull Set<Permissible> subscribers(String permission) {
        Collection<? extends Player> onlinePlayers =
                this.plugin.getBootstrap().getServer().getOnlinePlayers();
        Set<Permissible> set = new HashSet<>(onlinePlayers.size() + this.subscriptions.size());

        this.subscriptions.forEach((permissible, perms) -> {
            if (perms.contains(permission)) set.add(permissible);
        });

        for (Player player : onlinePlayers) {
            if (player.hasPermission(permission) || player.isPermissionSet(permission)) {
                set.add(player);
            }
        }

        return set;
    }

    public Map<String, Map<Permissible, Boolean>> detach() {
        Map<String, Map<Permissible, Boolean>> map = new HashMap<>();
        this.subscriptions.forEach((permissible, perms) -> {
            for (String perm : perms) {
                map.computeIfAbsent(perm, x -> new WeakHashMap<>()).put(permissible, true);
            }
        });
        return map;
    }

    @Override public Map<Permissible, Boolean> put(String key, Map<Permissible, Boolean> value) { throw new UnsupportedOperationException(); }
    @Override public Map<Permissible, Boolean> remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<? extends String, ? extends Map<Permissible, Boolean>> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public Set<String> keySet() { throw new UnsupportedOperationException(); }
    @Override public Collection<Map<Permissible, Boolean>> values() { throw new UnsupportedOperationException(); }
    @Override public Set<Entry<String, Map<Permissible, Boolean>>> entrySet() { throw new UnsupportedOperationException(); }
    @Override public int size() { return 0; }
    @Override public boolean isEmpty() { throw new UnsupportedOperationException(); }
    @Override public boolean containsKey(Object key) { throw new UnsupportedOperationException(); }
    @Override public boolean containsValue(Object value) { throw new UnsupportedOperationException(); }

    public final class ValueMap implements Map<Permissible, Boolean> {
        private final String permission;

        public ValueMap(String permission) { this.permission = permission; }

        @Override
        public Boolean put(Permissible key, Boolean value) {
            subscribe(key, this.permission);
            return null;
        }

        @Override
        public Boolean remove(Object k) {
            return unsubscribe((Permissible) k, this.permission) ? true : null;
        }

        @Override
        public @NonNull Set<Permissible> keySet() {
            return subscribers(this.permission);
        }

        @Override public boolean isEmpty() { return false; }
        @Override public int size() { return 1; }
        @Override public void putAll(Map<? extends Permissible, ? extends Boolean> m) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public Collection<Boolean> values() { throw new UnsupportedOperationException(); }
        @Override public Set<Entry<Permissible, Boolean>> entrySet() { throw new UnsupportedOperationException(); }
        @Override public boolean containsKey(Object key) { throw new UnsupportedOperationException(); }
        @Override public boolean containsValue(Object value) { throw new UnsupportedOperationException(); }
        @Override public Boolean get(Object key) { throw new UnsupportedOperationException(); }
    }
}
