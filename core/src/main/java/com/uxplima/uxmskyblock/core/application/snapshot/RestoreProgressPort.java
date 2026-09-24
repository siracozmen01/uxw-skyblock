package com.uxplima.uxmskyblock.core.application.snapshot;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import org.jspecify.annotations.Nullable;

/**
 * Where a restore writes down what it is about to put back and what it has put back.
 *
 * <p>The persistence specification's restore unit write-ahead protocol: each unit is recorded as
 * {@code APPLY_INTENT} with the checksum it must match before it touches the world, and as
 * {@code VERIFIED} once it is back. A node that stopped between the two finds the unit on its next
 * start and puts it back again, which is safe because putting a unit back replaces what is there
 * rather than adding to it.
 */
public interface RestoreProgressPort {

    /** One restore as it was begun: which island, which backup set, where it was read from, how far. */
    record RestoreOperation(
            UUID restoreId, String islandId, BackupSetId snapshotId, String sourcePrefix, RestoreMode mode) {
        public RestoreOperation {
            Objects.requireNonNull(restoreId, "restoreId must not be null");
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            Objects.requireNonNull(sourcePrefix, "sourcePrefix must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
        }
    }

    /** Where a restore stands, in the words the specification's table uses. */
    enum OperationState {
        APPLYING_WORLD,
        COMMITTED,
        FAILED
    }

    /** Writes the restore down before its first unit, so a stop after this point is found again. */
    void begin(RestoreOperation operation);

    /** Writes down that {@code unitId} is about to be put back, and the checksum it must match. */
    void intend(UUID restoreId, String unitId, String sourceChecksum);

    /** Writes down that {@code unitId} is back. */
    void verified(UUID restoreId, String unitId);

    /** The units of the restore that are already back, which a resumed restore does not repeat. */
    Set<String> verifiedUnits(UUID restoreId);

    /** Ends the restore, as committed or as failed with a reason. */
    void finish(UUID restoreId, OperationState state, @Nullable String failureReason);

    /** Every restore that was begun and neither committed nor failed: the ones a stop interrupted. */
    List<RestoreOperation> unfinished();
}
