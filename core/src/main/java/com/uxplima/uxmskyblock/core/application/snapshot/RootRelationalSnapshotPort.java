package com.uxplima.uxmskyblock.core.application.snapshot;

import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;

/**
 * Outbound driven port for root-scoped relational data snapshots (Section 2.29).
 *
 * <p>Implemented strictly by :persistence-adapter using pure SQL without any Bukkit or world dependencies.
 */
public interface RootRelationalSnapshotPort {

    /**
     * Captures relational database state for the target gameplay root.
     *
     * @param rootRef target root reference
     * @param revision revision version
     * @return serialized JSON or binary relational payload
     */
    byte[] captureRelationalSnapshot(PrimaryGameplayRootRef rootRef, long revision);

    /**
     * Restores relational database state for the target gameplay root, as far as {@code mode} allows.
     *
     * <p>No mode restores the economy. The island bank, its transaction history and the vault pages
     * are never written back, whatever the payload holds, because money a player has already spent
     * cannot be un-spent by an administrator restoring last night's file.
     *
     * @param rootRef target root reference
     * @param snapshotPayload serialized relational payload
     * @param mode how much of the payload may be applied
     */
    void restoreRelationalSnapshot(PrimaryGameplayRootRef rootRef, byte[] snapshotPayload, RestoreMode mode);
}
