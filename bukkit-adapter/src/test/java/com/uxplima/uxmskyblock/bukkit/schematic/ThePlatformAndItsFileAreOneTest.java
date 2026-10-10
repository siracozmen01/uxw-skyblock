package com.uxplima.uxmskyblock.bukkit.schematic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The platform the plugin lays and the file it writes for an operator to change are one island: every block
 * the file holds is the block laid at that place, around the block players stand on.
 */
class ThePlatformAndItsFileAreOneTest extends MockBukkitHarness {

    private static final int TOP = 64;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"classic", "desert", "nether", "cave", "poseidon", "a_preset_of_the_operators"})
    void aPresetsPlatform(String preset) {
        assertOne(PlatformLayout.ofPreset(preset), "preset_" + preset);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IslandDimensionType.class)
    void aDimensionsPlatform(IslandDimensionType dimension) {
        assertOne(
                PlatformLayout.ofDimension(dimension),
                "dimension_" + dimension.name().toLowerCase(Locale.ROOT));
    }

    private void assertOne(PlatformLayout layout, String worldName) {
        World world = server.addSimpleWorld(worldName);
        layout.lay(world, 0, TOP, 0);
        Schematic file = layout.schematic(4671);

        assertThat(file.offset())
                .describedAs("saved around the centre of the top")
                .isEqualTo(new Vec3i(-2, -2, -2));
        for (int x = 0; x < file.width(); x++) {
            for (int y = 0; y < file.height(); y++) {
                for (int z = 0; z < file.length(); z++) {
                    int wx = x + file.offset().x();
                    int wy = TOP + y + file.offset().y();
                    int wz = z + file.offset().z();
                    assertThat(file.blockAt(x, y, z))
                            .describedAs("(%d, %d, %d)", wx, wy, wz)
                            .isEqualTo(
                                    world.getBlockAt(wx, wy, wz).getBlockData().getAsString());
                }
            }
        }
    }
}
