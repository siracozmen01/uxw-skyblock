package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The operator's lifecycle rules are read as written, and a rule that cannot be read costs only itself. */
class LifecycleConfigurationTest {

    @Test
    @DisplayName("The shipped file switches nothing on, so every event stays the server's own")
    void theShippedFileChangesNothing() throws Exception {
        LifecycleConfiguration shipped = load(resource("modules/lifecycle.conf"));

        assertThat(shipped.rules()).isZero();
        for (LifecycleEvent event : LifecycleEvent.values()) {
            assertThat(shipped.policy().effects(event, GameModeType.ONEBLOCK, ProfileType.HARDCORE))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Rules are read with their event, mode, ruleset and effects")
    void rulesAreRead() throws Exception {
        LifecycleConfiguration config = load("""
                rules = [
                  { event = "kick", send-to-spawn = true, clear-ender-chest = true }
                  { event = "death", mode = "oneblock", keep-inventory = true }
                  { event = "death", ruleset = "hardcore", keep-inventory = false, clear-ender-chest = true }
                ]
                """);

        assertThat(config.rules()).isEqualTo(3);
        assertThat(config.policy().effects(LifecycleEvent.KICK, GameModeType.SKYBLOCK, ProfileType.CLASSIC))
                .containsExactlyInAnyOrder(LifecycleEffect.SEND_TO_SPAWN, LifecycleEffect.CLEAR_ENDER_CHEST);
        assertThat(config.policy().effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.CLASSIC))
                .containsExactly(LifecycleEffect.KEEP_INVENTORY);
        assertThat(config.policy().effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.HARDCORE))
                .containsExactly(LifecycleEffect.CLEAR_ENDER_CHEST);
    }

    @Test
    @DisplayName("A rule with a mistyped event, effect or ruleset, or a keep on a reset, is left out alone")
    void anUnreadableRuleCostsOnlyItself() throws Exception {
        LifecycleConfiguration config = load("""
                rules = [
                  { event = "leav", send-to-spawn = true }
                  { event = "leave", sent-to-spawn = true }
                  { event = "leave", ruleset = "hardcor", clear-inventory = true }
                  { event = "reset", keep-inventory = true }
                  { event = "leave", clear-inventory = true }
                ]
                """);

        assertThat(config.rules()).isEqualTo(1);
        assertThat(config.policy().effects(LifecycleEvent.LEAVE, GameModeType.SKYBLOCK, ProfileType.HARDCORE))
                .containsExactly(LifecycleEffect.CLEAR_INVENTORY);
    }

    private static LifecycleConfiguration load(String text) throws Exception {
        return LifecycleConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(text));
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
