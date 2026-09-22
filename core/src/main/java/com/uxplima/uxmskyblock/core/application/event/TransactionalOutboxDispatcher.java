package com.uxplima.uxmskyblock.core.application.event;

import java.time.Duration;
import java.time.Instant;
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
    /** How long a delivered event is kept before it is deleted. */
    public static final Duration DEFAULT_PROCESSED_RETENTION = Duration.ofDays(7);

    /** How often the delivered events are swept. */
    public static final Duration DEFAULT_PURGE_INTERVAL = Duration.ofHours(1);

    /** Where the marks that make a redelivery a no-op live, when this node keeps any. */
    private final java.util.concurrent.atomic.AtomicReference<ConsumerInboxPort> consumerInbox =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    private final Duration processedRetention;
    private final Duration purgeInterval;
    private final AtomicReference<AutoCloseable> purgeTask = new AtomicReference<>(null);
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
            int maxRetries,
            Duration processedRetention,
            Duration purgeInterval) {
        this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        this.batchSize = batchSize > 0 ? batchSize : 50;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff must not be null");
        this.maxRetries = maxRetries > 0 ? maxRetries : 5;
        this.processedRetention = Objects.requireNonNull(processedRetention, "processedRetention must not be null");
        if (processedRetention.isNegative()) {
            throw new IllegalArgumentException("processedRetention must not be negative: " + processedRetention);
        }
        this.purgeInterval = Objects.requireNonNull(purgeInterval, "purgeInterval must not be null");
        if (purgeInterval.isNegative() || purgeInterval.isZero()) {
            throw new IllegalArgumentException("purgeInterval must be positive: " + purgeInterval);
        }
    }

    public TransactionalOutboxDispatcher(
            OutboxPort outboxPort,
            SchedulerPort schedulerPort,
            String workerId,
            Duration leaseDuration,
            int batchSize,
            Duration pollInterval,
            Duration retryBackoff,
            int maxRetries) {
        this(
                outboxPort,
                schedulerPort,
                workerId,
                leaseDuration,
                batchSize,
                pollInterval,
                retryBackoff,
                maxRetries,
                DEFAULT_PROCESSED_RETENTION,
                DEFAULT_PURGE_INTERVAL);
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
                5,
                DEFAULT_PROCESSED_RETENTION,
                DEFAULT_PURGE_INTERVAL);
    }

    /**
     * Deletes what has been delivered and is old enough not to be worth keeping.
     *
     * <p>Nothing deleted a delivered event, so every island created, renamed or erased and every
     * bank transaction left a row with its payload in the table for as long as the server lived. A
     * dead lettered event is never deleted: it is the record of what failed.
     *
     * @return how many rows went
     */
    public int purgeDelivered() {
        try {
            Instant cutoff = Instant.now().minus(processedRetention);
            int purged = outboxPort.purgeProcessedBefore(cutoff);
            if (purged > 0) {
                LOGGER.fine(() -> "Purged " + purged + " delivered outbox events.");
            }
            ConsumerInboxPort inbox = consumerInbox.get();
            if (inbox != null) {
                // The marks are only worth keeping for as long as the events they are about, since
                // an event that is gone can never be delivered again.
                int marks = inbox.purgeProcessedBefore(cutoff);
                if (marks > 0) {
                    LOGGER.fine(() -> "Purged " + marks + " consumer inbox marks.");
                }
            }
            return purged;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Purging delivered outbox events failed. The dispatcher carries on.");
            return 0;
        }
    }

    /**
     * Where the inbox marks live, so the sweep that empties the outbox empties them too.
     *
     * <p>One row per consumer per event, and nothing ever deleted one. The schema carries an index
     * on the time a mark was made and it exists for exactly this sweep.
     */
    public void sweepInboxToo(ConsumerInboxPort inboxPort) {
        consumerInbox.set(Objects.requireNonNull(inboxPort, "inboxPort must not be null"));
    }

    public void registerConsumer(OutboxEventConsumer consumer) {
        consumers.add(Objects.requireNonNull(consumer, "consumer must not be null"));
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            AutoCloseable task = schedulerPort.repeatAsync(this::dispatchBatch, pollInterval, pollInterval);
            pollingTask.set(task);
            // Delivered events were never deleted, so the table only grew. The sweep is its own
            // schedule because it is rare work and the polling loop is not.
            purgeTask.set(schedulerPort.repeatAsync(this::purgeDelivered, purgeInterval, purgeInterval));
            LOGGER.info(() -> "TransactionalOutboxDispatcher started with workerId=" + workerId);
        }
    }

    public int dispatchBatch() {
        if (!running.get() || consumers.isEmpty()) {
            return 0;
        }

        try {
            OutboxClaim claim = outboxPort.claimPendingBatch(workerId, leaseDuration, batchSize);
            List<OutboxEventRecord> events = claim.claimedEvents();
            if (events.isEmpty() || consumers.isEmpty()) {
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
                    String message = failureMessage != null ? failureMessage : "Unknown failure during event dispatch";
                    outboxPort.recordFailure(
                            event.eventId(), workerId, claim.claimToken(), message, retryBackoff, maxRetries);
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
        for (AtomicReference<AutoCloseable> holder : List.of(pollingTask, purgeTask)) {
            AutoCloseable task = holder.getAndSet(null);
            if (task != null) {
                try {
                    task.close();
                } catch (Exception ignored) {
                }
            }
        }
        LOGGER.info("TransactionalOutboxDispatcher stopped.");
    }

    public void stop() {
        close();
    }
}
