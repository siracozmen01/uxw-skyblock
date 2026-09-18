package com.uxplima.uxmskyblock.bukkit.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration holder for internal feature module enablement toggles
 * and explicit capability selected-provider bindings loaded from modules.conf.
 */
public record ModuleSettingsConfiguration(Map<String, Boolean> moduleToggles, Map<String, String> selectedProviders) {

    public ModuleSettingsConfiguration {
        Objects.requireNonNull(moduleToggles, "moduleToggles cannot be null");
        Objects.requireNonNull(selectedProviders, "selectedProviders cannot be null");
        moduleToggles = Map.copyOf(moduleToggles);
        selectedProviders = Map.copyOf(selectedProviders);
    }

    public static ModuleSettingsConfiguration of(
            Map<String, Boolean> moduleToggles, Map<String, String> selectedProviders) {
        return new ModuleSettingsConfiguration(moduleToggles, selectedProviders);
    }

    public static ModuleSettingsConfiguration empty() {
        return new ModuleSettingsConfiguration(Map.of(), Map.of());
    }

    public boolean isModuleEnabled(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId cannot be null");
        return moduleToggles.getOrDefault(moduleId, Boolean.TRUE);
    }

    public Optional<String> selectedProvider(String capability) {
        Objects.requireNonNull(capability, "capability cannot be null");
        return Optional.ofNullable(selectedProviders.get(capability));
    }

    public static ModuleSettingsConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode cannot be null");

        Map<String, Boolean> toggles = new HashMap<>();
        ConfigurationNode modulesNode = rootNode.node("modules");
        if (!modulesNode.virtual() && modulesNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    modulesNode.childrenMap().entrySet()) {
                String moduleId = String.valueOf(entry.getKey()).trim();
                boolean enabled = entry.getValue().getBoolean(true);
                toggles.put(moduleId, enabled);
            }
        }

        Map<String, String> providers = new HashMap<>();
        ConfigurationNode capabilitiesNode = rootNode.node("capabilities");
        if (!capabilitiesNode.virtual() && capabilitiesNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    capabilitiesNode.childrenMap().entrySet()) {
                String capability = String.valueOf(entry.getKey()).trim();
                ConfigurationNode capNode = entry.getValue();
                String selected = capNode.node("selected-provider").getString();
                if (selected != null && !selected.isBlank()) {
                    providers.put(capability, selected.trim());
                }
            }
        }

        return new ModuleSettingsConfiguration(toggles, providers);
    }
}
