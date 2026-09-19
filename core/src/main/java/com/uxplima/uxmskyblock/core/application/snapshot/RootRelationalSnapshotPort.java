package com.uxplima.uxmskyblock.core.application.snapshot;

import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;

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
     * Restores relational database state for the target gameplay root.
     *
     * @param rootRef target root reference
     * @param snapshotPayload serialized relational payload
     */
    void restoreRelationalSnapshot(PrimaryGameplayRootRef rootRef, byte[] snapshotPayload);
}
