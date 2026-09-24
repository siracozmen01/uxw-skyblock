package com.uxplima.uxmskyblock.bukkit.i18n;

import java.util.Locale;

/**
 * An amount of the island currency as a player reads it.
 *
 * <p>Money is kept in minor units, a hundred to the unit. The bank showed it as units and cents while
 * the upgrades showed the raw count, so the same price read as 500.00 in one line and 50000 in the
 * next. Every line that shows money shows it through this.
 */
public final class MoneyText {

    private MoneyText() {}

    /** {@code minorUnits} as units with two decimals, the way the bank has always shown it. */
    public static String of(long minorUnits) {
        return String.format(Locale.ROOT, "%.2f", minorUnits / 100.0);
    }
}
