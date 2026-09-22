package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.core.domain.effect.InteractionEffect;
import com.uxplima.uxmskyblock.core.domain.effect.InteractionEffects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * What an interaction fires comes off the operator's file.
 *
 * <p>Four interactions had a sound written into Java and two of those a particle as well. The file
 * is where those live now, and what this plugin ships with is what it used to do, so a server that
 * never opens the file sees no change.
 */
class EffectsConfigurationTest {

    private static InteractionEffects load(String hocon) throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        return EffectsConfiguration.load(root);
    }

    @Test
    @DisplayName("What the operator wrote is what fires, in the order they wrote it")
    void theoperatorsListIsTheOne() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  mission-completed = [
                    "title:mission.done",
                    "sound:UI_TOAST_CHALLENGE_COMPLETE 1.0 1.0",
                    "particle:HEART 5 0.3 0.3 0.3 0.0"
                  ]
                }
                """);

        assertThat(effects.of("mission-completed"))
                .extracting(InteractionEffect::kind)
                .containsExactly(
                        InteractionEffect.Kind.TITLE, InteractionEffect.Kind.SOUND, InteractionEffect.Kind.PARTICLE);
    }

    @Test
    @DisplayName("An interaction written as an empty list is one the operator switched off")
    void anemptyListIsSwitchedOff() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  kinetic-ward = []
                }
                """);

        assertThat(effects.of("kinetic-ward")).isEmpty();
        assertThat(effects.names("kinetic-ward")).isFalse();
    }

    @Test
    @DisplayName("A line the file cannot make sense of is skipped and the rest still fire")
    void abadLineIsSkipped() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  limit-refused = [
                    "fireworks:BIG",
                    "sound:BLOCK_NOTE_BLOCK_BASS 1.0 0.5"
                  ]
                }
                """);

        assertThat(effects.of("limit-refused"))
                .describedAs("an island nobody can build on because one line has a typo is worse")
                .hasSize(1);
        assertThat(effects.of("limit-refused").get(0).name()).isEqualTo("BLOCK_NOTE_BLOCK_BASS");
    }

    @Test
    @DisplayName("A node with no file behaves the way this plugin always did")
    void nofileMeansTheShippedBehaviour() throws Exception {
        InteractionEffects shipped = EffectsConfiguration.defaultConfiguration();

        assertThat(shipped.of("kinetic-ward"))
                .extracting(InteractionEffect::name)
                .containsExactly("ENTITY_PLAYER_ATTACK_SWEEP", "CRIT");
        assertThat(shipped.of("obsidian-recovery"))
                .extracting(InteractionEffect::name)
                .containsExactly("ITEM_BUCKET_FILL_LAVA", "CAMPFIRE_COSY_SMOKE");
        assertThat(shipped.of("limit-refused"))
                .extracting(InteractionEffect::name)
                .containsExactly("BLOCK_NOTE_BLOCK_BASS");
        assertThat(shipped.of("mission-completed"))
                .extracting(InteractionEffect::name)
                .containsExactly("UI_TOAST_CHALLENGE_COMPLETE");

        assertThat(load("nothing { }")).isEqualTo(shipped);
    }

    @Test
    @DisplayName("The file this plugin ships is the file it says it ships")
    void theshippedFileMatchesTheShippedDefault() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .path(java.nio.file.Path.of("src/main/resources/modules/effects.conf"))
                .build()
                .load();

        assertThat(EffectsConfiguration.load(root))
                .describedAs("a file that ships saying one thing while the code falls back to another "
                        + "is two answers to one question")
                .isEqualTo(EffectsConfiguration.defaultConfiguration());
    }
}
