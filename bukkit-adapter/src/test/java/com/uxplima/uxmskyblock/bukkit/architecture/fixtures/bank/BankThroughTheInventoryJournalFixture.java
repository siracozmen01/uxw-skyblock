package com.uxplima.uxmskyblock.bukkit.architecture.fixtures.bank;

import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;

/** A bank class that routes a balance through the inventory journal, which the rule must refuse. */
public final class BankThroughTheInventoryJournalFixture {

    private final InventoryMutationJournalPort journal;

    public BankThroughTheInventoryJournalFixture(InventoryMutationJournalPort journal) {
        this.journal = journal;
    }

    public InventoryMutationJournalPort journal() {
        return journal;
    }
}
