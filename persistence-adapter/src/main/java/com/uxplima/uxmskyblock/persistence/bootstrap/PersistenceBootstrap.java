package com.uxplima.uxmskyblock.persistence.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.application.event.ConsumerInboxPort;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezePort;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardStoragePort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialStoragePort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.persistence.access.SqlTemporaryAccessAdapter;
import com.uxplima.uxmskyblock.persistence.alliance.PlayerIslandAllianceAdapter;
import com.uxplima.uxmskyblock.persistence.backup.PlayerBackupCatalogAdapter;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.economy.PlayerEconomySagaAdapter;
import com.uxplima.uxmskyblock.persistence.event.ConsumerInboxAdapter;
import com.uxplima.uxmskyblock.persistence.event.TransactionalOutboxAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerInventoryMutationJournalAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileHandoffFinalizationAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.leaderboard.PlayerIslandLeaderboardAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.profile.PlayerProfileSwitchAdapter;
import com.uxplima.uxmskyblock.persistence.reward.SqlRewardStorageAdapter;
import com.uxplima.uxmskyblock.persistence.season.PlayerIslandSeasonAdapter;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.social.PlayerIslandSocialAdapter;
import com.uxplima.uxmskyblock.persistence.upgrade.PlayerIslandUpgradeAdapter;
import com.uxplima.uxmskyblock.persistence.vault.SqlIslandVaultStorageAdapter;
import com.uxplima.uxmskyblock.persistence.warp.SqlIslandWarpStorageAdapter;
import com.uxplima.uxmskyblock.persistence.world.PlayerWorldGridAllocationAdapter;
import com.uxplima.uxmskyblock.persistence.world.SqlSpiralSlotPoolAdapter;

/**
 * Encapsulated persistence composition root establishing database migrations and
 * instantiating all production outbound persistence adapters.
 */
public final class PersistenceBootstrap implements AutoCloseable {

    private final Database database;
    private final PlayerIslandStorageAdapter islandStorageAdapter;
    private final PlayerIslandBankAdapter islandBankAdapter;
    private final PlayerIslandUpgradeAdapter islandUpgradeAdapter;
    private final PlayerIslandLeaderboardAdapter islandLeaderboardAdapter;
    private final PlayerBackupCatalogAdapter backupCatalogAdapter;
    private final TransactionalOutboxAdapter outboxAdapter;
    private final ConsumerInboxAdapter consumerInboxAdapter;
    private final PlayerSessionAuthorityAdapter sessionAuthorityAdapter;
    private final PlayerProfileInventoryAdapter inventoryAdapter;
    private final PlayerInventoryMutationJournalAdapter mutationJournalAdapter;
    private final PlayerProfileHandoffFinalizationAdapter handoffFinalizationAdapter;
    private final PlayerProfileSwitchAdapter profileSwitchAdapter;
    private final SqlSpiralSlotPoolAdapter spiralSlotPoolAdapter;
    private final PlayerWorldGridAllocationAdapter worldGridAllocationAdapter;
    private final PlayerEconomySagaAdapter economySagaAdapter;
    private final PlayerIslandSeasonAdapter islandSeasonAdapter;
    private final PlayerIslandSocialAdapter islandSocialAdapter;
    private final PlayerIslandAllianceAdapter islandAllianceAdapter;
    private final SqlTemporaryAccessAdapter temporaryAccessAdapter;
    private final SqlRewardStorageAdapter rewardStorageAdapter;
    private final SqlIslandWarpStorageAdapter islandWarpStorageAdapter;
    private final SqlIslandVaultStorageAdapter islandVaultStorageAdapter;
    private final com.uxplima.uxmskyblock.persistence.mission.PlayerIslandMissionAdapter islandMissionAdapter;

    public PersistenceBootstrap(Database database) {
        this.database = Objects.requireNonNull(database, "database");

        // Run migrations up to LATEST_VERSION
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        this.islandStorageAdapter = new PlayerIslandStorageAdapter(database);
        this.islandBankAdapter = new PlayerIslandBankAdapter(database);
        this.islandUpgradeAdapter = new PlayerIslandUpgradeAdapter(database);
        this.islandLeaderboardAdapter = new PlayerIslandLeaderboardAdapter(database);
        this.backupCatalogAdapter = new PlayerBackupCatalogAdapter(database);
        this.outboxAdapter = new TransactionalOutboxAdapter(database);
        this.consumerInboxAdapter = new ConsumerInboxAdapter(database);
        this.sessionAuthorityAdapter = new PlayerSessionAuthorityAdapter(database);
        this.inventoryAdapter = new PlayerProfileInventoryAdapter(database);
        this.mutationJournalAdapter = new PlayerInventoryMutationJournalAdapter(database);
        this.handoffFinalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(database);
        this.profileSwitchAdapter = new PlayerProfileSwitchAdapter(database);
        this.spiralSlotPoolAdapter = new SqlSpiralSlotPoolAdapter(database);
        this.worldGridAllocationAdapter = new PlayerWorldGridAllocationAdapter(database, spiralSlotPoolAdapter);
        this.economySagaAdapter = new PlayerEconomySagaAdapter(database);
        this.islandSeasonAdapter = new PlayerIslandSeasonAdapter(database);
        this.islandSocialAdapter = new PlayerIslandSocialAdapter(database);
        this.islandAllianceAdapter = new PlayerIslandAllianceAdapter(database);
        this.temporaryAccessAdapter = new SqlTemporaryAccessAdapter(database);
        this.rewardStorageAdapter = new SqlRewardStorageAdapter(database);
        this.islandWarpStorageAdapter = new SqlIslandWarpStorageAdapter(database);
        this.islandVaultStorageAdapter = new SqlIslandVaultStorageAdapter(database);
        this.islandMissionAdapter =
                new com.uxplima.uxmskyblock.persistence.mission.PlayerIslandMissionAdapter(database);
    }

    public static PersistenceBootstrap createSqlite(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Database db = Database.builder().sqlite(databaseFile).build();
        return new PersistenceBootstrap(db);
    }

    public static PersistenceBootstrap createSqliteInMemory() {
        Database db = Database.builder().sqliteInMemory().build();
        return new PersistenceBootstrap(db);
    }

    public static PersistenceBootstrap createRemote(String jdbcUrl, String username, String password, int maxPoolSize) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        com.uxplima.uxmlib.storage.sql.DatabaseBuilder builder =
                Database.builder().jdbcUrl(jdbcUrl);
        if (username != null && !username.isBlank()) {
            builder.username(username);
        }
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        if (maxPoolSize > 0) {
            builder.maxPoolSize(maxPoolSize);
        }
        return new PersistenceBootstrap(builder.build());
    }

    public IslandStoragePort islandStoragePort() {
        return islandStorageAdapter;
    }

    public IslandAuthorityPort islandAuthorityPort() {
        return islandStorageAdapter;
    }

    public IslandAdminFreezePort islandAdminFreezePort() {
        return islandStorageAdapter;
    }

    public IslandBankPort islandBankPort() {
        return islandBankAdapter;
    }

    public IslandUpgradeStoragePort islandUpgradeStoragePort() {
        return islandUpgradeAdapter;
    }

    public IslandLeaderboardPort islandLeaderboardPort() {
        return islandLeaderboardAdapter;
    }

    public BackupCatalogPort backupCatalogPort() {
        return backupCatalogAdapter;
    }

    public OutboxPort outboxPort() {
        return outboxAdapter;
    }

    public ConsumerInboxPort consumerInboxPort() {
        return consumerInboxAdapter;
    }

    public PlayerSessionAuthorityPort sessionAuthorityPort() {
        return sessionAuthorityAdapter;
    }

    public ProfileInventoryCheckpointPort inventoryPort() {
        return inventoryAdapter;
    }

    public InventoryMutationJournalPort mutationJournalPort() {
        return mutationJournalAdapter;
    }

    public ProfileHandoffFinalizationPort handoffFinalizationPort() {
        return handoffFinalizationAdapter;
    }

    public ProfileSwitchPort profileSwitchPort() {
        return profileSwitchAdapter;
    }

    public SpiralSlotPoolPort spiralSlotPoolPort() {
        return spiralSlotPoolAdapter;
    }

    public WorldGridAllocationPort worldGridAllocationPort() {
        return worldGridAllocationAdapter;
    }

    public EconomySagaPort economySagaPort() {
        return economySagaAdapter;
    }

    public IslandSeasonStoragePort islandSeasonStoragePort() {
        return islandSeasonAdapter;
    }

    public IslandSocialStoragePort islandSocialStoragePort() {
        return islandSocialAdapter;
    }

    public IslandAllianceStoragePort islandAllianceStoragePort() {
        return islandAllianceAdapter;
    }

    public TemporaryAccessStoragePort temporaryAccessStoragePort() {
        return temporaryAccessAdapter;
    }

    public RewardStoragePort rewardStoragePort() {
        return rewardStorageAdapter;
    }

    public IslandWarpStoragePort islandWarpStoragePort() {
        return islandWarpStorageAdapter;
    }

    public IslandVaultStoragePort islandVaultStoragePort() {
        return islandVaultStorageAdapter;
    }

    public com.uxplima.uxmskyblock.core.application.mission.IslandMissionStoragePort islandMissionStoragePort() {
        return islandMissionAdapter;
    }

    public void registerProfile(PlayerUuid playerUuid, ProfileId profileId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        try (java.sql.Connection conn = database.connection();
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, playerUuid.value().toString());
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to register profile", e);
        }
    }

    @Override
    public void close() {
        if (!database.isClosed()) {
            database.close();
        }
    }
}
