package com.uxplima.uxmskyblock.persistence.architecture.fixtures;

import com.uxplima.uxmskyblock.bukkit.stub.StubBukkitAdapterType;

public final class PersistenceForbiddenBukkitAdapterFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubBukkitAdapterType bukkitAdapterType = new StubBukkitAdapterType();

    public StubBukkitAdapterType getBukkitAdapterType() {
        return bukkitAdapterType;
    }
}
