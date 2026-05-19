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

import me.lucko.luckperms.common.plugin.logging.PluginLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Diagnostics utilities for the Pumpkin platform adapter.
 *
 * <p>Provides thread diagnostics, scheduler diagnostics, and permission
 * tracing facilities for debugging LuckPerms on the multi-threaded
 * Pumpkin platform.</p>
 */
public final class PumpkinDiagnostics {
    private static final AtomicLong PERMISSION_CHECK_COUNT = new AtomicLong(0);
    private static final AtomicLong CACHE_HIT_COUNT = new AtomicLong(0);
    private static final AtomicLong CACHE_MISS_COUNT = new AtomicLong(0);

    private PumpkinDiagnostics() {}

    /**
     * Records a permission check for diagnostics tracking.
     */
    public static void recordPermissionCheck() {
        PERMISSION_CHECK_COUNT.incrementAndGet();
    }

    /**
     * Records a cache hit for diagnostics tracking.
     */
    public static void recordCacheHit() {
        CACHE_HIT_COUNT.incrementAndGet();
    }

    /**
     * Records a cache miss for diagnostics tracking.
     */
    public static void recordCacheMiss() {
        CACHE_MISS_COUNT.incrementAndGet();
    }

    /**
     * Returns the total number of permission checks recorded.
     */
    public static long getPermissionCheckCount() {
        return PERMISSION_CHECK_COUNT.get();
    }

    /**
     * Returns the cache hit count.
     */
    public static long getCacheHitCount() {
        return CACHE_HIT_COUNT.get();
    }

    /**
     * Returns the cache miss count.
     */
    public static long getCacheMissCount() {
        return CACHE_MISS_COUNT.get();
    }

    /**
     * Resets all diagnostic counters.
     */
    public static void resetCounters() {
        PERMISSION_CHECK_COUNT.set(0);
        CACHE_HIT_COUNT.set(0);
        CACHE_MISS_COUNT.set(0);
    }

    /**
     * Dumps thread diagnostics to the logger, including LuckPerms worker
     * threads and scheduler threads.
     */
    public static void dumpThreadDiagnostics(PluginLogger logger) {
        logger.info("[Diagnostics] Thread dump at " + Instant.now());
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

        for (ThreadInfo info : threadInfos) {
            String name = info.getThreadName();
            if (name.startsWith("luckperms-") || name.contains("pumpkin") || name.contains("scheduler")) {
                logger.info("[Diagnostics] Thread: " + name + " State: " + info.getThreadState());
                String stackTrace = Arrays.stream(info.getStackTrace())
                        .limit(5)
                        .map(el -> "    at " + el)
                        .collect(Collectors.joining("\n"));
                if (!stackTrace.isEmpty()) {
                    logger.info(stackTrace);
                }
            }
        }
    }

    /**
     * Logs a summary of permission diagnostics.
     */
    public static void logDiagnosticsSummary(PluginLogger logger) {
        long checks = PERMISSION_CHECK_COUNT.get();
        long hits = CACHE_HIT_COUNT.get();
        long misses = CACHE_MISS_COUNT.get();
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100.0 : 0.0;

        logger.info("[Diagnostics] Permission checks: " + checks);
        logger.info("[Diagnostics] Cache hits: " + hits + ", misses: " + misses +
                " (hit rate: " + String.format("%.1f", hitRate) + "%)");

        Runtime runtime = Runtime.getRuntime();
        long usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMem = runtime.maxMemory() / (1024 * 1024);
        logger.info("[Diagnostics] Memory: " + usedMem + "MB / " + maxMem + "MB");
    }

    /**
     * Logs a permission trace entry for debugging.
     */
    public static void tracePermission(PluginLogger logger, String playerName, String permission, boolean result) {
        logger.info("[PermTrace] " + playerName + " -> " + permission + " = " + result +
                " [thread: " + Thread.currentThread().getName() + "]");
    }
}
