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

    private final Map<IslandId, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Runs {@code work} with nobody else changing this island, and gives back what it returns. */
    public <T> T inside(IslandId islandId, Supplier<T> work) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(work, "work must not be null");

        ReentrantLock lock = locks.computeIfAbsent(islandId, key -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
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
        ReentrantLock lock = locks.get(islandId);
        return lock != null && lock.isLocked();
    }
}
