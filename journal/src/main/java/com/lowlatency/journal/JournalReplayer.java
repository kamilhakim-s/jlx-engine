package com.lowlatency.journal;

import com.lowlatency.engine.FastMatchingEngine;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;

import java.nio.file.Path;

/**
 * Rebuilds engine state after a crash by replaying the journal: read every persisted command in order
 * and apply it to a fresh {@link FastMatchingEngine}. Because the engine is deterministic and the
 * commands are replayed in their original order, the rebuilt engine reaches <b>exactly</b> the same
 * state — and emits exactly the same trades — as the engine that wrote the journal. This is the
 * recovery half of event sourcing.
 */
public final class JournalReplayer {

    /**
     * Replays the whole journal into {@code engine}.
     *
     * @return number of commands applied
     */
    public long replay(Path journalDir, FastMatchingEngine engine) {
        long applied = 0;
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.single(journalDir.toString()).build()) {
            ExcerptTailer tailer = queue.createTailer();
            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) {
                        break; // reached the end of the journal
                    }
                    CommandCodec.apply(dc.wire(), engine);
                    applied++;
                }
            }
        }
        return applied;
    }
}
