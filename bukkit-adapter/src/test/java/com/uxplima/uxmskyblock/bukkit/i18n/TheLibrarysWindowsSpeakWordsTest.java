package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.uxplima.uxmlib.gui.input.TextInput;
import com.uxplima.uxmlib.text.language.LibraryWords;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The keys uxmLib's own windows ask this plugin for read as words, in every language it speaks.
 *
 * <p>The library's prompt, page arrows and confirm buttons ask the plugin's catalogue for their
 * {@code gui.*} lines. Nothing here held them, so a player who cancelled a prompt on a live server
 * read {@code gui.input.cancelled} in chat. The library ships the words; they sit under the plugin's
 * own lines.
 */
class TheLibrarysWindowsSpeakWordsTest {

    @Test
    @DisplayName("Every key the library ships reads as words in English and Turkish, never as itself")
    void everyLibraryKeyReadsAsWords() {
        MessageProvider provider = bundled();
        Map<String, String> english = LibraryWords.shipped().getOrDefault(Locale.ENGLISH, Map.of());
        assertThat(english).describedAs("the library's English words").isNotEmpty();

        List<String> bare = new ArrayList<>();
        for (String locale : List.of("en", "tr")) {
            for (String key : english.keySet()) {
                if (provider.getRaw(key, locale).equals(key)) {
                    bare.add(locale + ": " + key);
                }
            }
        }
        assertThat(bare).describedAs("keys a player would read as keys").isEmpty();
        assertThat(provider.getRaw(TextInput.CANCELLED_KEY, "tr"))
                .describedAs("Turkish reads Turkish, not the English floor")
                .isNotEqualTo(provider.getRaw(TextInput.CANCELLED_KEY, "en"));
    }

    @Test
    @DisplayName("A library line's theme tokens are painted, never printed as they were written")
    void theLibrarysTokensArePainted() {
        MessageProvider provider = bundled();
        for (String locale : List.of("en", "tr")) {
            String said = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(provider.getComponentWithoutPrefix(TextInput.CANCELLED_KEY, locale));
            assertThat(said)
                    .describedAs(locale)
                    .doesNotContain("<")
                    .doesNotContain("tag:")
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("A Turkish reader's cancelled prompt reads Turkish throughout, with no English badge in it")
    void aCancelledPromptReadsTheReadersLanguage() {
        String said = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(bundled().getComponentWithoutPrefix(TextInput.CANCELLED_KEY, "tr"));

        assertThat(said).doesNotContain("INPUT").doesNotContain("  ").contains("iptal");
    }

    @Test
    @DisplayName("An operator's line for a library key wins over the library's")
    void theOperatorsLineWins() throws Exception {
        MessageProvider provider = bundled();

        provider.loadFromStream(
                "en",
                new ByteArrayInputStream(
                        "gui { input { cancelled = \"Never mind.\" } }".getBytes(StandardCharsets.UTF_8)));

        assertThat(provider.getRaw(TextInput.CANCELLED_KEY, "en")).isEqualTo("Never mind.");
        assertThat(provider.getRaw("gui.confirm.yes", "en"))
                .describedAs("the keys the operator left out keep the library's words")
                .isNotEqualTo("gui.confirm.yes");
    }

    private static MessageProvider bundled() {
        MessageProvider provider = new MessageProvider(LanguageConfiguration.DEFAULT_LANGUAGE);
        provider.loadBundledDefaults(TheLibrarysWindowsSpeakWordsTest.class.getClassLoader());
        return provider;
    }
}
