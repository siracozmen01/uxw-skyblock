package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** How many roles of their own an owner may make is the operator's number, raised by a node the operator names. */
class RoleConfigurationTest {

    @Test
    @DisplayName("The shipped file lets every owner make three, and no node names a rank")
    void theShippedFile() throws Exception {
        RoleConfiguration roles = RoleConfiguration.load(HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/config.conf"))
                .build()
                .load());

        assertThat(roles.base()).isEqualTo(3);
        assertThat(roles.max()).isEqualTo(10);
        assertThat(roles.permissionTiers()).isEmpty();
        assertThat(roles.allowanceFor(node -> true)).isEqualTo(3);
    }

    @Test
    @DisplayName("The highest node an owner holds raises the allowance, and never past the ceiling")
    void theHighestNodeWins() throws Exception {
        RoleConfiguration roles =
                RoleConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        roles.custom {
                            base = 1
                            max = 6
                            permission-tiers { "myserver.roles.4" = 4, "myserver.roles.9" = 9, "myserver.none" = 0 }
                        }
                        """));

        assertThat(roles.allowanceFor(node -> false)).isEqualTo(1);
        assertThat(roles.allowanceFor(Set.of("myserver.roles.4")::contains)).isEqualTo(4);
        assertThat(roles.allowanceFor(Set.of("myserver.roles.4", "myserver.roles.9")::contains))
                .isEqualTo(6);
        assertThat(roles.permissionTiers()).doesNotContainKey("myserver.none");
    }

    @Test
    @DisplayName("A file that leaves roles out gets three, and none for an owner when the operator writes zero")
    void whatTheFileLeavesOut() throws Exception {
        assertThat(RoleConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("homes.base = 1")))
                .isEqualTo(RoleConfiguration.defaults());
        assertThat(RoleConfiguration.load(null)).isEqualTo(RoleConfiguration.defaults());
        assertThat(RoleConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("roles.custom { base = 0, max = 0 }"))
                        .allowanceFor(node -> true))
                .isZero();
    }
}
