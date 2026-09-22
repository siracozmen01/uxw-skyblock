package com.uxplima.uxmskyblock.persistence.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
import com.uxplima.uxmskyblock.core.domain.economy.SagaType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerEconomySagaSqliteTest {

    private Database database;
    private PlayerEconomySagaAdapter adapter;

    private final PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
    private final ProfileId profileId = new ProfileId(UUID.randomUUID());
    private final IslandId islandId = new IslandId(UUID.randomUUID());
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerEconomySagaAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A saga that has settled is swept, and one still running or failed is kept")
    void settledSagasAreSwept() {
        SagaId committed = newSaga(SagaState.COMMITTED, now.minusSeconds(600));
        SagaId rolledBack = newSaga(SagaState.ROLLED_BACK, now.minusSeconds(600));
        SagaId failed = newSaga(SagaState.FAILED, now.minusSeconds(600));
        SagaId running = newSaga(SagaState.STARTED, now.minusSeconds(600));
        SagaId recent = newSaga(SagaState.COMMITTED, now);

        assertThat(adapter.purgeSettledBefore(now.minusSeconds(300)))
                .describedAs("committed and rolled back, and old enough")
                .isEqualTo(2);

        assertThat(adapter.findSagaById(committed)).isEmpty();
        assertThat(adapter.findSagaById(rolledBack)).isEmpty();
        assertThat(adapter.findSagaById(failed))
                .describedAs("a money movement that went wrong is the evidence an operator needs")
                .isPresent();
        assertThat(adapter.findSagaById(running))
                .describedAs("a saga still running has everything left to recover")
                .isPresent();
        assertThat(adapter.findSagaById(recent))
                .describedAs("settled, but not old enough yet")
                .isPresent();
    }

    private SagaId newSaga(SagaState state, java.time.Instant updatedAt) {
        SagaId sagaId = SagaId.random();
        adapter.createSaga(new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                SagaType.DEPOSIT,
                state,
                1000L,
                "VAULT",
                now.plusSeconds(60),
                now.minusSeconds(900),
                updatedAt));
        return sagaId;
    }

    @Test
    @DisplayName("createSaga and findSagaById round-trips state correctly")
    void createAndFindRoundTrip() {
        SagaId sagaId = SagaId.random();
        EconomySagaRecord record = new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                SagaType.DEPOSIT,
                SagaState.STARTED,
                5000L,
                "VAULT",
                now.plusSeconds(60),
                now,
                now);

        adapter.createSaga(record);

        Optional<EconomySagaRecord> retrieved = adapter.findSagaById(sagaId);
        assertThat(retrieved).isPresent();
        EconomySagaRecord found = retrieved.get();
        assertThat(found.sagaId()).isEqualTo(sagaId);
        assertThat(found.playerUuid()).isEqualTo(playerUuid);
        assertThat(found.profileId()).isEqualTo(profileId);
        assertThat(found.islandId()).isEqualTo(islandId);
        assertThat(found.sagaType()).isEqualTo(SagaType.DEPOSIT);
        assertThat(found.state()).isEqualTo(SagaState.STARTED);
        assertThat(found.amountMinorUnits()).isEqualTo(5000L);
        assertThat(found.currency()).isEqualTo("VAULT");
    }

    @Test
    @DisplayName("updateState transitions saga state and updates timestamp")
    void updateStateTransitionsState() {
        SagaId sagaId = SagaId.random();
        EconomySagaRecord record = new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                SagaType.WITHDRAW,
                SagaState.STARTED,
                2500L,
                "VAULT",
                now.plusSeconds(30),
                now,
                now);

        adapter.createSaga(record);

        Instant updateTime = now.plusSeconds(5);
        adapter.updateState(sagaId, SagaState.COMMITTED, updateTime);

        Optional<EconomySagaRecord> updated = adapter.findSagaById(sagaId);
        assertThat(updated).isPresent();
        assertThat(updated.get().state()).isEqualTo(SagaState.COMMITTED);
    }

    @Test
    @DisplayName("findIncompleteSagas returns only expired STARTED and COMPENSATING sagas")
    void findIncompleteSagasReturnsTargetRecords() {
        SagaId s1 = SagaId.random();
        SagaId s2 = SagaId.random();
        SagaId s3 = SagaId.random();

        // Expired STARTED saga
        adapter.createSaga(new EconomySagaRecord(
                s1,
                playerUuid,
                profileId,
                islandId,
                SagaType.DEPOSIT,
                SagaState.STARTED,
                100L,
                "VAULT",
                now.minusSeconds(10),
                now.minusSeconds(40),
                now.minusSeconds(40)));

        // Expired COMPENSATING saga
        adapter.createSaga(new EconomySagaRecord(
                s2,
                playerUuid,
                profileId,
                islandId,
                SagaType.WITHDRAW,
                SagaState.COMPENSATING,
                200L,
                "VAULT",
                now.minusSeconds(5),
                now.minusSeconds(35),
                now.minusSeconds(35)));

        // Expired COMMITTED saga (should NOT be returned)
        adapter.createSaga(new EconomySagaRecord(
                s3,
                playerUuid,
                profileId,
                islandId,
                SagaType.DEPOSIT,
                SagaState.COMMITTED,
                300L,
                "VAULT",
                now.minusSeconds(1),
                now.minusSeconds(30),
                now.minusSeconds(30)));

        List<EconomySagaRecord> incomplete = adapter.findIncompleteSagas(now);
        assertThat(incomplete).hasSize(2);
        assertThat(incomplete.stream().map(EconomySagaRecord::sagaId).toList()).containsExactlyInAnyOrder(s1, s2);
    }

    @Test
    @DisplayName("findSagaById returns empty for non-existent id")
    void findNonExistentReturnsEmpty() {
        assertThat(adapter.findSagaById(SagaId.random())).isEmpty();
    }
}
