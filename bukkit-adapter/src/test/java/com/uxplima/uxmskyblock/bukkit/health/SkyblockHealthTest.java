package com.uxplima.uxmskyblock.bukkit.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.uxplima.uxmlib.health.HealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each check says OK, WARN or FAIL for what it reads, and names itself. */
class SkyblockHealthTest {

    @Test
    @DisplayName("A database that does not answer is a failure")
    void storage() {
        assertThat(SkyblockHealth.storage(() -> true).check().status()).isEqualTo(HealthStatus.OK);
        assertThat(SkyblockHealth.storage(() -> false).check().status()).isEqualTo(HealthStatus.FAIL);
        assertThat(SkyblockHealth.storage(() -> true).name()).isEqualTo("storage");
    }

    @Test
    @DisplayName("No menu file read is a failure, and the count is said otherwise")
    void windows() {
        assertThat(SkyblockHealth.windows(() -> 0).check().status()).isEqualTo(HealthStatus.FAIL);
        assertThat(SkyblockHealth.windows(() -> 26).check().message()).contains("26");
    }

    @Test
    @DisplayName("Placeholders and the economy missing are warnings, because the plugin still works")
    void optionalIntegrations() {
        assertThat(SkyblockHealth.placeholders(() -> false).check().status()).isEqualTo(HealthStatus.WARN);
        assertThat(SkyblockHealth.economy(() -> false).check().status()).isEqualTo(HealthStatus.WARN);
        assertThat(SkyblockHealth.economy(() -> true).check().status()).isEqualTo(HealthStatus.OK);
    }

    @Test
    @DisplayName("A missing island world is a failure, a terrain world a warning, an empty one OK")
    void islandWorld() {
        assertThat(SkyblockHealth.islandWorld(() -> Optional.of("not loaded"), () -> false)
                        .check()
                        .status())
                .isEqualTo(HealthStatus.FAIL);
        assertThat(SkyblockHealth.islandWorld(() -> Optional.of("terrain"), () -> true)
                        .check()
                        .status())
                .isEqualTo(HealthStatus.WARN);
        assertThat(SkyblockHealth.islandWorld(Optional::empty, () -> true)
                        .check()
                        .status())
                .isEqualTo(HealthStatus.OK);
    }
}
