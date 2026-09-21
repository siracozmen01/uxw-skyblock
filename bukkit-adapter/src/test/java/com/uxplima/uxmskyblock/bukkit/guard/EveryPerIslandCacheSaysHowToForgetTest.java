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
 * Every service that keeps something per island says how to forget one, and says it to the wiring.
 *
 * <p>An island id is a fresh uuid every time, so a cache that keeps an erased island never gives a
 * wrong answer. It simply never lets go. A server that has made a hundred thousand islands over a
 * year was carrying a hundred thousand material indexes, and a material index is a count per
 * material.
 *
 * <p>Several of these services were written with a method to forget an island and nothing ever
 * called it. This checks the other half: that the wiring says so out loud.
 */
class EveryPerIslandCacheSaysHowToForgetTest {

    private static final Path CORE_SERVICES = Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core/application");

    private static final Path WIRING = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap");

    /** A field that keeps something for every island, for as long as the server runs. */
    private static final Pattern PER_ISLAND_CACHE = Pattern.compile("private final (?:\\w+\\.)*\\w*Map<IslandId,");

    /**
     * Services whose per island map is not a cache, with the reason.
     *
     * <p>The mutation lock gives its own lock back when nobody wants it, counted rather than
     * cleared. The bankruptcy and quarantine records are the state itself rather than a copy of
     * something else, and each one is removed when it is settled or served.
     */
    private static final List<String> NOT_A_CACHE = List.of(
            "island/IslandMutationLock.java",
            "bank/IslandBankruptcyService.java",
            "antiabuse/IslandAntiAbuseService.java",
            "recycle/IslandRecycleService.java",
            "membership/IslandMembershipService.java",
            "alliance/IslandAllianceService.java");

    @Test
    @DisplayName("Every service that keeps something per island offers a way to forget one")
    void everyCacheOffersAWayToForget() throws IOException {
        TreeSet<String> offences = new TreeSet<>();
        int cachesRead = 0;

        try (Stream<Path> files = Files.walk(CORE_SERVICES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                if (NOT_A_CACHE.stream().anyMatch(path::endsWith)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = PER_ISLAND_CACHE.matcher(source);
                if (!matcher.find()) {
                    continue;
                }
                cachesRead++;
                if (!source.contains("(IslandId islandId) {") || !source.contains(".remove(islandId)")) {
                    offences.add(
                            file.getFileName() + " keeps something for every island and offers no way to forget one");
                }
            }
        }

        assertThat(cachesRead).describedAs("the guard really found the caches").isGreaterThan(2);
        assertThat(offences).isEmpty();
    }

    @Test
    @DisplayName("Every one of those ways is registered with the wiring, not just written")
    void everyWayIsRegistered() throws IOException {
        String wiring = readWiring();

        assertThat(wiring)
                .describedAs("the upgrade tiers must be let go when an island is erased")
                .contains("invalidateCache");
        assertThat(wiring)
                .describedAs("the block and spawner counts must be let go")
                .contains("clearIsland");
        assertThat(wiring)
                .describedAs("the material index the worth is computed from must be let go")
                .contains("forgetIsland");
        assertThat(wiring)
                .describedAs("the generated dimensions must be let go")
                .contains("resetIslandDimensions");
    }

    @Test
    @DisplayName("Erasing an island is what says it happened")
    void erasingAnIslandSaysSo() throws IOException {
        String recycle = Files.readString(
                Path.of("../core/src/main/java/com/uxplima/uxmskyblock/core/application/recycle/"
                        + "IslandRecycleService.java"),
                StandardCharsets.UTF_8);

        assertThat(recycle)
                .describedAs("the reset that erases an island is the moment every cache should let go")
                .contains("cacheEviction.forget(islandId)");
    }

    private static String readWiring() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(WIRING)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }
}
