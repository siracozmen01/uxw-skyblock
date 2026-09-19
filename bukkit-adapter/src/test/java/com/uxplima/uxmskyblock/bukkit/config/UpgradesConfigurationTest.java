package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class UpgradesConfigurationTest {

    @Test
    @DisplayName("Default configuration contains canonical upgrade definitions and tiers")
    void defaultConfigurationContainsCanonicalUpgrades() {
        UpgradesConfiguration config = UpgradesConfiguration.defaultConfiguration();

        assertThat(config.enabled()).isTrue();
        assertThat(config.definitions()).containsKey(UpgradeId.of("island_size"));
        assertThat(config.definitions()).containsKey(UpgradeId.of("member_limit"));
        assertThat(config.definitions()).containsKey(UpgradeId.of("ore_generator"));
        assertThat(config.definitions()).containsKey(UpgradeId.of("crop_growth"));
        assertThat(config.definitions()).containsKey(UpgradeId.of("spawner_rates"));

        UpgradeDefinition oreUpg =
                java.util.Objects.requireNonNull(config.definitions().get(UpgradeId.of("ore_generator")));
        assertThat(oreUpg.maxTier()).isEqualTo(4);
        assertThat(oreUpg.getTier(1).orElseThrow().costMinorUnits()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON node")
    void loadsCustomConfigurationFromHocon() throws Exception {
        String hocon = """
                enabled = true
                upgrades {
                    custom_upgrade {
                        display-name = "Custom Upgrade"
                        tiers = [
                            {
                                cost = 10000
                                currency = "PRIMARY"
                                properties {
                                    speed = 1.5
                                }
                            },
                            {
                                cost = 25000
                                currency = "PRIMARY"
                                properties {
                                    speed = 2.0
                                }
                            }
                        ]
                    }
                }
                """;

        ConfigurationNode node = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        UpgradesConfiguration config = UpgradesConfiguration.load(node);

        assertThat(config.enabled()).isTrue();
        Optional<UpgradeDefinition> optDef = config.getDefinition(UpgradeId.of("custom_upgrade"));
        assertThat(optDef).isPresent();
        UpgradeDefinition def = optDef.get();
        assertThat(def.displayName()).isEqualTo("Custom Upgrade");
        assertThat(def.tiers()).hasSize(2);
        assertThat(def.getTier(1).orElseThrow().properties()).containsEntry("speed", 1.5);
        assertThat(def.getTier(2).orElseThrow().costMinorUnits()).isEqualTo(25000L);
    }
}
