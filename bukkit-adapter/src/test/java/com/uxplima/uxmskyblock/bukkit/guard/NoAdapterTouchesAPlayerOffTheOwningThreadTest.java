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
 * No outbound adapter touches a player on a thread that does not own them.
 *
 * <p>{@link NothingTouchesBukkitOffTheOwningThreadTest} watches what happens inside an async block.
 * This watches the other door: an adapter implementing an outbound port is called by the core, and
 * the core calls it from wherever the work happened to be. The chat delivery adapter is the case
 * that taught this: it ran on the scheduler pool on one node and on a Redis subscriber thread across
 * a cluster, and it looked a player up and wrote to them right there.
 *
 * <p>A method that reaches a player has to hop first. Everything else an adapter does is its own
 * business.
 */
class NoAdapterTouchesAPlayerOffTheOwningThreadTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** A class that answers the core, which calls it from wherever the core happened to be. */
    private static final Pattern OUTBOUND_ADAPTER =
            Pattern.compile("class\\s+\\w+\\s+implements\\s+[A-Za-z., ]*\\b\\w*(?:Port|Provider)\\b");

    /**
     * A call that reaches a live player, which belongs to the thread that owns them.
     *
     * <p>Holding a reference to a player is not reaching them, so {@code Bukkit.getPlayer} is not
     * here: obtaining one is allowed anywhere and using one is not. Iterating the live player list
     * is here, because that list is rewritten by every join and quit on the thread that owns it.
     */
    private static final Pattern REACHES_A_PLAYER = Pattern.compile("\\bBukkit\\.getOnlinePlayers\\(|"
            + "\\b(player|viewer|target|online|recipient)\\.(sendMessage|teleport|teleportAsync|getInventory|"
            + "openInventory|closeInventory|setVelocity|getLocation|sendPluginMessage|playSound|showTitle)\\(");

    /** Where work is handed back to the thread that owns what it touches. */
    private static final List<String> HOPS = List.of("onEntity(", "onRegion(", "onGlobal(", "laterGlobal(");

    /**
     * Adapters that are allowed to reach a player without hopping, and why.
     *
     * <p>The scheduler is the thing that does the hopping, so it cannot hop first. The eviction
     * adapter reaches a player only inside its own {@code executeEvictionPlan}, which its caller
     * already runs on a hop.
     */
    private static final List<String> HOPS_SOMEWHERE_ELSE =
            List.of("scheduler/FoliaSchedulerAdapter.java", "freeze/BukkitIslandVisitorEvictionAdapter.java");

    @Test
    @DisplayName("No outbound adapter reaches a player without hopping onto their thread first")
    void noAdapterReachesAPlayerWithoutHopping() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int adaptersRead = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                if (HOPS_SOMEWHERE_ELSE.stream().anyMatch(path::endsWith)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!OUTBOUND_ADAPTER.matcher(source).find()) {
                    continue;
                }
                adaptersRead++;

                List<int[]> hops = new ArrayList<>();
                for (String hop : HOPS) {
                    hops.addAll(argumentSpans(source, hop));
                }

                Matcher matcher = REACHES_A_PLAYER.matcher(source);
                while (matcher.find()) {
                    int at = matcher.start();
                    if (hops.stream().anyMatch(span -> span[0] <= at && at <= span[1])) {
                        continue;
                    }
                    int line = source.substring(0, at).split("\n", -1).length;
                    offences.add(file.getFileName() + ":" + line + "  " + matcher.group());
                }
            }
        }

        assertThat(adaptersRead)
                .describedAs("the guard really found the adapters")
                .isGreaterThan(5);
        assertThat(offences)
                .describedAs("the core calls an adapter from wherever the work happened to be, which is "
                        + "the scheduler pool on one node and a subscriber thread across a cluster. "
                        + "Neither owns a player. Hop onto their thread before reaching them")
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

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        assertThat(REACHES_A_PLAYER
                        .matcher("for (Player p : Bukkit.getOnlinePlayers()) {")
                        .find())
                .isTrue();
        assertThat(REACHES_A_PLAYER.matcher("player.sendMessage(line);").find()).isTrue();
        assertThat(OUTBOUND_ADAPTER
                        .matcher("public final class X implements IslandChatDeliveryPort {")
                        .find())
                .isTrue();
    }

    @Test
    @DisplayName("Reading a player's own id is not reaching them")
    void readingAnIdIsNotReaching() {
        assertThat(REACHES_A_PLAYER.matcher("player.getUniqueId()").find()).isFalse();
        assertThat(REACHES_A_PLAYER.matcher("player.isOnline()").find()).isFalse();
        assertThat(REACHES_A_PLAYER.matcher("Player p = Bukkit.getPlayer(id);").find())
                .describedAs("holding a reference is not using one")
                .isFalse();
    }
}
