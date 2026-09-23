package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.world.VoidIslandGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /is doctor} on the booted plugin, one line per check and a verdict. */
class TheDoctorSaysHowThePluginIsTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("On an empty island world with no economy, every check answers and nothing fails")
    void everyCheckAnswers() {
        addWorldMadeBy("world", new VoidIslandGenerator());
        MockBukkit.load(UxMSkyblockPlugin.class);
        PlayerMock admin = createPlayer("Operator");
        admin.setOp(true);

        List<String> said = run(admin);

        assertThat(said).anyMatch(line -> line.contains("OK storage"));
        assertThat(said).anyMatch(line -> line.contains("OK windows") && line.contains("menu files read"));
        assertThat(said).anyMatch(line -> line.contains("WARN placeholders"));
        assertThat(said).anyMatch(line -> line.contains("OK island world"));
        assertThat(said).anyMatch(line -> line.contains("WARN economy"));
        assertThat(said).anyMatch(line -> line.contains("Nothing needs an operator"));
    }

    @Test
    @DisplayName("A missing island world fails, and the verdict says an operator is needed")
    void aMissingWorldFails() {
        addWorldMadeBy("lobby", null);
        MockBukkit.load(UxMSkyblockPlugin.class);
        PlayerMock admin = createPlayer("Operator");
        admin.setOp(true);

        List<String> said = run(admin);

        assertThat(said).anyMatch(line -> line.contains("FAIL island world"));
        assertThat(said).anyMatch(line -> line.contains("needs an operator"));
    }

    @Test
    @DisplayName("A player without the permission is not given the doctor")
    void aPlayerWithoutThePermissionIsRefused() {
        addWorldMadeBy("world", new VoidIslandGenerator());
        MockBukkit.load(UxMSkyblockPlugin.class);
        PlayerMock player = createPlayer("Player");

        assertThat(run(player)).noneMatch(line -> line.contains("storage"));
    }

    /** Runs the doctor and collects what it says, ticking the server so the queued lines arrive. */
    private List<String> run(PlayerMock sender) {
        sender.performCommand("is doctor");
        List<String> lines = new ArrayList<>();
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && lines.stream().noneMatch(line -> line.contains("operator"))) {
            server.getScheduler().performTicks(1);
            Component next;
            while ((next = sender.nextComponentMessage()) != null) {
                lines.add(PLAIN.serialize(next));
            }
        }
        return lines;
    }
}
