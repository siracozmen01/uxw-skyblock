package com.uxplima.uxmskyblock.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every write of the island aggregate happens inside the mutation lock.
 *
 * <p>{@code saveIsland} writes the whole island: its members, its roles, its flags and where it is.
 * A service that reads it, changes one part and writes it back without holding anything loses
 * whatever somebody else changed in between. Nine of them did, across membership, flags, freezing,
 * inactivity and the spawn point, and no message and no row in the database said so.
 *
 * <p>The inactivity sweep was excused from this guard, on the claim that a sweep only erases
 * islands and erasing is not read, change and write. It archives them and it transfers ownership,
 * so the claim was wrong and the excuse is gone.
 *
 * <p>The scan is lexical, so it can only see a save sitting inside a lock block in the same method.
 * That is the shape every one of them now has, and it is the shape the next one has to have.
 */
class EveryIslandWriteIsUnderTheLockTest {

    private static final Path SOURCES = Path.of("src/main/java/com/uxplima/uxmskyblock/core/application");

    /** Where a write is allowed to sit outside the lock, with the reason it is allowed. */
    private static final List<String> WRITES_A_NEW_ISLAND = List.of(
            // Creating one: there is nothing to lose an update against, because until this write
            // lands there is no island for anybody else to read.
            "island/CreateIslandUseCase.java");

    /**
     * Private helpers that only ever run inside the lock, so their own writes are already held.
     *
     * <p>A lexical scan cannot follow a call into the method it lands in, so each of these is named
     * here rather than inferred. The claim is not taken on trust: {@link #namedHelpersReallyRunUnderTheLock()}
     * fails if any call site of one of them moves outside a lock block.
     */
    private static final List<String> RUNS_UNDER_THE_LOCK = List.of(
            "freezeIslandInside(",
            "unfreezeIslandInside(",
            "transitionEconomicStateInside(",
            "transitionLifecycleInside(",
            "moveInside(",
            "write(",
            // The inactivity sweep decides and writes inside the lock, and this is where it does
            // both. It was excused from this guard on the claim that a sweep only erases islands.
            // It archives them and it transfers ownership, which is read, change and write.
            "evaluateIslandInternal(");

    @Test
    @DisplayName("No application service writes an island outside the mutation lock")
    void noServiceWritesOutsideTheLock() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int writesRead = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                if (WRITES_A_NEW_ISLAND.stream().anyMatch(path::endsWith)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                List<int[]> locked = argumentSpans(source, "mutationLock.inside(");
                for (String helper : RUNS_UNDER_THE_LOCK) {
                    locked.addAll(bodySpans(source, helper));
                }

                for (int at = source.indexOf(".saveIsland("); at >= 0; at = source.indexOf(".saveIsland(", at + 1)) {
                    writesRead++;
                    int writeAt = at;
                    if (locked.stream().anyMatch(span -> span[0] <= writeAt && writeAt <= span[1])) {
                        continue;
                    }
                    int line = source.substring(0, at).split("\n", -1).length;
                    offences.add(file.getFileName() + ":" + line);
                }
            }
        }

        assertThat(writesRead).describedAs("the guard really found the writes").isGreaterThan(5);
        assertThat(offences)
                .describedAs("saveIsland writes the whole island. Reading it, changing one part and "
                        + "writing it back without the lock loses whatever somebody else changed in "
                        + "between, and nothing reports it. Wrap the read and the write in "
                        + "mutationLock.inside")
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
     * Every call site of every named helper is inside a lock block.
     *
     * <p>This is what makes naming them safe. The moment somebody calls one of them from outside the
     * lock, the helper stops being held and this fails rather than the main scan quietly excusing it.
     */
    @Test
    @DisplayName("Every named helper is only ever called from inside a lock block")
    void namedHelpersReallyRunUnderTheLock() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        TreeSet<String> uncalled = new TreeSet<>(RUNS_UNDER_THE_LOCK);

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                List<int[]> locked = argumentSpans(source, "mutationLock.inside(");
                List<int[]> declarations = new ArrayList<>();
                for (String helper : RUNS_UNDER_THE_LOCK) {
                    declarations.addAll(bodySpans(source, helper));
                }
                locked.addAll(declarations);

                for (String helper : RUNS_UNDER_THE_LOCK) {
                    for (int at = source.indexOf(helper); at >= 0; at = source.indexOf(helper, at + 1)) {
                        if (declaresIt(source, at)) {
                            continue;
                        }
                        uncalled.remove(helper);
                        int callAt = at;
                        if (locked.stream().anyMatch(span -> span[0] <= callAt && callAt <= span[1])) {
                            continue;
                        }
                        int line = source.substring(0, at).split("\n", -1).length;
                        offences.add(file.getFileName() + ":" + line + "  " + helper);
                    }
                }
            }
        }

        assertThat(uncalled)
                .describedAs("a helper nobody calls is under the lock for no reason. Drop it from the "
                        + "list rather than leaving a name that proves nothing")
                .isEmpty();
        assertThat(offences)
                .describedAs("this helper writes an island, and it was named as safe because every "
                        + "caller was inside a lock block. This caller is not")
                .isEmpty();
    }

    /** Whether the text at {@code at} is the helper's own declaration rather than a call to it. */
    private static boolean declaresIt(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at) + 1;
        String before = source.substring(lineStart, at);
        return before.contains("private ") || before.contains("public ");
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
        String unlocked = "void change() { islandStoragePort.saveIsland(island, location); }";
        assertThat(argumentSpans(unlocked, "mutationLock.inside(")).isEmpty();
        assertThat(unlocked.indexOf(".saveIsland(")).isPositive();
    }
}
