package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A worker claiming outbox events takes the ones nobody holds and does not wait for the rest.
 *
 * <p>The persistence specification claims with {@code FOR UPDATE SKIP LOCKED} on every server
 * engine. MariaDB and MySQL were sent a plain {@code FOR UPDATE}, so on a cluster the second node's
 * relay stood in the first node's lock queue until that claim committed, and one slow claim held up
 * every node's delivery.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class OutboxClaimSkipsLockedRowsIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;
    private static Database mariaDatabase;
    private static Database postgresDatabase;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
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
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a claim passes over an event another node holds and takes the next one at once")
    void mariaSkipsTheHeldEvent() throws Exception {
        claimPassesOverAHeldEvent(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a claim passes over an event another node holds and takes the next one at once")
    void postgresSkipsTheHeldEvent() throws Exception {
        claimPassesOverAHeldEvent(postgresDatabase);
    }

    private static void claimPassesOverAHeldEvent(Database database) throws Exception {
        TransactionalOutboxAdapter outbox = new TransactionalOutboxAdapter(database);
        Instant now = Instant.now();
        EventId held = staged(database, now.minus(Duration.ofMinutes(2)));
        EventId free = staged(database, now.minus(Duration.ofMinutes(1)));

        try (Connection otherNode = database.connection()) {
            otherNode.setAutoCommit(false);
            try (PreparedStatement lock =
                    otherNode.prepareStatement("SELECT event_id FROM outbox_events WHERE event_id = ? FOR UPDATE")) {
                lock.setString(1, held.value().toString());
                try (ResultSet rs = lock.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }

            OutboxClaim claim = CompletableFuture.supplyAsync(
                            () -> outbox.claimPendingBatch("second-node", Duration.ofSeconds(30), 10))
                    .get(10, TimeUnit.SECONDS);

            assertThat(claim.claimedEvents())
                    .extracting(OutboxEventRecord::eventId)
                    .describedAs("what the second node took while the first held the oldest event")
                    .contains(free)
                    .doesNotContain(held);
            otherNode.rollback();
        }
    }

    private static EventId staged(Database database, Instant createdAt) throws Exception {
        EventId eventId = EventId.random();
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO outbox_events (event_id, event_type, aggregate_id, payload, status, created_at)
                        VALUES (?, 'ISLAND_CREATED', 'isl-skip', '{}', 'PENDING', ?)
                        """)) {
            stmt.setString(1, eventId.value().toString());
            stmt.setTimestamp(2, Timestamp.from(createdAt));
            stmt.executeUpdate();
        }
        return eventId;
    }
}
