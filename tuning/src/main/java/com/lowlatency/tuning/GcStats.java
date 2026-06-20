package com.lowlatency.tuning;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * A point-in-time snapshot of the JVM's garbage collectors, used to measure GC activity <i>across</i>
 * a benchmark run. Take one before the measured loop and one after; {@link #since(GcStats)} reports the
 * collections and total pause time that happened in between.
 *
 * <p>This is the observability half of Chunk 7. In low-latency work a GC pause is a tail-latency event
 * — a "stop the world" stall that shows up as a p99.9 spike (exactly the coordinated-omission victim
 * from Chunk 0). So when we compare collectors, the headline number isn't throughput, it's <b>how many
 * times the world stopped and for how long</b>. The data comes from {@link GarbageCollectorMXBean},
 * which every HotSpot collector exposes for free.
 *
 * <p>Note: under {@code -XX:+UseEpsilonGC} (the no-op collector) the counts stay at zero — there is no
 * collector to report pauses, which is the whole point of that experiment.
 */
public final class GcStats {

    /** One collector's cumulative counters at snapshot time. */
    public record Collector(String name, long collectionCount, long collectionTimeMillis) {
    }

    private final List<Collector> collectors;

    private GcStats(List<Collector> collectors) {
        this.collectors = collectors;
    }

    /** Captures the cumulative counters of every GC collector currently registered with the JVM. */
    public static GcStats snapshot() {
        List<Collector> list = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();   // -1 means "not available"; normalise to 0
            long time = bean.getCollectionTime();
            list.add(new Collector(bean.getName(), Math.max(count, 0), Math.max(time, 0)));
        }
        return new GcStats(list);
    }

    /** Total collections across all collectors since {@code earlier}. */
    public long collectionsSince(GcStats earlier) {
        long total = 0;
        for (Collector c : collectors) {
            total += c.collectionCount() - earlier.countFor(c.name());
        }
        return total;
    }

    /** Total stop-the-world pause time (ms) across all collectors since {@code earlier}. */
    public long pauseMillisSince(GcStats earlier) {
        long total = 0;
        for (Collector c : collectors) {
            total += c.collectionTimeMillis() - earlier.timeFor(c.name());
        }
        return total;
    }

    /** A one-line human summary of the GC activity that happened between {@code earlier} and now. */
    public String since(GcStats earlier) {
        StringBuilder sb = new StringBuilder();
        long totalCollections = collectionsSince(earlier);
        long totalPause = pauseMillisSince(earlier);
        sb.append(String.format("%d collections, %d ms total pause", totalCollections, totalPause));
        if (totalCollections > 0) {
            sb.append(" [");
            boolean first = true;
            for (Collector c : collectors) {
                long delta = c.collectionCount() - earlier.countFor(c.name());
                if (delta == 0) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                sb.append(String.format("%s ×%d/%dms", c.name(), delta,
                        c.collectionTimeMillis() - earlier.timeFor(c.name())));
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** Names of the active collectors, e.g. "G1 Young Generation + G1 Old Generation". */
    public String collectorNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < collectors.size(); i++) {
            if (i > 0) {
                sb.append(" + ");
            }
            sb.append(collectors.get(i).name());
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private long countFor(String name) {
        for (Collector c : collectors) {
            if (c.name().equals(name)) {
                return c.collectionCount();
            }
        }
        return 0;
    }

    private long timeFor(String name) {
        for (Collector c : collectors) {
            if (c.name().equals(name)) {
                return c.collectionTimeMillis();
            }
        }
        return 0;
    }
}
