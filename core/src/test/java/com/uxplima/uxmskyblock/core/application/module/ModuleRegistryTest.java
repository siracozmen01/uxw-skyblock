package com.uxplima.uxmskyblock.core.application.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.module.CapabilityCollisionException;
import com.uxplima.uxmskyblock.core.domain.module.CircularDependencyException;
import com.uxplima.uxmskyblock.core.domain.module.FailsafePluginShutdownException;
import com.uxplima.uxmskyblock.core.domain.module.MissingDependencyException;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModuleRegistryTest {

    private ModuleRegistry registry;
    private TestModuleContext context;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistry();
        context = new TestModuleContext("1.0.0");
    }

    @Test
    @DisplayName("resolves startup order according to dependency DAG topological sort")
    void resolvesStartupOrderAccordingToDag() {
        List<String> order = new ArrayList<>();

        TestFeatureModule core = new TestFeatureModule(
                new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of("island-core"), ">=1.0.0", true),
                order);
        TestFeatureModule bank = new TestFeatureModule(
                new ModuleDescriptor(
                        "bank", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-bank"), ">=1.0.0", false),
                order);
        TestFeatureModule missions = new TestFeatureModule(
                new ModuleDescriptor(
                        "missions",
                        "1.0.0",
                        List.of("core >= 1.0.0", "bank >= 1.0.0"),
                        List.of(),
                        List.of("island-missions"),
                        ">=1.0.0",
                        false),
                order);

        // Register in reverse / arbitrary order
        registry.register(missions);
        registry.register(core);
        registry.register(bank);

        registry.enableModules(context);

        assertThat(order).containsExactly("core", "bank", "missions");
        assertThat(core.state()).isEqualTo(ModuleState.ENABLED);
        assertThat(bank.state()).isEqualTo(ModuleState.ENABLED);
        assertThat(missions.state()).isEqualTo(ModuleState.ENABLED);
    }

    @Test
    @DisplayName("fails fast with CircularDependencyException when dependency cycle is detected")
    void detectsCircularDependencies() {
        List<String> order = new ArrayList<>();
        TestFeatureModule modA = new TestFeatureModule(
                new ModuleDescriptor("modA", "1.0.0", List.of("modB >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                order);
        TestFeatureModule modB = new TestFeatureModule(
                new ModuleDescriptor("modB", "1.0.0", List.of("modA >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                order);

        registry.register(modA);
        registry.register(modB);

        assertThatThrownBy(() -> registry.enableModules(context))
                .isInstanceOf(CircularDependencyException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    @DisplayName("fails fast with MissingDependencyException when required dependency is missing")
    void detectsMissingRequiredDependency() {
        TestFeatureModule missions = new TestFeatureModule(
                new ModuleDescriptor(
                        "missions", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                new ArrayList<>());

        registry.register(missions);

        assertThatThrownBy(() -> registry.enableModules(context))
                .isInstanceOf(MissingDependencyException.class)
                .hasMessageContaining("core");
    }

    @Test
    @DisplayName("fails fast with MissingDependencyException when dependency version is incompatible")
    void detectsIncompatibleDependencyVersion() {
        TestFeatureModule core = new TestFeatureModule(
                new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of(), ">=1.0.0", true),
                new ArrayList<>());
        TestFeatureModule missions = new TestFeatureModule(
                new ModuleDescriptor(
                        "missions", "1.0.0", List.of("core >= 2.0.0"), List.of(), List.of(), ">=1.0.0", false),
                new ArrayList<>());

        registry.register(core);
        registry.register(missions);

        assertThatThrownBy(() -> registry.enableModules(context))
                .isInstanceOf(MissingDependencyException.class)
                .hasMessageContaining("core");
    }

    @Test
    @DisplayName("starts successfully in degraded mode when optional dependency is missing")
    void startsSuccessfullyWhenOptionalDependencyMissing() {
        List<String> order = new ArrayList<>();
        TestFeatureModule missions = new TestFeatureModule(
                new ModuleDescriptor(
                        "missions", "1.0.0", List.of(), List.of("bank >= 1.0.0"), List.of(), ">=1.0.0", false),
                order);

        registry.register(missions);
        registry.enableModules(context);

        assertThat(missions.state()).isEqualTo(ModuleState.ENABLED);
        assertThat(order).containsExactly("missions");
    }

    @Test
    @DisplayName(
            "enforces CapabilityCollisionException when multiple providers register for same capability without selection")
    void detectsCapabilityCollisionWithoutSelectedProvider() {
        TestFeatureModule bank1 = new TestFeatureModule(
                new ModuleDescriptor("bank1", "1.0.0", List.of(), List.of(), List.of("island-bank"), ">=1.0.0", false),
                new ArrayList<>());
        TestFeatureModule bank2 = new TestFeatureModule(
                new ModuleDescriptor("bank2", "1.0.0", List.of(), List.of(), List.of("island-bank"), ">=1.0.0", false),
                new ArrayList<>());

        registry.register(bank1);
        registry.register(bank2);

        assertThatThrownBy(() -> registry.enableModules(context))
                .isInstanceOf(CapabilityCollisionException.class)
                .hasMessageContaining("island-bank");
    }

    @Test
    @DisplayName("permits multiple providers when selected-provider is explicitly declared in configuration")
    void permitsMultipleProvidersWithExplicitSelection() {
        TestFeatureModule bank1 = new TestFeatureModule(
                new ModuleDescriptor("bank1", "1.0.0", List.of(), List.of(), List.of("island-bank"), ">=1.0.0", false),
                new ArrayList<>());
        TestFeatureModule bank2 = new TestFeatureModule(
                new ModuleDescriptor("bank2", "1.0.0", List.of(), List.of(), List.of("island-bank"), ">=1.0.0", false),
                new ArrayList<>());

        registry.register(bank1);
        registry.register(bank2);
        registry.configure(Map.of(), Map.of("island-bank", "bank2"));

        registry.enableModules(context);

        assertThat(bank2.state()).isEqualTo(ModuleState.ENABLED);
        assertThat(bank1.state()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("omits disabled modules and verifies zero-resource inactive footprint")
    void omitsDisabledModules() {
        List<String> order = new ArrayList<>();
        TestFeatureModule core = new TestFeatureModule(
                new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of(), ">=1.0.0", true), order);
        TestFeatureModule seasons = new TestFeatureModule(
                new ModuleDescriptor(
                        "seasons", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                order);

        registry.register(core);
        registry.register(seasons);
        registry.configure(Map.of("seasons", false), Map.of());

        registry.enableModules(context);

        assertThat(core.state()).isEqualTo(ModuleState.ENABLED);
        assertThat(seasons.state()).isEqualTo(ModuleState.DISABLED);
        assertThat(order).containsExactly("core");
    }

    @Test
    @DisplayName("Tier 1 Core Required Module failure aborts boot with FailsafePluginShutdownException")
    void tier1FailureAbortsPluginStartup() {
        TestFeatureModule faultyCore =
                new TestFeatureModule(
                        new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of(), ">=1.0.0", true),
                        new ArrayList<>()) {
                    @Override
                    public void enable(ModuleContext ctx) {
                        throw new IllegalStateException("Simulated core database initialization failure");
                    }
                };

        registry.register(faultyCore);

        assertThatThrownBy(() -> registry.enableModules(context))
                .isInstanceOf(FailsafePluginShutdownException.class)
                .hasMessageContaining("core");
    }

    @Test
    @DisplayName("Tier 3 Optional Module failure marks module FAILED without halting server")
    void tier3FailureMarksModuleFailedWithoutHalting() {
        TestFeatureModule core = new TestFeatureModule(
                new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of(), ">=1.0.0", true),
                new ArrayList<>());
        TestFeatureModule faultyOptional =
                new TestFeatureModule(
                        new ModuleDescriptor(
                                "discord", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                        new ArrayList<>()) {
                    @Override
                    public void enable(ModuleContext ctx) {
                        this.state = ModuleState.FAILED;
                        throw new RuntimeException("Discord token missing");
                    }
                };

        registry.register(core);
        registry.register(faultyOptional);

        registry.enableModules(context);

        assertThat(core.state()).isEqualTo(ModuleState.ENABLED);
        assertThat(faultyOptional.state()).isEqualTo(ModuleState.FAILED);
    }

    @Test
    @DisplayName("disables modules in exact reverse order of startup")
    void disablesModulesInReverseOrder() {
        List<String> order = new ArrayList<>();
        TestFeatureModule core = new TestFeatureModule(
                new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of(), ">=1.0.0", true), order);
        TestFeatureModule bank = new TestFeatureModule(
                new ModuleDescriptor("bank", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                order);

        registry.register(core);
        registry.register(bank);

        registry.enableModules(context);
        order.clear();

        registry.disableModules();

        assertThat(order).containsExactly("bank-disabled", "core-disabled");
        assertThat(bank.state()).isEqualTo(ModuleState.DISABLED);
        assertThat(core.state()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("isModuleEnabled reflects true lifecycle state before, during, and after module activation")
    void isModuleEnabledReflectsLifecycle() {
        TestFeatureModule core = new TestFeatureModule(
                new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of(), ">=1.0.0", true),
                new ArrayList<>());
        TestFeatureModule missions = new TestFeatureModule(
                new ModuleDescriptor(
                        "missions", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of(), ">=1.0.0", false),
                new ArrayList<>());

        registry.register(core);
        registry.register(missions);
        registry.configure(Map.of("missions", false), Map.of());

        assertThat(registry.isModuleEnabled("core")).isFalse();
        assertThat(registry.isModuleEnabled("missions")).isFalse();
        assertThat(registry.isModuleEnabled(null)).isFalse();

        registry.enableModules(context);

        assertThat(registry.isModuleEnabled("core")).isTrue();
        assertThat(registry.isModuleEnabled("missions")).isFalse();
        assertThat(registry.isModuleEnabled("nonexistent")).isFalse();

        registry.disableModules();

        assertThat(registry.isModuleEnabled("core")).isFalse();
        assertThat(registry.isModuleEnabled("missions")).isFalse();
    }

    private static class TestFeatureModule implements FeatureModule {
        private final ModuleDescriptor descriptor;
        private final List<String> log;
        protected ModuleState state = ModuleState.UNINITIALIZED;

        TestFeatureModule(ModuleDescriptor descriptor, List<String> log) {
            this.descriptor = descriptor;
            this.log = log;
        }

        @Override
        public ModuleDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void enable(ModuleContext ctx) {
            this.state = ModuleState.ENABLED;
            log.add(descriptor.id());
        }

        @Override
        public void disable() {
            boolean wasEnabled = (this.state == ModuleState.ENABLED);
            this.state = ModuleState.DISABLED;
            if (wasEnabled) {
                log.add(descriptor.id() + "-disabled");
            }
        }

        @Override
        public ModuleState state() {
            return state;
        }
    }

    private static class TestModuleContext implements ModuleContext {
        private final String apiVersion;
        private final java.util.Map<Class<?>, Object> services = new java.util.HashMap<>();

        TestModuleContext(String apiVersion) {
            this.apiVersion = apiVersion;
        }

        @Override
        public String runtimeApiVersion() {
            return apiVersion;
        }

        @Override
        public <T> void registerService(Class<T> serviceClass, T instance) {
            services.put(serviceClass, instance);
        }

        @Override
        public <T> Optional<T> findService(Class<T> serviceClass) {
            return Optional.ofNullable(serviceClass.cast(services.get(serviceClass)));
        }
    }
}
