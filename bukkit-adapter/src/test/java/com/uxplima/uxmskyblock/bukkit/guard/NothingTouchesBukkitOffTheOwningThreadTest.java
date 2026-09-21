package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing touches a player or a world from a scheduler thread.
 *
 * <p>On Folia a player belongs to a region thread and a world to another. Reading a location, moving
 * an entity or opening an inventory from the async pool is not slow, it is wrong: Folia throws, and
 * on Paper it silently races. The way back is a hop, {@code onEntity} or {@code onRegion}, and the
 * whole reason those exist is that a caller must ask for the thread before it touches the thing.
 *
 * <p>So this reads every {@code async} and {@code repeatAsync} body, removes the parts already
 * handed back to an owning thread, and fails on a Bukkit call in what is left. It matches by brace,
 * not by pattern: a guard that a stray brace can fool is worse than no guard, because it is
 * believed.
 *
 * <p>A lambda handed to a helper that opens the async block is read too. Five commands are written
 * that way, {@code onOwnIsland} and its cousins, and the first version of this guard read only what
 * was lexically inside an {@code async(} call: it passed a {@code player.getLocation()} put inside
 * one of those lambdas on purpose. A guard is worth what it catches, so the helpers are named here
 * and their callers are scanned as if the body were inline.
 */
class NothingTouchesBukkitOffTheOwningThreadTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Where work is handed to a thread that owns nothing it may touch. */
    private static final List<String> ASYNC_OPENERS = List.of(".async(", ".asyncAfter(", ".repeatAsync(");

    /**
     * Helpers in this module whose body opens an async block around the lambda they are given.
     *
     * <p>A lambda passed to one of these runs off the owning thread just as surely as one written
     * inside {@code async(} directly, so a call site of one is scanned the same way. A helper added
     * later and not listed here is only ever a gap in the scan, never a false alarm.
     */
    private static final List<String> ASYNC_HELPERS = List.of("onOwnIsland(", "onIslandHere(", "withHome(");

    /**
     * Where work is handed back to a thread that owns what it touches.
     *
     * <p>The names are matched without a leading dot, because several commands wrap the hop in a
     * private {@code onEntity(player, work)} of their own that also checks the player is still
     * online. That is still a hop, and a scan that only saw {@code schedulerPort.onEntity(} would
     * call every one of those a violation.
     */
    private static final List<String> HOPS = List.of("onEntity(", "onRegion(", "onGlobal(", "laterGlobal(");

    /** A call onto something the server owns per thread. */
    private static final Pattern BUKKIT_CALL = Pattern.compile(
            "\\b(player|viewer|target|online|entity|world|live|standing)\\.[a-zA-Z]+\\(|\\bBukkit\\.[a-zA-Z]+\\(");

    /**
     * Calls that are safe anywhere: an identity, a name, and whether the player is still connected.
     *
     * <p>Every one of these reads a field the server keeps thread safe on purpose, and an async body
     * that could not ask a player for their own id would have to be handed one by every caller.
     */
    private static final Set<String> SAFE_ANYWHERE = Set.of(
            "player.getUniqueId(",
            "player.isOnline(",
            "player.getName(",
            "player.hasPermission(",
            "viewer.getUniqueId(",
            "viewer.isOnline(",
            "target.getUniqueId(",
            "online.getUniqueId(",
            "entity.getUniqueId(",
            "live.getUniqueId(",
            "live.isOnline(",
            "standing.getUniqueId(");

    @Test
    @DisplayName("No async body calls into Bukkit without hopping to the thread that owns it")
    void noAsyncBodyTouchesBukkit() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int bodiesRead = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                List<String> openers = new ArrayList<>(ASYNC_OPENERS);
                openers.addAll(ASYNC_HELPERS);
                for (String opener : openers) {
                    for (int at = source.indexOf(opener); at >= 0; at = source.indexOf(opener, at + 1)) {
                        String body = parenthesised(source, at + opener.length() - 1);
                        if (body == null) {
                            continue;
                        }
                        bodiesRead++;
                        String offThread = withoutHops(body);
                        Matcher matcher = BUKKIT_CALL.matcher(offThread);
                        while (matcher.find()) {
                            if (SAFE_ANYWHERE.contains(matcher.group())) {
                                continue;
                            }
                            int line = source.substring(0, at).split("\n", -1).length;
                            offences.add(file.getFileName() + ":" + line + "  " + matcher.group());
                        }
                    }
                }
            }
        }

        assertThat(bodiesRead)
                .describedAs("the guard really read the async bodies")
                .isGreaterThan(40);
        assertThat(offences)
                .describedAs("Folia owns a player per region thread: touching one from the async pool "
                        + "throws there and races on Paper. Hop with onEntity or onRegion first")
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

    /** The same body with every nested hop removed, because what a hop holds runs where it belongs. */
    private static String withoutHops(String body) {
        StringBuilder kept = new StringBuilder(body);
        for (String hop : HOPS) {
            int at;
            while ((at = kept.indexOf(hop)) >= 0) {
                String nested = parenthesised(kept.toString(), at + hop.length() - 1);
                if (nested == null) {
                    // An unbalanced fragment: cut from the hop to the end rather than guess, which
                    // only ever hides code from the scan, never invents a pass for code it read.
                    kept.setLength(at);
                    break;
                }
                kept.replace(at, at + hop.length() - 1 + nested.length() + 1, "");
            }
        }
        return kept.toString();
    }

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        String body = "() -> { player.teleport(somewhere); }";
        List<String> found = new ArrayList<>();
        Matcher matcher = BUKKIT_CALL.matcher(withoutHops(body));
        while (matcher.find()) {
            found.add(matcher.group());
        }
        assertThat(found).containsExactly("player.teleport(");
    }
}
