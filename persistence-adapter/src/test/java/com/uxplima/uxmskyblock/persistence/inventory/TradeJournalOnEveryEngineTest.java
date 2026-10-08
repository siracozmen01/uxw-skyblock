package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The trade journal on every engine a customer runs: an intent over two sessions, a side marked applied,
 * both inventories written in one commit, and a second trade whose side is put back from the inventory
 * its intent kept, read back through each engine's own JSON column.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class TradeJournalOnEveryEngineTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

    private static MariaDBContainer<?> mariaDbContainer;
    private static MySQLContainer<?> mySqlContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void startEngines() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        mySqlContainer = DatabaseTestFixture.startMySqlIfEnabled();
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void stopEngines() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (mySqlContainer != null) {
            mySqlContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @DisplayName("SQLite: a trade commits both sides and puts one back")
    void sqlite() {
        tradesOn(DatabaseTestFixture.createSqliteInMemory());
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a trade commits both sides and puts one back")
    void mariaDb() {
        tradesOn(DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: a trade commits both sides and puts one back")
    void mySql() {
        tradesOn(DatabaseTestFixture.connectToContainer(mySqlContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a trade commits both sides and puts one back")
    void postgres() {
        tradesOn(DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES));
    }

    private static void tradesOn(Database database) {
        try (database) {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            PlayerSessionAuthorityAdapter sessions = new PlayerSessionAuthorityAdapter(database);
            PlayerProfileInventoryAdapter inventories = new PlayerProfileInventoryAdapter(database);
            PlayerTradeJournalAdapter journal = new PlayerTradeJournalAdapter(database);
            TradeJournalPort.Holder ada = holder(sessions);
            TradeJournalPort.Holder bo = holder(sessions);
            write(inventories, ada, "emerald x3");
            write(inventories, bo, "diamond x1");

            InventoryMutationOperationId first = InventoryMutationOperationId.random();
            assertThat(journal.recordIntent(
                                    first,
                                    NODE,
                                    List.of(side(inventories, ada, "diamond x1"), side(inventories, bo, "emerald x3")),
                                    "{}",
                                    Duration.ofMinutes(1))
                            .isSuccess())
                    .isTrue();
            assertThat(journal.markApplied(first, 0).isSuccess()).isTrue();
            assertThat(journal.participants(first).get(0).applyState()).isEqualTo(ParticipantApplyState.APPLIED);
            assertThat(new String(journal.participants(first).get(1).beforeInventory(), StandardCharsets.UTF_8))
                    .isEqualTo("diamond x1");
            long adaVersion = version(inventories, ada);
            assertThat(journal.commit(
                                    first,
                                    NODE,
                                    List.of(
                                            outcome(inventories, ada, "diamond x1"),
                                            outcome(inventories, bo, "emerald x3")))
                            .isSuccess())
                    .isTrue();
            assertThat(journal.state(first)).hasValue(InventoryMutationJournalState.COMMITTED);
            assertThat(version(inventories, ada)).isEqualTo(adaVersion + 1);
            assertThat(contents(inventories, bo)).isEqualTo("emerald x3");

            InventoryMutationOperationId second = InventoryMutationOperationId.random();
            assertThat(journal.recordIntent(
                                    second,
                                    NODE,
                                    List.of(side(inventories, ada, "nothing"), side(inventories, bo, "everything")),
                                    "{}",
                                    Duration.ofMinutes(1))
                            .isSuccess())
                    .isTrue();
            assertThat(journal.findOpenTrades(bo.profile())).containsExactly(second);
            // Ada left holding her half, and the last write of her inventory kept it.
            keptOnLeaving(database, ada, "nothing");
            byte[] kept = journal.participants(second).get(0).beforeInventory();
            assertThat(journal.settleSide(second, 0, NODE, ada, kept, version(inventories, ada), true)
                            .isSuccess())
                    .isTrue();
            assertThat(journal.state(second)).hasValue(InventoryMutationJournalState.ABORTED);
            assertThat(contents(inventories, ada)).isEqualTo("diamond x1");
            assertThat(journal.findOpenTrades(bo.profile())).isEmpty();
        }
    }

    private static TradeJournalPort.Holder holder(PlayerSessionAuthorityAdapter sessions) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        SessionAuthorityOutcome taken = sessions.ensureSession(player, profile, NODE);
        assertThat(taken.isSuccess()).isTrue();
        return new TradeJournalPort.Holder(player, profile, ((SessionAuthorityOutcome.Success) taken).epoch());
    }

    private static void write(PlayerProfileInventoryAdapter inventories, TradeJournalPort.Holder holder, String text) {
        long version = inventories
                .loadInventory(holder.profile())
                .map(ProfileInventoryRecord::version)
                .orElse(1L);
        assertThat(inventories
                        .checkpointInventory(
                                holder.player(),
                                holder.profile(),
                                NODE,
                                holder.sessionEpoch(),
                                version,
                                ProfileInventoryRecord.createDefault(holder.profile(), bytes(text), new byte[0]))
                        .isSuccess())
                .isTrue();
    }

    private static void keptOnLeaving(Database database, TradeJournalPort.Holder holder, String text) {
        try (java.sql.Connection conn = database.connection();
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "UPDATE profile_inventories SET inventory_nbt = ? WHERE profile_id = ?")) {
            ps.setBytes(1, bytes(text));
            ps.setString(2, holder.profile().value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TradeJournalPort.Side side(
            PlayerProfileInventoryAdapter inventories, TradeJournalPort.Holder holder, String after) {
        byte[] before =
                inventories.loadInventory(holder.profile()).orElseThrow().inventoryNbt();
        return new TradeJournalPort.Side(
                holder,
                version(inventories, holder),
                InventoryFingerprint.of(before),
                InventoryFingerprint.of(bytes(after)),
                before);
    }

    private static TradeJournalPort.Outcome outcome(
            PlayerProfileInventoryAdapter inventories, TradeJournalPort.Holder holder, String after) {
        return new TradeJournalPort.Outcome(holder, version(inventories, holder), bytes(after));
    }

    private static long version(PlayerProfileInventoryAdapter inventories, TradeJournalPort.Holder holder) {
        return inventories.loadInventory(holder.profile()).orElseThrow().version();
    }

    private static String contents(PlayerProfileInventoryAdapter inventories, TradeJournalPort.Holder holder) {
        return new String(
                inventories.loadInventory(holder.profile()).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
