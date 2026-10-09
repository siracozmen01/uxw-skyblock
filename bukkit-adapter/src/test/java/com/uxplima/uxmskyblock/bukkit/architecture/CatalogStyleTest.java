package com.uxplima.uxmskyblock.bukkit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Fails when a shipped file breaks the UI style canon (uxm-briefs/UI-STYLE.md).
 *
 * <p>The canon is a set of rules a reviewer can only keep by reading every line, so the rules that a machine
 * can check are checked here. What is left to the reviewer is the shape of a sentence and the layout of a
 * menu, which no test can judge.
 */
final class CatalogStyleTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path MESSAGES = RESOURCES.resolve("messages");
    private static final Path THEME = RESOURCES.resolve("theme.conf");

    /** The palette. A colour in shipped content must be one of these, so example content cannot drift. */
    private static final Set<String> PALETTE = Set.of(
            "#ff6b8b", "#ff9eb5", "#ffa07a", "#ffc4a3", "#ffe66d", "#fff3a8", "#4ecca3", "#8fe0c4", "#48cae4",
            "#4fd6e8", "#8fddee", "#6c8dfb", "#9fb4ff", "#b388ff", "#d4b8ff", "#ffffff", "#e8ecf5", "#dde8f0",
            "#9aa5be", "#8a93a1", "#6b7886", "#565f6b", "#2a3045");

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /** The MiniMessage colour names. A message names a role, never a colour. */
    private static final Pattern NAMED_COLOUR = Pattern.compile(
            "</?(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|dark_grey"
                    + "|blue|green|aqua|red|light_purple|yellow|white)>");

    private static final Pattern LEGACY = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    /** A result glyph belongs in no message: a red error prefix already says that it failed. */
    private static final Pattern RESULT_GLYPH = Pattern.compile("[✔✖❌✅✗√]");

    @Test
    @DisplayName("no message names a colour, so the theme owns the palette")
    void messagesNameRolesOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : catalogs()) {
            offenders.addAll(matches(file, HEX, "a hex colour"));
            offenders.addAll(matches(file, NAMED_COLOUR, "a named colour"));
            offenders.addAll(matches(file, LEGACY, "a legacy colour code"));
            offenders.addAll(matches(file, RESULT_GLYPH, "a result glyph"));
        }
        assertThat(offenders)
                .describedAs("Use a style token (<accent>, <value>, <body>, <tag:'X'>). See UI-STYLE.md.")
                .isEmpty();
    }

    @Test
    @DisplayName("every colour in the shipped content is a palette colour")
    void contentUsesThePalette() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : content()) {
            String name = String.valueOf(RESOURCES.relativize(file));
            offenders.addAll(matches(file, NAMED_COLOUR, "a named colour"));
            offenders.addAll(matches(file, LEGACY, "a legacy colour code"));
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.strip().startsWith("#")) {
                    continue;
                }
                Matcher matcher = HEX.matcher(line);
                while (matcher.find()) {
                    String hex = matcher.group().toLowerCase(Locale.ROOT);
                    if (!PALETTE.contains(hex)) {
                        offenders.add(name + " holds " + hex + ", which is not a palette colour");
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * Every shipped file an operator edits: {@code config.conf} and the content folders beside it, such as
     * the per-item or per-type files. The example content is read as much as the messages are, so it follows
     * the same palette. The message files have their own test above, and {@code theme.conf} is the one file
     * that is allowed to name a colour.
     */
    private static List<Path> content() throws IOException {
        if (!Files.isDirectory(RESOURCES)) {
            return List.of();
        }
        try (var files = Files.walk(RESOURCES)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".conf"))
                    .filter(path -> !path.startsWith(MESSAGES))
                    .filter(path -> !path.equals(THEME))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("only theme.conf holds a colour of its own")
    void theThemeShipsWithThePlugin() throws IOException {
        assertThat(THEME).exists();
        assertThat(HEX.matcher(Files.readString(THEME, StandardCharsets.UTF_8)).find())
                .describedAs("theme.conf is the one file that names a colour")
                .isTrue();
    }

    @Test
    @DisplayName("every language holds every key, so no player reads a line in the wrong language")
    void everyLanguageIsComplete() throws IOException, ConfigurateException {
        List<Path> catalogs = catalogs();
        Set<String> english = keysOf(MESSAGES.resolve("messages_en.conf"));
        for (Path file : catalogs) {
            Set<String> keys = keysOf(file);
            Set<String> missing = new LinkedHashSet<>(english);
            missing.removeAll(keys);
            Set<String> extra = new LinkedHashSet<>(keys);
            extra.removeAll(english);
            assertThat(missing).describedAs("keys missing from " + file).isEmpty();
            assertThat(extra)
                    .describedAs("keys in " + file + " that English does not have")
                    .isEmpty();
        }
    }

    private static List<Path> catalogs() throws IOException {
        try (var files = Files.list(MESSAGES)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".conf"))
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> keysOf(Path file) throws ConfigurateException {
        ConfigurationNode root =
                HoconConfigurationLoader.builder().path(file).build().load();
        Set<String> paths = new LinkedHashSet<>();
        collect(root, "", paths);
        return paths;
    }

    private static void collect(ConfigurationNode node, String prefix, Set<String> out) {
        if (node.isMap()) {
            for (var child : node.childrenMap().entrySet()) {
                String key = String.valueOf(child.getKey());
                collect(child.getValue(), prefix.isEmpty() ? key : prefix + "." + key, out);
            }
            return;
        }
        if (!prefix.isEmpty()) {
            out.add(prefix);
        }
    }

    private static List<String> matches(Path file, Pattern pattern, String what) throws IOException {
        List<String> found = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.strip().startsWith("#")) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                found.add(file.getFileName() + ":" + (index + 1) + " holds " + what + " (" + matcher.group() + ")");
            }
        }
        return found;
    }
}
