package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The TradeWinds vessels are held in memory, and memory follows the table. */
class VesselServiceTest {

    private final Set<IslandId> table = new HashSet<>();
    private final VesselService service = new VesselService(new VesselsPort() {
        @Override
        public Set<IslandId> findAll() {
            return Set.copyOf(table);
        }

        @Override
        public boolean exists(IslandId islandId) {
            return table.contains(islandId);
        }

        @Override
        public void add(IslandId islandId) {
            table.add(islandId);
        }
    });

    @Test
    @DisplayName("An island made a vessel is one in the table and in memory, and another island is not")
    void anIslandIsRecorded() {
        IslandId island = IslandId.of(UUID.randomUUID());

        service.start(island);

        assertThat(table).contains(island);
        assertThat(service.isVessel(island)).isTrue();
        assertThat(service.isVessel(IslandId.of(UUID.randomUUID()))).isFalse();
    }

    @Test
    @DisplayName("Heard back as changed it stays, erased it goes, and priming reads every island there is")
    void memoryFollowsTheTable() {
        IslandId kept = IslandId.of(UUID.randomUUID());
        IslandId erased = IslandId.of(UUID.randomUUID());
        service.start(kept);
        service.start(erased);
        table.remove(erased);

        service.forget(kept);
        service.forget(erased);

        assertThat(service.isVessel(kept)).isTrue();
        assertThat(service.isVessel(erased)).isFalse();
        IslandId other = IslandId.of(UUID.randomUUID());
        table.add(other);
        assertThat(new VesselService(new VesselsPort() {
                            @Override
                            public Set<IslandId> findAll() {
                                return Set.copyOf(table);
                            }

                            @Override
                            public boolean exists(IslandId islandId) {
                                return table.contains(islandId);
                            }

                            @Override
                            public void add(IslandId islandId) {
                                table.add(islandId);
                            }
                        })
                        .prime())
                .isEqualTo(2);
    }
}
