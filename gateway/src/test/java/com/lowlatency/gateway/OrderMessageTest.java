package com.lowlatency.gateway;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the flat binary wire codec round-trips every field at its fixed offset. */
class OrderMessageTest {

    private final UnsafeBuffer buffer =
            new UnsafeBuffer(ByteBuffer.allocateDirect(OrderMessage.MESSAGE_LENGTH));

    @Test
    void newOrderRoundTrips() {
        OrderMessage.encodeNewOrder(buffer, OrderMessage.SIDE_BUY, OrderMessage.ORD_TYPE_LIMIT,
                42L, 100_000L, 7L, 123_456_789L);

        assertThat(OrderMessage.version(buffer, 0)).isEqualTo(OrderMessage.VERSION);
        assertThat(OrderMessage.type(buffer, 0)).isEqualTo(OrderMessage.TYPE_NEW_ORDER);
        assertThat(OrderMessage.side(buffer, 0)).isEqualTo(OrderMessage.SIDE_BUY);
        assertThat(OrderMessage.orderType(buffer, 0)).isEqualTo(OrderMessage.ORD_TYPE_LIMIT);
        assertThat(OrderMessage.orderId(buffer, 0)).isEqualTo(42L);
        assertThat(OrderMessage.price(buffer, 0)).isEqualTo(100_000L);
        assertThat(OrderMessage.quantity(buffer, 0)).isEqualTo(7L);
        assertThat(OrderMessage.ingressNanos(buffer, 0)).isEqualTo(123_456_789L);
    }

    @Test
    void cancelRoundTrips() {
        OrderMessage.encodeCancel(buffer, 99L, 555L);

        assertThat(OrderMessage.type(buffer, 0)).isEqualTo(OrderMessage.TYPE_CANCEL);
        assertThat(OrderMessage.orderId(buffer, 0)).isEqualTo(99L);
        assertThat(OrderMessage.ingressNanos(buffer, 0)).isEqualTo(555L);
    }

    @Test
    void decodesAtANonZeroOffset() {
        // Aeron delivers fragments at an arbitrary offset into a shared buffer; the accessors must honour
        // it. Encode at 0, then read the same bytes copied to offset 64 of a larger buffer.
        OrderMessage.encodeNewOrder(buffer, OrderMessage.SIDE_SELL, OrderMessage.ORD_TYPE_MARKET,
                7L, 200L, 3L, 9L);
        UnsafeBuffer wide = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
        wide.putBytes(64, buffer, 0, OrderMessage.MESSAGE_LENGTH);

        assertThat(OrderMessage.side(wide, 64)).isEqualTo(OrderMessage.SIDE_SELL);
        assertThat(OrderMessage.orderType(wide, 64)).isEqualTo(OrderMessage.ORD_TYPE_MARKET);
        assertThat(OrderMessage.orderId(wide, 64)).isEqualTo(7L);
        assertThat(OrderMessage.price(wide, 64)).isEqualTo(200L);
        assertThat(OrderMessage.quantity(wide, 64)).isEqualTo(3L);
    }
}
