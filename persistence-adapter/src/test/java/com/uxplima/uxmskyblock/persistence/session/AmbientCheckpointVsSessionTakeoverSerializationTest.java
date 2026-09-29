package com.uxplima.uxmskyblock.persistence.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * An ambient checkpoint and a session change take turns on the {@code player_sessions} row.
 *
 * <p>The game mode architecture names this test. A checkpoint reads the session under its row lock
 * and writes the inventory in the same transaction. A drain for a planned handoff, or another node's
 * attempt to take the session, waits on that lock until the checkpoint commits or rolls back. The other
 * order holds too: a checkpoint that arrives after the session has left ACTIVE writes nothing.
 *
 * <p>On PostgreSQL, MySQL and MariaDB the wait is the checkpoint's {@code SELECT ... FOR UPDATE}. On
 * SQLite it is the one pooled connection uxmLib gives a file database, which a session change has to
 * wait for; the checkpoint's {@code BEGIN IMMEDIATE} only matters against another process.
 *
 * <p>The checkpoint is held open at the one point that matters: it has read and locked the session and
 * is about to write the inventory. Every engine a customer runs is asked.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class AmbientCheckpointVsSessionTakeoverSerializationTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-b");

    /** Long enough that a session change which does not wait would have finished. */
    private static final long WAITS_MILLIS = 1500;

    private static MariaDBContainer<?> mariaDb;
    private static MySQLContainer<?> mySql;
    private static PostgreSQLContainer<?> postgres;

    @TempDir
    Path dir;

    @BeforeAll
    static void startEngines() {
        mariaDb = DatabaseTestFixture.startMariaDbIfEnabled();
        mySql = DatabaseTestFixture.startMySqlIfEnabled();
        postgres = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void stopEngines() {
        for (var container : new org.testcontainers.containers.JdbcDatabaseContainer<?>[] {mariaDb, mySql, postgres}) {
            if (container != null) {
                container.stop();
            }
        }
    }

    @Test
    @DisplayName("SQLite: a session change waits for the checkpoint, and a late checkpoint writes nothing")
    void sqlite() throws Exception {
        serialises(() -> DatabaseTestFixture.createSqliteFile(dir.resolve("serialise-" + UUID.randomUUID() + ".db")));
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a session change waits for the checkpoint, and a late checkpoint writes nothing")
    void mariaDb() throws Exception {
        serialises(() -> DatabaseTestFixture.connectToContainer(mariaDb, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: a session change waits for the checkpoint, and a late checkpoint writes nothing")
    void mySql() throws Exception {
        serialises(() -> DatabaseTestFixture.connectToContainer(mySql, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a session change waits for the checkpoint, and a late checkpoint writes nothing")
    void postgres() throws Exception {
        serialises(() -> DatabaseTestFixture.connectToContainer(postgres, Dialect.POSTGRES));
    }

    private static void serialises(Supplier<Database> engine) throws Exception {
        Database database = engine.get();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            aDrainWaitsForTheCheckpoint(database);
            aTakeoverWaitsForTheCheckpoint(database);
            aCheckpointAfterTheDrainWritesNothing(database);
        } finally {
            database.close();
        }
    }

    /** A planned handoff starts with a drain; it waits until the checkpoint under way commits. */
    private static void aDrainWaitsForTheCheckpoint(Database database) throws Exception {
        Scene scene = new Scene(database);
        long epoch = scene.loggedIn();
        long version = scene.flushedFirst(epoch);

        try (Held held = scene.holdCheckpoint(epoch, version, "checkpointed")) {
            Future<Boolean> drain = held.pool.submit(
                    () -> scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess());

            assertThatThrownBy(() -> drain.get(WAITS_MILLIS, TimeUnit.MILLISECONDS))
                    .describedAs("the drain waits on the session row the checkpoint holds")
                    .isInstanceOf(TimeoutException.class);

            assertThat(held.release().isSuccess()).isTrue();
            assertThat(drain.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(scene.inventory()).isEqualTo("checkpointed");
        assertThat(scene.sessions.findSession(scene.player).orElseThrow().state())
                .isEqualTo(SessionState.DRAINING);
    }

    /** Another node asking for the session waits too, and its answer is read after the commit. */
    private static void aTakeoverWaitsForTheCheckpoint(Database database) throws Exception {
        Scene scene = new Scene(database);
        long epoch = scene.loggedIn();
        long version = scene.flushedFirst(epoch);

        try (Held held = scene.holdCheckpoint(epoch, version, "checkpointed")) {
            Future<SessionAuthorityOutcome> takeover =
                    held.pool.submit(() -> scene.sessions.ensureSession(scene.player, scene.profile, NODE_B));

            assertThatThrownBy(() -> takeover.get(WAITS_MILLIS, TimeUnit.MILLISECONDS))
                    .describedAs("another node's acquire waits on the session row the checkpoint holds")
                    .isInstanceOf(TimeoutException.class);

            assertThat(held.release().isSuccess()).isTrue();
            assertThat(takeover.get(30, TimeUnit.SECONDS).isSuccess())
                    .describedAs("the lease node-a holds is still running, so node-b is refused")
                    .isFalse();
        }
        assertThat(scene.inventory()).isEqualTo("checkpointed");
        assertThat(scene.sessions.findSession(scene.player).orElseThrow().authoritativeNode())
                .isEqualTo(NODE_A);
    }

    /** The other order: the drain commits first, and the checkpoint that follows it writes nothing. */
    private static void aCheckpointAfterTheDrainWritesNothing(Database database) {
        Scene scene = new Scene(database);
        long epoch = scene.loggedIn();
        long version = scene.flushedFirst(epoch);

        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();

        assertThat(scene.plainInventories
                        .checkpointInventory(
                                scene.player, scene.profile, NODE_A, epoch, version, scene.record("too late"))
                        .isSuccess())
                .isFalse();
        assertThat(scene.inventory()).isEqualTo("durable");
        assertThat(scene.version()).isEqualTo(version);
    }

    /** One player on one engine, with a checkpoint that can be held open before its write. */
    private static final class Scene {

        final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        final ProfileId profile = ProfileId.of(UUID.randomUUID());
        final PlayerSessionAuthorityAdapter sessions;
        final PlayerProfileInventoryAdapter plainInventories;
        final PlayerProfileInventoryAdapter gatedInventories;
        final AtomicBoolean armed = new AtomicBoolean();
        volatile CountDownLatch paused = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        Scene(Database database) {
            this.sessions = new PlayerSessionAuthorityAdapter(database);
            this.plainInventories = new PlayerProfileInventoryAdapter(database);
            this.gatedInventories =
                    new PlayerProfileInventoryAdapter(Database.adopt(gate(database.dataSource()), database.dialect()));
        }

        long loggedIn() {
            SessionAuthorityOutcome outcome = sessions.ensureSession(player, profile, NODE_A);
            assertThat(outcome.isSuccess()).isTrue();
            return ((SessionAuthorityOutcome.Success) outcome).epoch();
        }

        /** The first write, which makes the row the checkpoint then moves forward. */
        long flushedFirst(long epoch) {
            long start = plainInventories
                    .loadInventory(profile)
                    .map(ProfileInventoryRecord::version)
                    .orElse(0L);
            assertThat(plainInventories
                            .checkpointInventory(player, profile, NODE_A, epoch, start, record("durable"))
                            .isSuccess())
                    .isTrue();
            return version();
        }

        /** Starts a checkpoint and returns once it holds the session row and is about to write. */
        Held holdCheckpoint(long epoch, long version, String contents) throws Exception {
            paused = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<ProfileInventoryMutationOutcome> checkpoint = pool.submit(() ->
                    gatedInventories.checkpointInventory(player, profile, NODE_A, epoch, version, record(contents)));
            assertThat(paused.await(30, TimeUnit.SECONDS))
                    .describedAs("the checkpoint reaches its write")
                    .isTrue();
            return new Held(this, pool, checkpoint);
        }

        ProfileInventoryRecord record(String contents) {
            return ProfileInventoryRecord.createDefault(
                    profile, contents.getBytes(StandardCharsets.UTF_8), new byte[0]);
        }

        long version() {
            return plainInventories.loadInventory(profile).orElseThrow().version();
        }

        String inventory() {
            return new String(
                    plainInventories.loadInventory(profile).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
        }

        /** A data source whose connections stop before the inventory write while the gate is armed. */
        private DataSource gate(DataSource real) {
            return (DataSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        return method.getName().equals("getConnection") ? gated((Connection) result) : result;
                    });
        }

        private Connection gated(Connection real) {
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")
                                && args != null
                                && args[0] instanceof String sql
                                && sql.startsWith("UPDATE profile_inventories")
                                && armed.compareAndSet(true, false)) {
                            paused.countDown();
                            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
                        }
                        return invoke(real, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /** A checkpoint held open at its write, and the threads the test runs beside it. */
    private record Held(Scene scene, ExecutorService pool, Future<ProfileInventoryMutationOutcome> checkpoint)
            implements AutoCloseable {

        ProfileInventoryMutationOutcome release() throws Exception {
            scene.release.countDown();
            return checkpoint.get(30, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            scene.release.countDown();
            pool.shutdownNow();
        }
    }
}
