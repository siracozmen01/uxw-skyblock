package com.uxplima.uxmskyblock.bukkit.module;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.module.ModuleContext;

/**
 * Bukkit runtime implementation of {@link ModuleContext} providing thread-safe service lookup
 * and API version reporting.
 */
public final class BukkitModuleContext implements ModuleContext {

    private final String runtimeApiVersion;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public BukkitModuleContext(String runtimeApiVersion) {
        this.runtimeApiVersion = Objects.requireNonNull(runtimeApiVersion, "runtimeApiVersion must not be null");
    }

    @Override
    public String runtimeApiVersion() {
        return runtimeApiVersion;
    }

    @Override
    public <T> void registerService(Class<T> serviceClass, T instance) {
        Objects.requireNonNull(serviceClass, "serviceClass must not be null");
        Objects.requireNonNull(instance, "instance must not be null");
        services.put(serviceClass, instance);
    }

    @Override
    public <T> Optional<T> findService(Class<T> serviceClass) {
        Objects.requireNonNull(serviceClass, "serviceClass must not be null");
        return Optional.ofNullable(serviceClass.cast(services.get(serviceClass)));
    }
}
