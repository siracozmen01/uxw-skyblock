package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuHolder;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The roles of an island are windows drawn from their files: the roles, what one role may do, and the
 * role one member holds.
 *
 * <p>The roles tile promised a role editor and dumped every permission to chat as its constant in lower
 * case, and a click on a member listed role ids to type, the owner's own head included.
 */
class TheRolesAreWindowsTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    @TempDir
    Path dataDir;

    private final IslandLocationService locations = mock(IslandLocationService.class);
    private final Messages messages = Messages.bundled();
    private final List<String> typed = new ArrayList<>();

    private PlayerMock ada;
    private PlayerMock bo;
    private Island island;
    private SkyblockMenuEngine engine;
    private RoleWindows windows;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        bo = createPlayer("Bo");
        ProfileId adaProfile = ProfileId.of(ada.getUniqueId());
        island = Island.create(
                        ISLAND,
                        IslandBounds.fromCenterAndRadius(0, 0, 50),
                        PlayerUuid.of(ada.getUniqueId()),
                        adaProfile,
                        NOW)
                .addMember(new IslandMember(
                        PlayerUuid.of(bo.getUniqueId()), ProfileId.of(bo.getUniqueId()), IslandRole.MEMBER, NOW));
        when(locations.findIslandId(adaProfile)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenAnswer(invocation -> Optional.of(island));
        engine = ShippedTemplates.engineWith(
                dataDir, "island-roles.conf", "island-role-permissions.conf", "island-member-role.conf");
        windows = new RoleWindows(
                engine,
                messages,
                uuid -> uuid.equals(ada.getUniqueId()) ? Optional.of(adaProfile) : Optional.empty(),
                locations,
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort(),
                line -> {
                    typed.add(line);
                    return "unregistered " + line;
                });
        windows.register();
        engine.install();
    }

    @Test
    @DisplayName("The roles are every role but the owner's, the highest first, each with the icon its file names")
    void theRolesAreEveryRoleButTheOwners() {
        List<MenuRow> rows = windows.roleRows(ada, island);

        assertThat(rows)
                .extracting(row -> row.words().get("role_name"))
                .containsExactly("Co-Owner", "Moderator", "Member", "Visitor");
        assertThat(rows.get(0).words())
                .containsEntry("material", "GOLDEN_HELMET")
                .containsEntry(
                        "allowed",
                        Integer.toString(IslandRole.CO_OWNER.permissions().size()))
                .containsEntry("total", Integer.toString(IslandPermission.values().length));
        assertThat(rows.get(0).handle()).isEqualTo(new RoleWindows.RoleEntry("CO_OWNER"));
    }

    @Test
    @DisplayName("What a role may do is every permission, by the name the reader's language gives it, on or off")
    void aRoleIsEveryPermissionOnOrOff() {
        List<MenuRow> rows = windows.permissionRows(ada, IslandRole.MEMBER);

        assertThat(rows).hasSize(IslandPermission.values().length);
        MenuRow place = rows.get(at(IslandPermission.BLOCK_PLACE));
        assertThat(place.words())
                .containsEntry("permission", "Place blocks")
                .containsEntry("status", "Allowed")
                .containsEntry("colour", "good")
                .containsEntry("material", "GRASS_BLOCK");
        MenuRow kick = rows.get(at(IslandPermission.MEMBER_KICK));
        assertThat(kick.words())
                .containsEntry("permission", "Remove members")
                .containsEntry("status", "Not allowed")
                .containsEntry("colour", "bad");
        assertThat(rows).extracting(row -> row.words().get("permission")).noneMatch(name -> name.contains("_"));
    }

    @Test
    @DisplayName("A click on a permission runs the permissions command the other way, under the operator's names")
    void aClickOnAPermissionRunsTheCommand() {
        List<MenuRow> rows = windows.permissionRows(ada, IslandRole.MEMBER);

        click("skyblock:role-permission", rows.get(at(IslandPermission.BLOCK_PLACE)));
        click("skyblock:role-permission", rows.get(at(IslandPermission.MEMBER_KICK)));

        assertThat(typed).containsExactly("permissions member block_place off", "permissions member member_kick on");
    }

    @Test
    @DisplayName("The roles tile opens the roles, and a role opens what it may do, named in the title")
    void theRolesTileOpensTheRoles() {
        click("skyblock:permissions", null);
        settle(() -> titleOf(ada).equals("Roles"));

        assertThat(loreAt(10)).contains("Co-Owner");
        click("skyblock:role-edit", windows.roleRows(ada, island).get(2));
        settle(() -> titleOf(ada).equals("Member"));

        assertThat(loreAt(at(IslandPermission.BLOCK_PLACE)))
                .contains("Place blocks")
                .contains("Allowed");
        assertThat(typed).isEmpty();
    }

    @Test
    @DisplayName("A click on the owner says the owner's role stays, and opens no roles to choose from")
    void theOwnersRoleStays() {
        windows.pickRole(ada, ada.getUniqueId());
        drain();

        assertThat(titleOf(ada)).isEmpty();
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(Objects.requireNonNull(ada.nextComponentMessage())))
                .contains("role of the owner does not change");
        assertThat(typed).isEmpty();
    }

    @Test
    @DisplayName("A click on a member opens the roles they could be given, the one they hold marked")
    void aMemberOpensTheirRoles() {
        windows.pickRole(ada, bo.getUniqueId());
        settle(() -> titleOf(ada).equals("Role of Bo"));

        assertThat(loreAt(10)).contains("Co-Owner").contains("Holds another role");
        assertThat(loreAt(12)).contains("Member").contains("Holds this role");
        assertThat(loreAt(13)).isEmpty();
        click("skyblock:member-role-give", rowAt(10));
        assertThat(typed).containsExactly("role Bo co_owner");
    }

    @Test
    @DisplayName("A change to this island's roles draws the open window again, a change to another island does not")
    void aChangeDrawsTheWindowAgain() {
        click("skyblock:role-edit", windows.roleRows(ada, island).get(2));
        settle(() -> titleOf(ada).equals("Member"));
        int place = at(IslandPermission.BLOCK_PLACE);
        java.util.Set<IslandPermission> fewer = java.util.EnumSet.copyOf(IslandRole.MEMBER.permissions());
        fewer.remove(IslandPermission.BLOCK_PLACE);
        island = island.withRole(new IslandRole("MEMBER", IslandRole.MEMBER.weight(), "Member", fewer, false));

        windows.rolesChanged(IslandId.of(UUID.randomUUID()));
        drain();
        assertThat(loreAt(place)).contains("Allowed").doesNotContain("Not allowed");

        windows.rolesChanged(ISLAND);
        settle(() -> loreAt(place).contains("Not allowed"));
        assertThat(titleOf(ada)).isEqualTo("Member");
    }

    @Test
    @DisplayName("A change made where no window is up, as from a Bedrock form a tap closed, shows the window again")
    void aChangeFromAFormShowsItAgain() {
        click("skyblock:role-edit", windows.roleRows(ada, island).get(2));
        settle(() -> titleOf(ada).equals("Member"));
        ada.closeInventory();

        click(
                "skyblock:role-permission",
                windows.permissionRows(ada, IslandRole.MEMBER).get(0));
        windows.rolesChanged(ISLAND);
        settle(() -> titleOf(ada).equals("Member"));

        click(
                "skyblock:role-permission",
                windows.permissionRows(ada, IslandRole.MEMBER).get(0));
        ada.closeInventory();
        windows.rolesChanged(ISLAND);
        drain();
        assertThat(titleOf(ada))
                .describedAs("a player who clicked in a window and closed it afterwards is not shown it again")
                .isEmpty();
    }

    @Test
    @DisplayName("A player with no island is told so, and no window opens")
    void noIslandIsSaid() {
        ShippedTemplates.click(
                engine,
                "skyblock:permissions",
                com.uxplima.uxmlib.menu.runtime.MenuContext.of(bo, null, 0),
                bo,
                ClickKind.LEFT,
                "");
        drain();

        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(Objects.requireNonNull(bo.nextComponentMessage())))
                .contains("You do not belong to an island");
        assertThat(titleOf(bo)).isEmpty();
    }

    @Test
    @DisplayName("Drawing one window again with fresh rows keeps the rows another window was handed")
    void aRedrawKeepsTheOtherLists() {
        engine.handedList("test:other");
        engine.open(ada, RoleWindows.ROLES_FILE, java.util.Map.of(), java.util.Map.of("test:other", List.of("kept")));

        engine.redraw(ada, RoleWindows.ROLES_FILE, java.util.Map.of(RoleWindows.ROLES, List.of()));

        com.uxplima.uxmlib.menu.runtime.MenuContext ctx = com.uxplima.uxmlib.menu.runtime.MenuContext.of(ada, null, 0);
        List<Object> other =
                List.copyOf(engine.bindings().list("test:other").orElseThrow().apply(ctx));
        assertThat(other).containsExactly("kept");
        assertThat(engine.bindings().list(RoleWindows.ROLES).orElseThrow().apply(ctx))
                .isEmpty();
    }

    /** Where {@code permission} stands in the list, which is its slot too: the list starts at the first slot. */
    private static int at(IslandPermission permission) {
        return List.of(IslandPermission.values()).indexOf(permission);
    }

    private void click(String verb, @org.jspecify.annotations.Nullable MenuRow row) {
        com.uxplima.uxmlib.menu.runtime.MenuContext ctx = com.uxplima.uxmlib.menu.runtime.MenuContext.of(ada, null, 0);
        ShippedTemplates.click(engine, verb, row == null ? ctx : ctx.withEntry(row), ada, ClickKind.LEFT, "");
    }

    /** The row a list stamped the tile at {@code slot} of Ada's window for. */
    private MenuRow rowAt(int slot) {
        MenuHolder holder = (MenuHolder) Objects.requireNonNull(top().getHolder());
        return (MenuRow)
                Objects.requireNonNull(holder.clickAt(slot).orElseThrow().entry());
    }

    private Inventory top() {
        return ada.getOpenInventory().getTopInventory();
    }

    private String titleOf(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof MenuHolder
                ? PlainTextComponentSerializer.plainText()
                        .serialize(player.getOpenInventory().title())
                        .strip()
                : "";
    }

    private String loreAt(int slot) {
        ItemStack item = top().getItem(slot);
        if (item == null || item.lore() == null) {
            return "";
        }
        return Objects.requireNonNull(item.lore()).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .collect(Collectors.joining("\n"));
    }

    /** Runs the server until nothing it was asked to do is left, the work off the main thread included. */
    private void drain() {
        for (int round = 0; round < 10; round++) {
            server.getScheduler().performOneTick();
            server.getScheduler().waitAsyncTasksFinished();
        }
    }

    /** Runs the server until {@code done} holds: the engine draws a window off the player's thread first. */
    private void settle(java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            if (done.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the server never settled, the window is titled '" + titleOf(ada) + "'");
    }
}
