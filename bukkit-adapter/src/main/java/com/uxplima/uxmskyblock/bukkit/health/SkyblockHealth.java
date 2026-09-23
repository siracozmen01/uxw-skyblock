package com.uxplima.uxmskyblock.bukkit.health;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.uxplima.uxmlib.health.HealthCheck;
import com.uxplima.uxmlib.health.HealthResult;

/**
 * Everything that can be wrong with this plugin on a running server, each answering in one line.
 *
 * <p>What the plugin could not read or could not reach at startup went to the console, once. An
 * operator who was not watching it had no way to ask again, and each of these fails in a way that
 * looks like something else: a bank that refuses every move, a menu that does not open, a scoreboard
 * line that reads nothing, an island pasted into a hillside.
 *
 * <p>Each check takes what it reads as a supplier, so the rule lives here and the wiring stays where
 * the plugin is put together.
 */
public final class SkyblockHealth {

    private SkyblockHealth() {}

    /** Whether the database answers, which is the failure behind every other one. */
    public static HealthCheck storage(BooleanSupplier answers) {
        return check(
                "storage",
                () -> answers.getAsBoolean()
                        ? HealthResult.ok("the database answered")
                        : HealthResult.fail("the database did not answer: islands, banks and profiles cannot"
                                + " be read or written"));
    }

    /** How many menu files were read. None means no menu opens. */
    public static HealthCheck windows(IntSupplier read) {
        return check("windows", () -> {
            int count = read.getAsInt();
            return count == 0
                    ? HealthResult.fail("no menu file was read: /is and every menu it opens show nothing")
                    : HealthResult.ok(count + " menu files read");
        });
    }

    /** Whether PlaceholderAPI took the expansion. A warning: only other plugins' lines depend on it. */
    public static HealthCheck placeholders(BooleanSupplier published) {
        return check(
                "placeholders",
                () -> published.getAsBoolean()
                        ? HealthResult.ok("published to PlaceholderAPI")
                        : HealthResult.warn("PlaceholderAPI is not on this server, so %skyblock_...% reads"
                                + " nothing in other plugins; skyblock itself works"));
    }

    /** Whether the island world is loaded and empty, read through the startup world check. */
    public static HealthCheck islandWorld(Supplier<Optional<String>> problem, BooleanSupplier loaded) {
        return check("island world", () -> {
            Optional<String> said = problem.get();
            if (said.isEmpty()) {
                return HealthResult.ok("loaded and empty");
            }
            return loaded.getAsBoolean() ? HealthResult.warn(said.get()) : HealthResult.fail(said.get());
        });
    }

    /** Whether an economy plugin answers. Without one the island bank refuses every move. */
    public static HealthCheck economy(BooleanSupplier available) {
        return check(
                "economy",
                () -> available.getAsBoolean()
                        ? HealthResult.ok("an economy plugin answers")
                        : HealthResult.warn("no economy plugin answers, so the island bank refuses every deposit"
                                + " and withdrawal"));
    }

    private static HealthCheck check(String name, Supplier<HealthResult> answer) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HealthResult check() {
                return answer.get();
            }
        };
    }
}
