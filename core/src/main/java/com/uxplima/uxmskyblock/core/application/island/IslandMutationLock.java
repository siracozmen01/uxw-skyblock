package com.uxplima.uxmskyblock.core.application.island;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Makes a read, a change and a write of one island one thing.
 *
 * <p>Every service that changes an island reads the whole aggregate, changes one part of it and
 * writes the whole thing back. Nine of them do, across membership, flags, freezing, inactivity and
 * the spawn point, and none of them held anything while they did it. Two of those running at once
 * on one island is a lost update: a player accepts an invite in the same moment the owner toggles a
 * flag, both read the island as it was, both write what they read plus their own change, and
 * whichever lands second erases the other. Nothing reports it and nothing in the database says it
 * happened.
 *
 * <p>The lock is per island, so two islands never wait on each other, and it is reentrant, so a
 * service that calls another one inside its own change does not deadlock against itself.
 *
 * <p>This bounds one node. Across nodes the island authority lease is what decides who may write,
 * and a node that does not hold it has no business changing an island at all.
 */
public final class IslandMutationLock {

    /**
     * One island's lock and how many callers are holding or waiting for it.
     *
     * <p>The count is what lets the lock be thrown away again. A map keyed by island that nothing
     * ever removes from is a slow leak on a server that has made a hundred thousand islands over a
     * year, and the count is kept under the map's own lock so a lock is never dropped from under a
     * caller who has already been handed it.
     */
    private record Guard(ReentrantLock lock, int users) {}

    private final Map<IslandId, Guard> locks = new ConcurrentHashMap<>();

    /** Runs {@code work} with nobody else changing this island, and gives back what it returns. */
    public <T> T inside(IslandId islandId, Supplier<T> work) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(work, "work must not be null");

        ReentrantLock lock = acquire(islandId);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
            release(islandId);
        }
    }

    /**
     * The lock for this island, counting this caller as one of its users.
     *
     * <p>{@code compute} runs under the map's own lock for this key, so the count and the lock move
     * together and nobody can be handed a lock that is being removed.
     */
    private ReentrantLock acquire(IslandId islandId) {
        return Objects.requireNonNull(locks.compute(
                        islandId,
                        (key, existing) -> existing == null
                                ? new Guard(new ReentrantLock(), 1)
                                : new Guard(existing.lock(), existing.users() + 1)))
                .lock();
    }

    /** Gives the lock back, and throws it away when nobody else wants it. */
    private void release(IslandId islandId) {
        var unused = locks.computeIfPresent(
                islandId,
                (key, existing) -> existing.users() <= 1 ? null : new Guard(existing.lock(), existing.users() - 1));
    }

    /** How many islands are being changed right now. For a test to assert the locks do not pile up. */
    public int held() {
        return locks.size();
    }

    /** Runs {@code work} with nobody else changing this island. */
    public void inside(IslandId islandId, Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        var unused = inside(islandId, () -> {
            work.run();
            return Boolean.TRUE;
        });
    }

    /**
     * Whether this island is being changed right now.
     *
     * <p>For a test to assert with. Nothing in the plugin decides anything on it, because an answer
     * about a lock is out of date the moment it is given.
     */
    public boolean isHeld(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Guard guard = locks.get(islandId);
        return guard != null && guard.lock().isLocked();
    }
}
