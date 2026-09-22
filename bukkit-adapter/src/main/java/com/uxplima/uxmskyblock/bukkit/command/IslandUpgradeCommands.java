package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is upgrades}: what the island has bought and what the next tier costs.
 *
 * <p>The upgrade service, its table, its tiers and a menu file with a slot for every one of them
 * have been here since the economy work, and the menu's click ran {@code is upgrades buy <key>},
 * which was a command nobody had written. Every click answered with the server's unknown command
 * message, and no island ever bought an upgrade.
 *
 * <p>The keys are the section names in {@code modules/upgrades.conf} and nothing here knows any of
 * them: the list, the suggestions and the purchase all read whatever the operator's file defines.
 */
public final class IslandUpgradeCommands {

    /**
     * What the operator wrote for the milestones this command group reaches.
     *
     * <p>The reply a command gives is the catalogue's. What a server wants beside it, a sound, a
     * title, a bar, is the operator's list and nothing here decides it. A node with no list fires
     * nothing, which is a server that wrote none.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer effectPlayer;

    /** Tells this command group what the operator wrote for its milestones. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    /** Fires one milestone for one player, when the operator wrote anything for it. */
    private void fireMilestone(String interaction, org.bukkit.entity.Player player) {
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        if (written != null && plays != null) {
            plays.fire(written, interaction, player);
        }
    }

    private final Supplier<@Nullable IslandUpgradeService> upgradeServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final Messages messages;

    /** Where a bought upgrade is written down for the island's members to read. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandUpgradeCommands(
            Supplier<@Nullable IslandUpgradeService> upgradeServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.upgradeServiceProvider =
                Objects.requireNonNull(upgradeServiceProvider, "upgradeServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return buildUnder("upgrades");
    }

    /** The same branch under another word, so {@code /is upgrade} and {@code /is upgrades} both work. */
    public LiteralArgumentBuilder<CommandSourceStack> buildUnder(String verb) {
        return Cmd.literal(verb)
                .executes(this::executeList)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.literal("buy")
                        .then(Cmd.argument("upgrade", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (String key : knownKeys()) {
                                        builder.suggest(key);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(this::executeBuy)));
    }

    private List<String> knownKeys() {
        IslandUpgradeService service = upgradeServiceProvider.get();
        if (service == null) {
            return List.of();
        }
        return service.definitions().keySet().stream()
                .map(UpgradeId::key)
                .sorted()
                .toList();
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandUpgradeService service = upgradeServiceProvider.get();
        if (service == null) {
            send(player, "upgrades.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            IslandId islandId = optIsland.get();
            List<String> lines = describe(service, islandId);
            schedulerPort.onEntity(playerUuid, () -> {
                send(player, "upgrades.header");
                if (lines.isEmpty()) {
                    send(player, "upgrades.none_defined");
                    return;
                }
                for (String line : lines) {
                    sendEntry(player, line);
                }
            });
        });
        return Cmd.OK;
    }

    /** One line per upgrade the operator defined: its key, where the island is and what comes next. */
    private List<String> describe(IslandUpgradeService service, IslandId islandId) {
        return service.definitions().values().stream()
                .map(definition -> describeOne(service, islandId, definition))
                .toList();
    }

    private String describeOne(IslandUpgradeService service, IslandId islandId, UpgradeDefinition definition) {
        int tier = service.getCurrentTier(islandId, definition.id());
        Optional<UpgradeTier> next = definition.getTier(tier + 1);
        String cost =
                next.map(candidate -> Long.toString(candidate.costMinorUnits())).orElse("");
        return definition.id().key()
                + '\u001f'
                + definition.displayName()
                + '\u001f'
                + tier
                + '\u001f'
                + definition.maxTier()
                + '\u001f'
                + cost;
    }

    private void sendEntry(Player player, String packed) {
        String[] parts = packed.split("\u001f", -1);
        String key = parts[0];
        String name = parts[1];
        String tier = parts[2];
        String maxTier = parts[3];
        String cost = parts[4];
        TagResolver[] resolvers = {
            Placeholder.unparsed("key", key),
            Placeholder.unparsed("name", name),
            Placeholder.unparsed("tier", tier),
            Placeholder.unparsed("max_tier", maxTier),
            Placeholder.unparsed("cost", cost)
        };
        send(player, cost.isEmpty() ? "upgrades.entry_maxed" : "upgrades.entry", resolvers);
    }

    /**
     * Whether this profile's role lets them buy an upgrade for the island.
     *
     * <p>Every purchase spends the island bank, so the withdraw permission is the floor. An upgrade
     * that names one more in the operator's file asks for that too.
     *
     * <p>An island that cannot be read is not a refusal: the purchase answers that itself.
     */
    private boolean theRoleAllowsIt(IslandId islandId, ProfileId profileId, @Nullable IslandPermission alsoNeeded) {
        return islandLocationService
                .findIsland(islandId)
                .map(island -> {
                    if (island.isOwner(profileId)) {
                        return true;
                    }
                    if (!island.hasPermission(profileId, IslandPermission.BANK_WITHDRAW)) {
                        return false;
                    }
                    return alsoNeeded == null || island.hasPermission(profileId, alsoNeeded);
                })
                .orElse(true);
    }

    private int executeBuy(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandUpgradeService service = upgradeServiceProvider.get();
        if (service == null) {
            send(player, "upgrades.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        String requested = StringArgumentType.getString(ctx, "upgrade");
        UpgradeId upgradeId = UpgradeId.of(requested);

        if (service.getDefinition(upgradeId).isEmpty()) {
            send(player, "upgrades.unknown", Placeholder.unparsed("key", requested));
            return Cmd.OK;
        }

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            // Buying spends the island bank, so it asks for the permission that lets a role spend
            // it. The upgrade's own entry may ask for one more, and the operator's file is where
            // that is named. Neither was read: any member could spend the island's money on any
            // upgrade whatever their role said.
            IslandPermission alsoNeeded = service.getDefinition(upgradeId)
                    .map(UpgradeDefinition::requiredPermission)
                    .orElse(null);
            if (!theRoleAllowsIt(optIsland.get(), profileId, alsoNeeded)) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "upgrades.permission_denied"));
                return;
            }

            UpgradePurchaseOutcome outcome =
                    service.purchaseUpgrade(optIsland.get(), upgradeId, player.getUniqueId(), serverNodeId);
            if (outcome instanceof UpgradePurchaseOutcome.Success bought) {
                activityLog.recordForMembers(
                        optIsland.get(),
                        profileId,
                        ActivityEventType.UPGRADE_PURCHASED,
                        "activity.upgrade_purchased",
                        java.util.Map.of(
                                "player",
                                player.getName(),
                                "key",
                                upgradeId.key(),
                                "tier",
                                Integer.toString(bought.newTier())));
            }
            schedulerPort.onEntity(playerUuid, () -> report(player, upgradeId, outcome));
        });
        return Cmd.OK;
    }

    private void report(Player player, UpgradeId upgradeId, UpgradePurchaseOutcome outcome) {
        switch (outcome) {
            case UpgradePurchaseOutcome.Success success -> {
                send(
                        player,
                        "upgrades.bought",
                        Placeholder.unparsed("key", upgradeId.key()),
                        Placeholder.unparsed("tier", Integer.toString(success.newTier())),
                        Placeholder.unparsed("cost", Long.toString(success.costPaid())));
                fireMilestone("upgrade-bought", player);
            }
            case UpgradePurchaseOutcome.MaxTierReached maxed ->
                send(
                        player,
                        "upgrades.maxed",
                        Placeholder.unparsed("key", upgradeId.key()),
                        Placeholder.unparsed("tier", Integer.toString(maxed.currentTier())));
            case UpgradePurchaseOutcome.InsufficientFunds poor ->
                send(
                        player,
                        "upgrades.too_poor",
                        Placeholder.unparsed("key", upgradeId.key()),
                        Placeholder.unparsed("cost", Long.toString(poor.requiredAmount())),
                        Placeholder.unparsed("balance", Long.toString(poor.availableAmount())));
            case UpgradePurchaseOutcome.UpgradeNotFound notFound ->
                send(
                        player,
                        "upgrades.unknown",
                        Placeholder.unparsed("key", notFound.upgradeId().key()));
            case UpgradePurchaseOutcome.PaymentFailed failed ->
                send(player, "upgrades.refused", Placeholder.unparsed("reason", failed.reason()));
        }
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
