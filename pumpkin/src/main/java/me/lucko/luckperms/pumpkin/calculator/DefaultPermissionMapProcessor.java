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

package me.lucko.luckperms.pumpkin.calculator;

import me.lucko.luckperms.common.cacheddata.result.TristateResult;
import me.lucko.luckperms.common.calculator.processor.AbstractPermissionProcessor;
import me.lucko.luckperms.common.calculator.processor.PermissionProcessor;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import net.luckperms.api.util.Tristate;
import org.bukkit.permissions.Permission;

import java.util.Locale;

/**
 * Resolves Bukkit's default permission system via PatchBukkit's PluginManager.
 *
 * Checks whether the requested permission has a registered default value that
 * applies to the current op state, mirroring what Bukkit's PermissibleBase does
 * for non-LP plugins.
 */
public class DefaultPermissionMapProcessor extends AbstractPermissionProcessor implements PermissionProcessor {
    private static final TristateResult.Factory RESULT_FACTORY = new TristateResult.Factory(DefaultPermissionMapProcessor.class);

    private final LPPumpkinPlugin plugin;
    private final boolean isOp;

    public DefaultPermissionMapProcessor(LPPumpkinPlugin plugin, boolean isOp) {
        this.plugin = plugin;
        this.isOp = isOp;
    }

    @Override
    public TristateResult hasPermission(String permission) {
        try {
            Permission def = this.plugin.getBootstrap().getServer()
                    .getPluginManager()
                    .getPermission(permission.toLowerCase(Locale.ROOT));

            if (def != null) {
                boolean value = def.getDefault().getValue(this.isOp);
                return RESULT_FACTORY.result(Tristate.of(value));
            }
        } catch (UnsupportedOperationException ignored) {
            // PatchBukkit may not implement getPermission yet — silently skip
        }
        return TristateResult.UNDEFINED;
    }
}
