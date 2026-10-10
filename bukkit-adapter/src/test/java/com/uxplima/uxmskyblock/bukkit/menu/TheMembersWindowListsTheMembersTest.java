package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.binding.MenuBindings;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The members window draws one tile for each member of the viewer's island.
 *
 * <p>Its one members tile sent the player to chat to read who belonged to the island, so the window
 * named the team and never showed it.
 */
class TheMembersWindowListsTheMembersTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private final IslandLocationService locations = mock(IslandLocationService.class);
    private final IslandMembershipService membership = mock(IslandMembershipService.class);
    private final Messages messages = Messages.bundled();

    private final List<UUID> picked = new java.util.ArrayList<>();

    private PlayerMock ada;
    private ProfileId adaProfile;
    private MemberList list;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        adaProfile = ProfileId.of(ada.getUniqueId());
        UUID away = UUID.randomUUID();
        when(locations.findIslandId(adaProfile)).thenReturn(Optional.of(ISLAND));
        when(membership.members(ISLAND))
                .thenReturn(List.of(
                        new IslandMember(
                                PlayerUuid.of(ada.getUniqueId()),
                                adaProfile,
                                IslandRole.OWNER,
                                NOW.minus(Duration.ofDays(3))),
                        new IslandMember(
                                PlayerUuid.of(away),
                                ProfileId.of(away),
                                IslandRole.MEMBER,
                                NOW.minus(Duration.ofHours(2)))));
        list = new MemberList(
                messages,
                uuid -> uuid.equals(ada.getUniqueId()) ? Optional.of(adaProfile) : Optional.empty(),
                locations,
                membership,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Every member is a row: the owner first, their role, how long, and whether they are here")
    void everyMemberIsARow() {
        List<MemberList.Row> rows = list.rows(MenuContext.of(ada, null, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Ada");
        assertThat(rows.get(0).role()).isEqualTo("Owner");
        assertThat(rows.get(0).state()).isEqualTo("Online");
        assertThat(rows.get(0).uuid()).isEqualTo(ada.getUniqueId().toString());
        assertThat(rows.get(1).role()).isEqualTo("Member");
        assertThat(rows.get(1).state()).isEqualTo("Offline");
        assertThat(rows.get(1).since()).isNotBlank();
    }

    @Test
    @DisplayName("A viewer with no island sees no members rather than somebody else's")
    void noIslandIsNoRows() {
        PlayerMock stranger = createPlayer("Bo");

        assertThat(list.rows(MenuContext.of(stranger, null, 0))).isEmpty();
    }

    @Test
    @DisplayName("A member's tile names that member, in the words the catalogue gives a tile")
    void theTileNamesTheMember() {
        MenuBindings bindings = new MenuBindings();
        SkyblockMenuEngine.answerEntries(bindings.placeholders());
        list.register(bindings, (viewer, member) -> picked.add(member));
        MenuContext drawn =
                MenuContext.of(ada, null, 0).withEntry(listed(bindings).get(1));
        MemberList.Row row = MenuRow.handle(drawn, MemberList.Row.class).orElseThrow();
        assertThat(bindings.placeholders().resolve("entry_name", drawn)).hasValue(row.name());
        assertThat(bindings.placeholders().resolve("entry_uuid", drawn)).hasValue(row.uuid());

        String tile = PlainTextComponentSerializer.plainText()
                .serialize(new CatalogueMenuWords(messages)
                        .renderFor(
                                ada,
                                "tile:rank @menu.members.member role since state",
                                askedByName(Map.of(
                                        "entry_name", row.name(),
                                        "entry_role", row.role(),
                                        "entry_since", row.since(),
                                        "entry_state", row.state()))));

        assertThat(tile)
                .contains("◆ " + row.name())
                .contains("Role Member")
                .contains("Now Offline")
                .doesNotContain("<entry_");
    }

    @Test
    @DisplayName("A click on a member's tile opens the roles of that member, not a line of role ids in chat")
    void aClickPicksThatMembersRole() {
        MenuBindings bindings = new MenuBindings();
        list.register(bindings, (viewer, member) -> picked.add(member));
        MemberList.Row row = list.rows(MenuContext.of(ada, null, 0)).get(1);

        bindings.actions()
                .get(MemberList.ROLE_VERB)
                .orElseThrow()
                .accept(new com.uxplima.uxmlib.menu.runtime.MenuActionContext(
                        MenuContext.of(ada, null, 0).withEntry(listed(bindings).get(1)),
                        ada,
                        com.uxplima.uxmlib.menu.spec.ClickKind.LEFT,
                        Map.of()));

        assertThat(picked).containsExactly(UUID.fromString(row.uuid()));
        assertThat(ada.nextMessage()).isNull();
    }

    /** The members as the registered list hands them to the engine, one entry per tile. */
    private List<?> listed(MenuBindings bindings) {
        return bindings.list(MemberList.SOURCE).orElseThrow().apply(MenuContext.of(ada, null, 0));
    }

    /**
     * The values as the engine hands them to a tile: a tile line spells no {@code %token%}, so the map
     * lists nothing and answers a value only when the catalogue asks for it by name.
     */
    private static Map<String, String> askedByName(Map<String, String> values) {
        return new java.util.AbstractMap<>() {
            @Override
            public java.util.Set<Map.Entry<String, String>> entrySet() {
                return java.util.Set.of();
            }

            @Override
            public @org.jspecify.annotations.Nullable String get(Object key) {
                return values.get(key);
            }
        };
    }
}
