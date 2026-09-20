package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Categorical declarative interactables configuration (Section 2.42).
 * Groups block materials into semantic sets mapped to granular permission keys.
 */
public final class InteractablesConfiguration {

    public static final PermissionKey PERM_DOORS_AND_GATES = PermissionKey.uxm("interact.doors");
    public static final PermissionKey PERM_REDSTONE_TRIGGERS = StandardPermissions.REDSTONE_INTERACT;
    public static final PermissionKey PERM_CONTAINERS = StandardPermissions.CHEST_OPEN;
    public static final PermissionKey PERM_WORKSTATIONS = PermissionKey.uxm("interact.workstations");

    private final Set<Material> doorsAndGates;
    private final Set<Material> redstoneTriggers;
    private final Set<Material> containers;
    private final Set<Material> workstations;

    public InteractablesConfiguration(
            Set<Material> doorsAndGates,
            Set<Material> redstoneTriggers,
            Set<Material> containers,
            Set<Material> workstations) {
        this.doorsAndGates = Collections.unmodifiableSet(new HashSet<>(doorsAndGates));
        this.redstoneTriggers = Collections.unmodifiableSet(new HashSet<>(redstoneTriggers));
        this.containers = Collections.unmodifiableSet(new HashSet<>(containers));
        this.workstations = Collections.unmodifiableSet(new HashSet<>(workstations));
    }

    public static InteractablesConfiguration defaultConfiguration() {
        return new InteractablesConfiguration(
                parseMaterials(List.of(
                        "OAK_DOOR",
                        "SPRUCE_DOOR",
                        "BIRCH_DOOR",
                        "JUNGLE_DOOR",
                        "ACACIA_DOOR",
                        "DARK_OAK_DOOR",
                        "MANGROVE_DOOR",
                        "CHERRY_DOOR",
                        "BAMBOO_DOOR",
                        "CRIMSON_DOOR",
                        "WARPED_DOOR",
                        "IRON_DOOR",
                        "OAK_TRAPDOOR",
                        "SPRUCE_TRAPDOOR",
                        "BIRCH_TRAPDOOR",
                        "JUNGLE_TRAPDOOR",
                        "ACACIA_TRAPDOOR",
                        "DARK_OAK_TRAPDOOR",
                        "MANGROVE_TRAPDOOR",
                        "CHERRY_TRAPDOOR",
                        "BAMBOO_TRAPDOOR",
                        "CRIMSON_TRAPDOOR",
                        "WARPED_TRAPDOOR",
                        "IRON_TRAPDOOR",
                        "OAK_FENCE_GATE",
                        "SPRUCE_FENCE_GATE",
                        "BIRCH_FENCE_GATE",
                        "JUNGLE_FENCE_GATE",
                        "ACACIA_FENCE_GATE",
                        "DARK_OAK_FENCE_GATE",
                        "MANGROVE_FENCE_GATE",
                        "CHERRY_FENCE_GATE",
                        "BAMBOO_FENCE_GATE",
                        "CRIMSON_FENCE_GATE",
                        "WARPED_FENCE_GATE")),
                parseMaterials(List.of(
                        "STONE_BUTTON",
                        "OAK_BUTTON",
                        "SPRUCE_BUTTON",
                        "BIRCH_BUTTON",
                        "JUNGLE_BUTTON",
                        "ACACIA_BUTTON",
                        "DARK_OAK_BUTTON",
                        "MANGROVE_BUTTON",
                        "CHERRY_BUTTON",
                        "BAMBOO_BUTTON",
                        "CRIMSON_BUTTON",
                        "WARPED_BUTTON",
                        "POLISHED_BLACKSTONE_BUTTON",
                        "LEVER",
                        "STONE_PRESSURE_PLATE",
                        "OAK_PRESSURE_PLATE",
                        "SPRUCE_PRESSURE_PLATE",
                        "BIRCH_PRESSURE_PLATE",
                        "JUNGLE_PRESSURE_PLATE",
                        "ACACIA_PRESSURE_PLATE",
                        "DARK_OAK_PRESSURE_PLATE",
                        "MANGROVE_PRESSURE_PLATE",
                        "CHERRY_PRESSURE_PLATE",
                        "BAMBOO_PRESSURE_PLATE",
                        "CRIMSON_PRESSURE_PLATE",
                        "WARPED_PRESSURE_PLATE",
                        "LIGHT_WEIGHTED_PRESSURE_PLATE",
                        "HEAVY_WEIGHTED_PRESSURE_PLATE")),
                parseMaterials(List.of(
                        "CHEST",
                        "TRAPPED_CHEST",
                        "BARREL",
                        "HOPPER",
                        "DISPENSER",
                        "DROPPER",
                        "FURNACE",
                        "BLAST_FURNACE",
                        "SMOKER",
                        "BREWING_STAND",
                        "SHULKER_BOX",
                        "WHITE_SHULKER_BOX",
                        "ORANGE_SHULKER_BOX",
                        "MAGENTA_SHULKER_BOX",
                        "LIGHT_BLUE_SHULKER_BOX",
                        "YELLOW_SHULKER_BOX",
                        "LIME_SHULKER_BOX",
                        "PINK_SHULKER_BOX",
                        "GRAY_SHULKER_BOX",
                        "LIGHT_GRAY_SHULKER_BOX",
                        "CYAN_SHULKER_BOX",
                        "PURPLE_SHULKER_BOX",
                        "BLUE_SHULKER_BOX",
                        "BROWN_SHULKER_BOX",
                        "GREEN_SHULKER_BOX",
                        "RED_SHULKER_BOX",
                        "BLACK_SHULKER_BOX")),
                parseMaterials(List.of(
                        "CRAFTING_TABLE",
                        "ANVIL",
                        "CHIPPED_ANVIL",
                        "DAMAGED_ANVIL",
                        "ENCHANTING_TABLE",
                        "LOOM",
                        "CARTOGRAPHY_TABLE",
                        "SMITHING_TABLE",
                        "STONECUTTER",
                        "GRINDSTONE")));
    }

    public static InteractablesConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        if (rootNode.virtual() || rootNode.empty()) {
            return defaultConfiguration();
        }

        ConfigurationNode catNode = rootNode.hasChild("categories") ? rootNode.node("categories") : rootNode;
        try {
            ConfigurationNode doorsNode =
                    catNode.hasChild("doors-and-gates") ? catNode.node("doors-and-gates") : catNode.node("doors");
            ConfigurationNode redstoneNode = catNode.hasChild("redstone-triggers")
                    ? catNode.node("redstone-triggers")
                    : catNode.node("redstone");
            ConfigurationNode containersNode = catNode.node("containers");
            ConfigurationNode workstationsNode = catNode.node("workstations");

            List<String> doorsList = doorsNode.getList(String.class);
            List<String> redstoneList = redstoneNode.getList(String.class);
            List<String> containersList = containersNode.getList(String.class);
            List<String> workstationsList = workstationsNode.getList(String.class);

            Set<Material> doors = doorsList != null ? parseMaterials(doorsList) : Set.of();
            Set<Material> redstone = redstoneList != null ? parseMaterials(redstoneList) : Set.of();
            Set<Material> containers = containersList != null ? parseMaterials(containersList) : Set.of();
            Set<Material> workstations = workstationsList != null ? parseMaterials(workstationsList) : Set.of();

            return new InteractablesConfiguration(doors, redstone, containers, workstations);
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            return defaultConfiguration();
        }
    }

    /**
     * Resolves the granular permission key corresponding to an interactable material.
     */
    public Optional<PermissionKey> resolvePermission(Material material) {
        if (doorsAndGates.contains(material)) {
            return Optional.of(PERM_DOORS_AND_GATES);
        }
        if (redstoneTriggers.contains(material)) {
            return Optional.of(PERM_REDSTONE_TRIGGERS);
        }
        if (containers.contains(material)) {
            return Optional.of(PERM_CONTAINERS);
        }
        if (workstations.contains(material)) {
            return Optional.of(PERM_WORKSTATIONS);
        }
        return Optional.empty();
    }

    private static Set<Material> parseMaterials(List<String> names) {
        Set<Material> set = new HashSet<>();
        for (String name : names) {
            try {
                set.add(Material.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore unknown materials across Minecraft versions
            }
        }
        return set;
    }

    public Set<Material> doorsAndGates() {
        return doorsAndGates;
    }

    public Set<Material> redstoneTriggers() {
        return redstoneTriggers;
    }

    public Set<Material> containers() {
        return containers;
    }

    public Set<Material> workstations() {
        return workstations;
    }

    public boolean isEmpty() {
        return doorsAndGates.isEmpty() && redstoneTriggers.isEmpty() && containers.isEmpty() && workstations.isEmpty();
    }
}
