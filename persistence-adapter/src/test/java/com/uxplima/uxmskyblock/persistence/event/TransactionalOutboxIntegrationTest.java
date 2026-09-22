package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class TransactionalOutboxIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static TransactionalOutboxAdapter mariaAdapter;
    private static TransactionalOutboxAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new TransactionalOutboxAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new TransactionalOutboxAdapter(postgresDatabase);
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @Order(1)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: outbox staging, claiming, completion, and dead-letter handling")
    void mariaDbOutboxLifecycle() {
        testOutboxLifecycle(mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: outbox staging, claiming with SKIP LOCKED, completion, and dead-letter handling")
    void postgresOutboxLifecycle() {
        testOutboxLifecycle(postgresAdapter);
    }

    @Test
    @Order(3)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: a delivered event old enough is swept, and nothing else is")
    void mariaDbSweepsDelivered() throws Exception {
        assertOnlyOldDeliveredEventsGo(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(4)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: a delivered event old enough is swept, and nothing else is")
    void postgresSweepsDelivered() throws Exception {
        assertOnlyOldDeliveredEventsGo(postgresDatabase, postgresAdapter);
    }

    /**
     * Nothing ever deleted a delivered event, so every island created, renamed or erased and every
     * bank transaction left a row with its payload in the table for as long as the server lived.
     *
     * <p>What must survive the sweep matters as much as what goes: an event still waiting to be
     * delivered, one delivered a moment ago, and above all a dead lettered one, which is the record
     * of what failed and is waiting for somebody to look at it.
     */
    private void assertOnlyOldDeliveredEventsGo(Database db, TransactionalOutboxAdapter adapter) throws Exception {
        Instant now = Instant.now();
        EventId old = stagedWith(db, "PROCESSED", now.minus(Duration.ofDays(30)));
        EventId recent = stagedWith(db, "PROCESSED", now.minus(Duration.ofMinutes(5)));
        EventId pending = stagedWith(db, "PENDING", null);
        EventId dead = stagedWith(db, "DEAD_LETTER", now.minus(Duration.ofDays(30)));

        int purged = adapter.purgeProcessedBefore(now.minus(Duration.ofDays(7)));

        assertThat(purged).describedAs("rows swept").isEqualTo(1);
        assertThat(adapter.findById(old))
                .describedAs("a delivered event a month old")
                .isEmpty();
        assertThat(adapter.findById(recent))
                .describedAs("a delivered event five minutes old")
                .isPresent();
        assertThat(adapter.findById(pending))
                .describedAs("an event still waiting to be delivered")
                .isPresent();
        assertThat(adapter.findById(dead))
                .describedAs("a dead lettered event, which is the record of what failed")
                .isPresent();
    }

    /** Writes one row in a given state, with a delivery time the test chooses. */
    private EventId stagedWith(Database db, String status, @org.jspecify.annotations.Nullable Instant processedAt)
            throws Exception {
        EventId eventId = EventId.random();
        try (java.sql.Connection conn = db.connection();
                java.sql.PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO outbox_events (event_id, event_type, aggregate_id, payload, status, processed_at)
                        VALUES (?, 'ISLAND_CREATED', 'isl-sweep', '{}', ?, ?)
                        """)) {
            stmt.setString(1, eventId.value().toString());
            stmt.setString(2, status);
            if (processedAt == null) {
                stmt.setNull(3, java.sql.Types.TIMESTAMP);
            } else {
                stmt.setTimestamp(3, java.sql.Timestamp.from(processedAt));
            }
            stmt.executeUpdate();
        }
        return eventId;
    }

    private void testOutboxLifecycle(TransactionalOutboxAdapter adapter) {
        EventId eventId = EventId.random();

        // 1. Stage event
        adapter.stageEvent(eventId, "ISLAND_UPGRADE_PURCHASED", "isl-int-outbox", "{\"tier\":2}");

        Optional<OutboxEventRecord> staged = adapter.findById(eventId);
        assertThat(staged).isPresent();
        assertThat(staged.get().status()).isEqualTo(OutboxStatus.PENDING);

        // 2. Claim event
        OutboxClaim claim = adapter.claimPendingBatch("worker-int", Duration.ofSeconds(30), 10);
        assertThat(claim.claimedEvents()).isNotEmpty();
        OutboxEventRecord claimed = claim.claimedEvents().stream()
                .filter(e -> e.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(claimed.status()).isEqualTo(OutboxStatus.CLAIMED);

        // 3. Complete event
        boolean completed = adapter.completeClaim(eventId, "worker-int", claim.claimToken());
        assertThat(completed).isTrue();

        Optional<OutboxEventRecord> processed = adapter.findById(eventId);
        assertThat(processed).isPresent();
        assertThat(processed.get().status()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(processed.get().processedAt()).isNotNull();

        // 4. Stale claim completion rejected
        boolean stale = adapter.completeClaim(eventId, "worker-int", claim.claimToken());
        assertThat(stale).isFalse();
    }
}
