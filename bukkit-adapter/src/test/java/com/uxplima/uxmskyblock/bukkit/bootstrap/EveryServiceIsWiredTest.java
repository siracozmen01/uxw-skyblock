package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A service nothing builds cannot run.
 *
 * <p>The board reported every subsystem complete while five of them were constructed in their own
 * tests and nowhere else, so they passed their tests and were absent from every server that ever
 * started the plugin. There is no compile error for that and no other test can see it: each
 * subsystem's own test builds the thing it tests.
 *
 * <p>{@link #NOT_WIRED_YET} is the measured gap, written here rather than in a document so it
 * cannot be forgotten. A name may leave this list when the subsystem is wired. Nothing may join it.
 */
class EveryServiceIsWiredTest {

    /**
     * Subsystems that exist, pass their own tests, and reach no server.
     *
     * <p>Each names the board card that called it complete:
     *
     * <p>The list is empty, and it stays here so a new name cannot join it quietly.
     *
     * <p>{@code GameModeHierarchyService} left it when island creation began binding each new island
     * into its owner's game mode instance. Until then every backup fell through to an instance id
     * synthesised from the owner's profile: a reference to a row that had never existed.
     *
     * <p>{@code JournaledInventoryMutationService} left it when the reward delivery handler stopped
     * writing the two phase protocol out by hand and called the service instead. That could not
     * happen until the service could undo the world as well as the ledger, because a rollback that
     * undoes only the ledger leaves the item in the player's hands and is a duplication.
     */
    private static final Set<String> NOT_WIRED_YET = Set.of();

    private static final Path CORE_SERVICES = Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core/application");

    @Test
    @DisplayName("No service is left unwired beyond the gap this file already records")
    void noNewServiceIsLeftUnwired() throws IOException {
        Set<String> unwired = unwiredServices();

        Set<String> unexpected = new TreeSet<>(unwired);
        unexpected.removeAll(NOT_WIRED_YET);

        assertThat(unexpected)
                .describedAs("services built nowhere in production code, so they cannot run on a server")
                .isEmpty();
    }

    @Test
    @DisplayName("The recorded gap is still real, so a wired subsystem does not stay on the list")
    void theRecordedGapIsStillReal() throws IOException {
        Set<String> unwired = unwiredServices();

        Set<String> nowWired = new TreeSet<>(NOT_WIRED_YET);
        nowWired.removeAll(unwired);

        assertThat(nowWired)
                .describedAs("services that are wired now and should be taken off NOT_WIRED_YET")
                .isEmpty();
    }

    @Test
    @DisplayName("The core sources really were read, so an empty scan cannot pass this file")
    void theScanFoundServices() throws IOException {
        assertThat(allServices()).hasSizeGreaterThan(20);
    }

    /**
     * The services nothing else builds.
     *
     * <p>A service's own file does not count. {@code IslandLevelService} has a {@code defaultService()}
     * factory that calls {@code new IslandLevelService(...)}, and for as long as this scan read that
     * line the service looked wired while reaching no server at all: a dead duplicate of the worth
     * service, sitting behind the one guard written to catch exactly that.
     */
    private static Set<String> unwiredServices() throws IOException {
        Set<String> unwired = new TreeSet<>();
        for (String service : allServices()) {
            if (!productionSourcesExcept(service).contains("new " + service + "(")) {
                unwired.add(service);
            }
        }
        return unwired;
    }

    private static Set<String> allServices() throws IOException {
        try (Stream<Path> files = Files.walk(CORE_SERVICES)) {
            Set<String> names = new TreeSet<>();
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("Service.java"))
                    .toList()) {
                String name = file.getFileName().toString();
                names.add(name.substring(0, name.length() - ".java".length()));
            }
            return names;
        }
    }

    /** Every production source of every module, except the one file that declares {@code service}. */
    private static String productionSourcesExcept(String service) throws IOException {
        StringBuilder all = new StringBuilder();
        for (String module : List.of(".", "../core", "../persistence-adapter", "../rest-adapter")) {
            Path main = Path.of(module, "src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file :
                        files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals(service + ".java")) {
                        continue;
                    }
                    all.append(Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return all.toString();
    }
}
