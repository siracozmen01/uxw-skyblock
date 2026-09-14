package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.Arrays;

import com.uxplima.uxmskyblock.core.domain.durability.DurabilityMode;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class PlayerStateConfigurationAdapterTest {

    private static CommentedConfigurationNode parseHocon(String hocon) throws Exception {
        return HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build()
                .load();
    }

    @Test
    @DisplayName("maps valid HOCON node to pure PlayerStateDurabilityConfig")
    void mapsValidHoconNode() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = HYBRID
                    ambient-checkpoint-interval = 45s
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        PlayerStateDurabilityConfig config = PlayerStateConfigurationAdapter.load(node);

        assertThat(config.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);
        assertThat(config.ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("defaults to canonical policy when player-state section is absent or empty")
    void defaultsWhenSectionAbsent() throws Exception {
        CommentedConfigurationNode emptyNode = parseHocon("");

        PlayerStateDurabilityConfig config = PlayerStateConfigurationAdapter.load(emptyNode);

        assertThat(config).isEqualTo(PlayerStateDurabilityConfig.defaultPolicy());
        assertThat(config.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);
        assertThat(config.ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("defaults durability mode to HYBRID when mode key is omitted")
    void defaultsDurabilityModeWhenOmitted() throws Exception {
        String hocon = """
                player-state {
                    ambient-checkpoint-interval = 90s
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThat(node.node("player-state", "durability-mode").virtual()).isTrue();
        assertThat(node.node("player-state", "durability-mode").getString()).isNull();

        PlayerStateDurabilityConfig config = PlayerStateConfigurationAdapter.load(node);

        assertThat(config.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);
        assertThat(config.ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("defaults ambient checkpoint interval to 60s when interval key is omitted")
    void defaultsIntervalWhenOmitted() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = HYBRID
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThat(node.node("player-state", "ambient-checkpoint-interval").virtual())
                .isTrue();
        assertThat(node.node("player-state", "ambient-checkpoint-interval").getString())
                .isNull();

        PlayerStateDurabilityConfig config = PlayerStateConfigurationAdapter.load(node);

        assertThat(config.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);
        assertThat(config.ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("rejects malformed duration string with descriptive error")
    void rejectsMalformedDuration() throws Exception {
        String hocon = """
                player-state {
                    ambient-checkpoint-interval = "unparseable-time"
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambient-checkpoint-interval");
    }

    @Test
    @DisplayName("rejects non-positive duration (0s or negative) with descriptive error")
    void rejectsNonPositiveDuration() throws Exception {
        String hoconZero = """
                player-state {
                    ambient-checkpoint-interval = "0s"
                }
                """;
        CommentedConfigurationNode nodeZero = parseHocon(hoconZero);
        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(nodeZero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        String hoconNegative = """
                player-state {
                    ambient-checkpoint-interval = "-10s"
                }
                """;
        CommentedConfigurationNode nodeNegative = parseHocon(hoconNegative);
        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(nodeNegative))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects unknown durability mode with descriptive error")
    void rejectsUnknownDurabilityMode() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = "UNSUPPORTED_ASYNC_MODE"
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durability-mode");
    }

    @Test
    @DisplayName("rejects non-canonical or unnormalized durability mode string")
    void rejectsNonCanonicalDurabilityMode() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = "hybrid"
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durability-mode");
    }

    @Test
    @DisplayName("rejects configured durability-mode when empty string")
    void rejectsEmptyDurabilityMode() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = ""
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThat(node.node("player-state", "durability-mode").virtual()).isFalse();
        assertThat(node.node("player-state", "durability-mode").getString()).isEmpty();

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durability-mode")
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("rejects configured durability-mode when whitespace-only string")
    void rejectsWhitespaceDurabilityMode() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = "   "
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThat(node.node("player-state", "durability-mode").virtual()).isFalse();
        assertThat(node.node("player-state", "durability-mode").getString()).isEqualTo("   ");

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durability-mode")
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("rejects configured ambient-checkpoint-interval when empty string")
    void rejectsEmptyAmbientCheckpointInterval() throws Exception {
        String hocon = """
                player-state {
                    ambient-checkpoint-interval = ""
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThat(node.node("player-state", "ambient-checkpoint-interval").virtual())
                .isFalse();
        assertThat(node.node("player-state", "ambient-checkpoint-interval").getString())
                .isEmpty();

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambient-checkpoint-interval")
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("rejects configured ambient-checkpoint-interval when whitespace-only string")
    void rejectsWhitespaceAmbientCheckpointInterval() throws Exception {
        String hocon = """
                player-state {
                    ambient-checkpoint-interval = "   "
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        assertThat(node.node("player-state", "ambient-checkpoint-interval").virtual())
                .isFalse();
        assertThat(node.node("player-state", "ambient-checkpoint-interval").getString())
                .isEqualTo("   ");

        assertThatThrownBy(() -> PlayerStateConfigurationAdapter.load(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambient-checkpoint-interval")
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName(
            "proves boundary separation end-to-end: returned core policy contains zero Configurate or platform types")
    void provesBoundarySeparationEndToEnd() throws Exception {
        String hocon = """
                player-state {
                    durability-mode = HYBRID
                    ambient-checkpoint-interval = 60s
                }
                """;
        CommentedConfigurationNode node = parseHocon(hocon);

        PlayerStateDurabilityConfig config = PlayerStateConfigurationAdapter.load(node);

        Class<?> clazz = config.getClass();
        assertThat(clazz.getPackageName()).startsWith("com.uxplima.uxmskyblock.core");

        // Verify no Configurate interfaces or superclasses
        boolean hasConfigurate = Arrays.stream(clazz.getInterfaces())
                .anyMatch(i -> i.getName().startsWith("org.spongepowered.configurate"));
        assertThat(hasConfigurate).isFalse();

        // Verify fields are pure Java
        Arrays.stream(clazz.getDeclaredFields()).forEach(field -> {
            String typeName = field.getType().getName();
            assertThat(typeName)
                    .doesNotStartWith("org.spongepowered")
                    .doesNotStartWith("org.bukkit")
                    .doesNotStartWith("io.papermc");
        });
    }
}
