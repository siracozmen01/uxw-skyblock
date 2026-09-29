package com.uxplima.uxmskyblock.persistence.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedProjection;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.event.DeduplicatingOutboxConsumer;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.event.ConsumerInboxAdapter;
import com.uxplima.uxmskyblock.persistence.event.TransactionalOutboxAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A feed that will not write leaves the bank move it describes standing, and gets the line later.
 *
 * <p>The testing standard names this test. The bank command wrote the island's feed itself once a move
 * had landed, and a feed that failed then lost the line for good. The line is now a projection of the
 * event the bank stages in the same transaction as the move: the move commits whatever the feed does,
 * a failed projection is retried by the outbox dispatcher, and the consumer inbox writes it once.
 */
class ActivityFeedProjectionFailureDoesNotRollbackDomainCommitTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

    private final AtomicInteger failuresLeft = new AtomicInteger();

    private Database database;
    private PlayerIslandBankAdapter banks;
    private IslandBankService bank;
    private ActivityFeedService feed;
    private TransactionalOutboxDispatcher dispatcher;
    private IslandId islandId;
    private PlayerUuid owner;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        PlayerIslandStorageAdapter islands = new PlayerIslandStorageAdapter(database);
        banks = new PlayerIslandBankAdapter(database);
        TransactionalOutboxAdapter outbox = new TransactionalOutboxAdapter(database);

        owner = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, owner.value().toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, owner.value().toString());
                stmt.executeUpdate();
            }
        }
        islandId = IslandId.of(UUID.randomUUID());
        islands.saveIsland(
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), owner, profile, Instant.now()),
                IslandLocation.fromCenterAndRadius(islandId, "skyblock", 0, 0, 100));
        banks.createBank(islandId);
        islands.acquireAuthority(islandId, NODE, 600);
        bank = new IslandBankService(banks, islands, islands, outbox);

        feed = new ActivityFeedService(failing(new SqlActivityFeedAdapter(database.dataSource())));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        when(scheduler.repeatAsync(any(), any(), any())).thenReturn(() -> {});
        dispatcher = new TransactionalOutboxDispatcher(
                outbox, scheduler, "worker-1", Duration.ofSeconds(30), 50, Duration.ofSeconds(1), Duration.ZERO, 5);
        dispatcher.registerConsumer(new DeduplicatingOutboxConsumer(
                ActivityFeedProjection.CONSUMER_NAME,
                new ConsumerInboxAdapter(database),
                new ActivityFeedProjection(feed, uuid -> Optional.of("Owner"))));
        dispatcher.start();
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A deposit stands while its feed line fails, and the line is written once on the retry")
    void theMoveStandsAndTheLineFollows() throws Exception {
        failuresLeft.set(1);

        assertThat(bank.depositToIsland(islandId, owner, 1_250L, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(dispatcher.dispatchBatch())
                .describedAs("the projection failed")
                .isZero();

        assertThat(balance()).describedAs("the move committed without the feed").isEqualTo(1_250L);
        assertThat(lines()).isEmpty();

        Thread.sleep(50);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch())
                .describedAs("nothing is left to deliver")
                .isZero();

        assertThat(lines()).singleElement().satisfies(line -> {
            assertThat(line.eventType()).isEqualTo(ActivityEventType.BANK_DEPOSIT);
            assertThat(line.payloadTypeId()).isEqualTo("activity.bank_deposit");
            assertThat(line.payloadData()).contains("Owner").contains("12.5");
        });
    }

    @Test
    @DisplayName("A withdrawal is written as one, with what left the bank")
    void aWithdrawalIsWrittenAsOne() {
        assertThat(bank.depositToIsland(islandId, owner, 5_000L, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(bank.withdrawFromIsland(islandId, owner, 2_000L, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);

        dispatcher.dispatchBatch();

        assertThat(lines())
                .extracting(ActivityEvent::eventType)
                .containsExactlyInAnyOrder(ActivityEventType.BANK_DEPOSIT, ActivityEventType.BANK_WITHDRAW);
    }

    @Test
    @DisplayName("The server hands every committed event to the projection, and the bank command no longer writes")
    void theServerRunsTheProjection() throws Exception {
        java.nio.file.Path bootstrap =
                java.nio.file.Path.of("../bukkit-adapter/src/main/java/com/uxplima/uxmskyblock/bukkit");
        assertThat(java.nio.file.Files.readString(bootstrap.resolve("bootstrap/IntegrationWiring.java")))
                .contains("ActivityFeedProjection.CONSUMER_NAME")
                .contains("new com.uxplima.uxmskyblock.core.application.activity.ActivityFeedProjection(");
        assertThat(java.nio.file.Files.readString(bootstrap.resolve("command/IslandBankCommands.java")))
                .describedAs("a second writer would put every line in twice")
                .doesNotContain("ActivityEventType.BANK_DEPOSIT")
                .doesNotContain("ActivityEventType.BANK_WITHDRAW");
    }

    private long balance() {
        return banks.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits();
    }

    private java.util.List<ActivityEvent> lines() {
        return feed.getRecentActivities(islandId.value().toString(), 50);
    }

    /** The feed's storage, whose next writes fail while {@link #failuresLeft} lasts. */
    private ActivityFeedStoragePort failing(ActivityFeedStoragePort real) {
        return (ActivityFeedStoragePort) Proxy.newProxyInstance(
                ActivityFeedStoragePort.class.getClassLoader(),
                new Class<?>[] {ActivityFeedStoragePort.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("appendEvent") && failuresLeft.getAndDecrement() > 0) {
                        throw new IllegalStateException("The feed table is locked");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
