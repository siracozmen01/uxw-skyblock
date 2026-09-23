package com.uxplima.uxmskyblock.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite that acknowledges a commit before it reaches the disk is refused under the strict profile.
 *
 * <p>The testing standard's scenario: WAL with {@code synchronous = NORMAL}, which is what the
 * library's SQLite pool opens with, loses the last commits when the machine loses power. The strict
 * profile refuses to start on it; the development profile warns and starts.
 */
class SQLiteWalNormalRejectedByDurableProductionTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("The pool's own SQLite settings are reported, and the strict profile refuses them")
    void normalIsRefusedUnderStrict() {
        Database database = DatabaseTestFixture.createSqliteFile(folder.resolve("normal.db"));
        try {
            assertThat(DurabilityCheck.findProblems(database))
                    .singleElement()
                    .asString()
                    .contains("synchronous is NORMAL");
            assertThatThrownBy(() -> DurabilityCheck.enforce(database, DurabilityCheck.Profile.PRODUCTION_STRICT))
                    .isInstanceOf(FatalDurabilityConfigurationException.class)
                    .hasMessageContaining("NON_DURABLE_STORAGE_CONFIG_DETECTED");
            assertThatNoException()
                    .isThrownBy(() -> DurabilityCheck.enforce(database, DurabilityCheck.Profile.DEVELOPMENT));
        } finally {
            database.close();
        }
    }

    @Test
    @DisplayName("SQLite at synchronous FULL has nothing to report")
    void fullIsDurable() {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + folder.resolve("full.db"));

        assertThat(DurabilityCheck.findProblems(Database.adopt(source, Dialect.SQLITE)))
                .isEmpty();
    }

    @Test
    @DisplayName("A profile is read however the operator wrote it, and an unknown one warns rather than refuses")
    void profilesParse() {
        assertThat(DurabilityCheck.Profile.parse("production-strict"))
                .isEqualTo(DurabilityCheck.Profile.PRODUCTION_STRICT);
        assertThat(DurabilityCheck.Profile.parse("PRODUCTION_STRICT"))
                .isEqualTo(DurabilityCheck.Profile.PRODUCTION_STRICT);
        assertThat(DurabilityCheck.Profile.parse(null)).isEqualTo(DurabilityCheck.Profile.DEVELOPMENT);
        assertThat(DurabilityCheck.Profile.parse("strict please")).isEqualTo(DurabilityCheck.Profile.DEVELOPMENT);
    }
}
