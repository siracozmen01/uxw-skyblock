package com.uxplima.uxmskyblock.core.application.acid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The AcidIsland islands are held in memory, and memory follows the table.
 *
 * <p>A node forgets an island when it hears the island changed, and it hears its own island being
 * created. An AcidIsland island dropped then burned nobody until the server restarted, so forgetting
 * reads the row again and only an island whose row is gone leaves memory.
 */
class AcidIslandServiceTest {

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final Map<IslandId, Integer> table = new HashMap<>();
    private final AcidIslandService service = new AcidIslandService(new AcidIslandsPort() {
        @Override
        public Map<IslandId, Integer> findAll() {
            return Map.copyOf(table);
        }

        @Override
        public OptionalInt find(IslandId id) {
            Integer level = table.get(id);
            return level == null ? OptionalInt.empty() : OptionalInt.of(level);
        }

        @Override
        public void add(IslandId id, int seaLevel) {
            table.putIfAbsent(id, seaLevel);
        }
    });

    @Test
    @DisplayName("An island is recorded in the table and in memory, and a second record keeps the first level")
    void anIslandIsRecorded() {
        service.add(islandId, 98);
        service.add(islandId, 40);

        assertThat(table).containsEntry(islandId, 98);
        assertThat(service.seaLevel(islandId)).hasValue(98);
        assertThat(service.isAcid(IslandId.of(UUID.randomUUID()))).isFalse();
    }

    @Test
    @DisplayName("An island heard back as changed is read again and stays an AcidIsland island")
    void aChangedIslandStays() {
        service.add(islandId, 98);

        service.forget(islandId);

        assertThat(service.isAcid(islandId)).isTrue();
        assertThat(service.seaLevel(islandId)).hasValue(98);
    }

    @Test
    @DisplayName("An island whose row is gone is dropped, and priming reads every island there is")
    void anErasedIslandIsDropped() {
        service.add(islandId, 98);
        table.remove(islandId);

        service.forget(islandId);

        assertThat(service.isAcid(islandId)).isFalse();
        IslandId other = IslandId.of(UUID.randomUUID());
        table.put(other, 60);
        assertThat(service.prime()).isEqualTo(1);
        assertThat(service.seaLevel(other)).hasValue(60);
    }
}
