package com.hazelcast.simulator.tests.special;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.simulator.test.TestContext;
import com.hazelcast.simulator.test.annotations.Run;
import com.hazelcast.simulator.test.annotations.Setup;
import com.hazelcast.simulator.test.annotations.Verify;
import com.hazelcast.simulator.test.annotations.Warmup;
import com.hazelcast.simulator.utils.ThreadSpawner;

import java.util.concurrent.TimeUnit;

import static com.hazelcast.simulator.tests.helpers.HazelcastTestUtils.isClient;
import static com.hazelcast.simulator.tests.helpers.HazelcastTestUtils.nextKeyOwnedBy;
import static com.hazelcast.simulator.utils.CommonUtils.humanReadableByteCount;
import static java.lang.String.format;

/**
 * Fills up the cluster to a defined heap usage factor during warmup phase.
 *
 * This tests intentionally has an empty run phase.
 */
public class MapHeapHogWarmupTest {

    private static final long APPROX_ENTRY_BYTES_SIZE = 238;
    private static final ILogger LOGGER = Logger.getLogger(MapHeapHogWarmupTest.class);

    // properties
    public String basename = this.getClass().getSimpleName();
    public int threadCount = 10;
    public int logFrequency = 50000;
    public int ttlHours = 24;
    public double approxHeapUsageFactor = 0.9;

    private HazelcastInstance targetInstance;
    private ThreadSpawner threadSpawner;
    private IMap<Long, Long> map;

    private long maxEntriesPerThread;

    @Setup
    public void setUp(TestContext testContext) {
        targetInstance = testContext.getTargetInstance();
        threadSpawner = new ThreadSpawner(testContext.getTestId());
        map = targetInstance.getMap(basename);

        // calculate how many entries we have to insert per member and thread
        long free = Runtime.getRuntime().freeMemory();
        long total = Runtime.getRuntime().totalMemory();
        long max = Runtime.getRuntime().maxMemory();

        long used = total - free;
        long totalFree = max - used;

        long maxLocalEntries = (long) ((totalFree / (double) APPROX_ENTRY_BYTES_SIZE) * approxHeapUsageFactor);
        maxEntriesPerThread = (long) (maxLocalEntries / (double) threadCount);
    }

    @Warmup
    public void warmup() {
        if (isClient(targetInstance)) {
            return;
        }

        // fill the cluster as fast as possible with data
        long startKey = 0;
        boolean isLogger = true;
        for (int i = 0; i < threadCount; i++) {
            threadSpawner.spawn(new FillMapWorker(isLogger, startKey));
            isLogger = false;
            startKey += maxEntriesPerThread;
        }
        threadSpawner.awaitCompletion();

        StringBuilder sb = new StringBuilder(basename).append(": After warmup phase the map size is ").append(map.size());
        addMemoryStatistics(sb);
        LOGGER.info(sb.toString());
    }

    @Verify
    public void verify() {
        if (isClient(targetInstance)) {
            return;
        }

        StringBuilder sb = new StringBuilder(basename).append(": In local verify phase the map size is ").append(map.size());
        addMemoryStatistics(sb);
        LOGGER.info(sb.toString());
    }

    @Run
    public void run() {
        // nothing to do here
    }

    private final class FillMapWorker implements Runnable {

        private final boolean isLogger;

        private long key;

        private FillMapWorker(boolean isLogger, long startKey) {
            this.isLogger = isLogger;
            this.key = startKey;
        }

        @Override
        public void run() {
            StringBuilder sb = new StringBuilder();
            for (int workerIteration = 0; workerIteration < maxEntriesPerThread; workerIteration++) {
                key = nextKeyOwnedBy(targetInstance, key);
                map.put(key, key, ttlHours, TimeUnit.HOURS);
                key++;

                if (isLogger && key % logFrequency == 0) {
                    sb.append(basename).append(": In warmup phase the map size is ").append(map.size());
                    addMemoryStatistics(sb);
                    LOGGER.info(sb.toString());
                    sb.setLength(0);
                }
            }
        }
    }

    private static void addMemoryStatistics(StringBuilder sb) {
        long free = Runtime.getRuntime().freeMemory();
        long total = Runtime.getRuntime().totalMemory();
        long max = Runtime.getRuntime().maxMemory();

        long used = total - free;
        long totalFree = max - used;
        double usedOfMax = 100.0 * ((double) used / (double) max);

        sb.append("\nfree = ").append(humanReadableByteCount(free, true)).append(" (").append(free).append(")")
                .append("\ntotal free = ").append(humanReadableByteCount(totalFree, true))
                .append(" (").append(totalFree).append(")")
                .append("\nused = ").append(humanReadableByteCount(used, true)).append(" (").append(used).append(")")
                .append("\nmax = ").append(humanReadableByteCount(max, true)).append(" (").append(max).append(")")
                .append(format("\nusedOfMax = %.2f%%", usedOfMax));
    }
}
