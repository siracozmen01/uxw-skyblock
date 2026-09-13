package com.uxplima.uxmskyblock.bukkit.bootstrap.fixtures;

import com.uxplima.uxmskyblock.persistence.stub.StubPersistenceAdapterType;

public final class BukkitBootstrapAllowedPersistenceWiringFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubPersistenceAdapterType persistenceAdapter = new StubPersistenceAdapterType();

    public StubPersistenceAdapterType getPersistenceAdapter() {
        return persistenceAdapter;
    }
}
