package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A player's state is written only through their session's authority; there is no weaker way in.
 *
 * <p>The testing standard names this test. Every statement that writes {@code profile_inventories}
 * sits beside the read of the player's session under its row lock, and the ambient checkpoint is
 * refused for a node whose session another node now holds. The vault's own write of the player's
 * inventory once had no session check at all; it was never called, and a caller that found it could
 * have overwritten a player another node was playing.
 */
class AmbientCheckpointDoesNotBypassPlayerSessionAuthorityTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Every statement that writes a player's state reads their session under its row lock beside it")
    void everyWriteReadsTheSession() throws Exception {
        List<String> weaker = new ArrayList<>();
        int writers = 0;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                if (!text.contains("UPDATE profile_inventories")) {
                    continue;
                }
                writers++;
                if (!text.contains("FROM player_sessions") || !text.contains("FOR UPDATE")) {
                    weaker.add(source.getFileName().toString());
                }
            }
        }
        assertThat(writers).describedAs("the guard really found the writers").isGreaterThanOrEqualTo(4);
        assertThat(weaker)
                .describedAs("writers that do not read the session under its lock")
                .isEmpty();
    }

    @Test
    @DisplayName("An ambient checkpoint from a node whose session another node took is refused")
    void aCheckpointFromAnotherNodeIsRefused() throws Exception {
        Database database = DatabaseTestFixture.createSqliteFile(dir.resolve("authority.db"));
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            long epoch = ((SessionAuthorityOutcome.Success) new PlayerSessionAuthorityAdapter(database)
                            .ensureSession(player, profile, ServerNodeId.of("node-b")))
                    .epoch();
            PlayerProfileInventoryAdapter inventories = new PlayerProfileInventoryAdapter(database);
            long version = inventories.loadInventory(profile).orElseThrow().version();

            assertThat(inventories
                            .checkpointInventory(
                                    player,
                                    profile,
                                    ServerNodeId.of("node-a"),
                                    epoch,
                                    version,
                                    ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[0]))
                            .isSuccess())
                    .isFalse();
            assertThat(inventories.loadInventory(profile).orElseThrow().version())
                    .isEqualTo(version);
        } finally {
            database.close();
        }
    }
}
