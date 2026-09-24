package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * {@code storage.capture-timeout} is the operator's, and a value that cannot be read keeps the default.
 */
class ACaptureTimeoutIsReadFromTheOperatorsFileTest {

    @Test
    @DisplayName("The timeout the operator wrote is the one a capture waits for")
    void theWrittenValueIsUsed() throws Exception {
        assertThat(AdminWiring.captureTimeoutOf(root("storage { capture-timeout = \"90s\" }")))
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("No value, an unreadable one and one that is not positive all keep the default")
    void anythingElseKeepsTheDefault() throws Exception {
        for (String written : new String[] {
            "storage { }", "storage { capture-timeout = \"soon\" }", "storage { capture-timeout = \"0s\" }"
        }) {
            assertThat(AdminWiring.captureTimeoutOf(root(written)))
                    .describedAs(written)
                    .isEqualTo(WorldDimensionSnapshotAdapter.DEFAULT_CAPTURE_TIMEOUT);
        }
        assertThat(AdminWiring.captureTimeoutOf(null)).isEqualTo(WorldDimensionSnapshotAdapter.DEFAULT_CAPTURE_TIMEOUT);
    }

    private static ConfigurationNode root(String hocon) throws Exception {
        return HoconConfigurationLoader.builder().buildAndLoadString(hocon);
    }
}
