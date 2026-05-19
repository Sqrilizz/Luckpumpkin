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
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Injects LP's custom permission maps into PatchBukkit's PluginManager.
 *
 * PatchBukkit uses {@code org.patchbukkit.PatchBukkitPluginManager} which stores:
 * - {@code Map<String, Permission> permissions}          — permission registry
 * - {@code Map<Boolean, Set<Permission>> defaultPerms}   — default op/nonop permissions
 * - {@code Map<String, Map<Permissible, Boolean>> permSubs} — subscriptions
 *
 * We locate these fields by type-matching so we don't hard-code field names that
 * might change between PatchBukkit versions.
 */
public final class PumpkinServerMapInjector {

    // Resolved lazily per plugin-manager class
    private static volatile Field permissionsField;
    private static volatile Field defaultPermsField;
    private static volatile Field permSubsField;
    private static volatile boolean fieldsResolved = false;

    private PumpkinServerMapInjector() {}

    @SuppressWarnings("unchecked")
    public static void inject(LPPumpkinPlugin plugin) {
        PluginManager pm = plugin.getBootstrap().getServer().getPluginManager();
        resolveFields(pm.getClass(), plugin);

        // --- permissions map ---
        if (permissionsField != null) {
            try {
                Object existing = permissionsField.get(pm);
                if (!(existing instanceof PumpkinPermissionMap) ||
                        ((PumpkinPermissionMap) existing).plugin != plugin) {
                    Map<String, Permission> cast = existing instanceof Map<?,?>
                            ? (Map<String, Permission>) existing : new HashMap<>();
                    PumpkinPermissionMap newMap = new PumpkinPermissionMap(plugin, cast);
                    permissionsField.set(pm, newMap);
                    plugin.setPermissionMap(newMap);
                    plugin.getLogger().info("Injected PumpkinPermissionMap into PluginManager.");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to inject PumpkinPermissionMap.", e);
            }
        }

        // --- defaults map ---
        if (defaultPermsField != null) {
            try {
                Object existing = defaultPermsField.get(pm);
                if (!(existing instanceof PumpkinDefaultsMap) ||
                        ((PumpkinDefaultsMap) existing).plugin != plugin) {
                    Map<Boolean, Set<Permission>> cast = existing instanceof Map<?,?>
                            ? (Map<Boolean, Set<Permission>>) existing
                            : new HashMap<>();
                    PumpkinDefaultsMap newMap = new PumpkinDefaultsMap(plugin, cast);
                    defaultPermsField.set(pm, newMap);
                    plugin.setDefaultPermissionMap(newMap);
                    plugin.getLogger().info("Injected PumpkinDefaultsMap into PluginManager.");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to inject PumpkinDefaultsMap.", e);
            }
        }

        // --- subscription map ---
        if (permSubsField != null) {
            try {
                Object existing = permSubsField.get(pm);
                if (existing instanceof PumpkinSubscriptionMap &&
                        ((PumpkinSubscriptionMap) existing).plugin == plugin) {
                    return;
                }
                Map<String, Map<Permissible, Boolean>> cast;
                if (existing instanceof PumpkinSubscriptionMap) {
                    cast = ((PumpkinSubscriptionMap) existing).detach();
                } else if (existing instanceof Map<?,?>) {
                    cast = (Map<String, Map<Permissible, Boolean>>) existing;
                } else {
                    cast = new HashMap<>();
                }
                PumpkinSubscriptionMap newMap = new PumpkinSubscriptionMap(plugin, cast);
                permSubsField.set(pm, newMap);
                plugin.setSubscriptionMap(newMap);
                plugin.getLogger().info("Injected PumpkinSubscriptionMap into PluginManager.");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to inject PumpkinSubscriptionMap.", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void uninject(LPPumpkinPlugin plugin) {
        PluginManager pm = plugin.getBootstrap().getServer().getPluginManager();

        if (permissionsField != null) {
            try {
                Object obj = permissionsField.get(pm);
                if (obj instanceof PumpkinPermissionMap) {
                    permissionsField.set(pm, new HashMap<>((PumpkinPermissionMap) obj));
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to uninject PumpkinPermissionMap.", e);
            }
        }

        if (defaultPermsField != null) {
            try {
                Object obj = defaultPermsField.get(pm);
                if (obj instanceof PumpkinDefaultsMap) {
                    Map<Boolean, Set<Permission>> restored = new HashMap<>();
                    PumpkinDefaultsMap dm = (PumpkinDefaultsMap) obj;
                    restored.put(Boolean.TRUE, new HashSet<>(dm.get(Boolean.TRUE)));
                    restored.put(Boolean.FALSE, new HashSet<>(dm.get(Boolean.FALSE)));
                    defaultPermsField.set(pm, restored);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to uninject PumpkinDefaultsMap.", e);
            }
        }

        if (permSubsField != null) {
            try {
                Object obj = permSubsField.get(pm);
                if (obj instanceof PumpkinSubscriptionMap) {
                    Map<String, Map<Permissible, Boolean>> detached =
                            new HashMap<>(((PumpkinSubscriptionMap) obj).detach());
                    // convert weak maps to regular maps for stability
                    detached.replaceAll((k, v) -> new WeakHashMap<>(v));
                    permSubsField.set(pm, detached);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to uninject PumpkinSubscriptionMap.", e);
            }
        }
    }

    /**
     * Walks the class hierarchy of the PluginManager to find the three target fields
     * by their declared types, falling back to known PatchBukkit field names.
     */
    private static synchronized void resolveFields(Class<?> pmClass, LPPumpkinPlugin plugin) {
        if (fieldsResolved) return;

        Class<?> c = pmClass;
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                Class<?> type = field.getType();
                String name = field.getName();

                if (permissionsField == null && Map.class.isAssignableFrom(type)
                        && (name.equals("permissions") || name.equals("permissionMap"))) {
                    field.setAccessible(true);
                    permissionsField = field;
                }

                if (defaultPermsField == null && Map.class.isAssignableFrom(type)
                        && (name.equals("defaultPerms") || name.equals("defaultPermissions"))) {
                    field.setAccessible(true);
                    defaultPermsField = field;
                }

                if (permSubsField == null && Map.class.isAssignableFrom(type)
                        && (name.equals("permSubs") || name.equals("permSubscriptions")
                            || name.equals("subscriptions"))) {
                    field.setAccessible(true);
                    permSubsField = field;
                }
            }
            c = c.getSuperclass();
        }

        if (permissionsField == null) {
            plugin.getLogger().warn("PumpkinServerMapInjector: could not find 'permissions' field on "
                    + pmClass.getName() + " — permission map injection skipped.");
        }
        if (defaultPermsField == null) {
            plugin.getLogger().warn("PumpkinServerMapInjector: could not find 'defaultPerms' field on "
                    + pmClass.getName() + " — default permission map injection skipped.");
        }
        if (permSubsField == null) {
            plugin.getLogger().warn("PumpkinServerMapInjector: could not find 'permSubs' field on "
                    + pmClass.getName() + " — subscription map injection skipped.");
        }

        fieldsResolved = true;
    }
}
