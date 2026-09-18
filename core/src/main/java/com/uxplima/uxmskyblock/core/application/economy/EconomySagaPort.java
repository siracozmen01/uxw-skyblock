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
}
