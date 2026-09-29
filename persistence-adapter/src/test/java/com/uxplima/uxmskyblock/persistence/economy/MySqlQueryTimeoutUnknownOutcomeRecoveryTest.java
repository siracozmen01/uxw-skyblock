package com.uxplima.uxmskyblock.persistence.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaCoordinator;
import com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bank move the database never answered is settled from the saga journal, once, and nothing is paid
 * back on a guess.
 *
 * <p>The testing standard names this test. A statement cut off by a timeout or a dropped socket may
 * have committed or not, and the connection cannot say which. The saga let the failure out of the
 * move: the player was told nothing, the saga waited for the next restart, and nothing settled it
 * while the server ran. The move's outcome is now reported as unknown, the saga stays where it stood,
 * and recovery, which runs every saga timeout, asks the bank again under the same key: a move that
 * landed is found and not repeated, one that did not is made, and a withdrawal is put back.
 */
class MySqlQueryTimeoutUnknownOutcomeRecoveryTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");
    private static final long FUNDS = 5_000L;
    private static final long MOVED = 1_000L;
    private static final long WALLET = 10_000L;
    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);

    /** Whether the next bank move lands before its answer is lost, is lost before it lands, or answers. */
    private enum Answer {
        NORMALLY,
        LOST_AFTER_IT_LANDED,
        LOST_BEFORE_IT_LANDED
    }

    private final AtomicReference<Answer> nextAnswer = new AtomicReference<>(Answer.NORMALLY);
    private final AtomicLong wallet = new AtomicLong(WALLET);

    private Database database;
    private PlayerIslandBankAdapter banks;
    private PlayerEconomySagaAdapter sagas;
    private EconomySagaCoordinator coordinator;
    private IslandId islandId;
    private PlayerUuid owner;
    private ProfileId profile;

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
        sagas = new PlayerEconomySagaAdapter(database);

        owner = PlayerUuid.of(UUID.randomUUID());
        profile = ProfileId.of(UUID.randomUUID());
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
        IslandBankService bank = new IslandBankService(losingAnswers(banks), islands, islands);
        assertThat(bank.depositToIsland(islandId, owner, FUNDS, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);
        coordinator = new EconomySagaCoordinator(sagas, wallet(), bank, SAGA_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A deposit that landed but never answered is unknown, is not refunded, and recovery credits it once")
    void aLandedDepositIsCreditedOnce() {
        SagaId saga = SagaId.random();
        nextAnswer.set(Answer.LOST_AFTER_IT_LANDED);

        BankTransactionOutcome outcome = deposit(saga);

        assertThat(outcome).isEqualTo(unknown(outcome));
        assertThat(wallet).describedAs("charged, and not paid back on a guess").hasValue(WALLET - MOVED);
        assertThat(state(saga)).isEqualTo(SagaState.WALLET_DEBITED);

        recoverLater();

        assertThat(state(saga)).isEqualTo(SagaState.COMMITTED);
        assertThat(balance()).isEqualTo(FUNDS + MOVED);
        assertThat(wallet).hasValue(WALLET - MOVED);
    }

    @Test
    @DisplayName("A deposit that never landed is unknown, and recovery makes it under the same key")
    void anUnlandedDepositIsMadeByRecovery() {
        SagaId saga = SagaId.random();
        nextAnswer.set(Answer.LOST_BEFORE_IT_LANDED);

        BankTransactionOutcome outcome = deposit(saga);

        assertThat(outcome).isEqualTo(unknown(outcome));
        assertThat(balance()).isEqualTo(FUNDS);

        recoverLater();

        assertThat(state(saga)).isEqualTo(SagaState.COMMITTED);
        assertThat(balance()).isEqualTo(FUNDS + MOVED);
        assertThat(wallet).hasValue(WALLET - MOVED);
    }

    @Test
    @DisplayName("A withdrawal that landed but never answered pays nothing out, and recovery puts it back once")
    void aLandedWithdrawalIsPutBack() {
        SagaId saga = SagaId.random();
        nextAnswer.set(Answer.LOST_AFTER_IT_LANDED);

        BankTransactionOutcome outcome =
                coordinator.executeWithdraw(saga, owner, profile, islandId, MOVED, "VAULT", NODE, Instant.now());

        assertThat(outcome).isEqualTo(unknown(outcome));
        assertThat(wallet).describedAs("nothing reaches the wallet on a guess").hasValue(WALLET);
        assertThat(balance()).isEqualTo(FUNDS - MOVED);

        recoverLater();

        assertThat(state(saga)).isEqualTo(SagaState.ROLLED_BACK);
        assertThat(balance()).isEqualTo(FUNDS);
        assertThat(wallet).hasValue(WALLET);
    }

    private BankTransactionOutcome deposit(SagaId saga) {
        return coordinator.executeDeposit(saga, owner, profile, islandId, MOVED, "VAULT", NODE, Instant.now());
    }

    /** Recovery as the repeating task runs it, once the saga's timeout has passed. */
    private void recoverLater() {
        coordinator.recoverIncompleteSagas(Instant.now().plus(SAGA_TIMEOUT).plusSeconds(1), NODE);
    }

    private static BankTransactionOutcome unknown(BankTransactionOutcome outcome) {
        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(((BankTransactionOutcome.AuthorityRejected) outcome).kind())
                .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.OUTCOME_UNKNOWN);
        return outcome;
    }

    private SagaState state(SagaId saga) {
        return sagas.findSagaById(saga).orElseThrow().state();
    }

    private long balance() {
        return banks.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits();
    }

    /** The bank port, whose next move loses its answer as {@link #nextAnswer} says. */
    private IslandBankPort losingAnswers(IslandBankPort real) {
        return (IslandBankPort) Proxy.newProxyInstance(
                IslandBankPort.class.getClassLoader(), new Class<?>[] {IslandBankPort.class}, (proxy, method, args) -> {
                    Answer answer = method.getName().equals("executeTransaction")
                            ? nextAnswer.getAndSet(Answer.NORMALLY)
                            : Answer.NORMALLY;
                    if (answer == Answer.LOST_BEFORE_IT_LANDED) {
                        throw new IllegalStateException("The socket closed while the statement was sent");
                    }
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (answer == Answer.LOST_AFTER_IT_LANDED) {
                        throw new IllegalStateException("The query timed out while the commit was answered");
                    }
                    return result;
                });
    }

    private ExternalWalletPort wallet() {
        return new ExternalWalletPort() {
            @Override
            public boolean hasFunds(PlayerUuid playerUuid, long amountMinorUnits) {
                return wallet.get() >= amountMinorUnits;
            }

            @Override
            public boolean withdraw(PlayerUuid playerUuid, long amountMinorUnits) {
                return wallet.getAndUpdate(held -> held >= amountMinorUnits ? held - amountMinorUnits : held)
                        >= amountMinorUnits;
            }

            @Override
            public boolean deposit(PlayerUuid playerUuid, long amountMinorUnits) {
                wallet.addAndGet(amountMinorUnits);
                return true;
            }
        };
    }
}
