package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.uxplima.uxmskyblock.core.domain.booster.BoosterCalculation;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterDurationPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterStackMode;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class BoosterConfigurationTest {

    @Test
    @DisplayName("Default configuration has canonical defaults")
    void defaultConfigurationHasCanonicalDefaults() {
        BoosterConfiguration config = BoosterConfiguration.defaultConfiguration();

        assertThat(config.pauseWhenEmpty()).isTrue();
        assertThat(config.cleanInterval()).isEqualTo(Duration.ofMinutes(1));

        CategoryBoosterPolicy spawner = config.policy(BoosterCategory.SPAWNER_RATE);
        assertThat(spawner.enabled()).isTrue();
        assertThat(spawner.stackMode()).isEqualTo(BoosterStackMode.DURATION);
        assertThat(spawner.calculation()).isEqualTo(BoosterCalculation.ADDITIVE);
        assertThat(spawner.durationPolicy()).isEqualTo(BoosterDurationPolicy.INDEPENDENT);
        assertThat(spawner.maxMultiplier()).isEqualTo(5.0);
        assertThat(spawner.maxDuration()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON")
    void loadsCustomConfigurationFromHocon() throws Exception {
        String hocon = """
                boosters {
                    pause-when-empty = false
                    clean-interval = "2m"
                    categories {
                        MOB_EXP {
                            enabled = true
                            stack-mode = "MULTIPLIER"
                            calculation = "COMPOUND"
                            duration-policy = "REFRESH"
                            default-duration = "2h"
                            max-duration = "14d"
                            max-multiplier = 10.0
                        }
                        SPAWNER_RATE {
                            enabled = false
                        }
                    }
                }
                """;

        ConfigurationNode node = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        BoosterConfiguration config = BoosterConfiguration.load(node);

        assertThat(config.pauseWhenEmpty()).isFalse();
        assertThat(config.cleanInterval()).isEqualTo(Duration.ofMinutes(2));

        CategoryBoosterPolicy mobExp = config.policy(BoosterCategory.MOB_EXP);
        assertThat(mobExp.enabled()).isTrue();
        assertThat(mobExp.stackMode()).isEqualTo(BoosterStackMode.MULTIPLIER);
        assertThat(mobExp.calculation()).isEqualTo(BoosterCalculation.COMPOUND);
        assertThat(mobExp.durationPolicy()).isEqualTo(BoosterDurationPolicy.REFRESH);
        assertThat(mobExp.defaultDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(mobExp.maxDuration()).isEqualTo(Duration.ofDays(14));
        assertThat(mobExp.maxMultiplier()).isEqualTo(10.0);

        CategoryBoosterPolicy spawner = config.policy(BoosterCategory.SPAWNER_RATE);
        assertThat(spawner.enabled()).isFalse();
    }
}
