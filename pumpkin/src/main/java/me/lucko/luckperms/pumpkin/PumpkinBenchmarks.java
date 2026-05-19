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

package me.lucko.luckperms.pumpkin;

import me.lucko.luckperms.common.cacheddata.type.PermissionCache;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import net.luckperms.api.query.QueryOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark suite for the Pumpkin platform adapter.
 *
 * <p>Provides benchmarks for permission lookup latency, concurrent reads,
 * inheritance depth traversal, cache rebuild speed, and player load
 * performance.</p>
 */
public final class PumpkinBenchmarks {

    private PumpkinBenchmarks() {}

    /**
     * Runs all benchmarks and logs results.
     */
    public static void runAll(LPPumpkinPlugin plugin) {
        PluginLogger logger = plugin.getLogger();
        logger.info("[Benchmark] Starting PumpkinPerms benchmark suite...");

        benchmarkPermissionLookup(plugin, logger);
        benchmarkConcurrentReads(plugin, logger);
        benchmarkCacheRebuild(plugin, logger);
        benchmarkPlayerLoad(plugin, logger);

        logger.info("[Benchmark] Benchmark suite complete.");
    }

    /**
     * Benchmarks single-thread permission lookup latency.
     */
    public static void benchmarkPermissionLookup(LPPumpkinPlugin plugin, PluginLogger logger) {
        int iterations = 10000;
        String[] permissions = {
                "luckperms.user.info",
                "minecraft.command.tp",
                "essentials.home",
                "worldedit.wand",
                "some.deeply.nested.permission.node.that.is.very.long"
        };

        User user = getFirstOnlineUser(plugin);
        if (user == null) {
            logger.info("[Benchmark] Permission lookup: skipped (no online users)");
            return;
        }

        QueryOptions queryOptions = plugin.getContextManager().getStaticQueryOptions();
        PermissionCache cache = user.getCachedData().getPermissionData(queryOptions);

        // warmup
        for (int i = 0; i < 1000; i++) {
            for (String perm : permissions) {
                cache.checkPermission(perm);
            }
        }

        // benchmark
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (String perm : permissions) {
                cache.checkPermission(perm);
            }
        }
        long elapsed = System.nanoTime() - start;
        long totalChecks = (long) iterations * permissions.length;
        double avgNanos = (double) elapsed / totalChecks;

        logger.info("[Benchmark] Permission lookup: " + totalChecks + " checks in " +
                TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms (avg " +
                String.format("%.1f", avgNanos) + "ns/check)");
    }

    /**
     * Benchmarks concurrent permission reads from multiple threads.
     */
    public static void benchmarkConcurrentReads(LPPumpkinPlugin plugin, PluginLogger logger) {
        int threadCount = 8;
        int checksPerThread = 5000;

        User user = getFirstOnlineUser(plugin);
        if (user == null) {
            logger.info("[Benchmark] Concurrent reads: skipped (no online users)");
            return;
        }

        QueryOptions queryOptions = plugin.getContextManager().getStaticQueryOptions();
        PermissionCache cache = user.getCachedData().getPermissionData(queryOptions);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong totalNanos = new AtomicLong(0);

        long start = System.nanoTime();
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    long threadStart = System.nanoTime();
                    for (int i = 0; i < checksPerThread; i++) {
                        cache.checkPermission("test.concurrent.permission." + (i % 100));
                    }
                    totalNanos.addAndGet(System.nanoTime() - threadStart);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsed = System.nanoTime() - start;
        executor.shutdown();

        long totalChecks = (long) threadCount * checksPerThread;
        double throughput = totalChecks / (elapsed / 1_000_000_000.0);

        logger.info("[Benchmark] Concurrent reads: " + totalChecks + " checks across " +
                threadCount + " threads in " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms (" +
                String.format("%.0f", throughput) + " checks/sec)");
    }

    /**
     * Benchmarks cache rebuild / recalculation speed.
     */
    public static void benchmarkCacheRebuild(LPPumpkinPlugin plugin, PluginLogger logger) {
        int iterations = 100;

        User user = getFirstOnlineUser(plugin);
        if (user == null) {
            logger.info("[Benchmark] Cache rebuild: skipped (no online users)");
            return;
        }

        // warmup
        for (int i = 0; i < 10; i++) {
            user.getCachedData().invalidate();
            QueryOptions queryOptions = plugin.getContextManager().getStaticQueryOptions();
            user.getCachedData().getPermissionData(queryOptions);
        }

        // benchmark
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            user.getCachedData().invalidate();
            QueryOptions queryOptions = plugin.getContextManager().getStaticQueryOptions();
            user.getCachedData().getPermissionData(queryOptions);
        }
        long elapsed = System.nanoTime() - start;
        double avgMs = (double) TimeUnit.NANOSECONDS.toMicros(elapsed) / iterations / 1000.0;

        logger.info("[Benchmark] Cache rebuild: " + iterations + " rebuilds in " +
                TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms (avg " +
                String.format("%.2f", avgMs) + "ms/rebuild)");
    }

    /**
     * Benchmarks player data load performance.
     */
    public static void benchmarkPlayerLoad(LPPumpkinPlugin plugin, PluginLogger logger) {
        int iterations = 50;

        long start = System.nanoTime();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            UUID testUuid = UUID.randomUUID();
            CompletableFuture<?> future = plugin.getStorage().loadUser(testUuid, null);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long elapsed = System.nanoTime() - start;
        double avgMs = (double) TimeUnit.NANOSECONDS.toMicros(elapsed) / iterations / 1000.0;

        logger.info("[Benchmark] Player load: " + iterations + " loads in " +
                TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms (avg " +
                String.format("%.2f", avgMs) + "ms/load)");
    }

    private static User getFirstOnlineUser(LPPumpkinPlugin plugin) {
        for (UUID uuid : plugin.getBootstrap().getOnlinePlayers()) {
            User user = plugin.getUserManager().getIfLoaded(uuid);
            if (user != null) {
                return user;
            }
        }
        return null;
    }
}
