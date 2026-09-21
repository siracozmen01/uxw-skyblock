package com.uxplima.uxmskyblock.core.application.island;

import java.util.Objects;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock;
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
 * <p>The limits are the other half of the same shape: the warps an island may have and the allies
 * it may keep are both counted and then written, and a count nobody holds is a number two callers
 * can read the same.
 *
 * <p>This bounds one node. Across nodes the island authority lease is what decides who may write,
 * and a node that does not hold it has no business changing an island at all.
 */
public final class IslandMutationLock {

    private final KeyedMutationLock<IslandId> locks = new KeyedMutationLock<>();

    /** Runs {@code work} with nobody else changing this island, and gives back what it returns. */
    public <T> T inside(IslandId islandId, Supplier<T> work) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return locks.inside(islandId, work);
    }

    /** Runs {@code work} with nobody else changing this island. */
    public void inside(IslandId islandId, Runnable work) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        locks.inside(islandId, work);
    }

    /**
     * Whether this island is being changed right now.
     *
     * <p>For a test to assert with. Nothing in the plugin decides anything on it, because an answer
     * about a lock is out of date the moment it is given.
     */
    public boolean isHeld(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return locks.isHeld(islandId);
    }

    /** How many islands are being changed right now. For a test to assert the locks do not pile up. */
    public int held() {
        return locks.held();
    }
}
