package com.uxplima.uxmskyblock.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsumerInboxSqliteTest {

    private Database database;
    private ConsumerInboxAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new ConsumerInboxAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("markProcessedIfAbsent returns true on first attempt, false on duplicate")
    void idempotentInboxProcessing() {
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
