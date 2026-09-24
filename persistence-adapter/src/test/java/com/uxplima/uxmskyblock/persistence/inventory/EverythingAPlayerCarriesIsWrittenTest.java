package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ambient checkpoint and the final write of a session write everything a join puts back.
 *
 * <p>Both wrote the inventory column alone, and a join applies every column: the ender chest,
 * experience, health, hunger, effects, logout place, game mode and flight came back as they were when
 * the profile was made.
 */
class EverythingAPlayerCarriesIsWrittenTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");

    @TempDir
    Path dir;

    private Database database;
    private PlayerProfileInventoryAdapter inventories;
    private final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    private final ProfileId profile = ProfileId.of(UUID.randomUUID());
    private PlayerSessionAuthorityAdapter sessions;
    private long epoch;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("state.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        inventories = new PlayerProfileInventoryAdapter(database);
        sessions = new PlayerSessionAuthorityAdapter(database);
        epoch = ((SessionAuthorityOutcome.Success) sessions.ensureSession(player, profile, NODE)).epoch();
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("An ambient checkpoint writes the whole of the player's state")
    void theCheckpointWritesEverything() {
        long version = version();
        ProfileInventoryRecord carried = carried(version, "nether");

        assertThat(inventories
                        .checkpointInventory(player, profile, NODE, epoch, version, carried)
                        .isSuccess())
                .isTrue();

        assertSameState(inventories.loadInventory(profile).orElseThrow(), carried);
    }

    @Test
    @DisplayName("The final write of a session writes the whole of the player's state")
    void theFinalWriteWritesEverything() {
        long version = version();
        ProfileInventoryRecord carried = carried(version, "the_end");
        assertThat(sessions.drain(player, NODE, epoch).isSuccess()).isTrue();

        assertThat(new PlayerProfileHandoffFinalizationAdapter(database)
                        .finalizeHandoffFlush(player, profile, NODE, epoch, version, carried)
                        .isSuccess())
                .isTrue();

        assertSameState(inventories.loadInventory(profile).orElseThrow(), carried);
    }

    @Test
    @DisplayName("A state is written only as the profile it belongs to")
    void aStateIsWrittenAsItsOwnProfile() {
        ProfileInventoryRecord someoneElses =
                ProfileInventoryRecord.createDefault(ProfileId.of(UUID.randomUUID()), new byte[] {1}, new byte[] {2});

        assertThatThrownBy(() -> inventories.checkpointInventory(player, profile, NODE, epoch, version(), someoneElses))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProfileInventoryRecord carried(long version, String world) {
        return new ProfileInventoryRecord(
                profile,
                version,
                new byte[] {1, 2, 3},
                new byte[] {4, 5, 6},
                1395,
                7.5,
                5,
                2.25f,
                new byte[] {7, 8},
                world,
                10.5,
                64.0,
                -3.25,
                "ADVENTURE",
                true);
    }

    private static void assertSameState(ProfileInventoryRecord stored, ProfileInventoryRecord carried) {
        assertThat(stored.inventoryNbt()).isEqualTo(carried.inventoryNbt());
        assertThat(stored.enderchestNbt()).isEqualTo(carried.enderchestNbt());
        assertThat(stored.experiencePoints()).isEqualTo(carried.experiencePoints());
        assertThat(stored.health()).isEqualTo(carried.health());
        assertThat(stored.foodLevel()).isEqualTo(carried.foodLevel());
        assertThat(stored.saturation()).isEqualTo(carried.saturation());
        assertThat(stored.activePotionEffectsNbt()).isEqualTo(carried.activePotionEffectsNbt());
        assertThat(stored.logoutWorld()).isEqualTo(carried.logoutWorld());
        assertThat(stored.logoutX()).isEqualTo(carried.logoutX());
        assertThat(stored.logoutY()).isEqualTo(carried.logoutY());
        assertThat(stored.logoutZ()).isEqualTo(carried.logoutZ());
        assertThat(stored.gamemode()).isEqualTo(carried.gamemode());
        assertThat(stored.flightAllowed()).isEqualTo(carried.flightAllowed());
    }

    private long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }
}
