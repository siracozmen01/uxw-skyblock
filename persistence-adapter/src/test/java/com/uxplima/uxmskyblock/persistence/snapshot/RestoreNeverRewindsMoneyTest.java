package com.uxplima.uxmskyblock.persistence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A restore must never hand money back.
 *
 * <p>The adapter used to write every table the snapshot held, the island bank among them, so an
 * administrator restoring last night's file returned a balance the owner had already spent. That is
 * duplication with extra steps, and nothing in the build said so.
 */
class RestoreNeverRewindsMoneyTest {

    private static final Set<String> MONEY_TABLES = Set.of("island_banks", "island_bankruptcies", "island_boosters");

    @Test
    @DisplayName("No mode restores a table that holds money")
    void noModeRestoresMoney() throws Exception {
        for (RestoreMode mode : RestoreMode.values()) {
            assertThat(tableNamesFor(mode))
                    .describedAs("tables %s would write", mode)
                    .doesNotContainAnyElementsOf(MONEY_TABLES);
        }
    }

    @Test
    @DisplayName("Only the full island mode writes the membership tables")
    void onlyFullIslandWritesMembership() throws Exception {
        assertThat(tableNamesFor(RestoreMode.FULL_ISLAND))
                .contains("island_members", "island_roles", "island_role_permissions");
        assertThat(tableNamesFor(RestoreMode.WORLD_CONTENT_SAFE))
                .doesNotContain("island_members", "island_roles", "island_role_permissions");
    }

    @Test
    @DisplayName("Every relational mode still writes the island and where it is, which is what a restore is for")
    void everyRelationalModeWritesTheIsland() throws Exception {
        for (RestoreMode mode : List.of(RestoreMode.WORLD_CONTENT_SAFE, RestoreMode.FULL_ISLAND)) {
            assertThat(tableNamesFor(mode)).contains("islands", "island_locations", "island_flags");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tableNamesFor(RestoreMode mode) throws Exception {
        Method tablesFor = SqlRootRelationalSnapshotAdapter.class.getDeclaredMethod("tablesFor", RestoreMode.class);
        tablesFor.setAccessible(true);
        List<Object> specs = (List<Object>) tablesFor.invoke(null, mode);
        return specs.stream().map(RestoreNeverRewindsMoneyTest::tableName).toList();
    }

    private static String tableName(Object spec) {
        try {
            Method accessor = spec.getClass().getDeclaredMethod("tableName");
            accessor.setAccessible(true);
            return (String) accessor.invoke(spec);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The table spec changed shape", e);
        }
    }
}
