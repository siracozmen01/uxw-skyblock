package com.uxplima.uxmskyblock.core.application.ward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.uxplima.uxmskyblock.core.application.ward.KineticWardService.TargetEntity;
import com.uxplima.uxmskyblock.core.domain.ward.KineticRepulsionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KineticWardServiceTest {

    private KineticWardService wardService;

    @BeforeEach
    void setUp() {
        wardService = new KineticWardService(5.0, 1.5, 0.4);
    }

    @Test
    @DisplayName("Invalid parameters throw IllegalArgumentException")
    void constructorValidation() {
        assertThatThrownBy(() -> new KineticWardService(0.0, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KineticWardService(5.0, -1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Entities within 5-block radius receive outward horizontal vector with vertical lift")
    void repulsesEntitiesWithinRadius() {
        TargetEntity zombieEast = new TargetEntity("zombie_1", 3.0, 64.0, 0.0);
        TargetEntity skeletonSouth = new TargetEntity("skeleton_1", 0.0, 64.0, 4.0);
        TargetEntity creeperFar = new TargetEntity("creeper_1", 10.0, 64.0, 0.0); // outside 5-block radius

        KineticRepulsionResult result =
                wardService.calculateRepulsion(0.0, 64.0, 0.0, List.of(zombieEast, skeletonSouth, creeperFar));

        assertThat(result.count()).isEqualTo(2);

        // zombie_1 should be pushed +X
        KineticRepulsionResult.RepulsedEntity repulsedZombie =
                result.repulsedEntities().get(0);
        assertThat(repulsedZombie.entityId()).isEqualTo("zombie_1");
        assertThat(repulsedZombie.velocityX()).isGreaterThan(1.4);
        assertThat(repulsedZombie.velocityZ()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(repulsedZombie.velocityY()).isEqualTo(0.4);

        // skeleton_1 should be pushed +Z
        KineticRepulsionResult.RepulsedEntity repulsedSkel =
                result.repulsedEntities().get(1);
        assertThat(repulsedSkel.entityId()).isEqualTo("skeleton_1");
        assertThat(repulsedSkel.velocityX()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(repulsedSkel.velocityZ()).isGreaterThan(1.4);
        assertThat(repulsedSkel.velocityY()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("Entity directly on arrival center gets non-zero default horizontal impulse")
    void entityAtCenterGetsImpulse() {
        TargetEntity centerMob = new TargetEntity("center_mob", 0.0, 64.0, 0.0);

        KineticRepulsionResult result = wardService.calculateRepulsion(0.0, 64.0, 0.0, List.of(centerMob));

        assertThat(result.count()).isEqualTo(1);
        KineticRepulsionResult.RepulsedEntity repulsed =
                result.repulsedEntities().get(0);
        assertThat(repulsed.velocityX()).isEqualTo(1.5);
        assertThat(repulsed.velocityY()).isEqualTo(0.4);
    }
}
