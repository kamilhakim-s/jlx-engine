package com.lowlatency.gateway;

import com.lowlatency.engine.OrderType;
import com.lowlatency.engine.Side;
import com.lowlatency.engine.disruptor.DisruptorMatchingService;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.IdleStrategy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The order-entry gateway: the seam between the network and the matching engine. It owns an Aeron
 * {@link Subscription} and a dedicated <b>poll thread</b> that, for every delivered fragment, decodes the
 * flat {@link OrderMessage} <i>straight from Aeron's buffer</i> (zero copy, zero allocation) and publishes
 * it into the Chunk 3 {@link DisruptorMatchingService} ring buffer.
 *
 * <p>This preserves the architecture the whole project is built on: the gateway thread is the <b>single
 * writer</b> to the ring buffer, so the engine downstream stays lock-free and single-threaded. We simply
 * moved the <i>source</i> of commands from an in-process loop (Chunks 3–7) to the wire — the engine
 * doesn't know or care that orders now arrive over Aeron.
 *
 * <p>The {@code ingressNanos} stamp in each message is passed through to the engine, so the engine's
 * existing latency histogram measures the <b>full</b> path: client encode → Aeron → gateway decode → ring
 * buffer → match. That's the end-to-end number that matters once you're off a single thread.
 */
public final class OrderGateway {

    private static final int FRAGMENT_LIMIT = 64;   // max fragments drained per poll (mechanical batching)

    // enum values() allocates a fresh array per call; cache once so decoding stays zero-allocation.
    private static final Side[] SIDES = Side.values();
    private static final OrderType[] ORDER_TYPES = OrderType.values();

    private final Subscription subscription;
    private final DisruptorMatchingService engine;
    private final IdleStrategy idleStrategy;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final FragmentHandler fragmentHandler = this::onFragment;

    private volatile Thread pollThread;
    private long decodedCount;

    public OrderGateway(Subscription subscription, DisruptorMatchingService engine, IdleStrategy idleStrategy) {
        this.subscription = subscription;
        this.engine = engine;
        this.idleStrategy = idleStrategy;
    }

    /** Starts the poll thread. The engine must already be started. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::pollLoop, "order-gateway");
        t.setDaemon(true);
        this.pollThread = t;
        t.start();
    }

    private void pollLoop() {
        final FragmentHandler handler = fragmentHandler;
        final Subscription sub = subscription;
        final IdleStrategy idle = idleStrategy;
        while (running.get()) {
            int fragments = sub.poll(handler, FRAGMENT_LIMIT);
            idle.idle(fragments);   // spin/yield/park when there was nothing to do — the latency/CPU dial
        }
    }

    /** Decodes one wire message and publishes it into the engine ring buffer. Runs on the poll thread. */
    private void onFragment(org.agrona.DirectBuffer buffer, int offset, int length, io.aeron.logbuffer.Header header) {
        byte type = OrderMessage.type(buffer, offset);
        long orderId = OrderMessage.orderId(buffer, offset);
        long ingress = OrderMessage.ingressNanos(buffer, offset);
        if (type == OrderMessage.TYPE_NEW_ORDER) {
            Side side = SIDES[OrderMessage.side(buffer, offset)];
            OrderType orderType = ORDER_TYPES[OrderMessage.orderType(buffer, offset)];
            long price = OrderMessage.price(buffer, offset);
            long quantity = OrderMessage.quantity(buffer, offset);
            engine.publishNewOrder(orderId, side, orderType, price, quantity, ingress);
        } else if (type == OrderMessage.TYPE_CANCEL) {
            engine.publishCancel(orderId, ingress);
        }
        decodedCount++;
    }

    /** Number of wire messages decoded and forwarded to the engine (poll-thread local; read after stop). */
    public long decodedCount() {
        return decodedCount;
    }

    /** Stops the poll thread and waits for it to finish. Does not close the engine or subscription. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread t = pollThread;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
