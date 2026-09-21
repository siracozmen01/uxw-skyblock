package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One place that forgets an island.
 *
 * <p>Half a dozen services keep something per island in memory. Several were written with a method
 * to forget one and nothing ever called it, and the material index the worth is computed from had
 * no such method at all. An island id is never reused, so a stale entry never gives a wrong answer:
 * it is simply never released.
 */
class IslandCacheEvictionTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    @Test
    @DisplayName("Every service that said how to forget an island is told when one goes")
    void everyServiceIsTold() {
        IslandCacheEviction eviction = new IslandCacheEviction();
        List<IslandId> upgrades = new ArrayList<>();
        List<IslandId> limits = new ArrayList<>();
        List<IslandId> worth = new ArrayList<>();
        eviction.whenForgotten(upgrades::add);
        eviction.whenForgotten(limits::add);
        eviction.whenForgotten(worth::add);

        eviction.forget(ISLAND);

        assertThat(upgrades).containsExactly(ISLAND);
        assertThat(limits).containsExactly(ISLAND);
        assertThat(worth).containsExactly(ISLAND);
    }

    @Test
    @DisplayName("One service that will not let go does not stop the rest")
    void oneStubbornServiceDoesNotStopTheRest() {
        IslandCacheEviction eviction = new IslandCacheEviction();
        List<IslandId> after = new ArrayList<>();
        eviction.whenForgotten(islandId -> {
            throw new IllegalStateException("not letting go");
        });
        eviction.whenForgotten(after::add);

        assertThatCode(() -> eviction.forget(ISLAND))
                .describedAs("the island is already erased, and the others are holding memory either way")
                .doesNotThrowAnyException();
        assertThat(after).containsExactly(ISLAND);
    }

    @Test
    @DisplayName("Forgetting an island nobody kept anything for is quiet")
    void forgettingAnUnknownIslandIsQuiet() {
        IslandCacheEviction eviction = new IslandCacheEviction();

        assertThatCode(() -> eviction.forget(ISLAND)).doesNotThrowAnyException();
        assertThat(eviction.registered()).isZero();
    }

    @Test
    @DisplayName("Only the island that went is forgotten, not every island")
    void onlyThatIslandIsForgotten() {
        IslandCacheEviction eviction = new IslandCacheEviction();
        List<IslandId> forgotten = new ArrayList<>();
        eviction.whenForgotten(forgotten::add);
        IslandId other = IslandId.of(UUID.randomUUID());

        eviction.forget(ISLAND);

        assertThat(forgotten).containsExactly(ISLAND).doesNotContain(other);
    }
}
