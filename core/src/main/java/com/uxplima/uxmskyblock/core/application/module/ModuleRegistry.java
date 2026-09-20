package com.uxplima.uxmskyblock.core.application.module;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.module.CapabilityCollisionException;
import com.uxplima.uxmskyblock.core.domain.module.CircularDependencyException;
import com.uxplima.uxmskyblock.core.domain.module.DependencyRequirement;
import com.uxplima.uxmskyblock.core.domain.module.FailsafePluginShutdownException;
import com.uxplima.uxmskyblock.core.domain.module.IncompatibleApiException;
import com.uxplima.uxmskyblock.core.domain.module.MissingDependencyException;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import com.uxplima.uxmskyblock.core.domain.module.SemVer;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic registry managing module registration, DAG topological startup ordering,
 * circular dependency detection, capability provider collision resolution, and three-tier fail-fast lifecycle.
 */
public final class ModuleRegistry {

    private final Map<String, FeatureModule> registeredModules = new LinkedHashMap<>();
    private final Map<String, Boolean> toggles = new HashMap<>();
    private final Map<String, String> selectedProviders = new HashMap<>();
    private final List<FeatureModule> activeStartupOrder = new ArrayList<>();

    public synchronized void register(FeatureModule module) {
        Objects.requireNonNull(module, "module cannot be null");
        registeredModules.put(module.descriptor().id(), module);
    }

    public synchronized void configure(Map<String, Boolean> moduleToggles, Map<String, String> providerSelections) {
        if (moduleToggles != null) {
            toggles.putAll(moduleToggles);
        }
        if (providerSelections != null) {
            selectedProviders.putAll(providerSelections);
        }
    }

    public synchronized List<FeatureModule> resolveStartupOrder(ModuleContext context) {
        // 1. Determine active vs disabled modules based on toggles and capability selections
        Map<String, FeatureModule> candidateModules = new LinkedHashMap<>();

        // Group providers by capability
        Map<String, List<FeatureModule>> capabilityProviders = new HashMap<>();
        for (FeatureModule module : registeredModules.values()) {
            for (String capability : module.descriptor().provides()) {
                capabilityProviders
                        .computeIfAbsent(capability, k -> new ArrayList<>())
                        .add(module);
            }
        }

        // Check capability collisions
        Set<String> unselectedCollisionModuleIds = new HashSet<>();
        for (Map.Entry<String, List<FeatureModule>> entry : capabilityProviders.entrySet()) {
            String capability = entry.getKey();
            List<FeatureModule> providers = entry.getValue();
            if (providers.size() > 1) {
                String selected = selectedProviders.get(capability);
                if (selected == null || selected.isBlank()) {
                    List<String> providerIds =
                            providers.stream().map(m -> m.descriptor().id()).toList();
                    throw new CapabilityCollisionException("Multiple providers registered for capability '" + capability
                            + "': " + providerIds + ". Explicit selected-provider required in configuration.");
                }
                for (FeatureModule provider : providers) {
                    if (!provider.descriptor().id().equals(selected)) {
                        unselectedCollisionModuleIds.add(provider.descriptor().id());
                    }
                }
            }
        }

        for (FeatureModule module : registeredModules.values()) {
            String id = module.descriptor().id();
            if (unselectedCollisionModuleIds.contains(id)) {
                disableModuleSilently(module);
                continue;
            }
            Boolean toggle = toggles.get(id);
            if (toggle != null && !toggle) {
                disableModuleSilently(module);
                continue;
            }
            candidateModules.put(id, module);
        }

        // 2. Validate API compatibility & required dependencies for candidates
        for (FeatureModule module : candidateModules.values()) {
            ModuleDescriptor descriptor = module.descriptor();
            if (context != null && !descriptor.apiCompatibility().equals("*")) {
                SemVer runtimeVer = SemVer.parse(context.runtimeApiVersion());
                if (!runtimeVer.satisfies(descriptor.apiCompatibility())) {
                    throw new IncompatibleApiException("Module '" + descriptor.id() + "' requires API '"
                            + descriptor.apiCompatibility() + "' but runtime is '" + context.runtimeApiVersion() + "'");
                }
            }

            for (String reqStr : descriptor.requires()) {
                DependencyRequirement req = SemVer.parseRequirement(reqStr);
                FeatureModule dep = candidateModules.get(req.moduleId());
                if (dep == null) {
                    throw new MissingDependencyException(
                            "Module '" + descriptor.id() + "' requires '" + reqStr + "' which is missing or disabled");
                }
                if (!req.isSatisfiedBy(dep.descriptor().semVer())) {
                    throw new MissingDependencyException("Module '" + descriptor.id() + "' requires '" + reqStr
                            + "', but found incompatible version '"
                            + dep.descriptor().version() + "'");
                }
            }
        }

        // 3. Build DAG for Kahn's topological sort
        // Graph edges: dependency -> dependent (dependency must be enabled first)
        Map<String, Set<String>> dependentsMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String id : candidateModules.keySet()) {
            dependentsMap.put(id, new HashSet<>());
            inDegree.put(id, 0);
        }

        for (FeatureModule module : candidateModules.values()) {
            String id = module.descriptor().id();
            Set<String> allDeps = new HashSet<>();
            for (String reqStr : module.descriptor().requires()) {
                allDeps.add(SemVer.parseRequirement(reqStr).moduleId());
            }
            for (String optStr : module.descriptor().optional()) {
                DependencyRequirement opt = SemVer.parseRequirement(optStr);
                if (candidateModules.containsKey(opt.moduleId())
                        && opt.isSatisfiedBy(candidateModules
                                .get(opt.moduleId())
                                .descriptor()
                                .semVer())) {
                    allDeps.add(opt.moduleId());
                }
            }

            for (String depId : allDeps) {
                Set<String> dependents = dependentsMap.get(depId);
                if (dependents != null) {
                    dependents.add(id);
                }
                inDegree.merge(id, 1, Integer::sum);
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<FeatureModule> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            FeatureModule currentModule = candidateModules.get(current);
            if (currentModule != null) {
                ordered.add(currentModule);
            }

            Set<String> dependents = dependentsMap.get(current);
            if (dependents != null) {
                for (String dependent : dependents) {
                    int deg = inDegree.merge(dependent, -1, Integer::sum);
                    if (deg == 0) {
                        queue.add(dependent);
                    }
                }
            }
        }

        if (ordered.size() < candidateModules.size()) {
            // Cycle detected
            List<String> remaining = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            throw new CircularDependencyException(
                    "Cycle detected: dependency graph contains cyclic references among modules " + remaining);
        }

        return ordered;
    }

    public synchronized void enableModules(ModuleContext context) {
        List<FeatureModule> order = resolveStartupOrder(context);
        activeStartupOrder.clear();

        for (FeatureModule module : order) {
            ModuleDescriptor descriptor = module.descriptor();
            try {
                module.enable(context);
                activeStartupOrder.add(module);
            } catch (Exception e) {
                if (descriptor.coreRequired()) {
                    throw new FailsafePluginShutdownException(
                            "Tier 1 Core Required Module '" + descriptor.id() + "' failed initialization: "
                                    + e.getMessage(),
                            e);
                }

                boolean isSelectedProvider = false;
                for (String cap : descriptor.provides()) {
                    if (descriptor.id().equals(selectedProviders.get(cap))) {
                        isSelectedProvider = true;
                        break;
                    }
                }

                if (isSelectedProvider) {
                    throw new FailsafePluginShutdownException(
                            "Tier 2 Required Capability Provider '" + descriptor.id() + "' failed initialization: "
                                    + e.getMessage(),
                            e);
                }
            }
        }
    }

    public synchronized void disableModules() {
        List<FeatureModule> reverse = new ArrayList<>(activeStartupOrder);
        Collections.reverse(reverse);
        for (FeatureModule module : reverse) {
            try {
                module.disable();
            } catch (Exception expected) {
                // Continue disabling remaining modules even if one encounters an error
            }
        }
        activeStartupOrder.clear();
    }

    public synchronized Optional<FeatureModule> findModule(String id) {
        return Optional.ofNullable(registeredModules.get(id));
    }

    public synchronized List<FeatureModule> modules() {
        return List.copyOf(registeredModules.values());
    }

    public synchronized List<FeatureModule> enabledModules() {
        return List.copyOf(activeStartupOrder);
    }

    public synchronized boolean isModuleEnabled(@Nullable String id) {
        if (id == null) {
            return false;
        }
        for (FeatureModule module : activeStartupOrder) {
            if (id.equals(module.descriptor().id())) {
                return true;
            }
        }
        return false;
    }

    private static void disableModuleSilently(FeatureModule module) {
        if (module.state() != ModuleState.DISABLED) {
            try {
                module.disable();
            } catch (Exception expected) {
                // Ignore exception when disabling silently during candidate filtering
            }
        }
    }
}
