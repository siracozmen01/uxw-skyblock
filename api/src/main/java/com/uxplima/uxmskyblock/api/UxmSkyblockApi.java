package com.uxplima.uxmskyblock.api;

/**
 * Root developer API surface for UXPLIMA Skyblock.
 */
public interface UxmSkyblockApi {

    UxmSkyblockQuery query();

    UxmSkyblockActions actions();

    static UxmSkyblockApi getInstance() {
        return UxmSkyblockApiProvider.get();
    }
}
