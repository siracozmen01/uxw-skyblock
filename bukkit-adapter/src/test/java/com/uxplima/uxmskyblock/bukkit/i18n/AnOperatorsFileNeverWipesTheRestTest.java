package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A catalogue read over another one adds to it rather than replacing it.
 *
 * <p>The bundled catalogue is loaded first and the operator's own file second, and the second used
 * to replace the first. An operator who trimmed their file to the three lines they wanted to change
 * lost every other message in the plugin. Worse, it is certain to bite on an update: the jar gains
 * a key, the operator's file predates it, and every player reads {@code error.players_only} where a
 * sentence belongs.
 */
class AnOperatorsFileNeverWipesTheRestTest {

    private static void read(MessageProvider provider, String locale, String hocon) throws IOException {
        provider.loadFromStream(locale, new ByteArrayInputStream(hocon.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("A key the second file does not carry keeps the answer the first one gave")
    void akeyTheSecondFileOmitsSurvives() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        read(provider, "en", "error {\n  no_island = \"shipped\"\n  players_only = \"shipped too\"\n}\n");

        read(provider, "en", "error {\n  no_island = \"mine\"\n}\n");

        assertThat(provider.getRaw("error.no_island", "en"))
                .describedAs("the operator's line wins")
                .isEqualTo("mine");
        assertThat(provider.getRaw("error.players_only", "en"))
                .describedAs("a key they did not write is not a key they deleted")
                .isEqualTo("shipped too");
    }

    @Test
    @DisplayName("A list the second file does not carry survives too")
    void alistTheSecondFileOmitsSurvives() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        read(provider, "en", "help {\n  lines = [\"one\", \"two\"]\n}\nerror {\n  no_island = \"shipped\"\n}\n");

        read(provider, "en", "error {\n  no_island = \"mine\"\n}\n");

        assertThat(provider.getRawList("help.lines", "en")).containsExactly("one", "two");
    }

    @Test
    @DisplayName("A file with no prefix of its own keeps the prefix it had")
    void afileWithNoPrefixKeepsTheOne() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        read(provider, "en", "prefix = \"[Skyblock] \"\nerror {\n  no_island = \"shipped\"\n}\n");

        read(provider, "en", "error {\n  no_island = \"mine\"\n}\n");

        assertThat(provider.getComponent("error.no_island", "en"))
                .describedAs("the prefix is part of the line a player reads")
                .isNotNull();
        assertThat(provider.getRaw("error.no_island", "en")).isEqualTo("mine");
    }

    @Test
    @DisplayName("A file that does name a prefix replaces the one that was there")
    void afileThatNamesAPrefixWins() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        read(provider, "en", "prefix = \"[Old] \"\nerror {\n  no_island = \"line\"\n}\n");

        read(provider, "en", "prefix = \"[New] \"\n");

        assertThat(provider.getComponentWithoutPrefix("error.no_island", "en")).isNotNull();
    }

    @Test
    @DisplayName("One locale's file never touches another locale's catalogue")
    void oneLocaleNeverTouchesAnother() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        read(provider, "en", "error {\n  no_island = \"english\"\n}\n");
        read(provider, "tr", "error {\n  no_island = \"turkce\"\n}\n");

        read(provider, "en", "error {\n  no_island = \"english again\"\n}\n");

        assertThat(provider.getRaw("error.no_island", "tr")).isEqualTo("turkce");
        assertThat(provider.getRaw("error.no_island", "en")).isEqualTo("english again");
    }
}
