package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every word a player can type is a word a test types.
 *
 * <p>Each command class having a test is not the same as each branch having one: a class with eight
 * verbs and a test for six passes that check and still ships two nobody has run. This reads the
 * verbs the command classes declare and the lines the tests actually execute, and asks whether any
 * verb is missing from the second list.
 *
 * <p>It compares words, not whole lines, so {@code run("warp move home")} covers {@code move}. That
 * is deliberately loose: the point is that no branch is entirely unvisited, not that every argument
 * combination is covered.
 */
class EveryCommandVerbIsRunByATestTest {

    private static final Path COMMANDS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/command");
    private static final Path TESTS = Path.of("src/test/java/com/uxplima/uxmskyblock/bukkit");

    /** {@code Cmd.literal("word")}, which is how every verb in this tree is declared. */
    private static final Pattern DECLARED = Pattern.compile("Cmd\\.literal\\(\"([a-z_]+)\"\\)");

    /** A line a test hands to Brigadier, through either of the two shapes the tests use. */
    private static final Pattern RUN =
            Pattern.compile("(?:run|execute|parse|assertRuns)\\(\\s*(?:\\w+\\s*,\\s*)?\"([^\"]+)\"");

    /**
     * Verbs a test cannot reach, with the reason.
     *
     * <p>Keep this empty. A verb belongs here only when running it in a test is impossible rather
     * than unwritten, and the reason has to say which.
     */
    private static final Set<String> UNREACHABLE = Set.of();

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Test
    @DisplayName("No command verb goes without a test that runs it")
    void everyVerbIsRun() throws IOException {
        Set<String> declared = readDeclaredVerbs();
        Set<String> run = readWordsTestsRun();

        assertThat(declared).describedAs("the guard really read the tree").hasSizeGreaterThan(30);
        assertThat(run).describedAs("the guard really read the tests").hasSizeGreaterThan(30);

        TreeSet<String> unrun = new TreeSet<>(declared);
        unrun.removeAll(run);
        unrun.removeAll(UNREACHABLE);

        assertThat(unrun)
                .describedAs("a verb no test types is a branch that ships without anybody having run it once")
                .isEmpty();
    }

    private static Set<String> readDeclaredVerbs() throws IOException {
        Set<String> verbs = new HashSet<>();
        try (Stream<Path> files = Files.walk(COMMANDS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                Matcher matcher = DECLARED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    verbs.add(matcher.group(1));
                }
            }
        }
        return verbs;
    }

    private static Set<String> readWordsTestsRun() throws IOException {
        Set<String> words = new HashSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                Matcher matcher = RUN.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    for (String word : WHITESPACE.split(matcher.group(1), -1)) {
                        if (!word.isEmpty()) {
                            words.add(word);
                        }
                    }
                }
            }
        }
        return words;
    }
}
