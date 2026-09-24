package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every flag a shipped menu toggles is a flag an island has.
 *
 * <p>The settings menu's visitors and mobs buttons ran {@code /is flag VISITORS} and
 * {@code /is flag MOB_SPAWNING}. No island has either, so on a live server both buttons answered
 * that there is no such flag and did nothing.
 */
class EveryFlagAMenuTogglesExistsTest {

    private static final Pattern FLAG = Pattern.compile("command:is flag ([A-Za-z_]+)");

    @Test
    @DisplayName("No shipped menu toggles a flag that no island has")
    void everyToggledFlagExists() throws Exception {
        List<String> unknown = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/menus"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".conf")).toList()) {
                Matcher flag = FLAG.matcher(Files.readString(file));
                while (flag.find()) {
                    found++;
                    if (!IslandFlags.defaults()
                            .values()
                            .containsKey(flag.group(1).toUpperCase(Locale.ROOT))) {
                        unknown.add(file.getFileName() + ": " + flag.group(1));
                    }
                }
            }
        }
        assertThat(found).describedAs("flag buttons in the shipped menus").isPositive();
        assertThat(unknown).isEmpty();
    }
}
