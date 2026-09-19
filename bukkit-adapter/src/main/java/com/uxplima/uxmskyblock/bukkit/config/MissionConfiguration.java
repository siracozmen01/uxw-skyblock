package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island missions, branches, trigger specifications, and rewards.
 */
public record MissionConfiguration(boolean enabled, List<MissionDefinition> missions) {

    public static final boolean DEFAULT_ENABLED = true;

    public MissionConfiguration {
        missions = (missions == null) ? List.of() : List.copyOf(missions);
    }

    public static MissionConfiguration defaultConfiguration() {
        List<MissionDefinition> defaults = List.of(
                new MissionDefinition(
                        MissionId.of("farming_wheat_1"),
                        MissionBranch.FARMING,
                        "Wheat Harvester I",
                        "Harvest 50 crops of wheat on your island",
                        MissionTriggerType.CROP_HARVEST,
                        "WHEAT",
                        50L,
                        new MissionReward(10L, 500L, 100L, List.of())),
                new MissionDefinition(
                        MissionId.of("farming_carrot_1"),
                        MissionBranch.FARMING,
                        "Carrot Grower I",
                        "Harvest 100 carrots",
                        MissionTriggerType.CROP_HARVEST,
                        "CARROT",
                        100L,
                        new MissionReward(15L, 1000L, 150L, List.of())),
                new MissionDefinition(
                        MissionId.of("mining_stone_1"),
                        MissionBranch.MINING,
                        "Quarry Novice",
                        "Mine 128 stone blocks",
                        MissionTriggerType.BLOCK_BREAK,
                        "STONE",
                        128L,
                        new MissionReward(10L, 400L, 100L, List.of())),
                new MissionDefinition(
                        MissionId.of("slayer_zombie_1"),
                        MissionBranch.SLAYER,
                        "Zombie Hunter I",
                        "Eliminate 25 zombies",
                        MissionTriggerType.MOB_KILL,
                        "ZOMBIE",
                        25L,
                        new MissionReward(20L, 1500L, 250L, List.of())),
                new MissionDefinition(
                        MissionId.of("builder_cobble_1"),
                        MissionBranch.BUILDER,
                        "Master Mason I",
                        "Place 256 cobblestone blocks",
                        MissionTriggerType.BLOCK_PLACE,
                        "COBBLESTONE",
                        256L,
                        new MissionReward(15L, 800L, 150L, List.of())),
                new MissionDefinition(
                        MissionId.of("adventure_fishing_1"),
                        MissionBranch.ADVENTURE,
                        "Deep Sea Angler I",
                        "Catch 20 fish from your island water",
                        MissionTriggerType.FISHING,
                        "*",
                        20L,
                        new MissionReward(25L, 2000L, 300L, List.of())),
                new MissionDefinition(
                        MissionId.of("economy_iron_1"),
                        MissionBranch.ECONOMY,
                        "Blacksmith Supply",
                        "Deliver 64 iron ingots to the community stockpile",
                        MissionTriggerType.ITEM_SUBMIT,
                        "IRON_INGOT",
                        64L,
                        new MissionReward(50L, 5000L, 1000L, List.of())));
        return new MissionConfiguration(DEFAULT_ENABLED, defaults);
    }

    public static MissionConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("missions");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        ConfigurationNode catalogNode = node.node("catalog");
        if (catalogNode.virtual() || catalogNode.empty()) {
            return new MissionConfiguration(enabled, defaultConfiguration().missions());
        }

        List<MissionDefinition> list = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                catalogNode.childrenMap().entrySet()) {
            String missionKey = String.valueOf(entry.getKey());
            ConfigurationNode mNode = entry.getValue();

            String branchRaw = mNode.node("branch").getString("ADVENTURE");
            MissionBranch branch;
            try {
                branch = MissionBranch.valueOf(branchRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                branch = MissionBranch.ADVENTURE;
            }

            String displayName = mNode.node("display-name").getString(missionKey);
            String description = mNode.node("description").getString("");
            String triggerRaw = mNode.node("trigger-type").getString("BLOCK_BREAK");
            MissionTriggerType triggerType;
            try {
                triggerType = MissionTriggerType.valueOf(triggerRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                triggerType = MissionTriggerType.BLOCK_BREAK;
            }

            String targetFilter = mNode.node("target-filter").getString("");
            long requiredAmount = Math.max(1L, mNode.node("required-amount").getLong(10L));

            ConfigurationNode rewardsNode = mNode.node("rewards");
            long crystals = Math.max(0L, rewardsNode.node("crystals").getLong(0L));
            long currency =
                    Math.max(0L, rewardsNode.node("currency-minor-units").getLong(0L));
            long exp = Math.max(0L, rewardsNode.node("island-exp").getLong(0L));
            List<String> commands = new ArrayList<>();
            for (ConfigurationNode cmdNode : rewardsNode.node("commands").childrenList()) {
                String cmd = cmdNode.getString();
                if (cmd != null && !cmd.isBlank()) {
                    commands.add(cmd);
                }
            }
            MissionReward reward = new MissionReward(crystals, currency, exp, commands);

            list.add(new MissionDefinition(
                    MissionId.of(missionKey),
                    branch,
                    displayName,
                    description,
                    triggerType,
                    targetFilter,
                    requiredAmount,
                    reward));
        }

        return new MissionConfiguration(enabled, Collections.unmodifiableList(list));
    }
}
