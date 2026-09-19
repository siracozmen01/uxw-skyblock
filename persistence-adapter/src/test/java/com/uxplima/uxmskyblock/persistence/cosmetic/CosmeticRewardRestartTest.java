package com.uxplima.uxmskyblock.persistence.cosmetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CosmeticRewardRestartTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Cosmetic grants persist across database restarts and adapter instances")
    void cosmeticGrantsPersistAcrossRestarts() {
        Path dbFile = tempDir.resolve("cosmetics_restart_test.db");
        ProfileId profileA = new ProfileId(UUID.randomUUID());
        ProfileId profileB = new ProfileId(UUID.randomUUID());

        // Phase 1: Initialize database, migrate to latest, grant cosmetics
        try (Database db1 = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db1);
            runner.apply(SkyblockMigrations.getMigrations(db1.dialect()));

            SqlProfileCosmeticStorageAdapter adapter1 = new SqlProfileCosmeticStorageAdapter(db1);

            assertThat(adapter1.hasCosmetic(profileA, "neon_wings")).isFalse();
            adapter1.grantCosmetic(profileA, "neon_wings", "QUEST_REWARD");
            adapter1.grantCosmetic(profileA, "crystal_aura", "ADMIN");
            adapter1.grantCosmetic(profileB, "fire_trail", "BATTLEPASS");

            assertThat(adapter1.hasCosmetic(profileA, "neon_wings")).isTrue();
            assertThat(adapter1.hasCosmetic(profileA, "crystal_aura")).isTrue();
            assertThat(adapter1.hasCosmetic(profileB, "fire_trail")).isTrue();
            assertThat(adapter1.hasCosmetic(profileA, "fire_trail")).isFalse();
        }

        // Phase 2: Simulate server reboot by opening a brand new database connection and adapter instance
        try (Database db2 = DatabaseTestFixture.createSqliteFile(dbFile)) {
            SqlProfileCosmeticStorageAdapter adapter2 = new SqlProfileCosmeticStorageAdapter(db2);

            // Assert data survived restart cleanly
            assertThat(adapter2.hasCosmetic(profileA, "neon_wings")).isTrue();
            assertThat(adapter2.hasCosmetic(profileA, "crystal_aura")).isTrue();
            assertThat(adapter2.getCosmetics(profileA)).containsExactlyInAnyOrder("neon_wings", "crystal_aura");

            assertThat(adapter2.hasCosmetic(profileB, "fire_trail")).isTrue();
            assertThat(adapter2.getCosmetics(profileB)).containsExactly("fire_trail");

            assertThat(adapter2.hasCosmetic(profileA, "fire_trail")).isFalse();
            assertThat(adapter2.hasCosmetic(profileB, "neon_wings")).isFalse();

            // Assert idempotency of re-granting after restart
            adapter2.grantCosmetic(profileA, "neon_wings", "REPEAT_GRANT");
            assertThat(adapter2.getCosmetics(profileA)).containsExactlyInAnyOrder("neon_wings", "crystal_aura");
        }
    }
}
