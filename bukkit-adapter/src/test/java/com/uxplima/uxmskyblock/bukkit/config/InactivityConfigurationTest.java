package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.uxplima.uxmskyblock.core.domain.inactivity.AbandonmentAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.FormerOwnerAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class InactivityConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        InactivityConfiguration config = InactivityConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.checkInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(config.ownerInactivityDuration()).isEqualTo(Duration.ofDays(30));
        assertThat(config.allMembersInactivityDuration()).isEqualTo(Duration.ofDays(60));
        assertThat(config.successionHierarchy())
                .containsExactly(IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER);
        assertThat(config.formerOwnerAction()).isEqualTo(FormerOwnerAction.DEMOTE_TO_CO_OWNER);
        assertThat(config.abandonmentAction()).isEqualTo(AbandonmentAction.ARCHIVE);

        InactivityPolicy policy = config.toPolicy();
        assertThat(policy.enabled()).isTrue();
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws Exception {
        String hocon = """
                inactivity {
                    enabled = true
                    check-interval-seconds = 3600
                    owner-inactivity-duration = "14d"
                    all-members-inactivity-duration = "45d"
                    succession-hierarchy = ["CO_OWNER", "MEMBER"]
                    former-owner-action = "DEMOTE_TO_MEMBER"
                    abandonment-action = "DELETE_AND_RECYCLE"
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        InactivityConfiguration config = InactivityConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.checkInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(config.ownerInactivityDuration()).isEqualTo(Duration.ofDays(14));
        assertThat(config.allMembersInactivityDuration()).isEqualTo(Duration.ofDays(45));
        assertThat(config.successionHierarchy()).containsExactly(IslandRole.CO_OWNER, IslandRole.MEMBER);
        assertThat(config.formerOwnerAction()).isEqualTo(FormerOwnerAction.DEMOTE_TO_MEMBER);
        assertThat(config.abandonmentAction()).isEqualTo(AbandonmentAction.DELETE_AND_RECYCLE);
    }
}
