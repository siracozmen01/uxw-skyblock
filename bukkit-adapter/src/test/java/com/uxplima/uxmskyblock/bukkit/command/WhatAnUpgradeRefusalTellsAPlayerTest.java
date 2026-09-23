package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome.PaymentFailed;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome.PaymentFailed.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * An upgrade that was not bought is told from the catalogue, never in the service's own words.
 *
 * <p>The refusal put its reason in front of the player as it stood: an English sentence with an
 * island id in it, or, when the bank refused, the Java record the bank answered with. The kind of
 * refusal picks the line now, and a bank refusal reads the way the bank and the shop say it.
 */
class WhatAnUpgradeRefusalTellsAPlayerTest {

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("Every kind of refusal has a line of its own that carries no reason")
    void everyKindHasALine(Kind kind) throws Exception {
        String line = IslandUpgradeCommands.refusalLine(new PaymentFailed(kind, "island 1234 said no"));

        assertThat(line).isNotBlank();
        for (String language : new String[] {"en", "tr"}) {
            assertThat(lineText(language, line))
                    .describedAs("%s in %s", line, language)
                    .doesNotContain("<reason>");
        }
    }

    @Test
    @DisplayName("A bank refusal reads the way the bank itself says it")
    void aBankRefusalReadsLikeTheBank() {
        BankTransactionOutcome elsewhere = new BankTransactionOutcome.AuthorityRejected(
                BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY, "x");
        BankTransactionOutcome stale = new BankTransactionOutcome.StaleVersion(3, 4);

        assertThat(IslandUpgradeCommands.refusalLine(new PaymentFailed(Kind.BANK_REFUSED, "x", elsewhere)))
                .isEqualTo("bank.refused_elsewhere");
        assertThat(IslandUpgradeCommands.refusalLine(new PaymentFailed(Kind.BANK_REFUSED, "x", stale)))
                .isEqualTo("bank.busy");
    }

    /** The text of {@code key} in a bundled catalogue, found by its section and its name. */
    private String lineText(String language, String key) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/messages_" + language + ".conf")) {
            String catalogue = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            String section = key.substring(0, key.indexOf('.'));
            String name = key.substring(key.indexOf('.') + 1);
            String block = catalogue.substring(catalogue.indexOf("\n" + section + " {"));
            block = block.substring(0, block.indexOf("\n}"));
            return block.lines()
                    .filter(l -> l.trim().startsWith(name + " ="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(key + " is missing from " + language));
        }
    }
}
