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
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileHandoffFinalizationAdapter;
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
 * A destination cannot take the player while the source is still writing the player's last inventory.
 *
 * <p>The testing standard names this test. Node A drains the player and starts its final write, which
 * takes the session row. Node B, told the handoff is coming, tries to take the player and waits on the
 * row. When node A's write commits the session is still draining, so node B's attempt takes nothing
 * and moves no epoch. Node A readies the handoff; node B's next attempt takes the player at the next
 * epoch and finds the inventory node A wrote last.
 *
 * <p>The standard describes the final write and the handoff mark as one commit. Here they are two,
 * the write and then the mark, and the attempt that waited on the write is the one that must find
 * nothing to take: that is the half of the rule that keeps a destination from overtaking a source.
 * MariaDB waits on the row, as the standard says. PostgreSQL does not wait: it reads the row as last
 * committed, sees it draining and takes nothing at once. Either way no epoch moves before the mark.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class PlannedAcquireVsFinalSourceFlushSerializationTest {

    private static final String HANDOFF = "handoff-1";

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
    @DisplayName("MariaDB: a planned acquire waits for the source's final write and takes nothing until the handoff")
    void mariaDb() throws Exception {
        theAcquireWaitsForTheFinalWrite(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a planned acquire during the source's final write takes nothing until the handoff")
    void postgres() throws Exception {
        theAcquireWaitsForTheFinalWrite(postgresDatabase);
    }

    private static void theAcquireWaitsForTheFinalWrite(Database database) throws Exception {
        SessionRowLockScene scene = new SessionRowLockScene(database);
        PlayerProfileHandoffFinalizationAdapter finals = new PlayerProfileHandoffFinalizationAdapter(database);
        long epoch = scene.loggedIn(NODE_A);
        long version = scene.version();
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();

        ExecutorService nodes = Executors.newFixedThreadPool(2);
        try {
            Future<ProfileInventoryMutationOutcome> finalWrite;
            Future<SessionAuthorityOutcome> early;
            SessionRowLockScene.InventoryRowHeld held = scene.holdInventoryRow();
            try {
                finalWrite = nodes.submit(() -> finals.finalizeHandoffFlush(
                        scene.player,
                        scene.profile,
                        NODE_A,
                        epoch,
                        version,
                        ProfileInventoryRecord.createDefault(scene.profile, bytes("final-by-a"), new byte[0])));
                stillWaiting(finalWrite, "node A, held at the inventory row after taking the session row");

                early = nodes.submit(() -> scene.sessions.plannedAcquire(scene.player, NODE_A, epoch, HANDOFF, NODE_B));
                if (database.dialect() == Dialect.POSTGRES) {
                    // PostgreSQL reads the row as last committed, finds it draining and takes nothing,
                    // without waiting: no lock is needed to learn there is nothing to take.
                    assertThat(early.get(10, TimeUnit.SECONDS).isSuccess())
                            .describedAs("node B, while node A's final write is open")
                            .isFalse();
                } else {
                    stillWaiting(early, "node B, while node A's final write holds the session row");
                }
            } finally {
                held.release();
            }

            assertThat(finalWrite.get(10, TimeUnit.SECONDS))
                    .isEqualTo(new ProfileInventoryMutationOutcome.Success(version + 1));
            assertThat(early.get(10, TimeUnit.SECONDS).isSuccess())
                    .describedAs("node B's attempt that waited on the final write")
                    .isFalse();
        } finally {
            nodes.shutdownNow();
        }
        assertThat(scene.sessions.findSession(scene.player).orElseThrow().sessionEpoch())
                .isEqualTo(epoch);
        assertThat(scene.sessions.findSession(scene.player).orElseThrow().state())
                .isEqualTo(SessionState.DRAINING);

        assertThat(scene.sessions
                        .prepareHandoff(scene.player, NODE_A, epoch, HANDOFF, NODE_B)
                        .isSuccess())
                .isTrue();
        assertThat(scene.sessions.plannedAcquire(scene.player, NODE_A, epoch, HANDOFF, NODE_B))
                .isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1));
        assertThat(scene.inventory()).isEqualTo("final-by-a");
    }
}
