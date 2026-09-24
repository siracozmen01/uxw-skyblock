package com.uxplima.uxmskyblock.persistence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A full island restore closes the island, puts the world back first and the rows last, and never
 * the money.
 *
 * <p>The testing standard names this test. An island cannot be rolled back in one instant across its
 * world regions and its rows, so it is quarantined for the whole restore: the world is put back, and
 * only then its configuration, members and upgrades. The bank keeps what it holds now, and the island's
 * version moves past both the live one and the backed up one by a hundred, so nothing that read the
 * island before the restore can write over it. The rows used to go back before the world, and the
 * version moved by one.
 */
class FullRestoreConsistencyPolicyTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");
    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final long FUNDS = 5_000L;
    private static final long SPENT = 1_200L;

    private Database database;
    private PlayerIslandStorageAdapter islands;
    private PlayerIslandBankAdapter banks;
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
        islands = new PlayerIslandStorageAdapter(database);
        banks = new PlayerIslandBankAdapter(database);

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
        assertThat(bank().depositToIsland(islandId, owner, FUNDS, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName(
            "A full island rollback quarantines, puts every world region back before the rows, keeps the money, and moves the version a hundred past")
    void aFullRestoreFollowsThePolicy() throws Exception {
        setVersion(40);
        SqlRootRelationalSnapshotAdapter rows = new SqlRootRelationalSnapshotAdapter(database.dataSource());
        byte[] relational = rows.captureRelationalSnapshot(rootRef(), 1L);
        // After the backup: money spent, and the version moved on by ordinary writes, but less than it held.
        assertThat(bank().withdrawFromIsland(islandId, owner, SPENT, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);
        setVersion(5);

        List<String> steps = new ArrayList<>();
        IslandAdminFreezeService freeze = mock(IslandAdminFreezeService.class);
        doAnswer(call -> steps.add("quarantine")).when(freeze).freezeIsland(any(), anyString(), anyString());
        doAnswer(call -> steps.add("open")).when(freeze).unfreezeIsland(any(), anyString());
        WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);
        doAnswer(call -> steps.add("world "
                        + call.getArgument(1, com.uxplima.uxmskyblock.core.domain.dimension.DimensionId.class)
                                .value()))
                .when(world)
                .restoreWorldDimension(any(), any(), any());
        RootRelationalSnapshotPort recordedRows = new RootRelationalSnapshotPort() {
            @Override
            public byte[] captureRelationalSnapshot(PrimaryGameplayRootRef rootRef, long revision) {
                return rows.captureRelationalSnapshot(rootRef, revision);
            }

            @Override
            public void restoreRelationalSnapshot(PrimaryGameplayRootRef rootRef, byte[] payload, RestoreMode mode) {
                steps.add("rows");
                rows.restoreRelationalSnapshot(rootRef, payload, mode);
            }
        };

        Map<String, byte[]> artifacts = new HashMap<>();
        artifacts.put("relational.json", relational);
        artifacts.put("world-nether.dat", new byte[] {1});
        artifacts.put("world-overworld.dat", new byte[] {2});
        IslandRestoreService restore =
                new IslandRestoreService(mock(BackupCatalogPort.class), storage(artifacts), recordedRows, world);
        restore.quarantineWith(freeze);

        IslandRestoreService.RestoreOutcome outcome =
                restore.executeRestore(manifest(artifacts), BUCKET, "backups/set", true, RestoreMode.FULL_ISLAND);

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        assertThat(steps).containsExactly("quarantine", "world the_nether", "world overworld", "rows", "open");
        assertThat(banks.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits())
                .describedAs("the money spent after the backup stays spent")
                .isEqualTo(FUNDS - SPENT);
        assertThat(version()).describedAs("max(5, 40) + 100").isEqualTo(140L);
    }

    private IslandBankService bank() {
        return new IslandBankService(banks, islands, islands);
    }

    private PrimaryGameplayRootRef rootRef() {
        return new PrimaryGameplayRootRef(
                GameModeInstanceId.fromString(islandId.value().toString()),
                islandId.value().toString(),
                "ISLAND",
                Instant.now());
    }

    private BackupManifest manifest(Map<String, byte[]> artifacts) {
        Map<String, BackupArtifact> described = new HashMap<>();
        artifacts.forEach((name, data) ->
                described.put(name, new BackupArtifact(name, data.length, BackupService.computeSha256(data))));
        return new BackupManifest(
                BackupSetId.random(),
                BackupType.ROOT_BACKUP,
                "ISLAND",
                islandId.value().toString(),
                Instant.now(),
                1L,
                1L,
                SkyblockMigrations.LATEST_VERSION,
                "0.1.0",
                described,
                "CONSISTENT");
    }

    private static ObjectStoragePort storage(Map<String, byte[]> artifacts) {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.exists(any(), anyString())).thenReturn(true);
        when(storage.getObject(any(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1, String.class);
            return Optional.ofNullable(artifacts.get(key.substring(key.lastIndexOf('/') + 1)));
        });
        return storage;
    }

    private void setVersion(long version) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("UPDATE islands SET version = ? WHERE id = ?")) {
            stmt.setLong(1, version);
            stmt.setString(2, islandId.value().toString());
            assertThat(stmt.executeUpdate()).isEqualTo(1);
        }
    }

    private long version() throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("SELECT version FROM islands WHERE id = ?")) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
