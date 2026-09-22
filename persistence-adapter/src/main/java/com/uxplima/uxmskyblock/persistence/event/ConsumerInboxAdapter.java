package com.uxplima.uxmskyblock.persistence.event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.event.ConsumerInboxPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * Production SQL persistence adapter for the idempotent Consumer Inbox.
 */
public final class ConsumerInboxAdapter implements ConsumerInboxPort {

    private final Database database;
    private final Dialect dialect;

    public ConsumerInboxAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        SupportedDialects.require(dialect, "consumer inbox persistence");
    }

    @Override
    public boolean markProcessedIfAbsent(String consumerName, EventId eventId) {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(eventId, "eventId");

        String sql =
                switch (dialect) {
                    case SQLITE -> """
                    INSERT OR IGNORE INTO consumer_inbox (consumer_name, event_id, processed_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """;
                    case MYSQL -> """
                    INSERT IGNORE INTO consumer_inbox (consumer_name, event_id, processed_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """;
                    case POSTGRES -> """
                    INSERT INTO consumer_inbox (consumer_name, event_id, processed_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (consumer_name, event_id) DO NOTHING
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
                };

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, consumerName);
            ps.setString(2, eventId.value().toString());
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            throw new OutboxPersistenceException(
                    "Failed to record consumer inbox entry for " + consumerName + ":" + eventId, e);
        }
    }

    @Override
    public void forget(String consumerName, EventId eventId) {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(eventId, "eventId");

        String sql = "DELETE FROM consumer_inbox WHERE consumer_name = ? AND event_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, consumerName);
            ps.setString(2, eventId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxPersistenceException(
                    "Failed to take back the consumer inbox entry for " + consumerName + ":" + eventId, e);
        }
    }

    @Override
    public int purgeProcessedBefore(java.time.Instant before) {
        Objects.requireNonNull(before, "before");

        String sql = "DELETE FROM consumer_inbox WHERE processed_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(before));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxPersistenceException("Failed to sweep the consumer inbox", e);
        }
    }

    @Override
    public boolean isProcessed(String consumerName, EventId eventId) {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(eventId, "eventId");

        String sql = "SELECT 1 FROM consumer_inbox WHERE consumer_name = ? AND event_id = ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, consumerName);
            ps.setString(2, eventId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new OutboxPersistenceException(
                    "Failed to query consumer inbox entry for " + consumerName + ":" + eventId, e);
        }
    }
}
