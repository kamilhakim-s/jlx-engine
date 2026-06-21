package com.lowlatency.tuning;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;

/**
 * Maps a short name to a Disruptor {@link WaitStrategy}. The wait strategy is the single biggest
 * latency/CPU dial in the system (Chunk 3): how the consumer waits when the ring buffer is empty.
 */
public final class WaitStrategies {

    public static WaitStrategy byName(String name) {
        return switch (name.toLowerCase()) {
            case "busyspin" -> new BusySpinWaitStrategy(); // never sleeps; lowest latency, burns a core
            case "yielding" -> new YieldingWaitStrategy(); // spins then Thread.yield()
            case "blocking" -> new BlockingWaitStrategy(); // lock + condition; lowest CPU, higher median
            case "sleeping" -> new SleepingWaitStrategy(); // spins then LockSupport.parkNanos
            default -> throw new IllegalArgumentException(
                    "Unknown wait strategy '" + name + "' (busyspin|yielding|blocking|sleeping)");
        };
    }

    private WaitStrategies() {
    }
}
