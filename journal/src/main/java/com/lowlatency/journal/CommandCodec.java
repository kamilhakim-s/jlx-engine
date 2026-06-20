package com.lowlatency.journal;

import com.lowlatency.engine.FastMatchingEngine;
import com.lowlatency.engine.Order;
import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.CommandType;
import com.lowlatency.engine.disruptor.OrderCommand;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;

/**
 * Serialises an {@link OrderCommand} to a Chronicle {@link WireOut} and applies a journalled record
 * back onto a {@link FastMatchingEngine}. A <b>fixed schema</b> (always the same six fields, in the
 * same order) keeps reads simple and robust: for a {@code CANCEL} the order-specific fields are
 * written as neutral sentinels rather than the (reused) event's stale values.
 *
 * <p>This is the contract that makes deterministic replay possible: write exactly what the engine
 * consumed, read it back in the same order, and apply it with the identical logic the live consumer
 * used — so a fresh engine re-derives byte-for-byte the same trades.
 */
final class CommandCodec {

    static void write(WireOut wire, OrderCommand command) {
        boolean isNew = command.type() == CommandType.NEW_ORDER;
        wire.write("t").int8((byte) command.type().ordinal())
                .write("id").int64(command.orderId())
                .write("s").int8(isNew ? (byte) command.side().ordinal() : (byte) -1)
                .write("o").int8(isNew ? (byte) command.orderType().ordinal() : (byte) -1)
                .write("p").int64(isNew ? command.price() : 0L)
                .write("q").int64(isNew ? command.quantity() : 0L);
    }

    /** Reads one journalled command and applies it to {@code engine}, exactly as the live consumer did. */
    static void apply(WireIn wire, FastMatchingEngine engine) {
        byte type = wire.read("t").int8();
        long orderId = wire.read("id").int64();
        byte side = wire.read("s").int8();
        byte orderType = wire.read("o").int8();
        long price = wire.read("p").int64();
        long quantity = wire.read("q").int64();

        if (CommandType.values()[type] == CommandType.NEW_ORDER) {
            Order order = engine.orderPool().acquire();
            order.reset(orderId, Side.values()[side], OrderType.values()[orderType], price, quantity);
            engine.submit(order);
        } else {
            engine.cancel(orderId);
        }
    }

    private CommandCodec() {
    }
}
