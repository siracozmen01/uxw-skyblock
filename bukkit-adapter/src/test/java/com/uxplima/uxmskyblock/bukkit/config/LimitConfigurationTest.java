package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class LimitConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing or empty")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        LimitConfiguration config = LimitConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.bypassPermission()).isEqualTo("uxmskyblock.bypass.limits");
        assertThat(config.quotas()).containsKey(LimitType.HOPPER);
        assertThat(config.quotas()).containsKey(LimitType.SPAWNER);
        assertThat(config.quotas()).containsKey(LimitType.VILLAGER);

        LimitQuota hopperQuota = Objects.requireNonNull(config.quotas().get(LimitType.HOPPER));
        assertThat(hopperQuota.baseLimit()).isEqualTo(50);
        assertThat(hopperQuota.upgradeId()).isEqualTo(new UpgradeId("HOPPER_LIMIT"));
        assertThat(hopperQuota.perTierBonus()).isEqualTo(50);
    }

    @Test
    @DisplayName("Loads custom limits configuration from HOCON")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                limits {
                    enabled = true
                    bypass-permission = "custom.bypass.permission"
                    quotas {
                        HOPPER {
                            base = 100
                            upgrade-id = "CUSTOM_HOPPERS"
                            per-tier = 100
                        }
                        VILLAGER {
                            base = 5
                            upgrade-id = ""
                            per-tier = 0
                        }
                    }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        LimitConfiguration config = LimitConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.bypassPermission()).isEqualTo("custom.bypass.permission");

        LimitQuota hopperQuota = Objects.requireNonNull(config.quotas().get(LimitType.HOPPER));
        assertThat(hopperQuota.baseLimit()).isEqualTo(100);
        assertThat(hopperQuota.upgradeId()).isEqualTo(new UpgradeId("CUSTOM_HOPPERS"));
        assertThat(hopperQuota.perTierBonus()).isEqualTo(100);

        LimitQuota villagerQuota = Objects.requireNonNull(config.quotas().get(LimitType.VILLAGER));
        assertThat(villagerQuota.baseLimit()).isEqualTo(5);
        assertThat(villagerQuota.upgradeId()).isNull();
        assertThat(villagerQuota.perTierBonus()).isEqualTo(0);
    }
}
