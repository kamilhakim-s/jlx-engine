package com.lowlatency.gateway;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * The order-entry wire format: a <b>flat, fixed-layout binary message</b> written into and read out of an
 * Agrona buffer with no intermediate objects. This is the codec the gateway speaks over Aeron.
 *
 * <p>Why hand-rolled binary rather than JSON? On the wire, latency engineers want a message that is:
 * <ul>
 *   <li><b>fixed-size and fixed-offset</b> — every field lives at a known byte offset, so decoding is a
 *       handful of aligned reads straight out of the network buffer (no parsing, no branching on
 *       structure);</li>
 *   <li><b>zero-copy / zero-allocation to decode</b> — {@link #decode}-style accessors read directly from
 *       the {@link DirectBuffer} Aeron hands us; we never build an intermediate {@code OrderMessage}
 *       object, so there's nothing for the GC to collect on the hot path (the Chunk 2 lesson, now applied
 *       to the wire).</li>
 * </ul>
 *
 * <p>This is, deliberately, a tiny hand-rolled version of what <b>SBE</b> (Simple Binary Encoding) — the
 * canonical low-latency codec, generated from an XML schema and used with Aeron in production — does for
 * you with versioning and code-gen. We hand-roll it here to keep the chunk focused on the transport; the
 * doc points at SBE as the production step up.
 *
 * <p><b>Byte order.</b> We use the buffer's native order (fast on the same machine, which is all the
 * IPC demo needs). A real cross-host protocol pins an explicit endianness; noted in the doc.
 */
public final class OrderMessage {

    /** Wire message kinds. Byte-valued so they cost one byte on the wire. */
    public static final byte TYPE_NEW_ORDER = 1;
    public static final byte TYPE_CANCEL = 2;

    /** Side encoding (matches the order of {@link com.lowlatency.engine.Side}). */
    public static final byte SIDE_BUY = 0;
    public static final byte SIDE_SELL = 1;

    /** Order-type encoding (matches the order of {@link com.lowlatency.engine.OrderType}). */
    public static final byte ORD_TYPE_LIMIT = 0;
    public static final byte ORD_TYPE_MARKET = 1;

    /** The single protocol version we speak; lets a future change reject mismatched peers. */
    public static final byte VERSION = 1;

    // Fixed field offsets. The four header bytes are followed by padding to an 8-byte boundary so the
    // long fields are aligned (aligned 64-bit reads are cheaper and never tear).
    private static final int VERSION_OFFSET = 0;
    private static final int TYPE_OFFSET = 1;
    private static final int SIDE_OFFSET = 2;
    private static final int ORD_TYPE_OFFSET = 3;
    private static final int ORDER_ID_OFFSET = 8;
    private static final int PRICE_OFFSET = 16;
    private static final int QUANTITY_OFFSET = 24;
    private static final int INGRESS_OFFSET = 32;

    /** Total encoded length in bytes. Every order-entry message is exactly this size. */
    public static final int MESSAGE_LENGTH = 40;

    /**
     * Encodes a NEW_ORDER into {@code buffer} at offset 0. {@code ingressNanos} is the sender's intended
     * send instant (a {@code System.nanoTime()} reading), carried through to the engine so it can measure
     * end-to-end latency from when the order was <i>due</i> — the coordinated-omission-safe rule.
     */
    public static void encodeNewOrder(MutableDirectBuffer buffer, byte side, byte orderType,
                                      long orderId, long price, long quantity, long ingressNanos) {
        buffer.putByte(VERSION_OFFSET, VERSION);
        buffer.putByte(TYPE_OFFSET, TYPE_NEW_ORDER);
        buffer.putByte(SIDE_OFFSET, side);
        buffer.putByte(ORD_TYPE_OFFSET, orderType);
        buffer.putLong(ORDER_ID_OFFSET, orderId);
        buffer.putLong(PRICE_OFFSET, price);
        buffer.putLong(QUANTITY_OFFSET, quantity);
        buffer.putLong(INGRESS_OFFSET, ingressNanos);
    }

    /** Encodes a CANCEL into {@code buffer} at offset 0. Only the order id and ingress stamp matter. */
    public static void encodeCancel(MutableDirectBuffer buffer, long orderId, long ingressNanos) {
        buffer.putByte(VERSION_OFFSET, VERSION);
        buffer.putByte(TYPE_OFFSET, TYPE_CANCEL);
        buffer.putByte(SIDE_OFFSET, (byte) 0);
        buffer.putByte(ORD_TYPE_OFFSET, (byte) 0);
        buffer.putLong(ORDER_ID_OFFSET, orderId);
        buffer.putLong(PRICE_OFFSET, 0);
        buffer.putLong(QUANTITY_OFFSET, 0);
        buffer.putLong(INGRESS_OFFSET, ingressNanos);
    }

    // --- Zero-copy accessors: read straight from the buffer Aeron delivered, at the fragment's offset. ---

    public static byte version(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + VERSION_OFFSET);
    }

    public static byte type(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + TYPE_OFFSET);
    }

    public static byte side(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + SIDE_OFFSET);
    }

    public static byte orderType(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + ORD_TYPE_OFFSET);
    }

    public static long orderId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + ORDER_ID_OFFSET);
    }

    public static long price(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + PRICE_OFFSET);
    }

    public static long quantity(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + QUANTITY_OFFSET);
    }

    public static long ingressNanos(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + INGRESS_OFFSET);
    }

    private OrderMessage() {
    }
}
