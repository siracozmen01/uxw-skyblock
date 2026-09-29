package com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** A shop class that moves an island's balance itself, around the bank service, which the rule must refuse. */
public final class BankOutsideItsProtocolFixture {

    private final IslandBankPort bank;

    public BankOutsideItsProtocolFixture(IslandBankPort bank) {
        this.bank = bank;
    }

    public BankTransactionOutcome charge(IslandId island, long amountMinorUnits) {
        return bank.executeTransaction(
                island,
                UUID.randomUUID(),
                "COINS",
                2,
                -amountMinorUnits,
                "shop",
                "node",
                1L,
                1L,
                UUID.randomUUID(),
                "k");
    }
}
