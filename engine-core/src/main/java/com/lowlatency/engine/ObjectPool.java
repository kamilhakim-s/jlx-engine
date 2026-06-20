package com.lowlatency.engine;

import java.util.function.Supplier;

/**
 * A trivial, single-threaded free-list pool. Acquiring reuses a previously released object instead
 * of allocating, which keeps steady-state allocation at zero and — just as importantly — avoids
 * creating garbage that the GC must later collect (a GC pause is a latency spike, see
 * {@code docs/00-foundations.md}).
 *
 * <p>It is intentionally not thread-safe: the whole engine runs on a single writer thread (Chunk 3),
 * so synchronisation would be pure overhead. The pool is pre-filled on construction so the hot path
 * never calls the factory.
 *
 * @param <T> pooled type; must be resettable (e.g. {@link Order#reset}, {@link PriceLevel#reset}).
 */
public final class ObjectPool<T> {

    private final Supplier<T> factory;
    private Object[] stack;
    private int size;

    public ObjectPool(Supplier<T> factory, int initialCapacity) {
        this.factory = factory;
        this.stack = new Object[Math.max(1, initialCapacity)];
        for (int i = 0; i < initialCapacity; i++) {
            stack[size++] = factory.get(); // pre-fill so the hot path never allocates
        }
    }

    /** Returns a pooled instance, or a freshly created one if the pool ran dry (then grows on release). */
    @SuppressWarnings("unchecked")
    public T acquire() {
        return size > 0 ? (T) stack[--size] : factory.get();
    }

    /** Returns an instance to the pool for reuse. */
    public void release(T object) {
        if (size == stack.length) {
            Object[] grown = new Object[stack.length * 2];
            System.arraycopy(stack, 0, grown, 0, size);
            stack = grown;
        }
        stack[size++] = object;
    }

    /** Number of instances currently available — used by tests to assert recycling. */
    public int available() {
        return size;
    }
}
