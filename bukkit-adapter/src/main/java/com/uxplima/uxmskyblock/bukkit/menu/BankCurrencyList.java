package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * The currencies an island bank keeps beside its money, one tile each in the bank window, and the window of one of
 * them, where a member puts some in or takes some out.
 *
 * <p>What the island holds of each was read with the rest of the island's values, as {@code held_<id>}, so drawing
 * a tile reads nothing.
 */
public final class BankCurrencyList {

    /** The list a menu file names, and the window of one currency. */
    static final String SOURCE = "skyblock:bank-currencies";

    public static final String FILE = "island-bank-currency";

    /** The verb a currency's tile runs: the window of that currency. */
    static final String OPEN = "skyblock:bank-currency";

    /** The value a held amount is read from, for the currency it ends with. */
    public static final String HELD = "held_";

    private final Messages messages;
    private final Supplier<List<BankCurrencySpec>> currencies;

    public BankCurrencyList(Messages messages, Supplier<List<BankCurrencySpec>> currencies) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.currencies = Objects.requireNonNull(currencies, "currencies must not be null");
    }

    /** Registers the list and the verb that opens one currency's window. */
    public void register(SkyblockMenuEngine engine) {
        Objects.requireNonNull(engine, "engine must not be null");
        engine.bindings().list(SOURCE, ctx -> rows(ctx.viewer(), ctx.arguments()));
        engine.action(
                OPEN,
                ctx -> MenuRow.handle(ctx.context(), BankCurrencySpec.class).ifPresent(spec -> {
                    Map<String, String> values = new HashMap<>(ctx.context().arguments());
                    values.putAll(valuesOf(ctx.player(), spec, ctx.context().arguments()));
                    engine.open(ctx.player(), FILE, values);
                }));
    }

    /** One row per currency the bank keeps, in order: its name, its icon, and what the island holds of it. */
    List<MenuRow> rows(Player viewer, Map<String, String> values) {
        List<MenuRow> rows = new ArrayList<>();
        for (BankCurrencySpec spec : currencies.get()) {
            Map<String, String> own = valuesOf(viewer, spec, values);
            rows.add(new MenuRow(
                    Map.of(
                            "id", spec.id(),
                            "name", own.get("currency_name"),
                            "amount", own.get("currency_amount"),
                            "icon", spec.icon()),
                    spec));
        }
        return List.copyOf(rows);
    }

    /** What a currency's window is opened with: its id, its name, and what the island holds of it. */
    private Map<String, String> valuesOf(Player viewer, BankCurrencySpec spec, Map<String, String> values) {
        return Map.of(
                "currency", spec.id(),
                "currency_name", messages.words(viewer, spec.displayName()),
                "currency_amount", values.getOrDefault(HELD + spec.id(), "0"));
    }
}
