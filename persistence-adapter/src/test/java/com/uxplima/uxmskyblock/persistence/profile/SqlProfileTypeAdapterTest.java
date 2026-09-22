package com.uxplima.uxmskyblock.persistence.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ruleset a profile plays under, read back at last.
 *
 * <p>The column has been written since the first migration and nothing ever read it, so every check
 * that asks a profile's ruleset was told CLASSIC. The Ironman barrier the documents call an
 * invariant could not refuse anything, because it never saw a profile that was not classic.
 */
class SqlProfileTypeAdapterTest {

    private Database database;
    private SqlProfileTypeAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlProfileTypeAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private ProfileId profileWith(String storedType) throws Exception {
        UUID player = UUID.randomUUID();
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, player.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, player.toString());
                stmt.setString(3, storedType);
                stmt.executeUpdate();
            }
        }
        return profile;
    }

    @Test
    @DisplayName("Every ruleset the code knows reads back as itself")
    void everyRulesetReadsBack() throws Exception {
        for (ProfileType type : ProfileType.values()) {
            assertThat(adapter.typeOf(profileWith(type.name())))
                    .describedAs("a profile stored as %s", type)
                    .isEqualTo(type);
        }
    }

    @Test
    @DisplayName("A ruleset written in other letters is still that ruleset")
    void thecaseDoesNotMatter() throws Exception {
        assertThat(adapter.typeOf(profileWith("ironman"))).isEqualTo(ProfileType.IRONMAN);
        assertThat(adapter.typeOf(profileWith("  Hardcore "))).isEqualTo(ProfileType.HARDCORE);
    }

    @Test
    @DisplayName("A profile nobody has heard of is classic rather than an error")
    void anunknownProfileIsClassic() {
        assertThat(adapter.typeOf(ProfileId.of(UUID.randomUUID()))).isEqualTo(ProfileType.CLASSIC);
    }

    @Test
    @DisplayName("A ruleset name this build does not know is read as classic, not refused")
    void anunknownRulesetIsClassic() throws Exception {
        assertThat(adapter.typeOf(profileWith("SOMETHING_NEWER")))
                .describedAs("stopping a player because a newer build wrote a word here is worse")
                .isEqualTo(ProfileType.CLASSIC);
    }
}
