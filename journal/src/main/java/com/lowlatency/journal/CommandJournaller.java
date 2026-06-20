package com.lowlatency.journal;

import com.lowlatency.engine.disruptor.OrderCommand;
import com.lmax.disruptor.EventHandler;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

import java.io.Closeable;
import java.nio.file.Path;

/**
 * Appends every {@link OrderCommand} to a Chronicle Queue. Wired as a <b>parallel</b> Disruptor
 * consumer (see {@link com.lowlatency.engine.disruptor.DisruptorMatchingService}), it sees the exact
 * same command sequence the matching engine does, in the same order — the precondition for
 * deterministic replay.
 *
 * <p>Chronicle Queue is an off-heap, <b>memory-mapped</b> persisted queue: appends are writes into a
 * memory-mapped file region (no per-record file I/O syscall, no serialization to the heap), which is
 * why journaling can keep up with a low-latency engine. The queue is a directory of roll-files on disk.
 *
 * <p>This handler runs on a single thread (its own Disruptor consumer thread), so the thread-local
 * appender is used safely from one thread.
 */
public final class CommandJournaller implements EventHandler<OrderCommand>, Closeable {

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private volatile long journalledCount;

    public CommandJournaller(Path journalDir) {
        this.queue = SingleChronicleQueueBuilder.single(journalDir.toString()).build();
        this.appender = queue.createAppender();
    }

    @Override
    public void onEvent(OrderCommand command, long sequence, boolean endOfBatch) {
        appender.writeDocument(wire -> CommandCodec.write(wire, command));
        journalledCount++;
    }

    public long journalledCount() {
        return journalledCount;
    }

    @Override
    public void close() {
        queue.close();
    }
}
