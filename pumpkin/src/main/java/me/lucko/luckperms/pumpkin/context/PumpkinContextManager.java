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

package me.lucko.luckperms.pumpkin.context;

import me.lucko.luckperms.common.context.manager.DetachedContextManager;
import me.lucko.luckperms.common.context.manager.QueryOptionsSupplier;
import me.lucko.luckperms.pumpkin.LPPumpkinPlugin;
import net.luckperms.api.query.OptionKey;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PumpkinContextManager extends DetachedContextManager<Player, Player> {

    public static final OptionKey<Boolean> OP_OPTION = OptionKey.of("op", Boolean.class);

    private final ConcurrentHashMap<UUID, QueryOptionsSupplier> supplierCache = new ConcurrentHashMap<>();

    public PumpkinContextManager(LPPumpkinPlugin plugin) {
        super(plugin, Player.class, Player.class);
    }

    @Override
    public UUID getUniqueId(Player player) {
        return player.getUniqueId();
    }

    @Override
    public @Nullable QueryOptionsSupplier getQueryOptionsSupplier(Player subject) {
        return this.supplierCache.get(subject.getUniqueId());
    }

    @Override
    public QueryOptionsSupplier createQueryOptionsSupplier(Player subject) {
        QueryOptionsSupplier supplier = super.createQueryOptionsSupplier(subject);
        this.supplierCache.put(subject.getUniqueId(), supplier);
        return supplier;
    }

    public void removeSupplier(UUID uuid) {
        this.supplierCache.remove(uuid);
    }

    @Override
    public void customizeQueryOptions(Player subject, QueryOptions.Builder builder) {
        if (subject.isOp()) {
            builder.option(OP_OPTION, true);
        }
    }
}
