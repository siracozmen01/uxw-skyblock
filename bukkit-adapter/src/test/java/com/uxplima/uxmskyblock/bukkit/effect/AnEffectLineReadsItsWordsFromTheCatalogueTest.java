package com.uxplima.uxmskyblock.bukkit.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A part of an operator's effect line written as {@code @key} says what the catalogue holds.
 *
 * <p>That is how an operator writes one line for every language. The effect engine asks the context
 * for the words and refuses the line when nothing answers, so an unwired catalogue turned every such
 * line into nothing at all.
 */
class AnEffectLineReadsItsWordsFromTheCatalogueTest extends MockBukkitHarness {

    @Test
    @DisplayName("A message line naming a key says the words the key holds, not the key")
    void aKeyedLineSaysItsWords() {
        PlayerMock player = createPlayer("Reader");

        new InteractionEffectPlayer(null, Messages.bundled())
                .fire(effects("[message] @doctor.well"), "island-create", player);

        String said = player.nextMessage();
        assertThat(said).isNotNull().contains("Nothing needs an operator").doesNotContain("doctor.well");
    }

    private static InteractionEffects effects(String line) {
        return new InteractionEffects(Map.of("island-create", ActionList.parse(List.of(line))));
    }
}
