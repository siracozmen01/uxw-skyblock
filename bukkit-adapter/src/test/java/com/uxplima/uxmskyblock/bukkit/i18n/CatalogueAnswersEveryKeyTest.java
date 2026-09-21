package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalog and the code have to agree, and nothing else says so.
 *
 * <p>A key the catalog does not hold reaches a player as the key itself. There is no compile error
 * for it and no failing test anywhere else, which is how a plugin ends up printing
 * {@code bank.status_locked} at somebody. This reads the keys out of the source and asks the
 * shipped catalogs for each one.
 */
class CatalogueAnswersEveryKeyTest {

    /**
     * The first string literal handed to a catalog call. A key built by concatenation ends at the
     * concatenation, so those are excluded below and covered by the parity check instead.
     */
    private static final Pattern CATALOG_CALL = Pattern.compile(
            "\\b(?:send|sendPlain|sendAll|render|renderPlain|renderAll)\\s*\\("
                    + "(?:[^();]|\\([^()]*\\))*?\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"",
            Pattern.DOTALL);

    private MessageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
    }

    @Test
    @DisplayName("Every key the code asks for is answered by the English catalog")
    void everyKeyInCodeIsInTheCatalogue() throws IOException {
        Set<String> known = allKeys("en");
        Set<String> missing = new TreeSet<>();

        for (String key : keysUsedInSource()) {
            if (!known.contains(key)) {
                missing.add(key);
            }
        }

        assertThat(missing)
                .describedAs("keys asked for in Java that no catalog line answers, so a player reads the key")
                .isEmpty();
    }

    @Test
    @DisplayName("Every language carries the exact key set English carries, in both directions")
    void everyLanguageMatchesEnglish() {
        Set<String> english = allKeys("en");

        for (String locale : provider.getAvailableLocales()) {
            if (locale.equals("en")) {
                continue;
            }
            Set<String> other = allKeys(locale);

            Set<String> absent = new TreeSet<>(english);
            absent.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(english);

            assertThat(absent)
                    .describedAs("keys English has that %s does not, so that language falls back silently", locale)
                    .isEmpty();
            assertThat(extra)
                    .describedAs("keys %s has that English does not, so nothing can reach them", locale)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("A key built by concatenation is answered for every case it can take")
    void concatenatedKeysAreAnswered() {
        for (String status : List.of("solvent", "grace", "locked")) {
            assertThat(allKeys("en")).contains("bank.status_" + status);
        }
    }

    @Test
    @DisplayName("The source really was read, so an empty scan cannot pass this file")
    void theScanFoundKeys() throws IOException {
        assertThat(keysUsedInSource()).hasSizeGreaterThan(20);
    }

    private Set<String> allKeys(String locale) {
        Set<String> keys = new HashSet<>(provider.getKeys(locale));
        keys.addAll(provider.getListKeys(locale));
        return keys;
    }

    private static Set<String> keysUsedInSource() throws IOException {
        Path sources = Path.of("src/main/java");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = CATALOG_CALL.matcher(body);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!key.endsWith("_")) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }
}
