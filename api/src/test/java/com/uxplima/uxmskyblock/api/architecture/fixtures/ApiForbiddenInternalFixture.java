package com.uxplima.uxmskyblock.api.architecture.fixtures;

import com.uxplima.uxmskyblock.core.stub.StubCoreInternalType;

public final class ApiForbiddenInternalFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubCoreInternalType leakedInternal = new StubCoreInternalType();

    public StubCoreInternalType getLeakedInternal() {
        return leakedInternal;
    }
}
