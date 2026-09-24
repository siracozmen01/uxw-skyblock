package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.SpecRefs;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.InMemoryVaultEconomy;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Every verb and condition a shipped menu names is one the running plugin answers.
 *
 * <p>The upgrade menu named its verb as {@code skyblock:buy-upgrade island_size}. The engine reads the
 * value after a colon, not after a space, so it looked for a verb called {@code skyblock} and every
 * upgrade tile did nothing when clicked, without a word. This loads the plugin as a server would and
 * reads each menu it loaded against the verbs it registered.
 *
 * <p>An {@code input:} or {@code confirm:} step is a prompt the engine runs before the verbs after it,
 * not a verb, and the library's own check names it anyway; those two are left out here.
 */
class EveryShippedMenuVerbIsAnsweredTest extends MockBukkitHarness {

    @Test
    @DisplayName("Every action and condition in every shipped menu resolves to a registered one")
    void everyVerbIsAnswered() {
        addWorldMadeBy("world", null);
        InMemoryVaultEconomy.install(server, 1_000.0);
        UxMSkyblockPlugin plugin = MockBukkit.load(UxMSkyblockPlugin.class);
        SkyblockMenuEngine engine = plugin.bootstrap().integrationWiring().menuEngine();
        assertThat(engine.loadedSpecs()).isNotEmpty();

        List<String> unanswered = new ArrayList<>();
        for (String id : engine.loadedSpecs()) {
            MenuSpec spec = engine.spec(id).orElseThrow();
            for (String action : SpecRefs.unknownActions(
                    spec, verb -> engine.bindings().action(verb).isPresent())) {
                if (!action.startsWith("input:") && !action.startsWith("confirm:")) {
                    unanswered.add(id + " action " + action);
                }
            }
            for (String condition : SpecRefs.unknownConditions(
                    spec, test -> engine.bindings().condition(test).isPresent())) {
                unanswered.add(id + " condition " + condition);
            }
        }

        assertThat(unanswered).isEmpty();
    }
}
