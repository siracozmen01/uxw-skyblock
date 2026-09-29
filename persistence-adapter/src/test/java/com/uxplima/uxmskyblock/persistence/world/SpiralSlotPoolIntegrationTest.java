package com.uxplima.uxmskyblock.persistence.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;
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
 * The pool of vacated island slots against the databases a customer runs.
 *
 * <p>Each query asked for a free slot as {@code is_allocated = 0 OR is_allocated = false}. SQLite and
 * MariaDB answer either; PostgreSQL declares the column BOOLEAN and refuses {@code boolean = integer},
 * so on PostgreSQL every island creation failed at its first step. Found by creating an island on the
 * lab server pointed at PostgreSQL.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class SpiralSlotPoolIntegrationTest {

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
        for (Database database : new Database[] {mariaDatabase, postgresDatabase}) {
            if (database != null && !database.isClosed()) {
                database.close();
            }
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: an empty pool, a released slot counted, and the lowest claimed once")
    void mariaDbPool() {
        assertThePoolWorks(new SqlSpiralSlotPoolAdapter(mariaDatabase));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: an empty pool, a released slot counted, and the lowest claimed once")
    void postgresPool() {
        assertThePoolWorks(new SqlSpiralSlotPoolAdapter(postgresDatabase));
    }

    private static void assertThePoolWorks(SqlSpiralSlotPoolAdapter pool) {
        String world = "world-" + System.nanoTime();

        assertThat(pool.claimNextAvailableSlot(world)).isEmpty();
        assertThat(pool.countAvailableSlots(world)).isZero();

        pool.recordAllocatedSlot(1L, world, 0, 0);
        pool.releaseSlot(9L, world, 900, 900);
        pool.releaseSlot(4L, world, 400, 400);

        assertThat(pool.countAvailableSlots(world)).isEqualTo(2);
        assertThat(pool.claimNextAvailableSlot(world)).get().satisfies(slot -> {
            assertThat(slot.slotIndex()).isEqualTo(4L);
            assertThat(slot.isAllocated()).isTrue();
        });
        assertThat(pool.claimNextAvailableSlot(world))
                .get()
                .extracting(RecycledSlot::slotIndex)
                .isEqualTo(9L);
        assertThat(pool.claimNextAvailableSlot(world)).isEmpty();
        assertThat(pool.findBySlotIndex(1L))
                .get()
                .extracting(RecycledSlot::isAllocated)
                .isEqualTo(true);
    }
}
