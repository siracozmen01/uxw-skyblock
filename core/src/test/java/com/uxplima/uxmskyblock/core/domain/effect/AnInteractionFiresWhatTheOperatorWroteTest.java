package com.uxplima.uxmskyblock.core.domain.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An interaction fires the list the operator wrote, in the order they wrote it.
 *
 * <p>Four interactions had a sound written into Java and two of those a particle as well, so a
 * server that wanted a different note, or none at all, or a title and a bar beside it, had nowhere
 * to say so.
 */
class AnInteractionFiresWhatTheOperatorWroteTest {

    @Test
    @DisplayName("Every kind the file publishes is read back as itself")
    void everykindIsRead() {
        assertThat(InteractionEffect.parse("message:limits.reached"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.MESSAGE);
        assertThat(InteractionEffect.parse("title:mission.done"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.TITLE);
        assertThat(InteractionEffect.parse("subtitle:mission.done"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.SUBTITLE);
        assertThat(InteractionEffect.parse("actionbar:limits.reached"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.ACTION_BAR);
        assertThat(InteractionEffect.parse("bossbar:mission.done PURPLE 5"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.BOSS_BAR);
        assertThat(InteractionEffect.parse("sound:UI_TOAST_CHALLENGE_COMPLETE 1.0 1.0"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.SOUND);
        assertThat(InteractionEffect.parse("particle:CRIT 20 0.8 0.2 0.8 0.1"))
                .get()
                .extracting(InteractionEffect::kind)
                .isEqualTo(InteractionEffect.Kind.PARTICLE);
    }

    @Test
    @DisplayName("The name and the numbers after it are read off the line")
    void thenameAndNumbersAreRead() {
        InteractionEffect sound =
                InteractionEffect.parse("sound:BLOCK_NOTE_BLOCK_BASS 1.0 0.5").orElseThrow();

        assertThat(sound.name()).isEqualTo("BLOCK_NOTE_BLOCK_BASS");
        assertThat(sound.number(0, 9.0)).isEqualTo(1.0);
        assertThat(sound.number(1, 9.0)).isEqualTo(0.5);
        assertThat(sound.number(2, 9.0))
                .describedAs("a number the operator did not write is the one the caller expected")
                .isEqualTo(9.0);
    }

    @Test
    @DisplayName("A line nobody can make sense of is skipped rather than fatal")
    void anunreadableLineIsSkipped() {
        assertThat(InteractionEffect.parse("fireworks:BIG")).isEmpty();
        assertThat(InteractionEffect.parse("sound:")).isEmpty();
        assertThat(InteractionEffect.parse("no colon here")).isEmpty();
        assertThat(InteractionEffect.parse(":nothing before it")).isEmpty();
    }

    @Test
    @DisplayName("A number that is not a number is the one the caller expected, not a crash")
    void agarbageNumberFallsBack() {
        InteractionEffect sound = InteractionEffect.parse("sound:BLOCK_NOTE_BLOCK_BASS loud quiet")
                .orElseThrow();

        assertThat(sound.number(0, 1.0)).isEqualTo(1.0);
        assertThat(sound.number(1, 0.5)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("An interaction the file does not name fires nothing")
    void anunnamedInteractionFiresNothing() {
        InteractionEffects effects = new InteractionEffects(Map.of(
                "limit-refused", List.of(InteractionEffect.parse("sound:X 1 1").orElseThrow())));

        assertThat(effects.of("limit-refused")).hasSize(1);
        assertThat(effects.of("something-else")).isEmpty();
        assertThat(effects.names("something-else")).isFalse();
        assertThat(InteractionEffects.none().of("limit-refused")).isEmpty();
    }

    @Test
    @DisplayName("The order the operator wrote is the order they happen")
    void theorderIsKept() {
        InteractionEffects effects = new InteractionEffects(Map.of(
                "mission-completed",
                List.of(
                        InteractionEffect.parse("title:mission.done").orElseThrow(),
                        InteractionEffect.parse("sound:UI_TOAST_CHALLENGE_COMPLETE 1 1")
                                .orElseThrow())));

        assertThat(effects.of("mission-completed"))
                .extracting(InteractionEffect::kind)
                .containsExactly(InteractionEffect.Kind.TITLE, InteractionEffect.Kind.SOUND);
    }
}
