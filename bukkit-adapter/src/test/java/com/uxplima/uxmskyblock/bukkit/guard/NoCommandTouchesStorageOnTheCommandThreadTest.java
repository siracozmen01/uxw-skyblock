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
 * No command handler reaches storage before it reaches the scheduler.
 *
 * <p>Brigadier runs a command on the thread that owns the sender, which under Folia is the region
 * thread running the game for everybody around them and on Paper is the main thread. A query there
 * stops that thread for as long as the database takes. {@link NoDatabaseReadOnTheOwningThreadTest}
 * watches the inside of a hop; this watches what runs before any hop at all, which is the same
 * defect one step earlier and was the more common one.
 *
 * <p>Seven were found this way, in three files. {@code /is reset} read which island the caller
 * belongs to and then wrote a reset challenge, both under their cursor. {@code /is reset confirm}
 * read the island again. {@code /is rename} read it and then wrote the new name. {@code /is level}
 * and {@code /is level recalculate} read it. {@code /is quarantine} read it. And the admin booster
 * command read it and then wrote a booster row.
 */
class NoCommandTouchesStorageOnTheCommandThreadTest {

    private static final Path COMMANDS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/command");

    /** Where work leaves the command thread. */
    private static final List<String> ASYNC = List.of("async(", "asyncAfter(", "thenAccept(", "thenApply(");

    /**
     * Helpers that run their action on the scheduler rather than in place.
     *
     * <p>Each one opens {@code schedulerPort.async} and calls the action inside it, so a storage
     * call in a lambda handed to one of these is already off the command thread. They are named
     * because a lexical scan cannot follow a lambda into the method that runs it.
     */
    private static final List<String> ASYNC_HELPERS =
            List.of("onOwnIsland(", "withHome(", "withIsland(", "confirmReset(");

    /**
     * Private helpers that only ever run inside an async block, so their own storage calls are
     * already off the command thread.
     *
     * <p>A lexical scan cannot follow a call into the method it lands in, so each of these is named
     * here rather than inferred. The claim is not taken on trust: {@link #namedHelpersReallyRunOffThread()}
     * fails if any call site of one of them moves back onto the command thread.
     */
    private static final List<String> OFF_THREAD_HELPERS =
            List.of("resolveIslandId(", "islandOwnerOrPlaceholder(", "refuseVisit(", "ownIsland(");

    /** A call that reaches storage: a port, or an application service that holds one. */
    private static final Pattern STORAGE_CALL = Pattern.compile("\\b\\w*(?:StoragePort|Port|Service)\\."
            + "(?:find|save|count|load|delete|insert|update|has|is|generate|rename|apply|settle|resolveVisit)"
            + "[A-Za-z]*\\(");

    /**
     * Collaborators that carry no storage, so a call onto one is not a query.
     *
     * <p>The scheduler is a thread, the catalogue and the configuration are read at startup, the
     * protection listener answers from its spatial index, the boundary and chat services keep a set
     * of who is looking, and the session coordinator answers from the session it already holds.
     */
    private static final Pattern NOT_STORAGE = Pattern.compile(
            "^(?:messages|schedulerPort|scheduler|configuration|config|bedrockFormService|protectionListener|"
                    + "sessionCoordinator|presetCatalog|schematicEngine|boundaryService|chatService)\\.");

    @Test
    @DisplayName("No command handler calls storage before handing the work to the scheduler")
    void noHandlerQueriesOnTheCommandThread() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int callsRead = 0;

        try (Stream<Path> files = Files.walk(COMMANDS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = blankComments(Files.readString(file, StandardCharsets.UTF_8));
                List<int[]> offThread = new ArrayList<>();
                for (String opener : ASYNC) {
                    offThread.addAll(argumentSpans(source, opener));
                }
                for (String helper : ASYNC_HELPERS) {
                    offThread.addAll(argumentSpans(source, helper));
                }
                for (String helper : OFF_THREAD_HELPERS) {
                    offThread.addAll(bodySpans(source, helper));
                }

                Matcher matcher = STORAGE_CALL.matcher(source);
                while (matcher.find()) {
                    callsRead++;
                    if (NOT_STORAGE.matcher(matcher.group()).find()) {
                        continue;
                    }
                    int at = matcher.start();
                    if (offThread.stream().anyMatch(span -> span[0] <= at && at <= span[1])) {
                        continue;
                    }
                    int line = source.substring(0, at).split("\n", -1).length;
                    offences.add(file.getFileName() + ":" + line + "  " + matcher.group());
                }
            }
        }

        assertThat(callsRead).describedAs("the guard really read the calls").isGreaterThan(20);
        assertThat(offences)
                .describedAs("Brigadier runs a command on the thread that owns the sender. A query "
                        + "there stops that region for as long as the database takes. Hand the work "
                        + "to schedulerPort.async first, and read what you need off the player before")
                .isEmpty();
    }

    /** The spans covering each argument list of {@code opener}, outermost parenthesis included. */
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

    /**
     * The source with every comment blanked and every offset kept.
     *
     * <p>A javadoc line naming a call would otherwise be read as the call. Blanking rather than
     * deleting keeps the line numbers in an offence honest.
     */
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

    /**
     * Every call site of every named helper is inside an async block.
     *
     * <p>This is what makes naming them safe. The moment somebody calls one of them straight out of
     * a command handler, the helper stops being off the command thread and this fails rather than
     * the main scan quietly excusing it.
     */
    @Test
    @DisplayName("Every named off thread helper is only ever called from inside an async block")
    void namedHelpersReallyRunOffThread() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        TreeSet<String> uncalled = new TreeSet<>(OFF_THREAD_HELPERS);

        try (Stream<Path> files = Files.walk(COMMANDS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = blankComments(Files.readString(file, StandardCharsets.UTF_8));
                List<int[]> offThread = new ArrayList<>();
                for (String opener : ASYNC) {
                    offThread.addAll(argumentSpans(source, opener));
                }
                for (String opener : ASYNC_HELPERS) {
                    offThread.addAll(argumentSpans(source, opener));
                }
                List<int[]> declarations = new ArrayList<>();
                for (String helper : OFF_THREAD_HELPERS) {
                    declarations.addAll(bodySpans(source, helper));
                    offThread.addAll(bodySpans(source, helper));
                }

                for (String helper : OFF_THREAD_HELPERS) {
                    for (int at = source.indexOf(helper); at >= 0; at = source.indexOf(helper, at + 1)) {
                        if (declaresIt(source, at)) {
                            continue;
                        }
                        uncalled.remove(helper);
                        int callAt = at;
                        if (offThread.stream().anyMatch(span -> span[0] <= callAt && callAt <= span[1])
                                && declarations.stream().noneMatch(span -> span[0] == callAt)) {
                            continue;
                        }
                        int line = source.substring(0, at).split("\n", -1).length;
                        offences.add(file.getFileName() + ":" + line + "  " + helper);
                    }
                }
            }
        }

        assertThat(uncalled)
                .describedAs("a helper nobody calls is off the command thread for no reason. Drop it "
                        + "from the list rather than leaving a name that proves nothing")
                .isEmpty();
        assertThat(offences)
                .describedAs("this helper reaches storage, and it was named as safe because every "
                        + "caller was inside an async block. This caller is not")
                .isEmpty();
    }

    /** Whether the text at {@code at} is the helper's own declaration rather than a call to it. */
    private static boolean declaresIt(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at) + 1;
        String before = source.substring(lineStart, at);
        return before.contains("private ") || before.contains("public ") || before.contains("static ");
    }

    /** The spans covering the body of each declaration of {@code opener}, braces included. */
    private static List<int[]> bodySpans(String source, String opener) {
        List<int[]> spans = new ArrayList<>();
        for (int at = source.indexOf(opener); at >= 0; at = source.indexOf(opener, at + 1)) {
            if (!declaresIt(source, at)) {
                continue;
            }
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

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        Matcher matcher = STORAGE_CALL.matcher("islandLocationService.findIslandId(profileId);");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("islandLocationService.findIslandId(");
    }

    @Test
    @DisplayName("A comment naming a call is not the call")
    void commentsAreBlanked() {
        String blanked = blankComments("// islandLocationService.findIslandId(id);\nint x = 1;");
        assertThat(STORAGE_CALL.matcher(blanked).find()).isFalse();
        assertThat(blanked.split("\n", -1)).hasSize(2);
    }
}
