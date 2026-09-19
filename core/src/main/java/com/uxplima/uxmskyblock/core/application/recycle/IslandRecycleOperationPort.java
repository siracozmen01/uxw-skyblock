package com.uxplima.uxmskyblock.core.application.recycle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port governing durable recording and state transitions
 * for island recycle operations.
 */
public interface IslandRecycleOperationPort {

    /**
     * Durably inserts an initial island recycle operation record.
     *
     * @param operation operation record to insert
     */
    void recordOperation(IslandRecycleOperation operation);

    /**
     * Durably updates the state of an existing island recycle operation.
     *
     * @param operationId operation identifier
     * @param newState new recycle state
     * @param backupPath optional backup path (if newly determined)
     * @param errorMessage optional error message (on failure)
     * @param updatedAt timestamp of the state transition
     */
    void updateState(
            String operationId,
            IslandRecycleState newState,
            @Nullable String backupPath,
            @Nullable String errorMessage,
            Instant updatedAt);

    /**
     * Looks up an operation record by its unique operation ID.
     *
     * @param operationId operation identifier
     * @return optional containing the operation if found
     */
    Optional<IslandRecycleOperation> findOperationById(String operationId);

    /**
     * Lists all recycle operations recorded for a given island ID in descending chronological order.
     *
     * @param islandId island identifier
     * @return list of operation records
     */
    List<IslandRecycleOperation> findOperationsByIslandId(IslandId islandId);
}
