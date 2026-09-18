package com.uxplima.uxmskyblock.core.application.warp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.warp.UnsafeTeleportDestinationException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SafeTeleportEngineTest {

    private SafeTeleportEngine engine;
    private MockBlockInspector inspector;

    @BeforeEach
    void setUp() {
        engine = new SafeTeleportEngine(5);
        inspector = new MockBlockInspector();
    }

    @Test
    @DisplayName("Destination is already safe: returned unmodified")
    void destinationAlreadySafe() {
        // Floor at (100, 63, 100) is solid
        inspector.setSolid("world", 100, 63, 100);
        inspector.setPassable("world", 100, 64, 100);
        inspector.setPassable("world", 100, 65, 100);

        WarpLocation target = new WarpLocation("world", 100.5, 64.0, 100.5, 90.0f, 0.0f);
        WarpLocation verified = engine.verifyOrFindSafeSpot(target, inspector);

        assertThat(verified).isEqualTo(target);
    }

    @Test
    @DisplayName("Destination has lava floor: finds safe spot within 5-block bounding box")
    void destinationFloorHazardFindsSafeSpot() {
        // Unsafe origin: floor at (100, 63, 100) is lava/hazardous
        inspector.setHazard("world", 100, 63, 100);
        inspector.setPassable("world", 100, 64, 100);
        inspector.setPassable("world", 100, 65, 100);

        // Safe spot nearby at (102, 64, 100) -> floor at 63 is solid
        inspector.setSolid("world", 102, 63, 100);
        inspector.setPassable("world", 102, 64, 100);
        inspector.setPassable("world", 102, 65, 100);

        WarpLocation target = new WarpLocation("world", 100.0, 64.0, 100.0, 45.0f, 10.0f);
        WarpLocation safe = engine.verifyOrFindSafeSpot(target, inspector);

        assertThat(safe.blockX()).isEqualTo(102);
        assertThat(safe.blockY()).isEqualTo(64);
        assertThat(safe.blockZ()).isEqualTo(100);
        assertThat(safe.x()).isEqualTo(102.5);
        assertThat(safe.z()).isEqualTo(100.5);
        assertThat(safe.yaw()).isEqualTo(45.0f);
        assertThat(safe.pitch()).isEqualTo(10.0f);
    }

    @Test
    @DisplayName("Destination suffocating head block: finds nearest safe spot")
    void destinationSuffocatingHeadFindsSafeSpot() {
        // Floor is solid, feet passable, but head is solid (not passable)
        inspector.setSolid("world", 100, 63, 100);
        inspector.setPassable("world", 100, 64, 100);
        inspector.setSolid("world", 100, 65, 100); // suffocating head block

        // Safe spot 1 block away at (100, 64, 101)
        inspector.setSolid("world", 100, 63, 101);
        inspector.setPassable("world", 100, 64, 101);
        inspector.setPassable("world", 100, 65, 101);

        WarpLocation target = new WarpLocation("world", 100.0, 64.0, 100.0, 0.0f, 0.0f);
        WarpLocation safe = engine.verifyOrFindSafeSpot(target, inspector);

        assertThat(safe.blockX()).isEqualTo(100);
        assertThat(safe.blockY()).isEqualTo(64);
        assertThat(safe.blockZ()).isEqualTo(101);
    }

    @Test
    @DisplayName("Multiple safe spots: selects nearest candidate by Euclidean distance")
    void selectsNearestCandidate() {
        // Origin is unsafe
        inspector.setHazard("world", 50, 63, 50);

        // Candidate A at dx=+3, dy=0, dz=0 (distSq = 9)
        inspector.setSolid("world", 53, 63, 50);
        inspector.setPassable("world", 53, 64, 50);
        inspector.setPassable("world", 53, 65, 50);

        // Candidate B at dx=+1, dy=0, dz=0 (distSq = 1) -> CLOSER
        inspector.setSolid("world", 51, 63, 50);
        inspector.setPassable("world", 51, 64, 50);
        inspector.setPassable("world", 51, 65, 50);

        WarpLocation target = new WarpLocation("world", 50.0, 64.0, 50.0, 0.0f, 0.0f);
        WarpLocation safe = engine.verifyOrFindSafeSpot(target, inspector);

        assertThat(safe.blockX()).isEqualTo(51);
        assertThat(safe.blockY()).isEqualTo(64);
        assertThat(safe.blockZ()).isEqualTo(50);
    }

    @Test
    @DisplayName("No safe spot in bounding box: fails closed with UnsafeTeleportDestinationException")
    void noSafeSpotThrowsUnsafeTeleportDestinationException() {
        // Void everywhere (nothing solid)
        WarpLocation target = new WarpLocation("world", 0.0, 64.0, 0.0, 0.0f, 0.0f);

        assertThatThrownBy(() -> engine.verifyOrFindSafeSpot(target, inspector))
                .isInstanceOf(UnsafeTeleportDestinationException.class)
                .hasMessageContaining("teleport.unsafe_destination");
    }

    private static final class MockBlockInspector implements SafeBlockInspector {

        private final Set<String> solidBlocks = new HashSet<>();
        private final Set<String> passableBlocks = new HashSet<>();
        private final Set<String> hazardBlocks = new HashSet<>();

        void setSolid(String world, int x, int y, int z) {
            solidBlocks.add(key(world, x, y, z));
            passableBlocks.remove(key(world, x, y, z));
        }

        void setPassable(String world, int x, int y, int z) {
            passableBlocks.add(key(world, x, y, z));
            solidBlocks.remove(key(world, x, y, z));
        }

        void setHazard(String world, int x, int y, int z) {
            hazardBlocks.add(key(world, x, y, z));
        }

        @Override
        public boolean isSolidFloor(String worldName, int x, int y, int z) {
            return solidBlocks.contains(key(worldName, x, y, z));
        }

        @Override
        public boolean isPassable(String worldName, int x, int y, int z) {
            return passableBlocks.contains(key(worldName, x, y, z));
        }

        @Override
        public boolean isHazardous(String worldName, int x, int y, int z) {
            return hazardBlocks.contains(key(worldName, x, y, z));
        }

        private String key(String world, int x, int y, int z) {
            return world + ":" + x + ":" + y + ":" + z;
        }
    }
}
