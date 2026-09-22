package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * What an interaction fires comes off the operator's file.
 *
 * <p>Four interactions had a sound written into Java and two of those a particle as well. The file
 * is where those live now, written in the family's own grammar and read by the library's action
 * engine, so a line copied out of any other plugin's file means here what it means there.
 */
class EffectsConfigurationTest {

    private static InteractionEffects load(String hocon) throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        return EffectsConfiguration.load(root);
    }

    @Test
    @DisplayName("What the operator wrote is what fires, all of it")
    void theoperatorsListIsTheOne() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  mission-completed = [
                    "[title] <green>Done",
                    "[sound] ui.toast.challenge_complete 1.0 1.0",
                    "[particle] HEART 5 0.3"
                  ]
                }
                """);

        assertThat(effects.of("mission-completed").actions())
                .describedAs("in the order the operator wrote them")
                .hasSize(3);
        assertThat(effects.names("mission-completed")).isTrue();
    }

    @Test
    @DisplayName("An interaction written as an empty list is one the operator switched off")
    void anemptyListIsSwitchedOff() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  kinetic-ward = []
                }
                """);

        assertThat(effects.of("kinetic-ward").actions()).isEmpty();
        assertThat(effects.names("kinetic-ward")).isFalse();
    }

    @Test
    @DisplayName("An interaction nobody named fires nothing")
    void anunnamedInteractionFiresNothing() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  kinetic-ward = ["[sound] block.note_block.bass 1.0 1.0"]
                }
                """);

        assertThat(effects.of("something-else").actions()).isEmpty();
        assertThat(InteractionEffects.none().of("kinetic-ward").actions()).isEmpty();
    }

    @Test
    @DisplayName("A line the engine cannot read is skipped and the rest still fire")
    void abadLineIsSkipped() throws Exception {
        InteractionEffects effects = load("""
                effects {
                  limit-refused = [
                    "[fireworks] BIG",
                    "[sound] block.note_block.bass 1.0 0.5"
                  ]
                }
                """);

        assertThat(effects.of("limit-refused").actions())
                .describedAs("the engine's own parser throws on the first line it cannot read, and a "
                        + "list built in one call would be dropped whole, taking the sound with it")
                .hasSize(1);
    }

    @Test
    @DisplayName("A node with no file behaves the way this plugin always did")
    void nofileMeansTheShippedBehaviour() throws Exception {
        InteractionEffects shipped = EffectsConfiguration.defaultConfiguration();

        assertThat(shipped.of("kinetic-ward").actions()).hasSize(2);
        assertThat(shipped.of("obsidian-recovery").actions()).hasSize(2);
        assertThat(shipped.of("limit-refused").actions()).hasSize(1);
        assertThat(shipped.of("mission-completed").actions()).hasSize(1);

        assertThat(load("nothing { }").byInteraction().keySet())
                .isEqualTo(shipped.byInteraction().keySet());
    }

    @Test
    @DisplayName("The file this plugin ships is the file it says it ships")
    void theshippedFileMatchesTheShippedDefault() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .path(java.nio.file.Path.of("src/main/resources/modules/effects.conf"))
                .build()
                .load();

        InteractionEffects fromTheFile = EffectsConfiguration.load(root);
        InteractionEffects shipped = EffectsConfiguration.defaultConfiguration();

        assertThat(fromTheFile.byInteraction().keySet())
                .describedAs("a file that ships saying one thing while the code falls back to another "
                        + "is two answers to one question")
                .isEqualTo(shipped.byInteraction().keySet());
        for (String interaction : shipped.byInteraction().keySet()) {
            assertThat(fromTheFile.of(interaction).actions())
                    .describedAs("%s", interaction)
                    .hasSameSizeAs(shipped.of(interaction).actions());
        }
    }

    @Test
    @DisplayName("Every line the shipped file carries is a line the engine really read")
    void everyshippedLineWasRead() {
        InteractionEffects shipped = EffectsConfiguration.defaultConfiguration();

        assertThat(shipped.byInteraction())
                .allSatisfy((interaction, list) -> assertThat(list.actions())
                        .describedAs(
                                "%s: a line the engine could not read is dropped, so a shipped "
                                        + "interaction with none left is a grammar this plugin got wrong",
                                interaction)
                        .isNotEmpty());
    }
}
