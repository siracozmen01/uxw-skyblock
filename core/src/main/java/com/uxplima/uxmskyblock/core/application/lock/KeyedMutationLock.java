package com.uxplima.uxmskyblock.core.application.lock;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Makes a read, a change and a write of one thing one thing.
 *
 * <p>The shape turns up everywhere a service counts something, compares the count to a limit and
 * then writes: the island's warps, its allies, a player's homes. Nothing held between the count and
 * the write means two callers both read one below the limit, both pass, and both write.
 *
 * <p>The lock is per key, so two keys never wait on each other, and it is reentrant, so a service
 * that calls another one inside its own change does not deadlock against itself.
 *
 * <p>A lock is given back when nobody holds or wants it. Throwing one away is the part that has to
 * be right: the obvious shape, remove it when it is not held, hands one caller a lock while another
 * creates a fresh one for the same key, and then both are inside at once. The count moves with the
 * lock under the map's own lock for that key, so a lock is never dropped from under a caller who has
 * already been handed it.
 *
 * @param <K> what the lock is keyed by
 */
public final class KeyedMutationLock<K> {

    /** One key's lock and how many callers are holding or waiting for it. */
    private record Guard(ReentrantLock lock, int users) {}

    private final Map<K, Guard> locks = new ConcurrentHashMap<>();

    /** Runs {@code work} with nobody else changing this key, and gives back what it returns. */
    public <T> T inside(K key, Supplier<T> work) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(work, "work must not be null");

        ReentrantLock lock = acquire(key);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
            release(key);
        }
    }

    /** Runs {@code work} with nobody else changing this key. */
    public void inside(K key, Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        var unused = inside(key, () -> {
            work.run();
            return Boolean.TRUE;
        });
    }

    /**
     * Whether this key is being changed right now.
     *
     * <p>For a test to assert with. Nothing decides anything on it, because an answer about a lock
     * is out of date the moment it is given.
     */
    public boolean isHeld(K key) {
        Objects.requireNonNull(key, "key must not be null");
        Guard guard = locks.get(key);
        return guard != null && guard.lock().isLocked();
    }

    /** How many keys are being changed right now. For a test to assert the locks do not pile up. */
    public int held() {
        return locks.size();
    }

    private ReentrantLock acquire(K key) {
        return Objects.requireNonNull(locks.compute(
                        key,
                        (ignored, existing) -> existing == null
                                ? new Guard(new ReentrantLock(), 1)
                                : new Guard(existing.lock(), existing.users() + 1)))
                .lock();
    }

    private void release(K key) {
        var unused = locks.computeIfPresent(
                key,
                (ignored, existing) -> existing.users() <= 1 ? null : new Guard(existing.lock(), existing.users() - 1));
    }
}
