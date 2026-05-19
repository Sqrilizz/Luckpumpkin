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
import me.lucko.luckperms.pumpkin.inject.server.PumpkinPermissionMap;
import net.luckperms.api.node.Node;
import net.luckperms.api.util.Tristate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Permission Processor for Bukkit's "child" permission system on PumpkinMC.
 *
 * Resolves child permissions from the {@link PumpkinPermissionMap} that is injected
 * into PatchBukkit's PluginManager. Mirrors the Bukkit {@code ChildProcessor} exactly.
 */
public class PumpkinChildProcessor extends AbstractPermissionProcessor implements PermissionProcessor {
    private static final TristateResult.Factory RESULT_FACTORY = new TristateResult.Factory(PumpkinChildProcessor.class);

    private final LPPumpkinPlugin plugin;
    private final Map<String, Node> sourceMap;

    private final AtomicBoolean needsRefresh = new AtomicBoolean(false);
    private Map<String, TristateResult> childPermissions;

    public PumpkinChildProcessor(LPPumpkinPlugin plugin, Map<String, Node> sourceMap) {
        this.plugin = plugin;
        this.sourceMap = sourceMap;
        refresh();
    }

    private void refresh() {
        PumpkinPermissionMap permissionMap = this.plugin.getPermissionMap();
        if (permissionMap == null) {
            this.childPermissions = Collections.emptyMap();
            return;
        }
        this.childPermissions = processChildPermissions(this.sourceMap, permissionMap);
    }

    @Override
    public TristateResult hasPermission(String permission) {
        if (this.needsRefresh.compareAndSet(true, false)) {
            refresh();
        }
        return this.childPermissions.getOrDefault(permission, TristateResult.UNDEFINED);
    }

    @Override
    public void invalidate() {
        this.needsRefresh.set(true);
    }

    private static Map<String, TristateResult> processChildPermissions(
            Map<String, Node> sourceMap, PumpkinPermissionMap permissionMap) {
        Map<String, TristateResult> result = new HashMap<>();
        sourceMap.forEach((key, node) -> {
            Map<String, Boolean> children = permissionMap.getChildPermissions(key, node.getValue());
            children.forEach((childKey, childValue) ->
                    result.put(childKey, RESULT_FACTORY.resultWithOverride(node, Tristate.of(childValue))));
        });
        return result;
    }
}
