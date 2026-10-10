package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.menu.binding.MenuBindings;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;

/**
 * The members of the viewer's island, as a list a menu file draws one tile each.
 *
 * <p>The members window had a tile that said "Members" and sent the player to chat to read them. A
 * file now writes {@code list { source = "skyblock:members" }} and a template, and the template's
 * words ask for {@code <entry_name>}, {@code <entry_role>}, {@code <entry_since>} and
 * {@code <entry_state>}. The file draws each member's own head as {@code head:%entry_uuid%}.
 *
 * <p>The list is read off the player's thread, where the island is: every word a tile shows is worked
 * out then, so drawing the tile reads nothing.
 */
public final class MemberList {

    /** The id a menu file names as the list's source. */
    public static final String SOURCE = "skyblock:members";

    /** The verb a member's tile runs: the roles that member could be given, under the operator's names. */
    public static final String ROLE_VERB = "skyblock:member-role";

    private final Messages messages;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final IslandLocationService locations;
    private final IslandMembershipService membership;
    private final Clock clock;

    public MemberList(
            Messages messages,
            Function<UUID, Optional<ProfileId>> activeProfile,
            IslandLocationService locations,
            IslandMembershipService membership,
            Clock clock) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.membership = Objects.requireNonNull(membership, "membership must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** One member as a tile shows them, in the viewer's language. */
    public record Row(String uuid, String name, String role, String since, String state) {

        public Row {
            Objects.requireNonNull(uuid, "uuid must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(since, "since must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }

        /** The words a tile asks for as {@code <entry_<name>>}, without the prefix. */
        public Map<String, String> words() {
            return Map.of("uuid", uuid, "name", name, "role", role, "since", since, "state", state);
        }
    }

    /** What a click on a member's tile opens: the roles that member could be given. */
    @FunctionalInterface
    public interface RolePicker {
        void pick(Player viewer, UUID member);
    }

    /**
     * Registers the list and the verb a tile runs.
     *
     * <p>Each member is handed over as a {@link MenuRow}, whose words every list answers the same way. This
     * registered {@code entry_name} and four more by name, and a name registered by one list is the answer for
     * every list: the warp directory asked for its warp's name, was told there was no member, and drew every warp
     * with a blank title.
     */
    public void register(MenuBindings bindings, RolePicker picker) {
        Objects.requireNonNull(bindings, "bindings must not be null");
        Objects.requireNonNull(picker, "picker must not be null");
        bindings.list(
                SOURCE,
                ctx -> rows(ctx).stream()
                        .map(row -> new MenuRow(row.words(), row))
                        .toList());
        bindings.action(
                ROLE_VERB,
                ctx -> MenuRow.handle(ctx.context(), Row.class)
                        .ifPresent(row -> picker.pick(ctx.player(), UUID.fromString(row.uuid()))));
    }

    /** The members of the viewer's island, the owner first, or none when the viewer has no island. */
    List<Row> rows(MenuContext ctx) {
        Player viewer = ctx.viewer();
        Optional<ProfileId> profile = activeProfile.apply(viewer.getUniqueId());
        if (profile.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<Row> rows = new ArrayList<>();
        locations.findIslandId(profile.get()).ifPresent(island -> {
            for (IslandMember member : membership.members(island)) {
                rows.add(row(viewer, member, now));
            }
        });
        return List.copyOf(rows);
    }

    private Row row(Player viewer, IslandMember member, Instant now) {
        UUID uuid = member.playerUuid().value();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        Duration since = Duration.between(member.joinedAt(), now);
        boolean online = Bukkit.getPlayer(uuid) != null;
        return new Row(
                uuid.toString(),
                name == null ? uuid.toString().substring(0, 8) : name,
                messages.named(
                        viewer, "roles", member.role().id(), member.role().displayName()),
                DurationText.of(messages, viewer, since.isNegative() ? Duration.ZERO : since),
                messages.words(viewer, online ? "@menu.members.member.online" : "@menu.members.member.offline"));
    }
}
