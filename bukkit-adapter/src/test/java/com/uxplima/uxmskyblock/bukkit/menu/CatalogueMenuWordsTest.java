package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Map;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogueMenuWordsTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private CatalogueMenuWords words;

    @BeforeEach
    void setUp() {
        words = new CatalogueMenuWords(Messages.bundled());
    }

    @Test
    @DisplayName("A menu key reads in the language the viewer's client speaks")
    void keyFollowsTheViewer() {
        assertThat(PLAIN.serialize(words.text(playerSpeaking(Locale.of("tr")), "error.no_island", Map.of())))
                .contains("adaya sahip değilsiniz");
        assertThat(PLAIN.serialize(words.text(playerSpeaking(Locale.US), "error.no_island", Map.of())))
                .contains("do not currently belong to an island");
    }

    @Test
    @DisplayName("A menu title carries no chat prefix")
    void titleCarriesNoPrefix() {
        assertThat(PLAIN.serialize(words.textUnprefixed(playerSpeaking(Locale.US), "error.no_island", Map.of())))
                .doesNotContain("SKYBLOCK");
    }

    @Test
    @DisplayName("A line an operator wrote in a menu file renders as they wrote it")
    void operatorTextRenders() {
        assertThat(PLAIN.serialize(words.render("<green>Island bank</green>"))).isEqualTo("Island bank");
    }

    @Test
    @DisplayName("A placeholder reaches the rendered line")
    void placeholderReachesTheLine() {
        assertThat(PLAIN.serialize(words.renderFor(
                        playerSpeaking(Locale.US), "<gray>Owner: <owner></gray>", Map.of("owner", "Alex"))))
                .isEqualTo("Owner: Alex");
    }

    @Test
    @DisplayName("A placeholder value holding markup is shown, not parsed")
    void placeholderValueIsNotParsed() {
        String rendered = PLAIN.serialize(
                words.renderFor(playerSpeaking(Locale.US), "<gray><name></gray>", Map.of("name", "<red>trap</red>")));

        assertThat(rendered).isEqualTo("<red>trap</red>");
    }

    private static Player playerSpeaking(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }
}
