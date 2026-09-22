package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** How long an island waits between two rescans is read from {@code modules/levels.conf}. */
class TheRescanCooldownIsTheOperatorsTest {

    @Test
    @DisplayName("The file the plugin ships names the cooldown the code falls back to")
    void theShippedFileNamesTheFallback() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("modules/levels.conf"), "modules/levels.conf")) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(LevelConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(shipped))
                        .recalculationCooldown())
                .isEqualTo(LevelConfiguration.DEFAULT_RECALCULATION_COOLDOWN);
    }

    @Test
    @DisplayName("An operator who writes another cooldown gets that cooldown")
    void theOperatorsNumberIsRead() throws Exception {
        LevelConfiguration config = LevelConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("recalculation-cooldown = \"15s\""));

        assertThat(config.recalculationCooldown()).isEqualTo(Duration.ofSeconds(15));
    }
}
