package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing reads a database on the thread that owns a player.
 *
 * <p>An {@code onEntity} or {@code onRegion} block runs where the server can touch an entity, which
 * is the same thread that is running the game for everyone in that region. A query there stops the
 * region for as long as the database takes. The standards call a database write on an event thread
 * the worst defect in this estate, and a read inside a hop is the same defect one step further in:
 * the code looks careful, because it hops.
 *
 * <p>Three were found this way. A bankruptcy record read under the cursor of every player who typed
 * {@code /is upkeep}. An island location read on every cross island visit. And {@code withHome},
 * which opened an async block and hopped straight back before doing anything, so every home command
 * did all of its database work on the entity thread while wearing a hop as a disguise.
 */
class NoDatabaseReadOnTheOwningThreadTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Where work runs on the thread that owns a player or a region. */
    private static final List<String> HOPS = List.of("onEntity(", "onRegion(", "onGlobal(");

    /** A call that reaches storage: a port, or an application service that holds one. */
    private static final Pattern STORAGE_CALL = Pattern.compile("\\b\\w*(?:StoragePort|Port|Service)\\."
            + "(?:find|save|get|count|load|delete|update|insert|list|execute|purchase|claim|"
            + "create|rate|sign|toggle|record)[A-Za-z]*\\(");

    /**
     * Collaborators that carry no storage, so a call onto one is not a query.
     *
     * <p>The scheduler is a thread, the catalogue is a map already in memory, the configuration is a
     * record read at startup, and the protection listener answers from its spatial index.
     */
    private static final Pattern NOT_STORAGE =
            Pattern.compile("^(?:messages|schedulerPort|scheduler|configuration|config|bedrockFormService|"
                    + "protectionListener|sessionCoordinator|economyBridge|presetCatalog)\\.");

    @Test
    @DisplayName("No hop onto an owning thread reads storage")
    void noHopReadsStorage() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int hopsRead = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String hop : HOPS) {
                    for (int at = source.indexOf(hop); at >= 0; at = source.indexOf(hop, at + 1)) {
                        String body = parenthesised(source, at + hop.length() - 1);
                        if (body == null) {
                            continue;
                        }
                        hopsRead++;
                        Matcher matcher = STORAGE_CALL.matcher(body);
                        while (matcher.find()) {
                            if (NOT_STORAGE.matcher(matcher.group()).find()) {
                                continue;
                            }
                            int line = source.substring(0, at).split("\n", -1).length;
                            offences.add(file.getFileName() + ":" + line + "  " + matcher.group());
                        }
                    }
                }
            }
        }

        assertThat(hopsRead).describedAs("the guard really read the hops").isGreaterThan(40);
        assertThat(offences)
                .describedAs("a query on the thread that owns a player stops the region for as long "
                        + "as the database takes. Read it before the hop, not inside one")
                .isEmpty();
    }

    /** The text between the parenthesis at {@code open} and the one that closes it, or null. */
    private static String parenthesised(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i);
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        Matcher matcher = STORAGE_CALL.matcher("() -> { islandStoragePort.findIslandById(id); }");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("islandStoragePort.findIslandById(");
    }
}
