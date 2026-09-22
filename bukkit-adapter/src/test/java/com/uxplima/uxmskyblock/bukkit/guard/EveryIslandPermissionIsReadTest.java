package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A permission the role editor publishes is a permission something reads.
 *
 * <p>Half of this list was decoration. The editor showed a switch, the operator turned it off, and
 * the plugin went on doing the thing: containers, the bank, the shop, the vault, the biome, the
 * bucket, the spawner, the animals, the crops, the upgrades. Each one was a separate hole and each
 * one looked like a working feature from the outside.
 *
 * <p>The scan skips the four files that define the list rather than act on it: the permission enum,
 * the roles that hold them, the key registry that names them and the access service whose whole job
 * is one line per permission.
 */
class EveryIslandPermissionIsReadTest {

    private static final List<Path> MODULES = List.of(
            Path.of("src/main/java"),
            Path.of("../core/src/main/java"),
            Path.of("../persistence-adapter/src/main/java"),
            Path.of("../rest-adapter/src/main/java"));

    /** The files that define the list. Naming a permission there is not reading it. */
    private static final List<String> DEFINITIONS =
            List.of("IslandPermission.java", "IslandRole.java", "StandardPermissions.java", "IslandAccessService.java");

    @Test
    @DisplayName("Every island permission is read by something that can refuse")
    void everyPermissionIsRead() throws IOException {
        List<String> sources = productionSources();
        TreeSet<String> unread = new TreeSet<>();

        List<String> shippedFiles = shippedConfigurations();
        for (IslandPermission permission : IslandPermission.values()) {
            Pattern named = Pattern.compile("IslandPermission\\." + permission.name() + "\\b");
            Pattern quoted = Pattern.compile("\"" + permission.name() + "\"");
            boolean read = sources.stream().anyMatch(body -> named.matcher(body).find())
                    || shippedFiles.stream()
                            .anyMatch(body -> quoted.matcher(body).find());
            if (!read) {
                unread.add(permission.name());
            }
        }

        assertThat(unread)
                .describedAs("permissions the role editor publishes and nothing reads, "
                        + "so an operator turns them off and nothing changes")
                .isEmpty();
    }

    @Test
    @DisplayName("The tree really was read, so an empty scan cannot pass this file")
    void thescanFoundTheTree() throws IOException {
        assertThat(productionSources()).hasSizeGreaterThanOrEqualTo(200);
        assertThat(shippedConfigurations()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(IslandPermission.values()).hasSizeGreaterThanOrEqualTo(20);
    }

    /** The configuration this plugin ships, where an operator names a permission by its word. */
    private static List<String> shippedConfigurations() throws IOException {
        Path modules = Path.of("src/main/resources/modules");
        if (!Files.isDirectory(modules)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(modules)) {
            return files.filter(path -> path.toString().endsWith(".conf"))
                    .map(EveryIslandPermissionIsReadTest::read)
                    .toList();
        }
    }

    private static List<String> productionSources() throws IOException {
        return MODULES.stream()
                .filter(Files::isDirectory)
                .flatMap(EveryIslandPermissionIsReadTest::javaFilesUnder)
                .filter(path -> DEFINITIONS.stream()
                        .noneMatch(name -> path.getFileName().toString().equals(name)))
                .map(EveryIslandPermissionIsReadTest::read)
                .toList();
    }

    private static Stream<Path> javaFilesUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList().stream();
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
