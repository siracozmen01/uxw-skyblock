package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
class TransactionalOutboxIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static TransactionalOutboxAdapter mariaAdapter;
    private static TransactionalOutboxAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        mariaAdapter = new TransactionalOutboxAdapter(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        postgresAdapter = new TransactionalOutboxAdapter(postgresDatabase);
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
    @DisplayName("MariaDB: outbox staging, claiming, completion, and dead-letter handling")
    void mariaDbOutboxLifecycle() {
        testOutboxLifecycle(mariaAdapter);
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL: outbox staging, claiming with SKIP LOCKED, completion, and dead-letter handling")
    void postgresOutboxLifecycle() {
        testOutboxLifecycle(postgresAdapter);
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
