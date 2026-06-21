package com.lowlatency.gateway;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

/**
 * The order-entry side of the wire: encodes orders with {@link OrderMessage} and offers them to an Aeron
 * {@link Publication}. One client owns one reusable off-heap buffer, so sending an order allocates
 * nothing — it overwrites the buffer in place and hands its address to Aeron.
 *
 * <p><b>Backpressure is explicit.</b> {@link Publication#offer} returns a <i>negative</i> value when the
 * message could not be placed — the subscriber is behind (back-pressured), not yet connected, or the
 * driver is mid-admin. Unlike a blocking socket, Aeron never silently buffers without bound: the sender
 * decides what to do. Here we spin-retry (the lowest-latency policy) and count how often we had to, so
 * backpressure is observable rather than hidden.
 */
public final class OrderEntryClient implements AutoCloseable {

    private final Publication publication;
    // Off-heap, cache-line-friendly buffer reused for every message. DirectByteBuffer so Aeron can take
    // the bytes without a copy through the heap.
    private final UnsafeBuffer buffer =
            new UnsafeBuffer(ByteBuffer.allocateDirect(OrderMessage.MESSAGE_LENGTH));

    private long backpressureCount;

    public OrderEntryClient(Publication publication) {
        this.publication = publication;
    }

    /** Blocks (spinning) until the gateway's subscription is connected to this publication. */
    public void awaitConnected() {
        while (!publication.isConnected()) {
            LockSupport.parkNanos(1_000);
        }
    }

    public void sendNewOrder(long orderId, Side side, OrderType orderType,
                             long price, long quantity, long ingressNanos) {
        OrderMessage.encodeNewOrder(buffer, encodeSide(side), encodeOrderType(orderType),
                orderId, price, quantity, ingressNanos);
        offer();
    }

    public void sendCancel(long orderId, long ingressNanos) {
        OrderMessage.encodeCancel(buffer, orderId, ingressNanos);
        offer();
    }

    /** Number of times {@code offer} was back-pressured and retried (a load/health signal). */
    public long backpressureCount() {
        return backpressureCount;
    }

    private void offer() {
        long result;
        while ((result = publication.offer(buffer, 0, OrderMessage.MESSAGE_LENGTH)) < 0) {
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                throw new IllegalStateException("publication unusable: " + result);
            }
            backpressureCount++;     // BACK_PRESSURED / NOT_CONNECTED / ADMIN_ACTION — retry
            Thread.onSpinWait();
        }
    }

    private static byte encodeSide(Side side) {
        return side == Side.BUY ? OrderMessage.SIDE_BUY : OrderMessage.SIDE_SELL;
    }

    private static byte encodeOrderType(OrderType type) {
        return type == OrderType.LIMIT ? OrderMessage.ORD_TYPE_LIMIT : OrderMessage.ORD_TYPE_MARKET;
    }

    @Override
    public void close() {
        publication.close();
    }
}
