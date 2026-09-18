package com.uxplima.uxmskyblock.core.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PermissionRegistryTest {

    private PermissionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PermissionRegistry();
    }

    @Test
    @DisplayName("PermissionRegistryCollisionTest: duplicate PermissionKey names fail fast")
    void permissionRegistryCollisionTest() {
        PermissionKey key1 = PermissionKey.of("uxm:block.break");
        PermissionKey duplicate = PermissionKey.of("uxm:block.break");

        PermissionId id1 = registry.register(key1);
        assertThat(id1.index()).isEqualTo(0);

        assertThatThrownBy(() -> registry.register(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate permission key registration detected");
    }

    @Test
    @DisplayName("CompiledPermissionStableKeyRoundTripTest: keys compile to dense indexes and round-trip without drift")
    void compiledPermissionStableKeyRoundTripTest() {
        StandardPermissions.registerAll(registry);

        Set<PermissionKey> allKeys = StandardPermissions.allKeys();
        assertThat(registry.size()).isEqualTo(allKeys.size());

        for (PermissionKey key : allKeys) {
            PermissionId id = registry.getIdOrThrow(key);
            assertThat(id.index()).isBetween(0, registry.size() - 1);

            PermissionKey resolved = registry.findKey(id).orElseThrow();
            assertThat(resolved).isEqualTo(key);
            assertThat(resolved.qualifiedName()).isEqualTo(key.qualifiedName());
        }

        PermissionSet set =
                registry.compileSet(Set.of(StandardPermissions.BLOCK_BREAK, StandardPermissions.BANK_DEPOSIT));
        assertThat(set.cardinality()).isEqualTo(2);

        Set<PermissionKey> resolvedSet = registry.resolveSet(set);
        assertThat(resolvedSet)
                .containsExactlyInAnyOrder(StandardPermissions.BLOCK_BREAK, StandardPermissions.BANK_DEPOSIT);
    }

    @Test
    @DisplayName("PermissionKey enforces strict namespace and value formatting")
    void permissionKeyFormattingValidation() {
        assertThat(PermissionKey.of("custom_plugin:spawner.boost").namespace()).isEqualTo("custom_plugin");
        assertThat(PermissionKey.of("custom_plugin:spawner.boost").value()).isEqualTo("spawner.boost");

        assertThatThrownBy(() -> PermissionKey.of("invalid_no_colon")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionKey.of(":empty_namespace")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionKey.of("empty_value:")).isInstanceOf(IllegalArgumentException.class);
    }
}
