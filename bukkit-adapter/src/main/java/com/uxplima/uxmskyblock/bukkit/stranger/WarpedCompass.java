package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * The warped compass: held on a StrangerRealms island, it points at the nearest player on the other side
 * of the veil, at the same place on this side, and spins when nobody is there.
 *
 * <p>A beat on the global thread hands each player to their own thread. There the player's place is
 * written down, and a compass in their hand is turned to the nearest place written down on the other
 * side. Nobody reads another player; each reads only what the others wrote.
 */
public final class WarpedCompass implements Listener {

    public static final NamespacedKey MARK =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:warped_compass"));
    public static final NamespacedKey RECIPE =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:make_warped_compass"));

    private static final Logger LOGGER = Logger.getLogger(WarpedCompass.class.getName());

    /** Where a player stood at the last beat. */
    record Seen(IslandId island, boolean upsideDown, double x, double y, double z) {}

    private final Realms realms;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final StrangerRealmsConfiguration.Compass config;
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();

    public WarpedCompass(
            Realms realms, SchedulerPort scheduler, Messages messages, StrangerRealmsConfiguration.Compass config) {
        this.realms = Objects.requireNonNull(realms, "realms must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** A warped compass, before it is named for whoever makes it. */
    public static ItemStack compass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        compass.editMeta(meta -> {
            meta.getPersistentDataContainer().set(MARK, PersistentDataType.BOOLEAN, true);
            meta.setEnchantmentGlintOverride(true);
        });
        return compass;
    }

    /** Whether the item is a warped compass. */
    public static boolean isWarped(@Nullable ItemStack item) {
        return item != null
                && item.getType() == Material.COMPASS
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(MARK, PersistentDataType.BOOLEAN);
    }

    /** The recipe the operator's ingredients make, or empty when one of them is no item. */
    public Optional<ShapelessRecipe> recipe() {
        ShapelessRecipe recipe = new ShapelessRecipe(RECIPE, compass());
        for (String written : config.ingredients()) {
            Material item = Material.matchMaterial(written.trim().toUpperCase(Locale.ROOT));
            if (item == null || !item.isItem()) {
                LOGGER.warning(() -> "modules/strangerrealms.conf warped-compass ingredients: " + written
                        + " is no item. The compass cannot be made.");
                return Optional.empty();
            }
            recipe.addIngredient(item);
        }
        return Optional.of(recipe);
    }

    /** Names the compass in the grid in the language of the player making it. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        CraftingInventory grid = event.getInventory();
        ItemStack result = grid.getResult();
        List<HumanEntity> viewers = event.getViewers();
        if (!isWarped(result) || viewers.isEmpty() || !(viewers.getFirst() instanceof Player maker)) {
            return;
        }
        grid.setResult(named(result, maker));
    }

    /** The compass with its name and lines in the player's language. */
    ItemStack named(ItemStack compass, Player maker) {
        ItemStack named = compass.clone();
        named.editMeta(meta -> {
            meta.itemName(messages.renderPlain(maker, "stranger.compass.name"));
            meta.lore(messages.renderAll(maker, "stranger.compass.lore"));
        });
        return named;
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, config.checkEvery(), config.checkEvery());
    }

    private void round() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> check(player));
        }
        seen.keySet().retainAll(online);
    }

    /**
     * Writes down where the player is and turns a compass in their hand. On the player's own thread.
     * Returns where the compass points, or empty when it spins or there is none.
     */
    public Optional<Location> check(Player player) {
        Location at = player.getLocation();
        if (at == null || at.getWorld() == null) {
            return Optional.empty();
        }
        Optional<Realms.Place> place = realms.placeOf(at.getWorld().getName(), at.getBlockX(), at.getBlockZ());
        if (place.isEmpty()) {
            seen.remove(player.getUniqueId());
            return Optional.empty();
        }
        Seen here = new Seen(place.get().island(), place.get().upsideDown(), at.getX(), at.getY(), at.getZ());
        seen.put(player.getUniqueId(), here);
        EquipmentSlot hand = isWarped(player.getInventory().getItemInMainHand())
                ? EquipmentSlot.HAND
                : isWarped(player.getInventory().getItemInOffHand()) ? EquipmentSlot.OFF_HAND : null;
        if (hand == null) {
            return Optional.empty();
        }
        Location target = nearestAcross(player.getUniqueId(), here)
                .map(other -> new Location(at.getWorld(), other.x(), other.y(), other.z()))
                .orElse(null);
        point(player, hand, target);
        return Optional.ofNullable(target);
    }

    private Optional<Seen> nearestAcross(UUID self, Seen here) {
        Seen nearest = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<UUID, Seen> entry : seen.entrySet()) {
            Seen other = entry.getValue();
            if (entry.getKey().equals(self)
                    || !other.island().equals(here.island())
                    || other.upsideDown() == here.upsideDown()) {
                continue;
            }
            double dx = other.x() - here.x();
            double dz = other.z() - here.z();
            double distance = dx * dx + dz * dz;
            if (distance < best) {
                best = distance;
                nearest = other;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static void point(Player player, EquipmentSlot hand, @Nullable Location target) {
        ItemStack compass = player.getInventory().getItem(hand);
        if (!(compass.getItemMeta() instanceof CompassMeta meta)) {
            return;
        }
        Location was = meta.getLodestone();
        if (sameBlock(was, target)) {
            return;
        }
        meta.setLodestone(target);
        meta.setLodestoneTracked(false);
        compass.setItemMeta(meta);
        player.getInventory().setItem(hand, compass);
    }

    private static boolean sameBlock(@Nullable Location a, @Nullable Location b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && Objects.equals(a.getWorld(), b.getWorld());
    }
}
