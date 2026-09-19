package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SqlIslandBankruptcyStorageAdapterTest {

    private Database database;
    private SqlIslandBankruptcyStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlIslandBankruptcyStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private void seedAccountAndProfile(UUID playerUuid, UUID profileId) throws SQLException {
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, ?)")) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, profileId.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                stmt.setString(1, profileId.toString());
                stmt.setString(2, playerUuid.toString());
                stmt.executeUpdate();
            }
        }
    }

    private void seedIsland(UUID islandId, UUID ownerAccountUuid, UUID ownerProfileId) throws SQLException {
        seedAccountAndProfile(ownerAccountUuid, ownerProfileId);
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO islands (
                    id, owner_profile_id, owner_account_uuid, custom_name,
                    lifecycle, economic_state, administrative_state, version
                ) VALUES (?, ?, ?, 'Test Island', 'ACTIVE', 'NORMAL', 'NORMAL', 1)
                """)) {
            stmt.setString(1, islandId.toString());
            stmt.setString(2, ownerProfileId.toString());
            stmt.setString(3, ownerAccountUuid.toString());
            stmt.executeUpdate();
        }
    }

    @Test
    @DisplayName("Bankruptcy insert, find, update to GRACE, LOCKED, and SOLVENT roundtrip")
    void bankruptcyCrudRoundtrip() throws SQLException {
        UUID islandUuid = UUID.randomUUID();
        seedIsland(islandUuid, UUID.randomUUID(), UUID.randomUUID());
        IslandId islandId = new IslandId(islandUuid);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");

        // Initially empty
        assertThat(adapter.findByIslandId(islandId)).isEmpty();

        // 1. Save solvent record
        IslandBankruptcyRecord solvent = IslandBankruptcyRecord.solvent(islandId, now);
        adapter.save(solvent);

        Optional<IslandBankruptcyRecord> loaded = adapter.findByIslandId(islandId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().islandId()).isEqualTo(islandId);
        assertThat(loaded.get().status()).isEqualTo(BankruptcyStatus.SOLVENT);
        assertThat(loaded.get().debtMinorUnits()).isEqualTo(0L);
        assertThat(loaded.get().graceUntil()).isNull();

        // 2. Transition to GRACE
        Instant graceDeadline = now.plusSeconds(86400 * 3);
        IslandBankruptcyRecord grace = solvent.toGrace(50000L, graceDeadline, now.plusSeconds(60));
        adapter.save(grace);

        Optional<IslandBankruptcyRecord> loadedGrace = adapter.findByIslandId(islandId);
        assertThat(loadedGrace).isPresent();
        assertThat(loadedGrace.get().status()).isEqualTo(BankruptcyStatus.GRACE);
        assertThat(loadedGrace.get().debtMinorUnits()).isEqualTo(50000L);
        assertThat(loadedGrace.get().graceUntil()).isEqualTo(graceDeadline);

        // 3. Transition to LOCKED
        IslandBankruptcyRecord locked = loadedGrace.get().toLocked(now.plusSeconds(86400 * 3 + 10));
        adapter.save(locked);

        Optional<IslandBankruptcyRecord> loadedLocked = adapter.findByIslandId(islandId);
        assertThat(loadedLocked).isPresent();
        assertThat(loadedLocked.get().status()).isEqualTo(BankruptcyStatus.LOCKED);
        assertThat(loadedLocked.get().debtMinorUnits()).isEqualTo(50000L);
        assertThat(loadedLocked.get().graceUntil()).isNull();

        // 4. Delete
        adapter.deleteByIslandId(islandId);
        assertThat(adapter.findByIslandId(islandId)).isEmpty();
    }

    @Test
    @DisplayName("findAllBankruptcies returns active GRACE and LOCKED records only")
    void findAllBankruptciesFiltersSolvent() throws SQLException {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID island3 = UUID.randomUUID();

        seedIsland(island1, UUID.randomUUID(), UUID.randomUUID());
        seedIsland(island2, UUID.randomUUID(), UUID.randomUUID());
        seedIsland(island3, UUID.randomUUID(), UUID.randomUUID());

        Instant now = Instant.parse("2026-09-19T12:00:00Z");

        IslandBankruptcyRecord solvent = IslandBankruptcyRecord.solvent(new IslandId(island1), now);
        IslandBankruptcyRecord grace =
                IslandBankruptcyRecord.solvent(new IslandId(island2), now).toGrace(10000L, now.plusSeconds(3600), now);
        IslandBankruptcyRecord locked = IslandBankruptcyRecord.solvent(new IslandId(island3), now)
                .toGrace(20000L, now.minusSeconds(10), now)
                .toLocked(now);

        adapter.save(solvent);
        adapter.save(grace);
        adapter.save(locked);

        List<IslandBankruptcyRecord> active = adapter.findAllBankruptcies();
        assertThat(active).hasSize(2);
        assertThat(active)
                .extracting(IslandBankruptcyRecord::status)
                .containsExactlyInAnyOrder(BankruptcyStatus.GRACE, BankruptcyStatus.LOCKED);
    }
}
