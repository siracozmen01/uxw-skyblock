package com.uxplima.uxmskyblock.persistence.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.event.ConsumerInboxPort;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.persistence.backup.PlayerBackupCatalogAdapter;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.event.ConsumerInboxAdapter;
import com.uxplima.uxmskyblock.persistence.event.TransactionalOutboxAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerInventoryMutationJournalAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileHandoffFinalizationAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.leaderboard.PlayerIslandLeaderboardAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.profile.PlayerProfileSwitchAdapter;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.upgrade.PlayerIslandUpgradeAdapter;
import com.uxplima.uxmskyblock.persistence.world.PlayerWorldGridAllocationAdapter;

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
    private final PlayerWorldGridAllocationAdapter worldGridAllocationAdapter;

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
        this.worldGridAllocationAdapter = new PlayerWorldGridAllocationAdapter(database);
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

    public IslandStoragePort islandStoragePort() {
        return islandStorageAdapter;
    }

    public IslandAuthorityPort islandAuthorityPort() {
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

    public WorldGridAllocationPort worldGridAllocationPort() {
        return worldGridAllocationAdapter;
    }

    @Override
    public void close() {
        if (!database.isClosed()) {
            database.close();
        }
    }
}
