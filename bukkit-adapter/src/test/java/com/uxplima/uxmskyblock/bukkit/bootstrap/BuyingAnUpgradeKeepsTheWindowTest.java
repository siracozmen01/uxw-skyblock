package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A tier bought from the upgrades window leaves the window up.
 *
 * <p>The button closed the window before it bought anything, so a player who wanted two tiers opened
 * the island menu twice, and a refusal was read with the prices already gone from the screen.
 */
class BuyingAnUpgradeKeepsTheWindowTest extends MockBukkitHarness {

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("The buy button runs the purchase under the operator's names and leaves the window up")
    void theWindowStaysUp() {
        PlayerMock ada = createPlayer("Ada");
        List<String> typed = new ArrayList<>();
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-upgrades.conf");
        SkyblockMenuVerbs.register(engine, Messages.bundled(), line -> {
            typed.add(line);
            return "unregistered " + line;
        });
        engine.install();
        engine.open(ada, "island-upgrades", Map.of());
        settle(() -> engine.showing(ada, "island-upgrades"));

        ShippedTemplates.click(
                engine, "skyblock:buy-upgrade", MenuContext.of(ada, null, 0), ada, ClickKind.LEFT, "island_size");
        for (int tick = 0; tick < 5; tick++) {
            server.getScheduler().performOneTick();
            server.getScheduler().waitAsyncTasksFinished();
        }

        assertThat(typed).containsExactly("upgrades buy island_size");
        assertThat(engine.showing(ada, "island-upgrades")).isTrue();
    }

    private void settle(java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            if (done.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the window never opened");
    }
}
