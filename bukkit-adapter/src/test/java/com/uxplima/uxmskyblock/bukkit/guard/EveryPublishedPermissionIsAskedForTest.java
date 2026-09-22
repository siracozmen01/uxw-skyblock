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

import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A permission this plugin registers with the server is a permission something reads.
 *
 * <p>The catalogue registered eighteen nodes and twelve of them were read nowhere. They reached
 * every permission plugin's list, they carried a description and a default, and an operator could
 * take {@code uxmskyblock.island.create} off a group, see it taken off, and watch that group go on
 * making islands. Two more nodes went the other way: the commands asked for them by their word and
 * the catalogue never published them, so they were in nobody's list.
 *
 * <p>A node counts as read when the source names it, either as the catalogue constant or as the
 * word itself. Both are ways of asking.
 */
class EveryPublishedPermissionIsAskedForTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("../core/src/main/java"),
            Path.of("../persistence-adapter/src/main/java"),
            Path.of("../rest-adapter/src/main/java"));

    /** The one file that defines the list rather than reading it. */
    private static final String DEFINITION = "CatalogPermissions.java";

    @Test
    @DisplayName("Every node the catalogue registers is asked for somewhere")
    void everypublishedNodeIsAskedFor() throws IOException {
        List<String> sources = readersOfTheCatalogue();
        TreeSet<String> unread = new TreeSet<>();

        for (CatalogPermissions permission : CatalogPermissions.values()) {
            boolean asked = sources.stream()
                    .anyMatch(body -> body.contains("CatalogPermissions." + permission.name())
                            || body.contains('"' + permission.node() + '"'));
            if (!asked) {
                unread.add(permission.node());
            }
        }

        assertThat(unread)
                .describedAs("nodes this plugin registers with the server that nothing reads, "
                        + "so an operator takes one away and nothing changes")
                .isEmpty();
    }

    @Test
    @DisplayName("Every node a command asks for by its word is one the catalogue publishes")
    void everyaskedNodeIsPublished() throws IOException {
        Set<String> published = Stream.of(CatalogPermissions.values())
                .map(CatalogPermissions::node)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        TreeSet<String> unpublished = new TreeSet<>();
        Pattern asked = Pattern.compile("hasPermission\\(\"(uxmskyblock\\.[a-z.]+)\"\\)");
        for (String source : readersOfTheCatalogue()) {
            Matcher matcher = asked.matcher(source);
            while (matcher.find()) {
                if (!published.contains(matcher.group(1))) {
                    unpublished.add(matcher.group(1));
                }
            }
        }

        assertThat(unpublished)
                .describedAs("nodes a command asks for that reach no permission plugin's list, "
                        + "so they have no description and no declared default")
                .isEmpty();
    }

    @Test
    @DisplayName("The tree really was read, so an empty scan cannot pass this file")
    void thescanFoundTheTree() throws IOException {
        assertThat(readersOfTheCatalogue()).hasSizeGreaterThanOrEqualTo(200);
        assertThat(CatalogPermissions.values()).hasSizeGreaterThanOrEqualTo(15);
    }

    private static List<String> readersOfTheCatalogue() throws IOException {
        return SOURCES.stream()
                .filter(Files::isDirectory)
                .flatMap(EveryPublishedPermissionIsAskedForTest::javaUnder)
                .toList();
    }

    private static Stream<String> javaUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(DEFINITION))
                    .map(EveryPublishedPermissionIsAskedForTest::read)
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
