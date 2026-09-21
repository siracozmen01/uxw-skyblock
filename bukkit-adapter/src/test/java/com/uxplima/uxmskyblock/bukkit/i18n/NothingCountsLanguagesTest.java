package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The number of languages this plugin speaks is the number of files in {@code messages/}.
 *
 * <p>{@code loadBundledDefaults} used to name two, English and Turkish. A build that shipped a
 * third catalogue got nothing from it, and the only sign was a player reading English. Nothing
 * counts languages: the folder is read, and whatever is in it is what the plugin speaks.
 */
class NothingCountsLanguagesTest {

    @TempDir
    Path tempDir;

    private static final String A_CATALOG = """
            prefix = ""
            error {
                no_island = "You have no island."
            }
            """;

    private ClassLoader loaderWith(String... locales) throws IOException {
        Path messages = Files.createDirectories(tempDir.resolve("messages"));
        for (String locale : locales) {
            Files.writeString(messages.resolve("messages_" + locale + ".conf"), A_CATALOG, StandardCharsets.UTF_8);
        }
        return new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, null);
    }

    @Test
    @DisplayName("Every catalogue the build ships is read, not the two somebody wrote down")
    void everyBundledCatalogueIsRead() throws IOException {
        ClassLoader loader = loaderWith("en", "tr", "de", "fr");

        assertThat(MessageProvider.bundledLocales(loader)).containsExactlyInAnyOrder("en", "tr", "de", "fr");
    }

    @Test
    @DisplayName("A language nobody thought of is spoken as soon as its file is there")
    void anUnexpectedLanguageIsSpoken() throws IOException {
        ClassLoader loader = loaderWith("en", "az");

        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(loader);

        assertThat(provider.getAvailableLocales())
                .describedAs("English and Turkish are the floor, not the list")
                .contains("az");
    }

    @Test
    @DisplayName("A file that is not a catalogue is not a language")
    void otherFilesAreNotLanguages() throws IOException {
        Path messages = Files.createDirectories(tempDir.resolve("messages"));
        Files.writeString(messages.resolve("messages_en.conf"), A_CATALOG, StandardCharsets.UTF_8);
        Files.writeString(messages.resolve("README.md"), "not a catalogue", StandardCharsets.UTF_8);
        Files.writeString(messages.resolve("messages_en.conf.bak"), A_CATALOG, StandardCharsets.UTF_8);
        ClassLoader loader = new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, null);

        assertThat(MessageProvider.bundledLocales(loader)).containsExactly("en");
    }

    @Test
    @DisplayName("A build that ships no catalogue at all says so rather than failing silently")
    void noCatalogueIsAnAnswer() throws IOException {
        Files.createDirectories(tempDir.resolve("messages"));
        ClassLoader loader = new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, null);

        assertThat(MessageProvider.bundledLocales(loader)).isEmpty();
    }

    @Test
    @DisplayName("The catalogues this build really ships include the two that are the floor")
    void thisBuildShipsTheFloor() {
        Set<String> shipped = MessageProvider.bundledLocales(MessageProvider.class.getClassLoader());

        assertThat(shipped).contains("en", "tr");
    }
}
