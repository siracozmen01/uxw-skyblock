package com.uxplima.uxmskyblock.core.application.dimension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMode;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionAccessResult;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandDimensionServiceTest {

    private IslandUpgradeStoragePort upgradeStoragePort;
    private IslandDimensionService service;
    private IslandId islandId;

    private static final UpgradeId NETHER_UPGRADE = new UpgradeId("island_nether");
    private static final UpgradeId END_UPGRADE = new UpgradeId("island_end");

    @BeforeEach
    void setUp() {
        upgradeStoragePort = mock(IslandUpgradeStoragePort.class);
        islandId = new IslandId(UUID.randomUUID());

        Map<IslandDimensionType, DimensionMapping> mappings = Map.of(
                IslandDimensionType.OVERWORLD,
                new DimensionMapping(
                        IslandDimensionType.OVERWORLD, DimensionMode.PRIVATE_ISLAND, "skyblock_world", null, null),
                IslandDimensionType.NETHER,
                new DimensionMapping(
                        IslandDimensionType.NETHER,
                        DimensionMode.PRIVATE_ISLAND,
                        "skyblock_nether",
                        NETHER_UPGRADE,
                        "island_nether"),
                IslandDimensionType.THE_END,
                new DimensionMapping(
                        IslandDimensionType.THE_END,
                        DimensionMode.SHARED_WORLD,
                        "skyblock_the_end",
                        END_UPGRADE,
                        null));

        service = new IslandDimensionService(upgradeStoragePort, mappings);
    }

    @Test
    @DisplayName("Constructor should reject null parameters")
    @SuppressWarnings("NullAway")
    void constructorNullValidation() {
        assertThatThrownBy(() -> new IslandDimensionService(null, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IslandDimensionService(upgradeStoragePort, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("checkAccess should return Disabled when mapping is missing or mode is DISABLED")
    void checkAccessDisabled() {
        IslandDimensionService emptyService = new IslandDimensionService(upgradeStoragePort, Map.of());
        assertThat(emptyService.checkAccess(islandId, IslandDimensionType.NETHER))
                .isInstanceOf(IslandDimensionAccessResult.Disabled.class);

        IslandDimensionService disabledService = new IslandDimensionService(
                upgradeStoragePort,
                Map.of(
                        IslandDimensionType.NETHER,
                        new DimensionMapping(
                                IslandDimensionType.NETHER, DimensionMode.DISABLED, "nether", null, null)));

        assertThat(disabledService.checkAccess(islandId, IslandDimensionType.NETHER))
                .isInstanceOf(IslandDimensionAccessResult.Disabled.class);
    }

    @Test
    @DisplayName("checkAccess should return Locked when required upgrade tier is 0")
    void checkAccessLockedWhenUpgradeMissing() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(0);

        IslandDimensionAccessResult result = service.checkAccess(islandId, IslandDimensionType.NETHER);
        assertThat(result).isInstanceOf(IslandDimensionAccessResult.Locked.class);

        IslandDimensionAccessResult.Locked locked = (IslandDimensionAccessResult.Locked) result;
        assertThat(locked.dimensionType()).isEqualTo(IslandDimensionType.NETHER);
        assertThat(locked.requiredUpgrade()).isEqualTo(NETHER_UPGRADE);
        assertThat(service.canAccessDimension(islandId, IslandDimensionType.NETHER))
                .isFalse();
    }

    @Test
    @DisplayName("checkAccess should return Allowed with schematicRequired true on first visit for PRIVATE_ISLAND")
    void checkAccessAllowedPrivateIslandInitial() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);

        IslandDimensionAccessResult result = service.checkAccess(islandId, IslandDimensionType.NETHER);
        assertThat(result).isInstanceOf(IslandDimensionAccessResult.Allowed.class);

        IslandDimensionAccessResult.Allowed allowed = (IslandDimensionAccessResult.Allowed) result;
        assertThat(allowed.dimensionType()).isEqualTo(IslandDimensionType.NETHER);
        assertThat(allowed.mode()).isEqualTo(DimensionMode.PRIVATE_ISLAND);
        assertThat(allowed.targetWorld()).isEqualTo("skyblock_nether");
        assertThat(allowed.schematicRequired()).isTrue();
        assertThat(service.canAccessDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();
    }

    @Test
    @DisplayName("Marking dimension as generated sets schematicRequired to false")
    void checkAccessAfterMarkingGenerated() {
        when(upgradeStoragePort.getUpgradeTier(islandId, NETHER_UPGRADE)).thenReturn(1);

        assertThat(service.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isFalse();
        service.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        assertThat(service.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();

        IslandDimensionAccessResult result = service.checkAccess(islandId, IslandDimensionType.NETHER);
        assertThat(result).isInstanceOf(IslandDimensionAccessResult.Allowed.class);

        IslandDimensionAccessResult.Allowed allowed = (IslandDimensionAccessResult.Allowed) result;
        assertThat(allowed.schematicRequired()).isFalse();
    }

    @Test
    @DisplayName("SHARED_WORLD should never require schematic generation")
    void checkAccessSharedWorldNeverRequiresSchematic() {
        when(upgradeStoragePort.getUpgradeTier(islandId, END_UPGRADE)).thenReturn(1);

        IslandDimensionAccessResult result = service.checkAccess(islandId, IslandDimensionType.THE_END);
        assertThat(result).isInstanceOf(IslandDimensionAccessResult.Allowed.class);

        IslandDimensionAccessResult.Allowed allowed = (IslandDimensionAccessResult.Allowed) result;
        assertThat(allowed.mode()).isEqualTo(DimensionMode.SHARED_WORLD);
        assertThat(allowed.schematicRequired()).isFalse();
    }

    @Test
    @DisplayName("resetIslandDimensions clears generated dimensions for the island")
    void resetIslandDimensionsClearsState() {
        service.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        assertThat(service.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();

        service.resetIslandDimensions(islandId);
        assertThat(service.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isFalse();
    }

    @Test
    @DisplayName("mapping and allMappings provide access to dimension configurations")
    void mappingAccess() {
        assertThat(service.mapping(IslandDimensionType.OVERWORLD)).isPresent();
        assertThat(service.mapping(IslandDimensionType.OVERWORLD).get().worldName())
                .isEqualTo("skyblock_world");
        assertThat(service.allMappings()).hasSize(3);
    }
}
