package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every plugin this one asks about is loaded before it.
 *
 * <p>A lookup such as "is floodgate enabled" answers no when floodgate simply has not enabled yet. The
 * Bedrock detector made that answer once, as the menus were built, so a server where floodgate
 * happened to load after this plugin served Java windows to every Bedrock player for as long as it
 * ran. The web maps were drawn at startup the same way. paper-plugin.yml is how the order is asked
 * for, and it named three of the eight.
 */
class EveryPluginAskedAfterLoadsFirstTest {

    /** Plugins the family library looks up on this plugin's behalf, which its own sources never name. */
    private static final Set<String> ASKED_THROUGH_THE_LIBRARY =
            Set.of("Vault", "PlaceholderAPI", "mcMMO", "floodgate", "Geyser-Spigot");

    private static final Pattern ASKED = Pattern.compile("(?:getPlugin|isPluginEnabled)\\(\"([^\"]+)\"\\)");

    @Test
    @DisplayName("Every plugin asked about is named in paper-plugin.yml to load first, and as optional")
    void everyAskedPluginLoadsFirst() throws IOException {
        Set<String> asked = new TreeSet<>(ASKED_THROUGH_THE_LIBRARY);
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ASKED.matcher(Files.readString(file));
                while (matcher.find()) {
                    asked.add(matcher.group(1));
                }
            }
        }
        String descriptor = Files.readString(Path.of("src", "main", "resources", "paper-plugin.yml"));

        List<String> missing = new ArrayList<>();
        for (String plugin : asked) {
            Pattern declared = Pattern.compile(
                    "\\n\\s+" + Pattern.quote(plugin) + ":\\s*\\n\\s+load: BEFORE\\s*\\n\\s+required: false");
            if (!declared.matcher(descriptor).find()) {
                missing.add(plugin);
            }
        }

        assertThat(missing)
                .describedAs("asked about, but free to enable after this plugin has already asked")
                .isEmpty();
    }
}
