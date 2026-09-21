package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A menu must not name a command that does not exist.
 *
 * <p>Clicking a slot that runs an unknown command answers "Unknown command", which reads to a player
 * exactly like a broken plugin. Ten menus were written naming commands for warps, alliances, the
 * guestbook, ratings, bookmarks, the reward inbox and the island flags, and not one of those
 * commands existed: every one of those subsystems was built, wired, running, and had no door.
 *
 * <p>This reads the command tree's own vocabulary out of the source rather than starting a server,
 * because the point is to fail in the build, before anybody clicks.
 */
class EveryMenuCommandExistsTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    private static final Path COMMANDS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/command");

    /** A verb a menu file runs, for example {@code command:is warp create}. */
    private static final Pattern MENU_COMMAND = Pattern.compile("\"command:is ([a-z_]+)");

    /** A branch the command tree declares, for example {@code Cmd.literal("warp")}. */
    private static final Pattern DECLARED = Pattern.compile("literal\\(\"([a-z-]+)\"\\)");

    private static List<String> declaredBranches() throws IOException {
        List<String> declared = new ArrayList<>();
        try (Stream<Path> files = Files.walk(COMMANDS)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = DECLARED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    declared.add(matcher.group(1));
                }
            }
        }
        return declared;
    }

    @Test
    @DisplayName("Every command a shipped menu runs is a branch the command tree declares")
    void everyMenuCommandIsDeclared() throws IOException {
        List<String> declared = declaredBranches();
        assertThat(declared)
                .describedAs("the guard really read the command tree")
                .hasSizeGreaterThan(20);

        TreeSet<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".conf")).toList()) {
                Matcher matcher = MENU_COMMAND.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!declared.contains(matcher.group(1))) {
                        missing.add(file.getFileName() + " runs /is " + matcher.group(1));
                    }
                }
            }
        }

        assertThat(missing)
                .describedAs("a menu slot that runs an unknown command reads to a player as a broken plugin")
                .isEmpty();
    }
}
