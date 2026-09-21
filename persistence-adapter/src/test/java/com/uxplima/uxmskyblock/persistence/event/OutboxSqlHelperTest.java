package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Staging an outbox event inside somebody else's transaction.
 *
 * <p>This is the hinge of the transactional outbox: the event and the state change it announces
 * either both land or neither does. A helper that opened its own transaction, or that swallowed a
 * failure, would publish an event for a change that was rolled back, and nothing downstream could
 * tell the difference.
 */
class OutboxSqlHelperTest {

    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE outbox_events (
                        event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        event_type VARCHAR(64) NOT NULL,
                        aggregate_id VARCHAR(36) NOT NULL,
                        payload TEXT NOT NULL,
                        status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                        retry_count INT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A staged event lands pending, unattempted, and with every field the caller gave")
    void aStagedEventLandsWhole() throws Exception {
        StagedOutboxEvent event = StagedOutboxEvent.of("island.created", "island-7", "{\"a\":1}");

        try (Connection connection = database.connection()) {
            OutboxSqlHelper.stageEvent(connection, event);
        }

        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT event_id, event_type, aggregate_id, payload, status, retry_count FROM outbox_events")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("event_id"))
                    .isEqualTo(event.eventId().value().toString());
            assertThat(rs.getString("event_type")).isEqualTo("island.created");
            assertThat(rs.getString("aggregate_id")).isEqualTo("island-7");
            assertThat(rs.getString("payload")).isEqualTo("{\"a\":1}");
            assertThat(rs.getString("status"))
                    .describedAs("a dispatcher must see it as unsent")
                    .isEqualTo("PENDING");
            assertThat(rs.getInt("retry_count"))
                    .describedAs("a fresh event has not been tried yet")
                    .isZero();
            assertThat(rs.next()).describedAs("one call stages one row").isFalse();
        }
    }

    @Test
    @DisplayName("No event at all stages no row, so a caller with nothing to announce need not branch")
    void noEventStagesNoRow() throws Exception {
        try (Connection connection = database.connection()) {
            assertThatCode(() -> OutboxSqlHelper.stageEvent(connection, null)).doesNotThrowAnyException();
        }

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("The event is rolled back with the change it announces, never published on its own")
    void theEventIsRolledBackWithTheChange() throws Exception {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            OutboxSqlHelper.stageEvent(connection, StagedOutboxEvent.of("island.deleted", "island-9", "{}"));
            connection.rollback();
        }

        assertThat(rowCount())
                .describedAs("an event that outlived its own transaction announces a change nobody made")
                .isZero();
    }

    @Test
    @DisplayName("The event is committed with the change it announces")
    void theEventIsCommittedWithTheChange() throws Exception {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            OutboxSqlHelper.stageEvent(connection, StagedOutboxEvent.of("island.deleted", "island-9", "{}"));
            connection.commit();
        }

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Staging the same event twice is refused rather than announced twice")
    void theSameEventIsNeverStagedTwice() throws Exception {
        StagedOutboxEvent event = StagedOutboxEvent.of("bank.deposit", "island-3", "{}");

        try (Connection connection = database.connection()) {
            OutboxSqlHelper.stageEvent(connection, event);
            assertThatThrownBy(() -> OutboxSqlHelper.stageEvent(connection, event))
                    .describedAs("the primary key is what stops a duplicate announcement")
                    .isInstanceOf(SQLException.class);
        }

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A payload that looks like SQL is stored as text, because it goes through a statement parameter")
    void aPayloadThatLooksLikeSqlIsStoredAsText() throws Exception {
        String payload = "{\"note\":\"'); DROP TABLE outbox_events; --\"}";

        try (Connection connection = database.connection()) {
            OutboxSqlHelper.stageEvent(connection, StagedOutboxEvent.of("island.renamed", "island-4", payload));
        }

        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT payload FROM outbox_events")) {
            assertThat(rs.next()).describedAs("the table is still here").isTrue();
            assertThat(rs.getString(1)).isEqualTo(payload);
        }
    }

    private int rowCount() throws Exception {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM outbox_events")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
