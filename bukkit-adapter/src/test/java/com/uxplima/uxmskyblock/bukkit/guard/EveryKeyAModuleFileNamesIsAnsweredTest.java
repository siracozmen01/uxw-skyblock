package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A module file may name a catalogue key, and every language has to answer it.
 *
 * <p>The menu files were held to this and the module files were not, which was fine while no module
 * file named a key. The starter presets do now: their names and descriptions are sentences a player
 * reads, so they live in the language files rather than in Java or in one language inside a
 * configuration file. A key one language answers and another does not is an empty line in front of
 * whoever runs the other one.
 *
 * <p>Nothing here counts languages. Every file under {@code messages/} is a language, and each of
 * them is asked the same question.
 */
class EveryKeyAModuleFileNamesIsAnsweredTest {

    private static final Path MODULES = Path.of("src/main/resources/modules");

    private static final Path MESSAGES = Path.of("src/main/resources/messages");

    /** A quoted value that is a catalogue key rather than a word. */
    private static final Pattern KEY = Pattern.compile("\"@([a-z][a-z_0-9.]*)\"");

    private static List<String> shippedLocales() throws IOException {
        try (Stream<Path> files = Files.list(MESSAGES)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("messages_") && name.endsWith(".conf"))
                    .map(name -> name.substring("messages_".length(), name.length() - ".conf".length()))
                    .sorted()
                    .toList();
        }
    }

    private static SortedSet<String> keysNamedByModules() throws IOException {
        TreeSet<String> named = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MODULES)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    named.add(matcher.group(1));
                }
            }
        }
        return named;
    }

    @Test
    @DisplayName("A module file really does name keys, so a clean result means something")
    void theScanFindsSomething() throws IOException {
        assertThat(keysNamedByModules())
                .describedAs("catalogue keys named in modules/")
                .isNotEmpty();
        assertThat(shippedLocales()).describedAs("languages under messages/").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Every language answers every key a module file names")
    void everyLanguageAnswersEveryKey() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(EveryKeyAModuleFileNamesIsAnsweredTest.class.getClassLoader());

        SortedSet<String> named = keysNamedByModules();
        TreeSet<String> unanswered = new TreeSet<>();
        for (String locale : shippedLocales()) {
            Set<String> answered = provider.getKeys(locale);
            for (String key : named) {
                if (!answered.contains(key)) {
                    unanswered.add(locale + " does not answer " + key);
                }
            }
        }

        assertThat(unanswered)
                .describedAs("a key one language answers and another does not is an empty line "
                        + "in front of whoever runs the other one")
                .isEmpty();
    }
}
