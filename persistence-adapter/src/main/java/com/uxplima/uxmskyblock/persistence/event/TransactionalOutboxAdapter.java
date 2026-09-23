package com.uxplima.uxmskyblock.persistence.event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import com.uxplima.uxmskyblock.persistence.sql.DialectTransactions;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * Production SQL persistence adapter for the Transactional Outbox Pipeline.
 */
public final class TransactionalOutboxAdapter implements OutboxPort {

    private final Database database;
    private final Dialect dialect;
    private final DialectTransactions tx;

    public TransactionalOutboxAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        this.tx = new DialectTransactions(this.dialect);
        SupportedDialects.require(dialect, "outbox persistence");
    }

    @Override
    public void stageEvent(EventId eventId, String eventType, String aggregateId, String payload) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");

        String sql =
                switch (dialect) {
                    case SQLITE -> """
                INSERT OR IGNORE INTO outbox_events (
                    event_id, event_type, aggregate_id, payload,
                    status, retry_count, created_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """;
                    case MYSQL -> """
                INSERT IGNORE INTO outbox_events (
                    event_id, event_type, aggregate_id, payload,
                    status, retry_count, created_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """;
                    case POSTGRES -> """
                INSERT INTO outbox_events (
                    event_id, event_type, aggregate_id, payload,
                    status, retry_count, created_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """;
                    default -> """
                INSERT INTO outbox_events (
                    event_id, event_type, aggregate_id, payload,
                    status, retry_count, created_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """;
                };

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventId.value().toString());
            ps.setString(2, eventType);
            ps.setString(3, aggregateId);
            ps.setString(4, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to stage outbox event: " + eventId, e);
        }
    }

    @Override
    public OutboxClaim claimPendingBatch(String workerId, Duration leaseDuration, int batchSize) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }

        String claimToken = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant claimExpiresAt = now.plus(leaseDuration);
        Timestamp nowTs = Timestamp.from(now);
        Timestamp expiresTs = Timestamp.from(claimExpiresAt);

        // A server engine skips what another node's claim holds rather than waiting for it. A plain
        // FOR UPDATE on MariaDB and MySQL put the second node's relay in the first node's lock queue.
        String selectSql =
                switch (dialect) {
                    case SQLITE -> """
                    SELECT event_id, event_type, aggregate_id, payload, status,
                           claim_owner, claim_token, claim_expires_at, retry_count,
                           next_attempt_at, last_error, created_at, processed_at
                    FROM outbox_events
                    WHERE (status = 'PENDING' OR (status = 'CLAIMED' AND claim_expires_at < ?))
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                      AND status != 'DEAD_LETTER'
                    ORDER BY created_at ASC
                    LIMIT ?
                    """;
                    case MYSQL -> """
                    SELECT event_id, event_type, aggregate_id, payload, status,
                           claim_owner, claim_token, claim_expires_at, retry_count,
                           next_attempt_at, last_error, created_at, processed_at
                    FROM outbox_events
                    WHERE (status = 'PENDING' OR (status = 'CLAIMED' AND claim_expires_at < ?))
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                      AND status != 'DEAD_LETTER'
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """;
                    case POSTGRES -> """
                    SELECT event_id, event_type, aggregate_id, payload, status,
                           claim_owner, claim_token, claim_expires_at, retry_count,
                           next_attempt_at, last_error, created_at, processed_at
                    FROM outbox_events
                    WHERE (status = 'PENDING' OR (status = 'CLAIMED' AND claim_expires_at < ?))
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                      AND status != 'DEAD_LETTER'
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
                };

        String updateSql = """
                UPDATE outbox_events
                SET status = 'CLAIMED',
                    claim_owner = ?,
                    claim_token = ?,
                    claim_expires_at = ?,
                    retry_count = retry_count + 1
                WHERE event_id = ?
                """;

        try (Connection conn = database.connection()) {
            tx.begin(conn);
            List<OutboxEventRecord> claimedList = new ArrayList<>();
            try {
                try (PreparedStatement selectPs = conn.prepareStatement(selectSql)) {
                    selectPs.setTimestamp(1, nowTs);
                    selectPs.setTimestamp(2, nowTs);
                    selectPs.setInt(3, batchSize);

                    try (ResultSet rs = selectPs.executeQuery()) {
                        while (rs.next()) {
                            claimedList.add(mapRecord(rs));
                        }
                    }
                }

                if (!claimedList.isEmpty()) {
                    try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                        for (OutboxEventRecord rec : claimedList) {
                            updatePs.setString(1, workerId);
                            updatePs.setString(2, claimToken);
                            updatePs.setTimestamp(3, expiresTs);
                            updatePs.setString(4, rec.eventId().value().toString());
                            updatePs.addBatch();
                        }
                        updatePs.executeBatch();
                    }
                }

                tx.commit(conn);
            } catch (Exception e) {
                tx.rollbackQuietly(conn);
                throw e;
            }

            List<OutboxEventRecord> updatedRecords = new ArrayList<>(claimedList.size());
            for (OutboxEventRecord rec : claimedList) {
                updatedRecords.add(new OutboxEventRecord(
                        rec.eventId(),
                        rec.eventType(),
                        rec.aggregateId(),
                        rec.payload(),
                        OutboxStatus.CLAIMED,
                        workerId,
                        claimToken,
                        claimExpiresAt,
                        rec.retryCount() + 1,
                        rec.nextAttemptAt(),
                        rec.lastError(),
                        rec.createdAt(),
                        rec.processedAt()));
            }

            return new OutboxClaim(workerId, claimToken, claimExpiresAt, Collections.unmodifiableList(updatedRecords));
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to claim pending outbox batch for worker: " + workerId, e);
        }
    }

    @Override
    public boolean completeClaim(EventId eventId, String workerId, String claimToken) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(claimToken, "claimToken");

        String sql = """
                UPDATE outbox_events
                SET status = 'PROCESSED',
                    processed_at = ?
                WHERE event_id = ?
                  AND status = 'CLAIMED'
                  AND claim_token = ?
                  AND claim_owner = ?
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, eventId.value().toString());
            ps.setString(3, claimToken);
            ps.setString(4, workerId);

            int updated = ps.executeUpdate();
            return updated == 1;
        } catch (SQLException e) {
            throw new OutboxPersistenceException(
                    "Failed to complete outbox claim for event: " + eventId + " worker: " + workerId, e);
        }
    }

    @Override
    public void recordFailure(
            EventId eventId,
            String workerId,
            String claimToken,
            String errorMessage,
            Duration retryBackoff,
            int maxRetries) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(claimToken, "claimToken");

        try (Connection conn = database.connection()) {
            boolean toDeadLetter = false;
            int retries = 0;
            try (PreparedStatement checkStmt =
                    conn.prepareStatement("SELECT retry_count FROM outbox_events WHERE event_id = ?")) {
                checkStmt.setString(1, eventId.value().toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        retries = rs.getInt("retry_count");
                        if (retries >= maxRetries) {
                            toDeadLetter = true;
                        }
                    }
                }
            }

            if (toDeadLetter) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE outbox_events
                        SET status = 'DEAD_LETTER',
                            last_error = ?
                        WHERE event_id = ?
                          AND status = 'CLAIMED'
                          AND claim_token = ?
                          AND claim_owner = ?
                        """)) {
                    ps.setString(1, errorMessage);
                    ps.setString(2, eventId.value().toString());
                    ps.setString(3, claimToken);
                    ps.setString(4, workerId);
                    ps.executeUpdate();
                }
            } else {
                // Doubles with every attempt, as the testing standard says a poison event backs off. It
                // waited the same length every time, so an event that could never succeed was tried at
                // the same pace to the end of its retries.
                Instant nextAttempt = Instant.now().plus(backoffAfter(retryBackoff, retries));
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE outbox_events
                        SET status = 'PENDING',
                            claim_owner = NULL,
                            claim_token = NULL,
                            claim_expires_at = NULL,
                            next_attempt_at = ?,
                            last_error = ?
                        WHERE event_id = ?
                          AND status = 'CLAIMED'
                          AND claim_token = ?
                          AND claim_owner = ?
                        """)) {
                    ps.setTimestamp(1, Timestamp.from(nextAttempt));
                    ps.setString(2, errorMessage);
                    ps.setString(3, eventId.value().toString());
                    ps.setString(4, claimToken);
                    ps.setString(5, workerId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to record failure for outbox event: " + eventId, e);
        }
    }

    @Override
    public Optional<OutboxEventRecord> findById(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId");

        String sql = """
                SELECT event_id, event_type, aggregate_id, payload, status,
                       claim_owner, claim_token, claim_expires_at, retry_count,
                       next_attempt_at, last_error, created_at, processed_at
                FROM outbox_events
                WHERE event_id = ?
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRecord(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to find outbox event: " + eventId, e);
        }
    }

    @Override
    public int getPendingCount() {
        String sql = "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to count pending outbox events", e);
        }
    }

    @Override
    public int purgeProcessedBefore(Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        // Only what was delivered. A dead lettered event is the record of what failed and is waiting
        // for somebody to look at it, so it stays however old it is.
        String sql = "DELETE FROM outbox_events WHERE status = 'PROCESSED' AND processed_at IS NOT NULL "
                + "AND processed_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(before));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to purge delivered outbox events", e);
        }
    }

    private static OutboxEventRecord mapRecord(ResultSet rs) throws SQLException {
        EventId eventId = EventId.fromString(rs.getString("event_id"));
        String eventType = rs.getString("event_type");
        String aggregateId = rs.getString("aggregate_id");
        String payload = rs.getString("payload");
        OutboxStatus status = OutboxStatus.valueOf(rs.getString("status"));
        String claimOwner = rs.getString("claim_owner");
        String claimToken = rs.getString("claim_token");
        Timestamp claimExpTs = rs.getTimestamp("claim_expires_at");
        Instant claimExpiresAt = claimExpTs != null ? claimExpTs.toInstant() : null;
        int retryCount = rs.getInt("retry_count");
        Timestamp nextAttemptTs = rs.getTimestamp("next_attempt_at");
        Instant nextAttemptAt = nextAttemptTs != null ? nextAttemptTs.toInstant() : null;
        String lastError = rs.getString("last_error");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp procTs = rs.getTimestamp("processed_at");
        Instant processedAt = procTs != null ? procTs.toInstant() : null;

        return new OutboxEventRecord(
                eventId,
                eventType,
                aggregateId,
                payload,
                status,
                claimOwner,
                claimToken,
                claimExpiresAt,
                retryCount,
                nextAttemptAt,
                lastError,
                createdAt,
                processedAt);
    }

    /** The wait before the next attempt: the base for the first failure, doubling after each one. */
    static Duration backoffAfter(Duration base, int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 16);
        return base.multipliedBy(1L << doublings);
    }
}
