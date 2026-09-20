package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
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
class ConsumerInboxIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static ConsumerInboxAdapter mariaAdapter;
    private static ConsumerInboxAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new ConsumerInboxAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new ConsumerInboxAdapter(postgresDatabase);
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
    @DisplayName("MariaDB: markProcessedIfAbsent returns true on first attempt, false on duplicate")
    void mariaDbInboxLifecycle() {
        testInboxLifecycle(mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: markProcessedIfAbsent returns true on first attempt, false on duplicate")
    void postgresInboxLifecycle() {
        testInboxLifecycle(postgresAdapter);
    }

    private void testInboxLifecycle(ConsumerInboxAdapter adapter) {
        EventId eventId = EventId.random();
        String consumer = "activity-feed-consumer";

        assertThat(adapter.isProcessed(consumer, eventId)).isFalse();

        // First delivery: returns true
        boolean firstDelivery = adapter.markProcessedIfAbsent(consumer, eventId);
        assertThat(firstDelivery).isTrue();
        assertThat(adapter.isProcessed(consumer, eventId)).isTrue();

        // Duplicate delivery: returns false
        boolean duplicateDelivery = adapter.markProcessedIfAbsent(consumer, eventId);
        assertThat(duplicateDelivery).isFalse();
        assertThat(adapter.isProcessed(consumer, eventId)).isTrue();

        // Separate consumer can process the same event once
        String otherConsumer = "audit-consumer";
        assertThat(adapter.isProcessed(otherConsumer, eventId)).isFalse();
        assertThat(adapter.markProcessedIfAbsent(otherConsumer, eventId)).isTrue();
        assertThat(adapter.markProcessedIfAbsent(otherConsumer, eventId)).isFalse();
    }
}
