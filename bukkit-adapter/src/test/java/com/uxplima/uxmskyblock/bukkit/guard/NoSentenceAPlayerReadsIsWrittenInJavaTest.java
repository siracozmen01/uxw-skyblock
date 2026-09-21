package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No sentence a player reads is written in Java.
 *
 * <p>Every line a player sees is a catalogue key, declared once in English and once in every other
 * {@code messages_<tag>.conf}. A sentence written into a class is a sentence no translator can
 * reach, and the number of languages this plugin speaks is the number of files in {@code messages/}
 * rather than a number anything counts.
 *
 * <p>Eighteen of them lived in the dimension listener. Two of those pasted an enum constant into
 * the middle of the sentence, so a player was told about the "THE_END" dimension. Two more were
 * handed to the eviction adapter, which wrapped them in {@code Component.text}, so the player was
 * shown the MiniMessage tags themselves, angle brackets and all.
 */
class NoSentenceAPlayerReadsIsWrittenInJavaTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** A string literal that opens with a MiniMessage tag and then says something. */
    private static final Pattern SENTENCE_IN_A_TAG = Pattern.compile("\"<[a-z_]+>[^\"]*[A-Za-z]{2,}[^\"]*\"");

    /**
     * Where a tagged string is a format rather than a sentence.
     *
     * <p>A configuration default is a line the operator rewrites in their own file, which is the
     * same freedom a catalogue key gives and the reason the chat format lives there. A menu glyph
     * bar is a shape, not a sentence.
     */
    private static final List<String> FORMATS_RATHER_THAN_SENTENCES =
            List.of("config/ChatConfiguration.java", "menu/IslandBoosterMenu.java");

    @Test
    @DisplayName("Every sentence a player reads comes out of the catalogue")
    void everySentenceComesOutOfTheCatalogue() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int filesRead = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                if (FORMATS_RATHER_THAN_SENTENCES.stream().anyMatch(path::endsWith)) {
                    continue;
                }
                filesRead++;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = SENTENCE_IN_A_TAG.matcher(source);
                while (matcher.find()) {
                    int line = source.substring(0, matcher.start()).split("\n", -1).length;
                    offences.add(file.getFileName() + ":" + line + "  " + trimmed(matcher.group()));
                }
            }
        }

        assertThat(filesRead).describedAs("the guard really read the sources").isGreaterThan(100);
        assertThat(offences)
                .describedAs("a sentence written into a class is a sentence no translator can reach. "
                        + "Declare it in messages_en.conf and every other messages file, and send the key")
                .isEmpty();
    }

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        assertThat(SENTENCE_IN_A_TAG
                        .matcher("send(player, \"<red>You do not belong to an island.</red>\");")
                        .find())
                .isTrue();
    }

    @Test
    @DisplayName("A bare colour with nothing after it is a shape, not a sentence")
    void aBareColourIsNotASentence() {
        assertThat(SENTENCE_IN_A_TAG.matcher("return \"<green>\" + bar;").find())
                .isFalse();
    }

    private static String trimmed(String literal) {
        return literal.length() <= 60 ? literal : literal.substring(0, 57) + "...";
    }
}
