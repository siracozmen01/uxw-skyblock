package com.uxplima.uxmskyblock.core.application.event;

import java.time.Duration;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Outbound application port for transactional outbox staging, claiming, completion, and DLQ tracking.
 */
public interface OutboxPort {

    /**
     * Stages a new outbox event in PENDING state.
     */
    void stageEvent(EventId eventId, String eventType, String aggregateId, String payload);

    /**
     * Claims up to batchSize pending or expired-claim events under lease.
     */
    OutboxClaim claimPendingBatch(String workerId, Duration leaseDuration, int batchSize);

    /**
     * Completes a claimed event, transitioning it to PROCESSED.
     * Returns true if completed, or false if fenced by a stale claim.
     */
    boolean completeClaim(EventId eventId, String workerId, String claimToken);

    /**
     * Records a failure for a claimed event, scheduling retry or moving to DEAD_LETTER if maxRetries exceeded.
     */
    void recordFailure(
            EventId eventId,
            String workerId,
            String claimToken,
            String errorMessage,
            Duration retryBackoff,
            int maxRetries);

    /**
     * Finds an outbox event by ID.
     */
    Optional<OutboxEventRecord> findById(EventId eventId);

    /**
     * Gets the count of pending events.
     */
    int getPendingCount();

    /**
     * Deletes events that were delivered before {@code before}, and answers how many went.
     *
     * <p>A delivered event is a row nobody will read again. Nothing deleted one, so every island
     * created, renamed or erased and every bank transaction left a row with its payload in the table
     * for as long as the server lived. A dead lettered event is never deleted here: it is the record
     * of what failed and it is waiting for somebody to look at it.
     *
     * @param before the moment before which a delivered event is no longer worth keeping
     * @return how many rows went
     */
    int purgeProcessedBefore(java.time.Instant before);
}
