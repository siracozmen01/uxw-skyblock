package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class MissionConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        MissionConfiguration config = MissionConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.missions()).isNotEmpty();
        assertThat(config.missions()).anyMatch(m -> m.id().value().equals("farming_wheat_1"));
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                missions {
                    enabled = true
                    catalog {
                        "custom_mission_1" {
                            branch = "MINING"
                            display-name = "Custom Mining Quest"
                            description = "Break diamond blocks"
                            trigger-type = "BLOCK_BREAK"
                            target-filter = "DIAMOND_BLOCK"
                            required-amount = 10
                            rewards {
                                crystals = 50
                                currency-minor-units = 10000
                                island-exp = 500
                                commands = [
                                    "broadcast Great job {player}!"
                                ]
                            }
                        }
                    }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        MissionConfiguration config = MissionConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.missions()).hasSize(1);
        var mission = config.missions().getFirst();
        assertThat(mission.id().value()).isEqualTo("custom_mission_1");
        assertThat(mission.branch()).isEqualTo(MissionBranch.MINING);
        assertThat(mission.displayName()).isEqualTo("Custom Mining Quest");
        assertThat(mission.triggerType()).isEqualTo(MissionTriggerType.BLOCK_BREAK);
        assertThat(mission.targetFilter()).isEqualTo("DIAMOND_BLOCK");
        assertThat(mission.requiredAmount()).isEqualTo(10L);
        assertThat(mission.reward().crystals()).isEqualTo(50L);
        assertThat(mission.reward().currencyMinorUnits()).isEqualTo(10000L);
        assertThat(mission.reward().islandExp()).isEqualTo(500L);
        assertThat(mission.reward().commands()).containsExactly("broadcast Great job {player}!");
    }
}
