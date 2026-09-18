package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class SeasonConfigurationTest {

    @Test
    @DisplayName("loads season configuration correctly from valid HOCON")
    void loadsSeasonConfigurationFromValidHocon() throws Exception {
        String hocon = """
                seasons {
                  season-number = 2
                  season-name = "Season 2 - Ascension"
                  duration = "14d"
                  check-interval = "30s"
                  rewards {
                    1 = ["eco give %player% 5000000"]
                    2 = ["eco give %player% 2500000"]
                  }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        SeasonConfiguration config = SeasonConfiguration.load(root);

        assertThat(config.seasonNumber()).isEqualTo(2);
        assertThat(config.seasonName()).isEqualTo("Season 2 - Ascension");
        assertThat(config.duration()).isEqualTo(Duration.ofDays(14));
        assertThat(config.checkInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.tierRewards()).containsKey(1);
        assertThat(config.tierRewards().get(1)).containsExactly("eco give %player% 5000000");
        assertThat(config.tierRewards().get(2)).containsExactly("eco give %player% 2500000");
    }

    @Test
    @DisplayName("defaults properly when seasons section is missing")
    void defaultsWhenMissing() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("");
        SeasonConfiguration config = SeasonConfiguration.load(root);

        assertThat(config.seasonNumber()).isEqualTo(SeasonConfiguration.DEFAULT_SEASON_NUMBER);
        assertThat(config.seasonName()).isEqualTo(SeasonConfiguration.DEFAULT_SEASON_NAME);
        assertThat(config.duration()).isEqualTo(SeasonConfiguration.DEFAULT_DURATION);
        assertThat(config.checkInterval()).isEqualTo(SeasonConfiguration.DEFAULT_CHECK_INTERVAL);
        assertThat(config.tierRewards()).containsKey(1);
    }

    @Test
    @DisplayName("rejects null configuration node")
    @SuppressWarnings("NullAway")
    void rejectsNullNode() {
        ConfigurationNode nullNode = null;
        assertThatThrownBy(() -> SeasonConfiguration.load(nullNode)).isInstanceOf(NullPointerException.class);
    }
}
