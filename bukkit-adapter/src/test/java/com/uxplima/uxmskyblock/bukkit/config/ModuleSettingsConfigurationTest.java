package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class ModuleSettingsConfigurationTest {

    @Test
    @DisplayName("loads module toggles and capability selections from valid HOCON")
    void loadsModuleSettingsFromValidHocon() throws Exception {
        String hocon = """
                modules {
                  core = true
                  bank = true
                  seasons = false
                }
                capabilities {
                  "island-bank" {
                    selected-provider = "builtin-bank"
                  }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);

        ModuleSettingsConfiguration config = ModuleSettingsConfiguration.load(root);

        assertThat(config.isModuleEnabled("core")).isTrue();
        assertThat(config.isModuleEnabled("bank")).isTrue();
        assertThat(config.isModuleEnabled("seasons")).isFalse();
        assertThat(config.isModuleEnabled("unconfigured")).isTrue(); // default enabled
        assertThat(config.selectedProvider("island-bank")).contains("builtin-bank");
        assertThat(config.selectedProvider("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("defaults to all enabled and empty capabilities when sections are missing")
    void defaultsWhenSectionsAreMissing() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString("");

        ModuleSettingsConfiguration config = ModuleSettingsConfiguration.load(root);

        assertThat(config.isModuleEnabled("core")).isTrue();
        assertThat(config.isModuleEnabled("anything")).isTrue();
        assertThat(config.selectedProviders()).isEmpty();
    }

    @Test
    @DisplayName("rejects null configuration node")
    @SuppressWarnings("NullAway")
    void rejectsNullNode() {
        ConfigurationNode nullNode = null;
        assertThatThrownBy(() -> ModuleSettingsConfiguration.load(nullNode)).isInstanceOf(NullPointerException.class);
    }
}
