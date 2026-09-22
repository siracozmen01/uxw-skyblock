package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every kind of thing the feed can hold is a kind of thing something writes.
 *
 * <p>The feed, its table, its twelve event types and {@code /is activity} were all here and nothing
 * ever wrote a row, so every island's feed was empty for as long as the server ran. An enum naming
 * twelve kinds of event where only nine have a writer is the same shape starting again.
 *
 * <p>Each type also needs a line in every language file. A type nobody can read is a type nobody
 * has, whatever the table says.
 */
class EveryActivityTypeIsWrittenTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit"),
            Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core"));

    private static final Path MESSAGES = Path.of("src/main/resources/messages");

    @Test
    @DisplayName("Every activity event type has something that writes it")
    void everyTypeHasAWriter() throws IOException {
        String tree = readEverything();
        TreeSet<String> unwritten = new TreeSet<>();

        for (ActivityEventType type : ActivityEventType.values()) {
            if (!tree.contains("ActivityEventType." + type.name())) {
                unwritten.add(type.name());
            }
        }

        assertThat(unwritten)
                .describedAs("an event type nothing ever records is a feed entry no island can ever have")
                .isEmpty();
    }

    @Test
    @DisplayName("Every activity event type has a line in every language file")
    void everyTypeHasALine() throws IOException {
        TreeSet<String> missing = new TreeSet<>();

        try (Stream<Path> files = Files.list(MESSAGES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                String catalogue = Files.readString(file, StandardCharsets.UTF_8);
                for (ActivityEventType type : ActivityEventType.values()) {
                    String key = type.name().toLowerCase(Locale.ROOT);
                    if (!catalogue.contains("\n    " + key + " =")) {
                        missing.add(file.getFileName() + " :: activity." + key);
                    }
                }
            }
        }

        assertThat(missing)
                .describedAs("a feed line with no message renders as its own key, which is not a language")
                .isEmpty();
    }

    private static String readEverything() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    if (file.toString().contains("ActivityEventType.java")) {
                        continue;
                    }
                    all.append(Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return all.toString();
    }
}
