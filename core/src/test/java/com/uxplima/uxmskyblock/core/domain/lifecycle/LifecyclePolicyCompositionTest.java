package com.uxplima.uxmskyblock.core.domain.lifecycle;

import static com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect.CLEAR_ENDER_CHEST;
import static com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect.CLEAR_INVENTORY;
import static com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect.KEEP_EXPERIENCE;
import static com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect.KEEP_INVENTORY;
import static com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect.RESET_EXPERIENCE;
import static com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect.SEND_TO_SPAWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ruleset and game mode compose into exact, repeatable effects for a leave, a kick, a death and a
 * reset.
 *
 * <p>The game mode architecture names this test. The rules below are one server's: every island is
 * left with the player sent to spawn; a kick also empties the ender chest; a death keeps what was
 * carried on a OneBlock island, loses it for a Hardcore profile wherever it plays, and a Hardcore
 * profile on a OneBlock island loses its experience too; a reset empties everything.
 */
class LifecyclePolicyCompositionTest {

    private final LifecyclePolicy policy = new LifecyclePolicy(List.of(
            rule(LifecycleEvent.LEAVE, null, null, Map.of(SEND_TO_SPAWN, true)),
            rule(LifecycleEvent.KICK, null, null, Map.of(SEND_TO_SPAWN, true, CLEAR_ENDER_CHEST, true)),
            // Written before the rules it sits under, so the order they apply in is not file order.
            rule(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.HARDCORE, Map.of(KEEP_EXPERIENCE, false)),
            rule(LifecycleEvent.DEATH, null, ProfileType.HARDCORE, Map.of(KEEP_INVENTORY, false)),
            rule(
                    LifecycleEvent.DEATH,
                    GameModeType.ONEBLOCK,
                    null,
                    Map.of(KEEP_INVENTORY, true, KEEP_EXPERIENCE, true)),
            rule(
                    LifecycleEvent.RESET,
                    null,
                    null,
                    Map.of(CLEAR_INVENTORY, true, CLEAR_ENDER_CHEST, true, RESET_EXPERIENCE, true)),
            rule(LifecycleEvent.RESET, null, ProfileType.STRANDED, Map.of(RESET_EXPERIENCE, false))));

    @Test
    @DisplayName("A leave sends every player to spawn and takes nothing, whatever the mode or ruleset")
    void aLeave() {
        for (GameModeType mode : GameModeType.values()) {
            for (ProfileType ruleset : ProfileType.values()) {
                assertThat(policy.effects(LifecycleEvent.LEAVE, mode, ruleset)).containsExactly(SEND_TO_SPAWN);
            }
        }
    }

    @Test
    @DisplayName("A kick sends the player to spawn and empties their ender chest")
    void aKick() {
        assertThat(policy.effects(LifecycleEvent.KICK, GameModeType.SKYBLOCK, ProfileType.IRONMAN))
                .containsExactlyInAnyOrder(SEND_TO_SPAWN, CLEAR_ENDER_CHEST);
    }

    @Test
    @DisplayName("A death composes: the mode keeps, the ruleset takes, and both together take a little more")
    void aDeath() {
        assertThat(policy.effects(LifecycleEvent.DEATH, GameModeType.SKYBLOCK, ProfileType.CLASSIC))
                .describedAs("no rule: the server's own death")
                .isEmpty();
        assertThat(policy.effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.CLASSIC))
                .containsExactlyInAnyOrder(KEEP_INVENTORY, KEEP_EXPERIENCE);
        assertThat(policy.effects(LifecycleEvent.DEATH, GameModeType.SKYBLOCK, ProfileType.HARDCORE))
                .isEmpty();
        assertThat(policy.effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.HARDCORE))
                .describedAs("the ruleset beats the mode, and the rule naming both beats either")
                .isEmpty();
        assertThat(policy.effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.STRANDED))
                .containsExactlyInAnyOrder(KEEP_INVENTORY, KEEP_EXPERIENCE);
    }

    @Test
    @DisplayName("A reset empties everything, and a ruleset may keep one part of it")
    void aReset() {
        assertThat(policy.effects(LifecycleEvent.RESET, GameModeType.ONEBLOCK, ProfileType.CLASSIC))
                .containsExactlyInAnyOrder(CLEAR_INVENTORY, CLEAR_ENDER_CHEST, RESET_EXPERIENCE);
        assertThat(policy.effects(LifecycleEvent.RESET, GameModeType.SKYBLOCK, ProfileType.STRANDED))
                .containsExactlyInAnyOrder(CLEAR_INVENTORY, CLEAR_ENDER_CHEST);
    }

    @Test
    @DisplayName("Rules as specific as each other apply in the order they were written, so the later wins")
    void theLaterOfTwoEqualRulesWins() {
        LifecyclePolicy twice = new LifecyclePolicy(List.of(
                rule(LifecycleEvent.LEAVE, null, null, Map.of(CLEAR_INVENTORY, true)),
                rule(LifecycleEvent.LEAVE, null, null, Map.of(CLEAR_INVENTORY, false))));

        assertThat(twice.effects(LifecycleEvent.LEAVE, GameModeType.SKYBLOCK, ProfileType.CLASSIC))
                .isEmpty();
    }

    @Test
    @DisplayName("The same rules give the same effects every time they are asked")
    void theAnswerIsDeterministic() {
        for (int i = 0; i < 50; i++) {
            assertThat(policy.effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.HARDCORE))
                    .isEqualTo(policy.effects(LifecycleEvent.DEATH, GameModeType.ONEBLOCK, ProfileType.HARDCORE));
        }
    }

    @Test
    @DisplayName("Keeping belongs to a death: a rule that keeps on a reset is refused")
    void keepingIsOnlyForADeath() {
        assertThatThrownBy(() -> rule(LifecycleEvent.RESET, null, null, Map.of(KEEP_INVENTORY, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keep-inventory");
    }

    private static LifecycleRule rule(
            LifecycleEvent event,
            @Nullable GameModeType mode,
            @Nullable ProfileType ruleset,
            Map<LifecycleEffect, Boolean> effects) {
        return new LifecycleRule(event, mode, ruleset, effects);
    }
}
