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

import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.PermissionAttachment;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Injects a {@link LuckPermsPermissible} into a PatchBukkit {@link Player}.
 *
 * PatchBukkit stores the permissible in {@code PatchBukkitHumanEntity.perm} as a
 * {@code protected final PermissibleBase} field. We use reflection to replace it.
 */
public final class PumpkinPermissibleInjector {
    private PumpkinPermissibleInjector() {}

    /**
     * The field holding {@code PermissibleBase} on {@code PatchBukkitHumanEntity}.
     * Resolved lazily so the class can load even if PatchBukkit is not on the classpath.
     */
    private static Field humanEntityPermField;
    private static Field permissibleBaseAttachmentsField;
    private static boolean fieldsInitialized = false;
    private static Throwable initError = null;

    private static void ensureInitialized() throws Exception {
        if (fieldsInitialized) {
            if (initError != null) throw new RuntimeException("PumpkinPermissibleInjector failed to initialise", initError);
            return;
        }
        try {
            Field permField = null;

            // Try PatchBukkit class names (most specific first)
            String[] candidateClasses = {
                    "org.patchbukkit.entity.PatchBukkitHumanEntity",
                    "org.patchbukkit.entity.PatchBukkitPlayer"
            };

            for (String className : candidateClasses) {
                try {
                    Class<?> clazz = Class.forName(className);
                    // Walk up the hierarchy looking for the 'perm' field
                    Class<?> c = clazz;
                    while (c != null && c != Object.class) {
                        try {
                            permField = c.getDeclaredField("perm");
                            break;
                        } catch (NoSuchFieldException ignored) {
                            c = c.getSuperclass();
                        }
                    }
                    if (permField != null) break;
                } catch (ClassNotFoundException ignored) {
                    // try next
                }
            }

            if (permField == null) {
                throw new NoSuchFieldException("Could not find 'perm' field on any PatchBukkit HumanEntity class");
            }

            permField.setAccessible(true);
            humanEntityPermField = permField;

            Field attField = PermissibleBase.class.getDeclaredField("attachments");
            attField.setAccessible(true);
            permissibleBaseAttachmentsField = attField;

        } catch (Throwable t) {
            initError = t;
            fieldsInitialized = true;
            throw new RuntimeException("PumpkinPermissibleInjector failed to initialise", t);
        }
        fieldsInitialized = true;
    }

    /**
     * Injects {@link LuckPermsPermissible} into the given player.
     */
    public static void inject(Player player, LuckPermsPermissible newPermissible, PluginLogger logger) throws Exception {
        ensureInitialized();

        PermissibleBase oldPermissible = (PermissibleBase) humanEntityPermField.get(player);

        if (oldPermissible instanceof LuckPermsPermissible) {
            throw new IllegalStateException("LuckPermsPermissible already injected into " + player.getName());
        }

        Class<?> oldClass = oldPermissible.getClass();
        if (!PermissibleBase.class.equals(oldClass)) {
            logger.warn("Player " + player.getName() + " has a custom PermissibleBase (" + oldClass.getName() + "). " +
                    "Make sure LuckPerms is the only permissions plugin.");
        }

        // Migrate existing attachments
        @SuppressWarnings("unchecked")
        List<PermissionAttachment> attachments = (List<PermissionAttachment>) permissibleBaseAttachmentsField.get(oldPermissible);
        newPermissible.convertAndAddAttachments(attachments);
        attachments.clear();
        oldPermissible.clearPermissions();

        newPermissible.getActive().set(true);
        newPermissible.setOldPermissible(oldPermissible);

        humanEntityPermField.set(player, newPermissible);
    }

    /**
     * Removes {@link LuckPermsPermissible} from the given player, restoring the original.
     *
     * @param dummy if true, a minimal dummy is injected (use on player quit)
     */
    public static void uninject(Player player, boolean dummy) throws Exception {
        ensureInitialized();

        PermissibleBase current = (PermissibleBase) humanEntityPermField.get(player);

        if (!(current instanceof LuckPermsPermissible)) {
            return;
        }

        LuckPermsPermissible lpPerm = (LuckPermsPermissible) current;
        lpPerm.clearPermissions();
        lpPerm.getActive().set(false);

        PermissibleBase replacement;
        if (dummy) {
            replacement = new PermissibleBase(player);
        } else {
            replacement = lpPerm.getOldPermissible();
            if (replacement == null) {
                replacement = new PermissibleBase(player);
            }
        }

        humanEntityPermField.set(player, replacement);
    }

    /**
     * Returns the injected {@link LuckPermsPermissible} for the player, or null.
     */
    public static @Nullable LuckPermsPermissible get(Player player) {
        try {
            ensureInitialized();
            Object perm = humanEntityPermField.get(player);
            return perm instanceof LuckPermsPermissible ? (LuckPermsPermissible) perm : null;
        } catch (Exception e) {
            return null;
        }
    }
}
