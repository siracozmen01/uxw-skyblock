package com.uxplima.uxmskyblock.persistence.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileHandoffFinalizationAdapter;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerProfileInventoryAdapter;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;

/**
 * One player with a session on this node, an island page and an open window on it, over any database
 * with the schema in place; each scenario of a vault close starts from a scene of its own.
 */
final class VaultCloseScene {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");

    private final Database database;
    private final SqlIslandVaultStorageAdapter vault;
    private final PlayerProfileInventoryAdapter inventories;
    private final IslandId island = IslandId.of(UUID.randomUUID());
    private final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    private final ProfileId profile = ProfileId.of(UUID.randomUUID());
    private final long epoch;
    private final VaultSessionId session;

    VaultCloseScene(Database database) throws SQLException {
        this.database = database;
        epoch = ((SessionAuthorityOutcome.Success)
                        new PlayerSessionAuthorityAdapter(database).ensureSession(player, profile, NODE))
                .epoch();
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, lifecycle, economic_state,"
                                + " administrative_state, level_score, net_worth_minor_units, version)"
                                + " VALUES (?, ?, ?, 'ACTIVE', 'NORMAL', 'NORMAL', 0, 0, 1)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, profile.value().toString());
            ps.setString(3, player.value().toString());
            ps.executeUpdate();
        }
        vault = new SqlIslandVaultStorageAdapter(database);
        inventories = new PlayerProfileInventoryAdapter(database);
        vault.createPage(island, 1, new byte[] {1}, profile.toString());
        session = vault.acquireEditSession(island, 1, player.value(), Duration.ofSeconds(60))
                .orElseThrow()
                .sessionId();
    }

    /** The page and the player's whole state are written together. */
    void bothAreWritten() {
        long version = version();

        assertThat(vault.commitEditSession(session, new byte[] {2}, profile.toString(), write(epoch, version)))
                .isTrue();

        assertThat(vault.findPage(island, 1).orElseThrow().contentsNbt()).isEqualTo(new byte[] {2});
        ProfileInventoryRecord stored = inventories.loadInventory(profile).orElseThrow();
        assertThat(stored.inventoryNbt()).isEqualTo(new byte[] {7, 7});
        assertThat(stored.enderchestNbt()).isEqualTo(new byte[] {8});
        assertThat(stored.experiencePoints()).isEqualTo(91);
        assertThat(stored.version()).isEqualTo(version + 1);
        assertThat(new PlayerProfileHandoffFinalizationAdapter(database).loadLastDurableInventoryVersion(player))
                .hasValue(version + 1);
    }

    /** A session this node no longer holds writes neither the page nor the player. */
    void aStaleSessionWritesNeither() {
        long version = version();

        assertThat(vault.commitEditSession(session, new byte[] {2}, profile.toString(), write(epoch + 1, version)))
                .isFalse();

        nothingWritten(version);
    }

    /** A player state at a version the store is not at writes neither the page nor the player. */
    void aMovedVersionWritesNeither() {
        long version = version();

        assertThat(vault.commitEditSession(session, new byte[] {2}, profile.toString(), write(epoch, version + 1)))
                .isFalse();

        nothingWritten(version);
    }

    private void nothingWritten(long version) {
        assertThat(vault.findPage(island, 1).orElseThrow().contentsNbt()).isEqualTo(new byte[] {1});
        assertThat(inventories.loadInventory(profile).orElseThrow().version()).isEqualTo(version);
        assertThat(vault.findSession(session).orElseThrow().state())
                .describedAs("the window's lease still stands, for a close that can still land")
                .isEqualTo(VaultSessionState.ACTIVE);
    }

    private PlayerStateWrite write(long atEpoch, long expectedVersion) {
        ProfileInventoryRecord state = new ProfileInventoryRecord(
                profile,
                expectedVersion,
                new byte[] {7, 7},
                new byte[] {8},
                91,
                18.0,
                17,
                3.0f,
                new byte[0],
                null,
                null,
                null,
                null,
                "SURVIVAL",
                false);
        return new PlayerStateWrite(player, NODE, atEpoch, expectedVersion, state);
    }

    private long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }
}
