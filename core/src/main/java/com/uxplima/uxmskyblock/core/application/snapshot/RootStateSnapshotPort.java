package com.uxplima.uxmskyblock.core.application.snapshot;

import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;

/**
 * Coarse application-level coordinator façade composing relational and world dimension snapshot ports (Section 2.29).
 */
public interface RootStateSnapshotPort {

    byte[] captureRootState(PrimaryGameplayRootRef rootRef, long revision);

    void restoreRootState(PrimaryGameplayRootRef rootRef, byte[] statePayload);
}
