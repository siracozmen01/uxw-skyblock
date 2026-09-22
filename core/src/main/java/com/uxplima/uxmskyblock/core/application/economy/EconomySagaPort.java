package com.uxplima.uxmskyblock.core.application.economy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;

/**
 * Outbound persistence port for recording and transitioning economy sagas.
 */
public interface EconomySagaPort {

    void createSaga(EconomySagaRecord saga);

    void updateState(SagaId sagaId, SagaState newState, Instant updatedAt);

    Optional<EconomySagaRecord> findSagaById(SagaId sagaId);

    List<EconomySagaRecord> findIncompleteSagas(Instant expiredBefore);

    /**
     * Deletes the sagas that have nothing left to recover.
     *
     * <p>A saga is a record of a money movement across a boundary this plugin does not own, kept so
     * a crash in the middle can be finished or undone. Once it has committed or been rolled back it
     * has done its job, and nothing ever deleted one: the table held every external money movement
     * a server had ever made.
     *
     * <p>A failed saga is kept. A money movement that went wrong is the evidence an operator needs,
     * and a failure is rare enough that keeping it is not the growth this is about.
     *
     * @return how many were deleted
     */
    int purgeSettledBefore(Instant before);
}
