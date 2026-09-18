package com.uxplima.uxmskyblock.core.application.module;

import java.util.Optional;

/**
 * Execution context provided to FeatureModules during initialization.
 */
public interface ModuleContext {

    /**
     * Current running API version string.
     */
    String runtimeApiVersion();

    /**
     * Registers a domain service instance.
     */
    <T> void registerService(Class<T> serviceClass, T instance);

    /**
     * Looks up an existing domain service instance, returning Optional.empty() if missing or degraded.
     */
    <T> Optional<T> findService(Class<T> serviceClass);
}
