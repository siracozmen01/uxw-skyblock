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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A file name or a menu verb in a catalogue line is wrapped in {@code <plain>}.
 *
 * <p>English is drawn in small capitals, letter by letter. A line that told an operator to edit
 * {@code menus/island-upgrades.conf} after {@code skyblock:buy-upgrade} showed both in small capitals,
 * and neither can be typed back as it reads.
 */
class TechnicalWordsKeepTheirLettersTest {

    private static final Path CATALOGUES = Path.of("src", "main", "resources", "messages");

    private static final Pattern VALUE = Pattern.compile("=\\s*\"(.*)\"\\s*$");

    private static final Pattern KEPT = Pattern.compile("<plain>.*?</plain>");

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /** A file a server owner edits, or a verb of a menu file such as {@code skyblock:buy-upgrade}. */
    private static final Pattern TECHNICAL =
            Pattern.compile("[a-z_-]+\\.(?:conf|yml|json)\\b|\\b[a-z]+:[a-z][a-z-]*\\b|%[a-z_]+%");

    @Test
    @DisplayName("No catalogue line writes a file name or a menu verb outside <plain>")
    void technicalWordsAreKeptPlain() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var files = Files.list(CATALOGUES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".conf")).toList()) {
                offenders.addAll(
                        offenders(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        assertThat(offenders)
                .describedAs("Wrap the word in <plain>, so small capitals leave its letters alone")
                .isEmpty();
    }

    @Test
    @DisplayName("The reader sees a bare file name and lets a kept one pass")
    void theReaderSeesBothSpellings() {
        assertThat(offenders("x", "a = \"<body>Edit menus/island-main.conf now.\""))
                .describedAs("a reader that cannot see a bare file name guards nothing")
                .hasSize(1);
        assertThat(offenders("x", "a = \"<body>Edit <plain>menus/island-main.conf</plain> now.\""))
                .isEmpty();
        assertThat(offenders("x", "a = \"<tag:'Bank'> <body>Paid.\""))
                .describedAs("a tag is markup, not a word")
                .isEmpty();
    }

    private static List<String> offenders(String name, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int at = 0; at < lines.length; at++) {
            Matcher value = VALUE.matcher(lines[at]);
            if (!value.find()) {
                continue;
            }
            String words =
                    TAG.matcher(KEPT.matcher(value.group(1)).replaceAll("")).replaceAll("");
            Matcher technical = TECHNICAL.matcher(words);
            if (technical.find()) {
                found.add(name + ":" + (at + 1) + " writes " + technical.group() + " outside <plain>");
            }
        }
        return found;
    }
}
