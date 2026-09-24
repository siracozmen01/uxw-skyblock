package com.uxplima.uxmskyblock.persistence.session;

import static com.uxplima.uxmskyblock.persistence.session.SessionRowLockScene.NODE_A;
import static com.uxplima.uxmskyblock.persistence.session.SessionRowLockScene.NODE_B;
import static com.uxplima.uxmskyblock.persistence.session.SessionRowLockScene.bytes;
import static com.uxplima.uxmskyblock.persistence.session.SessionRowLockScene.stillWaiting;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
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
 * A node's write and another node's takeover of the same player happen one after the other.
 *
 * <p>The testing standard names this test. Node A starts writing the player's inventory and takes the
 * session row. Its lease runs out while the write is still going, and node B tries to take the player
 * over. Node B waits on the row. Node A's write, authorised when it took the row, commits. Node B then
 * takes the player at the next epoch, and anything node A writes after that is refused.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class PlayerStateCommitVsTakeoverSerializationTest {

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
    @DisplayName("MariaDB: a takeover waits for the write that holds the session row")
    void mariaDb() throws Exception {
        aTakeoverWaitsForTheWrite(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a takeover waits for the write that holds the session row")
    void postgres() throws Exception {
        aTakeoverWaitsForTheWrite(postgresDatabase);
    }

    private static void aTakeoverWaitsForTheWrite(Database database) throws Exception {
        SessionRowLockScene scene = new SessionRowLockScene(database);
        long epoch = scene.loggedIn(NODE_A);
        long version = scene.version();
        scene.leaseEndsIn(2);

        ExecutorService nodes = Executors.newFixedThreadPool(2);
        try {
            Future<ProfileInventoryMutationOutcome> write;
            Future<SessionAuthorityOutcome> takeover;
            SessionRowLockScene.InventoryRowHeld held = scene.holdInventoryRow();
            try {
                write = nodes.submit(() -> scene.inventories.checkpointInventory(
                        scene.player,
                        scene.profile,
                        NODE_A,
                        epoch,
                        version,
                        ProfileInventoryRecord.createDefault(scene.profile, bytes("written-by-a"), new byte[0])));
                stillWaiting(write, "node A, held at the inventory row after taking the session row");

                scene.awaitLeaseEnded();
                takeover = nodes.submit(() -> scene.sessions.failureTakeover(scene.player, epoch, NODE_B));
                stillWaiting(takeover, "node B, while node A holds the session row");
                assertThat(write.isDone()).isFalse();
            } finally {
                held.release();
            }

            assertThat(write.get(10, TimeUnit.SECONDS))
                    .describedAs("node A's write, authorised when it took the row")
                    .isEqualTo(new ProfileInventoryMutationOutcome.Success(version + 1));
            assertThat(takeover.get(10, TimeUnit.SECONDS))
                    .isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1, true));
        } finally {
            nodes.shutdownNow();
        }

        assertThat(scene.inventory()).isEqualTo("written-by-a");
        assertThat(scene.inventories
                        .checkpointInventory(
                                scene.player,
                                scene.profile,
                                NODE_A,
                                epoch,
                                scene.version(),
                                ProfileInventoryRecord.createDefault(scene.profile, bytes("late-by-a"), new byte[0]))
                        .isSuccess())
                .describedAs("node A after the takeover")
                .isFalse();
        assertThat(scene.inventory()).isEqualTo("written-by-a");
    }
}
