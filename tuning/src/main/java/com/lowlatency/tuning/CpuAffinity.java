package com.lowlatency.tuning;

import net.openhft.affinity.AffinityLock;

/**
 * Best-effort CPU pinning for the calling thread, via OpenHFT Java-Thread-Affinity.
 *
 * <p><b>Why pin?</b> By default the OS scheduler is free to migrate your thread between cores. Every
 * migration is a tail-latency event: the new core's L1/L2 caches are cold, so the next few thousand
 * instructions stall on cache misses; you may also cross a NUMA boundary. Pinning the latency-critical
 * thread to one core keeps its working set hot and removes scheduler jitter — one of the biggest
 * remaining wins after the wait-strategy choice. In a real deployment you go further: {@code isolcpus}
 * / {@code nohz_full} on the kernel command line to evict <i>everything else</i> from that core, then
 * pin the engine thread to it. Then a busy-spin consumer truly owns the core.
 *
 * <p><b>Honesty about portability.</b> Real pinning only happens on Linux. On macOS and Windows the
 * OS exposes no thread-to-core binding API, so the library degrades to a no-op and {@link #isReal()}
 * returns false — the code path still runs and teaches the API, it just doesn't bind. That's why this
 * is a <i>demonstration</i> on a laptop and a <i>production technique</i> on an isolated Linux box.
 *
 * <p>Usage is try-with-resources so the lock is always released:
 * <pre>{@code
 * try (CpuAffinity ignored = CpuAffinity.pinToAnyCore()) {
 *     // latency-critical work runs on a fixed core
 * }
 * }</pre>
 */
public final class CpuAffinity implements AutoCloseable {

    private final AffinityLock lock;
    private final boolean real;

    private CpuAffinity(AffinityLock lock, boolean real) {
        this.lock = lock;
        this.real = real;
    }

    /**
     * Acquires a free core and binds the current thread to it, if the platform supports it. Never
     * throws: if the native library is unavailable or pinning isn't supported, returns a no-op handle.
     */
    public static CpuAffinity pinToAnyCore() {
        try {
            AffinityLock lock = AffinityLock.acquireLock();
            // acquireLock() returns a lock with a bound CPU id only when it actually bound a core.
            boolean bound = lock != null && lock.cpuId() >= 0 && lock.isAllocated();
            return new CpuAffinity(lock, bound);
        } catch (Throwable t) {
            // Missing JNA, unsupported OS, sandboxed environment — degrade to a no-op rather than fail.
            return new CpuAffinity(null, false);
        }
    }

    /** True only if the current thread is actually bound to a single core (Linux with a free core). */
    public boolean isReal() {
        return real;
    }

    /** The core this thread is bound to, or -1 if pinning is a no-op on this platform. */
    public int boundCore() {
        try {
            return (lock != null && real) ? lock.cpuId() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** A short human description for the benchmark header. */
    public String describe() {
        return real ? ("pinned to core " + boundCore()) : "not pinned (no-op on this OS)";
    }

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (Throwable ignored) {
            // releasing a no-op lock can throw on some platforms; ignore.
        }
    }
}
