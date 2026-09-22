package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The island permissions the protection rules never asked for.
 *
 * <p>The role editor publishes a permission for using a bucket, for breaking a spawner, for
 * changing what one spawns, for breeding an animal, for killing one and for walking over a crop.
 * Not one of them was read anywhere, so an operator could take any of them off a role, the editor
 * would show it taken off, and the member went on doing it.
 */
@SuppressWarnings({"deprecation", "removal"})
class TheActionPermissionsAreReadTest extends MockBukkitHarness {

    private static final IslandStoragePort NO_STORAGE = new IslandStoragePort() {
        @Override
        public void saveIsland(Island island, IslandLocation location) {}

        @Override
        public Optional<Island> findIslandById(IslandId id) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return Optional.empty();
        }

        @Override
        public void deleteIsland(IslandId id) {}
    };

    private World world;
    private PlayerMock member;
    private IslandProtectionListener protection;
    private IslandActionPermissionListener listener;
    private Island island;
    private ProfileId memberProfile;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("action_world");
        protection = new IslandProtectionListener(NO_STORAGE, new IslandAccessService());
        listener = new IslandActionPermissionListener(protection, Messages.bundled());

        PlayerMock owner = createPlayer("Owner");
        member = createPlayer("Member");
        memberProfile = new ProfileId(member.getUniqueId());
        protection.setActiveProfile(new PlayerUuid(member.getUniqueId()), memberProfile);

        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(50, 50, 50),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(owner.getUniqueId()),
                Instant.now());
    }

    /** Puts the member on the island under a role holding exactly these permissions. */
    private void memberHolding(IslandPermission... permissions) {
        IslandRole role = new IslandRole(
                "CUSTOM",
                400,
                "Custom",
                permissions.length == 0
                        ? EnumSet.noneOf(IslandPermission.class)
                        : EnumSet.of(permissions[0], permissions),
                false);
        protection.cacheIsland(island.addMember(
                new IslandMember(new PlayerUuid(member.getUniqueId()), memberProfile, role, Instant.now())));
    }

    private Block blockAt(Material material) {
        Block block = world.getBlockAt(50, 64, 50);
        block.setType(material);
        return block;
    }

    private boolean bucketRefused() {
        Block water = blockAt(Material.WATER);
        org.bukkit.event.player.PlayerBucketEmptyEvent event = new org.bukkit.event.player.PlayerBucketEmptyEvent(
                member,
                water,
                water.getRelative(org.bukkit.block.BlockFace.UP),
                org.bukkit.block.BlockFace.UP,
                Material.WATER_BUCKET,
                new ItemStack(Material.WATER_BUCKET),
                EquipmentSlot.HAND);
        listener.onBucketEmpty(event);
        return event.isCancelled();
    }

    @Test
    @DisplayName("A role without the bucket permission cannot empty one here")
    void aroleWithoutTheBucketPermissionIsStopped() {
        memberHolding(IslandPermission.BLOCK_PLACE);

        assertThat(bucketRefused()).isTrue();
        assertThat(member.nextMessage()).describedAs("and is told why").isNotNull();
    }

    @Test
    @DisplayName("A role holding the bucket permission empties one")
    void aroleWithTheBucketPermissionIsLetThrough() {
        memberHolding(IslandPermission.BUCKET_USE);

        assertThat(bucketRefused()).isFalse();
    }

    @Test
    @DisplayName("Breaking blocks is not breaking spawners: the spawner asks for its own permission")
    void aspawnerAsksForItsOwnPermission() {
        memberHolding(IslandPermission.BLOCK_BREAK);

        BlockBreakEvent event = new BlockBreakEvent(blockAt(Material.SPAWNER), member);
        listener.onSpawnerBreak(event);

        assertThat(event.isCancelled())
                .describedAs("the most valuable block an island holds, named on its own in the editor")
                .isTrue();
    }

    @Test
    @DisplayName("A role holding the spawner permission breaks it")
    void aroleHoldingTheSpawnerPermissionBreaksIt() {
        memberHolding(IslandPermission.BLOCK_BREAK, IslandPermission.SPAWNER_BREAK);

        BlockBreakEvent event = new BlockBreakEvent(blockAt(Material.SPAWNER), member);
        listener.onSpawnerBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A block that is not a spawner is not this rule's business")
    void anordinaryBlockIsNotTouchedHere() {
        memberHolding();

        BlockBreakEvent event = new BlockBreakEvent(blockAt(Material.STONE), member);
        listener.onSpawnerBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A spawn egg on a spawner asks for the permission that changes what it spawns")
    void aspawnEggAsksForTheChangePermission() {
        memberHolding(IslandPermission.NATURAL_INTERACT, IslandPermission.SPAWNER_BREAK);

        Block spawner = blockAt(Material.SPAWNER);
        PlayerInteractEvent event = new PlayerInteractEvent(
                member,
                Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.COW_SPAWN_EGG),
                spawner,
                org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND);
        listener.onSpawnerRetype(event);

        assertThat(event.useInteractedBlock()).isEqualTo(org.bukkit.event.Event.Result.DENY);
    }

    @Test
    @DisplayName("Walking over farmland ruins it, so it asks for the permission named for that")
    void tramplingAsksForTheBypass() {
        memberHolding(IslandPermission.NATURAL_INTERACT);

        Block farmland = blockAt(Material.FARMLAND);
        PlayerInteractEvent event =
                new PlayerInteractEvent(member, Action.PHYSICAL, null, farmland, org.bukkit.block.BlockFace.UP);
        listener.onCropTrample(event);

        assertThat(event.useInteractedBlock()).isEqualTo(org.bukkit.event.Event.Result.DENY);
    }

    @Test
    @DisplayName("A role holding the trample bypass walks where it likes")
    void thebypassLetsThemTrample() {
        memberHolding(IslandPermission.CROP_TRAMPLE_BYPASS);

        Block farmland = blockAt(Material.FARMLAND);
        PlayerInteractEvent event =
                new PlayerInteractEvent(member, Action.PHYSICAL, null, farmland, org.bukkit.block.BlockFace.UP);
        listener.onCropTrample(event);

        assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
    }

    @Test
    @DisplayName("A role without the animal permission cannot harm the island's animals")
    void harmingAnAnimalAsksForThePermission() {
        memberHolding(IslandPermission.BLOCK_BREAK);
        Cow cow = (Cow) world.spawnEntity(new Location(world, 50, 64, 50), EntityType.COW);

        EntityDamageByEntityEvent event =
                new EntityDamageByEntityEvent(member, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0);
        listener.onAnimalDamage(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("A role holding the animal permission may hit one")
    void aroleHoldingTheAnimalPermissionMayHit() {
        memberHolding(IslandPermission.ANIMAL_KILL);
        Cow cow = (Cow) world.spawnEntity(new Location(world, 50, 64, 50), EntityType.COW);

        EntityDamageByEntityEvent event =
                new EntityDamageByEntityEvent(member, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0);
        listener.onAnimalDamage(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A place on no island is nobody's to refuse")
    void offTheIslandNothingIsRefused() {
        memberHolding();
        Block far = world.getBlockAt(5000, 64, 5000);
        far.setType(Material.SPAWNER);

        BlockBreakEvent event = new BlockBreakEvent(far, member);
        listener.onSpawnerBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
