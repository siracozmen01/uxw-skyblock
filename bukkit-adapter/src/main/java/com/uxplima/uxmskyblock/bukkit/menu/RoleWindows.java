package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import com.uxplima.uxmlib.menu.runtime.MenuHolder;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.jspecify.annotations.Nullable;

/**
 * The roles of an island as three windows, each a menu file: the roles, what one role may do, and the
 * role one member holds.
 *
 * <p>The roles tile of the members window promised a role editor and sent the player to chat, where
 * every permission was its constant in lower case. A click on a member listed the role ids to type.
 *
 * <p>A click changes nothing here. It runs the island command under the operator's names for it, so
 * the window and the command share every check, every message and every record of the change. When
 * the change is written the service says so, and every window showing that island's roles is drawn
 * again, whoever made the change.
 *
 * <p>A file names a tile's icon in its own {@code placeholders} block, {@code icon_<id>} for one
 * permission or role and {@code icon_default} for the rest, so no material is decided here.
 */
public final class RoleWindows {

    static final String ROLES_FILE = "island-roles";
    static final String PERMISSIONS_FILE = "island-role-permissions";
    static final String MEMBER_FILE = "island-member-role";

    static final String ROLES = "skyblock:roles";
    static final String PERMISSIONS = "skyblock:role-permissions";
    static final String MEMBER_ROLES = "skyblock:member-roles";

    /** The requirement that holds in the window of a role the island's owner made. */
    static final String OWN_MADE = "skyblock:role-own-made";

    /** The verb that takes the role a window shows away. */
    static final String DELETE = "skyblock:role-delete";

    private final SkyblockMenuEngine engine;
    private final Messages messages;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final IslandLocationService locations;
    private final SchedulerPort scheduler;
    private final UnaryOperator<String> typed;
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    private final Set<UUID> showAgain = ConcurrentHashMap.newKeySet();

    /** A role on the roles window. */
    record RoleEntry(String roleId) {}

    /** One permission of one role, as it stood when the window was drawn. */
    record PermissionEntry(String roleId, IslandPermission permission, boolean allowed) {}

    /** A role one member could be given. */
    record MemberRoleEntry(String member, String roleId) {}

    /** The window a viewer was last shown and what it was about, so a change draws it again. */
    private record Shown(IslandId island, String file, String about) {}

    /**
     * @param typed an island command line under the names the operator gave the command
     */
    public RoleWindows(
            SkyblockMenuEngine engine,
            Messages messages,
            Function<UUID, Optional<ProfileId>> activeProfile,
            IslandLocationService locations,
            SchedulerPort scheduler,
            UnaryOperator<String> typed) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.typed = Objects.requireNonNull(typed, "typed must not be null");
    }

    /** Registers the three lists and the verbs their files name. */
    public void register() {
        engine.handedList(ROLES);
        engine.handedList(PERMISSIONS);
        engine.handedList(MEMBER_ROLES);
        engine.action("skyblock:permissions", ctx -> show(ctx.player(), ROLES_FILE, "", true));
        engine.action(
                "skyblock:role-edit",
                ctx -> MenuRow.handle(ctx.context(), RoleEntry.class)
                        .ifPresent(entry -> show(ctx.player(), PERMISSIONS_FILE, entry.roleId(), true)));
        engine.action(
                "skyblock:role-permission",
                ctx -> MenuRow.handle(ctx.context(), PermissionEntry.class)
                        .ifPresent(entry -> change(
                                ctx.player(),
                                "permissions " + lower(entry.roleId()) + " "
                                        + lower(entry.permission().name()) + " " + (entry.allowed() ? "off" : "on"))));
        // A role the owner made is taken away from its own window; one every island has shows no such button.
        engine.bindings()
                .condition(OWN_MADE, (ctx, args) -> "yes".equals(ctx.arguments().get("role_own")));
        engine.action(DELETE, ctx -> {
            String roleId = ctx.context().arguments().get("role_id");
            if (roleId != null && !roleId.isBlank()) {
                change(ctx.player(), "permissions delete " + roleId);
            }
        });
        engine.action(
                "skyblock:member-role-give",
                ctx -> MenuRow.handle(ctx.context(), MemberRoleEntry.class)
                        .ifPresent(
                                entry -> change(ctx.player(), "role " + entry.member() + " " + lower(entry.roleId()))));
    }

    /**
     * Runs the island command a click asks for. A Bedrock form closes when it is tapped, so a player who
     * changes something from one is shown it again once the change is written.
     */
    private void change(Player viewer, String line) {
        Inventory top = viewer.getOpenInventory().getTopInventory();
        if (top == null || !(top.getHolder() instanceof MenuHolder)) {
            showAgain.add(viewer.getUniqueId());
        }
        viewer.performCommand(typed.apply(line));
    }

    /** Opens the roles window, as {@code /is permissions} does, or answers false when the operator removed it. */
    public boolean openRoles(Player viewer) {
        if (!engine.has(ROLES_FILE)) {
            return false;
        }
        show(viewer, ROLES_FILE, "", true);
        return true;
    }

    /** Opens the roles {@code member} could be given, the click on a member's tile. */
    public void pickRole(Player viewer, UUID member) {
        show(viewer, MEMBER_FILE, member.toString(), true);
    }

    /** Draws again every window that shows the roles of {@code island}. Any thread. */
    public void rolesChanged(IslandId island) {
        shown.forEach((viewer, window) -> {
            if (!window.island().equals(island)) {
                return;
            }
            Player player = Bukkit.getPlayer(viewer);
            if (player == null) {
                shown.remove(viewer, window);
                return;
            }
            show(player, window.file(), window.about(), showAgain.remove(viewer));
        });
    }

    /**
     * Reads the viewer's island off their thread and opens {@code file} on it, or draws it again in
     * place when {@code open} is false and the viewer still has it up.
     */
    private void show(Player viewer, String file, String about, boolean open) {
        UUID uuid = viewer.getUniqueId();
        scheduler.async(() -> {
            Optional<IslandId> islandId = activeProfile.apply(uuid).flatMap(locations::findIslandId);
            Optional<Island> island = islandId.flatMap(locations::findIsland);
            scheduler.onEntity(uuid, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (island.isEmpty()) {
                    if (open) {
                        messages.send(viewer, "error.no_island");
                    }
                    return;
                }
                Optional<Window> drawn = drawn(viewer, island.get(), file, about);
                // The role this window showed was taken away: whoever still has it up sees the roles that are left.
                boolean gone = drawn.isEmpty() && PERMISSIONS_FILE.equals(file);
                if (gone && !open && !engine.showing(viewer, PERMISSIONS_FILE)) {
                    shown.remove(uuid);
                    return;
                }
                String showing = gone ? ROLES_FILE : file;
                String showingAbout = gone ? "" : about;
                boolean opening = open || gone;
                (gone ? drawn(viewer, island.get(), ROLES_FILE, "") : drawn).ifPresent(window -> {
                    shown.put(uuid, new Shown(island.get().id(), showing, showingAbout));
                    if (!opening) {
                        engine.redraw(viewer, showing, window.lists());
                    } else if (!engine.open(viewer, showing, window.values(), window.lists())) {
                        // The file is gone or would not parse: the command says it in chat instead.
                        viewer.performCommand(typed.apply(window.fallback()));
                    }
                });
            });
        });
    }

    /** What one window is drawn with, and the command that says it in chat when its file is missing. */
    record Window(Map<String, String> values, Map<String, List<?>> lists, String fallback) {}

    /** The window {@code file} draws for {@code viewer}, or nothing when the viewer was told why not. */
    Optional<Window> drawn(Player viewer, Island island, String file, String about) {
        return switch (file) {
            case ROLES_FILE ->
                Optional.of(new Window(Map.of(), Map.of(ROLES, roleRows(viewer, island)), "permissions"));
            case PERMISSIONS_FILE ->
                Optional.ofNullable(island.roles().get(about))
                        .map(role -> new Window(
                                Map.of(
                                        "role", roleName(viewer, role),
                                        "role_id", lower(role.id()),
                                        "role_own",
                                                com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper
                                                                .isOwnMade(role)
                                                        ? "yes"
                                                        : "no"),
                                Map.of(PERMISSIONS, permissionRows(viewer, role)),
                                "permissions"));
            case MEMBER_FILE -> memberWindow(viewer, island, about);
            default -> Optional.empty();
        };
    }

    /** Every role but the owner's, the highest first, with how much of the whole it may do. */
    List<MenuRow> roleRows(Player viewer, Island island) {
        Map<String, String> icons = iconsOf(ROLES_FILE);
        List<MenuRow> rows = new ArrayList<>();
        for (IslandRole role : byWeight(island)) {
            if (role.id().equals(IslandRole.OWNER.id())) {
                continue;
            }
            rows.add(new MenuRow(
                    Map.of(
                            "role_name", roleName(viewer, role),
                            "allowed", Integer.toString(role.permissions().size()),
                            "total", Integer.toString(IslandPermission.values().length),
                            "material", iconFor(icons, role.id())),
                    new RoleEntry(role.id())));
        }
        return rows;
    }

    /** Every permission there is, in the order the game groups them, on or off for {@code role}. */
    List<MenuRow> permissionRows(Player viewer, IslandRole role) {
        Map<String, String> icons = iconsOf(PERMISSIONS_FILE);
        List<MenuRow> rows = new ArrayList<>();
        for (IslandPermission permission : IslandPermission.values()) {
            boolean allowed = role.permissions().contains(permission);
            rows.add(new MenuRow(
                    Map.of(
                            "permission", messages.named(viewer, "permissions", permission.name(), permission.name()),
                            "status",
                                    messages.words(
                                            viewer,
                                            allowed
                                                    ? "@menu.role_permissions.allowed"
                                                    : "@menu.role_permissions.denied"),
                            "colour", allowed ? "good" : "bad",
                            "material", iconFor(icons, permission.name())),
                    new PermissionEntry(role.id(), permission, allowed)));
        }
        return rows;
    }

    /**
     * The roles {@code about}, a member's uuid, could be given. The owner's tile says the owner's role
     * stays rather than offering roles the owner cannot be given.
     */
    private Optional<Window> memberWindow(Player viewer, Island island, String about) {
        Optional<IslandMember> member = island.members().values().stream()
                .filter(candidate -> candidate.playerUuid().value().toString().equals(about))
                .findFirst();
        if (member.isEmpty()) {
            return Optional.empty();
        }
        if (member.get().profileId().equals(island.ownerProfileId())) {
            messages.send(viewer, "member.owner_role_stays");
            return Optional.empty();
        }
        String name = Optional.ofNullable(
                        Bukkit.getOfflinePlayer(member.get().playerUuid().value())
                                .getName())
                .orElse(about);
        return Optional.of(new Window(
                Map.of("player", name),
                Map.of(MEMBER_ROLES, memberRoleRows(viewer, island, member.get(), name)),
                "role " + name));
    }

    /** Every role a member may be given, the highest first, the one they hold marked. */
    List<MenuRow> memberRoleRows(Player viewer, Island island, IslandMember member, String name) {
        Map<String, String> icons = iconsOf(MEMBER_FILE);
        List<MenuRow> rows = new ArrayList<>();
        for (IslandRole role : byWeight(island)) {
            if (role.isSystem()) {
                continue;
            }
            boolean holds = role.id().equals(member.role().id());
            rows.add(new MenuRow(
                    Map.of(
                            "role_name", roleName(viewer, role),
                            "holds",
                                    messages.words(
                                            viewer, holds ? "@menu.member_role.holds" : "@menu.member_role.free"),
                            "colour", holds ? "good" : "rank",
                            "material", iconFor(icons, role.id())),
                    new MemberRoleEntry(name, role.id())));
        }
        return rows;
    }

    private String roleName(Player viewer, IslandRole role) {
        return messages.named(viewer, "roles", role.id(), role.displayName());
    }

    private Map<String, String> iconsOf(String file) {
        return engine.spec(file).map(MenuSpec::placeholders).orElse(Map.of());
    }

    private static String iconFor(Map<String, String> icons, String id) {
        String own = icons.get("icon_" + lower(id));
        return own != null ? own : icons.getOrDefault("icon_default", "");
    }

    private static List<IslandRole> byWeight(Island island) {
        return island.roles().values().stream()
                .sorted(Comparator.comparingInt(IslandRole::weight).reversed())
                .toList();
    }

    private static String lower(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    /** The window a viewer was last shown, for a test. */
    @Nullable String shownTo(UUID viewer) {
        Shown window = shown.get(viewer);
        return window == null ? null : window.file() + " " + window.about();
    }
}
