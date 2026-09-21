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
     * <ul>
     *   <li>{@code GameModeHierarchyService}: BDR-001
     *   <li>{@code JournaledInventoryMutationService}: WP2-005
     * </ul>
     */
    private static final Set<String> NOT_WIRED_YET =
            Set.of("GameModeHierarchyService", "JournaledInventoryMutationService");

    // JournaledInventoryMutationService is not unused by accident. The reward delivery handler runs
    // the same two phase protocol inline, and it does one thing more: on a failed commit it puts the
    // slots it changed back. The service could not do that until it was given a compensation hook,
    // and routing the handler through it before that would have turned a rollback into a
    // duplication. The hook exists now, so the move is a change of its own rather than something
    // buried in a wiring commit.

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

    private static Set<String> unwiredServices() throws IOException {
        String production = productionSources();
        Set<String> unwired = new TreeSet<>();
        for (String service : allServices()) {
            if (!production.contains("new " + service + "(")) {
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

    /** Every production source of every module, which is everywhere a service could be built. */
    private static String productionSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String module : List.of(".", "../core", "../persistence-adapter", "../rest-adapter")) {
            Path main = Path.of(module, "src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file :
                        files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    all.append(Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return all.toString();
    }
}
