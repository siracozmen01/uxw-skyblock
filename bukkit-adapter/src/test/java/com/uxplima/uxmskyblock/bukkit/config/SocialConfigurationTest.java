package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class SocialConfigurationTest {

    @Test
    @DisplayName("loads social configuration correctly from valid HOCON")
    void loadsSocialConfigurationFromValidHocon() throws Exception {
        String hocon = """
                social {
                  min-dwell-time = "45s"
                  prior-weight = 10
                  prior-mean = 3.5
                  max-pinned = 5
                  max-message-length = 300
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        SocialConfiguration config = SocialConfiguration.load(root);

        assertThat(config.minDwellTime()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.priorWeight()).isEqualTo(10);
        assertThat(config.priorMean()).isEqualTo(3.5);
        assertThat(config.maxPinned()).isEqualTo(5);
        assertThat(config.maxMessageLength()).isEqualTo(300);
    }

    @Test
    @DisplayName("defaults properly when social section is missing")
    void defaultsWhenMissing() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("");
        SocialConfiguration config = SocialConfiguration.load(root);

        assertThat(config.minDwellTime()).isEqualTo(SocialConfiguration.DEFAULT_MIN_DWELL_TIME);
        assertThat(config.priorWeight()).isEqualTo(SocialConfiguration.DEFAULT_PRIOR_WEIGHT);
        assertThat(config.priorMean()).isEqualTo(SocialConfiguration.DEFAULT_PRIOR_MEAN);
        assertThat(config.maxPinned()).isEqualTo(SocialConfiguration.DEFAULT_MAX_PINNED);
        assertThat(config.maxMessageLength()).isEqualTo(SocialConfiguration.DEFAULT_MAX_MESSAGE_LENGTH);
    }

    @Test
    @DisplayName("rejects null configuration node")
    @SuppressWarnings("NullAway")
    void rejectsNullNode() {
        ConfigurationNode nullNode = null;
        assertThatThrownBy(() -> SocialConfiguration.load(nullNode)).isInstanceOf(NullPointerException.class);
    }
}
