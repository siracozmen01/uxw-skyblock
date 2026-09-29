package com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop;

import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;

/** A shop class that leaves a sale for the ambient checkpoint to write, which the rule must refuse. */
public final class ShopThroughTheCheckpointFixture {

    private final ProfileInventoryCheckpointPort checkpoints;

    public ShopThroughTheCheckpointFixture(ProfileInventoryCheckpointPort checkpoints) {
        this.checkpoints = checkpoints;
    }

    public ProfileInventoryCheckpointPort checkpoints() {
        return checkpoints;
    }
}
