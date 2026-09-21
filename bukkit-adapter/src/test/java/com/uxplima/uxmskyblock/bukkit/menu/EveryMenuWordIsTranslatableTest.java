package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A menu a Turkish player opens is in Turkish.
 *
 * <p>Thirteen menu files shipped with every word written in English inside the file, so a server
 * running with a Turkish catalogue had Turkish chat and English menus. The number of languages this
 * plugin has is the number of files under {@code messages/}, and a menu that cannot join them is
 * not finished.
 *
 * <p>The one exception is a line carrying a live value. The engine fills a
 * {@code %argument_<name>%} token from the line as the file writes it, so a line replaced by a
 * catalogue key would lose the value it was showing. Those stay in the file, and this guard says so
 * rather than letting the exception grow quietly.
 */
class EveryMenuWordIsTranslatableTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    /** A title, an item name or a lore line: everything in a menu file a player reads. */
    private static final Pattern WRITTEN_LINE =
            Pattern.compile("^\\s*(?:title = |name = )?\"(<.*)\",?$", Pattern.MULTILINE);

    private record Line(String file, String text) {}

    private static List<Line> writtenLines() throws IOException {
        List<Line> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                Matcher matcher = WRITTEN_LINE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    lines.add(new Line(file.getFileName().toString(), matcher.group(1)));
                }
            }
        }
        return lines;
    }

    @Test
    @DisplayName("Every word in a menu file is a catalogue key, unless it carries a live value")
    void everyWordIsAKeyOrCarriesAValue() throws IOException {
        TreeSet<String> untranslatable = new TreeSet<>();
        for (Line line : writtenLines()) {
            if (line.text().contains("%argument_")) {
                continue;
            }
            untranslatable.add(line.file() + "  " + line.text());
        }

        assertThat(untranslatable)
                .describedAs("a word written into a menu file cannot be translated, "
                        + "so a Turkish server would read it in English")
                .isEmpty();
    }

    @Test
    @DisplayName("Every key a menu file names is answered by the catalogue")
    void everyKeyIsAnswered() throws IOException {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(EveryMenuWordIsTranslatableTest.class.getClassLoader());
        Set<String> answered = provider.getKeys("en");

        Pattern key = Pattern.compile("\"@(menu\\.[a-z_0-9.]+)\"");
        TreeSet<String> unanswered = new TreeSet<>();
        int found = 0;

        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                Matcher matcher = key.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    found++;
                    if (!answered.contains(matcher.group(1))) {
                        unanswered.add(file.getFileName() + " names " + matcher.group(1));
                    }
                }
            }
        }

        assertThat(found)
                .describedAs("the menus really do read through the catalogue")
                .isGreaterThan(100);
        assertThat(unanswered)
                .describedAs("a key the catalogue does not answer draws an empty line in the menu")
                .isEmpty();
    }
}
