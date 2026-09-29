package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * {@code database.lock-timeout} and {@code database.idle-transaction-timeout} reach every remote
 * connection, and a value that cannot be read keeps the specification's.
 */
class TheDatabaseWaitsAreTheOperatorsTest {

    @Test
    @DisplayName("The waits the operator wrote are the ones the connection carries")
    void theWrittenWaitsAreUsed() throws Exception {
        ConfigurationNode root = root("database { lock-timeout = \"3s\", idle-transaction-timeout = \"20s\" }");

        assertThat(PersistenceWiring.withDeadlines("jdbc:mariadb://db/sky", root))
                .endsWith("innodb_lock_wait_timeout=3");
        assertThat(PersistenceWiring.withDeadlines("jdbc:postgresql://db/sky", root))
                .contains("lock_timeout%3D3000")
                .contains("idle_in_transaction_session_timeout%3D20000");
    }

    @Test
    @DisplayName("No value, an unreadable one and one that is not positive keep the specification's")
    void anythingElseKeepsTheDefault() throws Exception {
        for (String written : new String[] {
            "database { }", "database { lock-timeout = \"soon\" }", "database { lock-timeout = \"0s\" }"
        }) {
            assertThat(PersistenceWiring.withDeadlines("jdbc:mariadb://db/sky", root(written)))
                    .describedAs(written)
                    .endsWith("innodb_lock_wait_timeout=1");
        }
        assertThat(PersistenceWiring.withDeadlines("jdbc:mariadb://db/sky", null))
                .endsWith("innodb_lock_wait_timeout=1");
    }

    @Test
    @DisplayName("Both ways to a remote database, the file and the environment, carry the waits")
    void bothRemotePathsCarryThem() throws Exception {
        String wiring = Files.readString(
                Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/PersistenceWiring.java"));
        assertThat(wiring)
                .contains("createRemote(withDeadlines(envJdbc.trim(), rootNode)")
                .contains("createRemote(withDeadlines(jdbcUrl.trim(), rootNode)");
    }

    private static ConfigurationNode root(String hocon) throws Exception {
        return HoconConfigurationLoader.builder().buildAndLoadString(hocon);
    }
}
