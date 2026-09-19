package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Enterprise protection policies and anti-exploit configuration (Section 2.42).
 */
public record ProtectionConfiguration(
        boolean obsidianRecoveryEnabled,
        boolean obsidianRecoveryAccidentalOnly,
        Duration obsidianRecoveryExpiration,
        boolean voidRecoveryEnabled,
        int voidRecoveryThresholdY,
        Duration voidRecoveryFallDamageShield,
        boolean kineticWardEnabled,
        double kineticWardRadius,
        double kineticWardForce,
        double kineticWardVerticalLift) {

    public static final boolean DEFAULT_OBSIDIAN_ENABLED = true;
    public static final boolean DEFAULT_OBSIDIAN_ACCIDENTAL = true;
    public static final Duration DEFAULT_OBSIDIAN_EXPIRATION = Duration.ofSeconds(60);

    public static final boolean DEFAULT_VOID_ENABLED = true;
    public static final int DEFAULT_VOID_THRESHOLD_Y = -64;
    public static final Duration DEFAULT_VOID_FALL_SHIELD = Duration.ofSeconds(10);

    public static final boolean DEFAULT_WARD_ENABLED = true;
    public static final double DEFAULT_WARD_RADIUS = 5.0;
    public static final double DEFAULT_WARD_FORCE = 1.5;
    public static final double DEFAULT_WARD_LIFT = 0.35;

    public static ProtectionConfiguration defaultConfiguration() {
        return new ProtectionConfiguration(
                DEFAULT_OBSIDIAN_ENABLED,
                DEFAULT_OBSIDIAN_ACCIDENTAL,
                DEFAULT_OBSIDIAN_EXPIRATION,
                DEFAULT_VOID_ENABLED,
                DEFAULT_VOID_THRESHOLD_Y,
                DEFAULT_VOID_FALL_SHIELD,
                DEFAULT_WARD_ENABLED,
                DEFAULT_WARD_RADIUS,
                DEFAULT_WARD_FORCE,
                DEFAULT_WARD_LIFT);
    }

    public static ProtectionConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        if (rootNode.virtual() || rootNode.empty()) {
            return defaultConfiguration();
        }

        ConfigurationNode obsNode = rootNode.node("obsidian-recovery");
        boolean obsEnabled = obsNode.node("enabled").getBoolean(DEFAULT_OBSIDIAN_ENABLED);
        boolean obsAccidental = obsNode.node("accidental-only").getBoolean(DEFAULT_OBSIDIAN_ACCIDENTAL);
        int obsExpSec = obsNode.node("expiration-seconds").getInt(60);

        ConfigurationNode voidNode = rootNode.node("void-recovery");
        boolean voidEnabled = voidNode.node("enabled").getBoolean(DEFAULT_VOID_ENABLED);
        int voidThresholdY = voidNode.node("threshold-y").getInt(DEFAULT_VOID_THRESHOLD_Y);
        int voidShieldSec = voidNode.node("fall-damage-shield-seconds").getInt(10);

        ConfigurationNode wardNode = rootNode.node("kinetic-ward");
        boolean wardEnabled = wardNode.node("enabled").getBoolean(DEFAULT_WARD_ENABLED);
        double wardRadius = wardNode.node("radius").getDouble(DEFAULT_WARD_RADIUS);
        double wardForce = wardNode.node("repulsion-force").getDouble(DEFAULT_WARD_FORCE);
        double wardLift = wardNode.node("vertical-lift").getDouble(DEFAULT_WARD_LIFT);

        return new ProtectionConfiguration(
                obsEnabled,
                obsAccidental,
                Duration.ofSeconds(obsExpSec),
                voidEnabled,
                voidThresholdY,
                Duration.ofSeconds(voidShieldSec),
                wardEnabled,
                wardRadius,
                wardForce,
                wardLift);
    }
}
