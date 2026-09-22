package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A switch in the module file is a switch the code asks for, under the same name.
 *
 * <p>An unknown module is treated as on, which is the right answer for a file written before a
 * module existed and the wrong one for a name that does not agree. The file said
 * {@code admin-freeze} and the code asked for {@code freeze}; the file said {@code rewards} and the
 * code asked for {@code reward-inbox}. An operator who turned either of them off still had it, and
 * nothing anywhere said so.
 *
 * <p>The two halves are checked both ways. A name in the file that nothing asks for is a switch
 * that does nothing. A name the code asks for that the file does not publish is a switch nobody
 * knows how to reach.
 */
class EveryModuleSwitchIsTheSameNameTest {

    private static final Path MODULES_FILE = Path.of("src/main/resources/modules.conf");

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("../core/src/main/java"),
            Path.of("../persistence-adapter/src/main/java"),
            Path.of("../rest-adapter/src/main/java"));

    /** Every {@code name = true} or {@code name = false} the shipped module file publishes. */
    private static Set<String> switchesInTheFile() throws IOException {
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("^\\s*([a-z][a-z0-9-]*)\\s*=\\s*(?:true|false)\\s*$", Pattern.MULTILINE)
                .matcher(Files.readString(MODULES_FILE, StandardCharsets.UTF_8));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Every module name the code asks about. */
    private static Set<String> switchesTheCodeAsksFor() throws IOException {
        TreeSet<String> names = new TreeSet<>();
        Pattern asked = Pattern.compile("isModuleEnabled\\(\"([a-z0-9-]+)\"\\)");
        for (String source : productionSources()) {
            Matcher matcher = asked.matcher(source);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    @Test
    @DisplayName("Every switch the file publishes is one the code asks for")
    void everySwitchInTheFileIsAskedFor() throws IOException {
        TreeSet<String> unread = new TreeSet<>(switchesInTheFile());
        unread.removeAll(switchesTheCodeAsksFor());

        assertThat(unread)
                .describedAs("switches an operator can turn off with nothing listening, "
                        + "and an unknown module is treated as on so nothing says a word")
                .isEmpty();
    }

    @Test
    @DisplayName("Every switch the code asks for is one the file publishes")
    void everySwitchTheCodeAsksForIsPublished() throws IOException {
        TreeSet<String> unpublished = new TreeSet<>(switchesTheCodeAsksFor());
        unpublished.removeAll(switchesInTheFile());

        assertThat(unpublished)
                .describedAs("switches nobody knows how to reach, because the file they would be "
                        + "written in does not name them")
                .isEmpty();
    }

    @Test
    @DisplayName("Both halves really were read, so an empty scan cannot pass this file")
    void bothhalvesWereRead() throws IOException {
        assertThat(switchesInTheFile()).hasSizeGreaterThanOrEqualTo(15);
        assertThat(switchesTheCodeAsksFor()).hasSizeGreaterThanOrEqualTo(15);
    }

    private static List<String> productionSources() throws IOException {
        return SOURCES.stream()
                .filter(Files::isDirectory)
                .flatMap(EveryModuleSwitchIsTheSameNameTest::javaUnder)
                .toList();
    }

    private static Stream<String> javaUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(EveryModuleSwitchIsTheSameNameTest::read)
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new IllegalStateException("Could not walk " + root, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
