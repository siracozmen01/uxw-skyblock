package com.uxplima.uxmskyblock.core.application.event;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Scheduled background dispatcher polling pending transactional outbox events,
 * fanning out to registered consumers, and committing completion or failure with backoff.
 */
public final class TransactionalOutboxDispatcher implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(TransactionalOutboxDispatcher.class.getName());

    private final OutboxPort outboxPort;
    private final SchedulerPort schedulerPort;
    private final String workerId;
    private final Duration leaseDuration;
    private final int batchSize;
    private final Duration pollInterval;
    private final Duration retryBackoff;
    private final int maxRetries;
    private final List<OutboxEventConsumer> consumers = new CopyOnWriteArrayList<>();
    private final AtomicReference<AutoCloseable> pollingTask = new AtomicReference<>(null);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TransactionalOutboxDispatcher(
            OutboxPort outboxPort,
            SchedulerPort schedulerPort,
            String workerId,
            Duration leaseDuration,
            int batchSize,
            Duration pollInterval,
            Duration retryBackoff,
            int maxRetries) {
        this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        this.batchSize = batchSize > 0 ? batchSize : 50;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff must not be null");
        this.maxRetries = maxRetries > 0 ? maxRetries : 5;
    }

    public TransactionalOutboxDispatcher(OutboxPort outboxPort, SchedulerPort schedulerPort, String workerId) {
        this(
                outboxPort,
                schedulerPort,
                workerId,
                Duration.ofSeconds(30),
                50,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                5);
    }

    public void registerConsumer(OutboxEventConsumer consumer) {
        consumers.add(Objects.requireNonNull(consumer, "consumer must not be null"));
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            AutoCloseable task = schedulerPort.repeatAsync(this::dispatchBatch, pollInterval, pollInterval);
            pollingTask.set(task);
            LOGGER.info(() -> "TransactionalOutboxDispatcher started with workerId=" + workerId);
        }
    }

    public int dispatchBatch() {
        if (!running.get()) {
            return 0;
        }

        try {
            OutboxClaim claim = outboxPort.claimPendingBatch(workerId, leaseDuration, batchSize);
            List<OutboxEventRecord> events = claim.claimedEvents();
            if (events.isEmpty()) {
                return 0;
            }

            int processedCount = 0;
            for (OutboxEventRecord event : events) {
                boolean success = true;
                String failureMessage = null;

                for (OutboxEventConsumer consumer : consumers) {
                    try {
                        consumer.consume(event);
                    } catch (Exception e) {
                        success = false;
                        failureMessage = e.getMessage() != null
                                ? e.getMessage()
                                : e.getClass().getSimpleName();
                        LOGGER.log(
                                Level.WARNING, "Error dispatching outbox event " + event.eventId() + " to consumer", e);
                        break;
                    }
                }

                if (success) {
                    boolean completed = outboxPort.completeClaim(event.eventId(), workerId, claim.claimToken());
                    if (completed) {
                        processedCount++;
                    } else {
                        LOGGER.warning(() -> "Outbox claim completion fenced for event " + event.eventId());
                    }
                } else {
                    outboxPort.recordFailure(
                            event.eventId(), workerId, claim.claimToken(), failureMessage, retryBackoff, maxRetries);
                }
            }
            return processedCount;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error in TransactionalOutboxDispatcher dispatch loop", e);
            return 0;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    @SuppressWarnings("EmptyCatch")
    public synchronized void close() {
        running.set(false);
        AutoCloseable task = pollingTask.getAndSet(null);
        if (task != null) {
            try {
                task.close();
            } catch (Exception ignored) {
            }
        }
        LOGGER.info("TransactionalOutboxDispatcher stopped.");
    }

    public void stop() {
        close();
    }
}
