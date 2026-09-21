package com.uxplima.uxmskyblock.bukkit.guard;

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
 * No event handler reaches storage where it stands.
 *
 * <p>An event handler runs on the thread that owns the thing the event is about: the region under
 * Folia, the main thread on Paper. A query there stops that thread for as long as the database
 * takes, and some events are not rare. The island limit was the case that taught this: it asked the
 * upgrade tier from the database on every block a player placed, and a player building runs several
 * placements a second.
 *
 * <p>The sibling guards watch a command before its first hop and the inside of a hop. This watches
 * the third door, where the server calls into the plugin.
 */
class NoEventHandlerReachesStorageInPlaceTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Where work leaves the thread the event arrived on. */
    private static final List<String> OFF_THREAD =
            List.of("async(", "asyncAfter(", "thenAccept(", "thenApply(", "thenRun(", "thenCompose(");

    /**
     * Where the handler builds the work as a value and hands it to the scheduler further down.
     *
     * <p>Several listeners write {@code Runnable task = () -> { ... };} and then choose between
     * {@code schedulerPort.async(task)} and running it in place when they have no scheduler. The
     * body is off the thread in every wiring the plugin actually builds, and a lexical scan cannot
     * see that the variable is handed over ten lines later.
     */
    private static final List<String> BUILDS_THE_WORK_FIRST = List.of("Runnable checkTask = ", "Runnable task = ");

    /** A call that reaches storage: a port, or an application service that holds one. */
    private static final Pattern STORAGE_CALL = Pattern.compile("\\b\\w*(?:StoragePort|Port|Service)\\."
            + "(?:find|save|count|load|delete|insert|update|getUpgradeTier|getCurrentTier|getEffectiveLimit)"
            + "[A-Za-z]*\\(");

    /**
     * Collaborators that answer from memory, so a call onto one is not a query.
     *
     * <p>Each of these keeps what it knows in a map: the scheduler is a thread, the configuration is
     * read at startup, the protection listener answers from its spatial index, the limit service
     * counts in memory and asks the upgrade service for a tier it has cached, and the worth service
     * keeps a per island histogram.
     */
    private static final Pattern NOT_STORAGE = Pattern.compile(
            "^(?:messages|schedulerPort|scheduler|configuration|config|protectionListener|sessionCoordinator|"
                    + "presetCatalog|boundaryService|chatService|worthService|limitService|dimensionService|"
                    + "upgradeService|transportPort|deliveryPort|onlineMemberProvider|allianceService|accessService)\\.");

    @Test
    @DisplayName("No event handler calls storage on the thread the event arrived on")
    void noHandlerQueriesInPlace() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int handlersRead = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = blankComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!source.contains("@EventHandler")) {
                    continue;
                }

                List<int[]> offThread = new ArrayList<>();
                for (String opener : OFF_THREAD) {
                    offThread.addAll(argumentSpans(source, opener));
                }
                for (String opener : BUILDS_THE_WORK_FIRST) {
                    offThread.addAll(statementSpans(source, opener));
                }

                for (int[] handler : handlerBodies(source)) {
                    handlersRead++;
                    Matcher matcher = STORAGE_CALL.matcher(source);
                    while (matcher.find()) {
                        int at = matcher.start();
                        if (at < handler[0] || at > handler[1]) {
                            continue;
                        }
                        if (NOT_STORAGE.matcher(matcher.group()).find()) {
                            continue;
                        }
                        if (offThread.stream().anyMatch(span -> span[0] <= at && at <= span[1])) {
                            continue;
                        }
                        int line = source.substring(0, at).split("\n", -1).length;
                        offences.add(file.getFileName() + ":" + line + "  " + matcher.group());
                    }
                }
            }
        }

        assertThat(handlersRead)
                .describedAs("the guard really found the handlers")
                .isGreaterThan(20);
        assertThat(offences)
                .describedAs("an event handler runs on the thread that owns what the event is about, "
                        + "and some events are not rare. Hand the work to the scheduler, or answer "
                        + "from something that keeps what it knows in memory")
                .isEmpty();
    }

    /** The body span of each {@code @EventHandler} method. */
    private static List<int[]> handlerBodies(String source) {
        List<int[]> bodies = new ArrayList<>();
        Matcher matcher = Pattern.compile("@EventHandler[^\n]*\n\\s*public\\s+void\\s+\\w+\\s*\\([^)]*\\)\\s*\\{")
                .matcher(source);
        while (matcher.find()) {
            int brace = source.indexOf('{', matcher.end() - 1);
            int depth = 0;
            for (int i = brace; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        bodies.add(new int[] {matcher.start(), i});
                        break;
                    }
                }
            }
        }
        return bodies;
    }

    /** The spans covering each argument list of {@code opener}. */
    private static List<int[]> argumentSpans(String source, String opener) {
        List<int[]> spans = new ArrayList<>();
        for (int at = source.indexOf(opener); at >= 0; at = source.indexOf(opener, at + 1)) {
            int open = at + opener.length() - 1;
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        spans.add(new int[] {at, i});
                        break;
                    }
                }
            }
        }
        return spans;
    }

    /** The spans covering each {@code Runnable x = () -> { ... };} statement. */
    private static List<int[]> statementSpans(String source, String opener) {
        List<int[]> spans = new ArrayList<>();
        for (int at = source.indexOf(opener); at >= 0; at = source.indexOf(opener, at + 1)) {
            int brace = source.indexOf('{', at);
            if (brace < 0) {
                continue;
            }
            int depth = 0;
            for (int i = brace; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        spans.add(new int[] {at, i});
                        break;
                    }
                }
            }
        }
        return spans;
    }

    /** The source with every comment blanked and every offset kept. */
    private static String blankComments(String source) {
        char[] out = source.toCharArray();
        boolean inBlock = false;
        boolean inLine = false;
        for (int i = 0; i < out.length; i++) {
            if (inLine) {
                if (out[i] == '\n') {
                    inLine = false;
                } else {
                    out[i] = ' ';
                }
            } else if (inBlock) {
                if (out[i] == '*' && i + 1 < out.length && out[i + 1] == '/') {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                    inBlock = false;
                } else if (out[i] != '\n') {
                    out[i] = ' ';
                }
            } else if (out[i] == '/' && i + 1 < out.length && out[i + 1] == '/') {
                inLine = true;
                out[i] = ' ';
                out[i + 1] = ' ';
                i++;
            } else if (out[i] == '/' && i + 1 < out.length && out[i + 1] == '*') {
                inBlock = true;
                out[i] = ' ';
                out[i + 1] = ' ';
                i++;
            }
        }
        return new String(out);
    }

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        Matcher matcher = STORAGE_CALL.matcher("islandStoragePort.findIslandById(id);");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("islandStoragePort.findIslandById(");
        assertThat(handlerBodies("@EventHandler\n    public void onX(Event e) {\n        int a = 1;\n    }\n"))
                .hasSize(1);
    }
}
