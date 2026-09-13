package com.uxplima.uxmskyblock.api.architecture.fixtures;

import org.bukkit.stub.StubBukkitType;

public final class ApiForbiddenPlatformFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubBukkitType leakedPlatform = new StubBukkitType();

    public StubBukkitType getLeakedPlatform() {
        return leakedPlatform;
    }
}
