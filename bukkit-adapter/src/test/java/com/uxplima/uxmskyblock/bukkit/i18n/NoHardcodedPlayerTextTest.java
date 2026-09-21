package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No sentence a player can read is written in Java.
 *
 * <p>The zero hardcoding rule had no guard, and the result was 222 English strings reaching
 * players out of the command handlers, the listeners and the four windows, while the message
 * catalogs sat complete and unread beside them. A rule with no test is a rule that comes back.
 *
 * <p>Two shapes are caught, because the code used both: a component built from a literal, and a
 * MiniMessage string parsed from a literal. A blank or single space is allowed, since a filler tile
 * has no words to translate.
 */
class NoHardcodedPlayerTextTest {

    private static final Pattern COMPONENT_LITERAL = Pattern.compile("Component\\.text\\(\\s*\"([^\"]*)\"");
    private static final Pattern MINI_MESSAGE_LITERAL = Pattern.compile("deserialize\\(\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("No component is built from a literal sentence")
    void noComponentHoldsASentence() throws IOException {
        assertThat(offences(COMPONENT_LITERAL))
                .describedAs("text written in Java rather than taken from the catalog")
                .isEmpty();
    }

    @Test
    @DisplayName("No MiniMessage string is parsed from a literal")
    void noMiniMessageHoldsASentence() throws IOException {
        assertThat(offences(MINI_MESSAGE_LITERAL))
                .describedAs("markup written in Java rather than taken from the catalog")
                .isEmpty();
    }

    @Test
    @DisplayName("The source really was read, so an empty scan cannot pass this file")
    void theScanReadTheSource() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            assertThat(files.filter(f -> f.toString().endsWith(".java")).count())
                    .isGreaterThan(50);
        }
    }

    private static List<String> offences(Pattern pattern) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = pattern.matcher(body);
                while (matcher.find()) {
                    String literal = matcher.group(1);
                    if (literal.isBlank()) {
                        continue;
                    }
                    found.add(file.getFileName() + ": \"" + literal + "\"");
                }
            }
        }
        return found;
    }
}
