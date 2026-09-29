package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.entity.Player;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The lore of the sea is read by a Poseidon island's players only: as a written book on Java, and as a
 * form a page at a time on Bedrock, every page from the reader's language file.
 */
class ThePoseidonLoreIsReadTest extends MockBukkitHarness {

    private static final ProfileId SEA_PROFILE = new ProfileId(UUID.randomUUID());
    private static final ProfileId LAND_PROFILE = new ProfileId(UUID.randomUUID());

    private final Messages messages = Messages.bundled();
    private final List<Book> opened = new ArrayList<>();
    private final List<Form> forms = new ArrayList<>();
    private final Map<ProfileId, GameModeType> modes =
            Map.of(SEA_PROFILE, GameModeType.POSEIDON, LAND_PROFILE, GameModeType.SKYBLOCK);

    private record Form(String title, String body, List<String> buttons, IntConsumer pick) {}

    @SuppressWarnings("NullAway.Init")
    private BedrockScreen screen;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock diver;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock farmer;

    private boolean bedrock;

    @BeforeEach
    void setUpLore() {
        diver = createPlayer("Diver");
        farmer = createPlayer("Farmer");
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        screen = mock(BedrockScreen.class);
        doAnswer(call -> {
                    List<BedrockButton> buttons = call.getArgument(3);
                    forms.add(new Form(
                            call.getArgument(1),
                            call.getArgument(2),
                            buttons.stream().map(BedrockButton::text).toList(),
                            call.getArgument(4)));
                    return null;
                })
                .when(screen)
                .sendSimpleForm(any(Player.class), anyString(), anyString(), any(), any(IntConsumer.class));
    }

    private PoseidonLore lore() {
        return new PoseidonLore(
                messages,
                scheduler,
                uuid -> uuid.equals(diver.getUniqueId())
                        ? Optional.of(SEA_PROFILE)
                        : uuid.equals(farmer.getUniqueId()) ? Optional.of(LAND_PROFILE) : Optional.empty(),
                profile -> {
                    GameModeType mode = modes.get(profile);
                    if (mode == null) {
                        throw new IllegalStateException("the database is gone");
                    }
                    return mode;
                },
                player -> bedrock,
                screen,
                (player, book) -> opened.add(book));
    }

    @Test
    @DisplayName("A Java player on a Poseidon island reads the book, every page of it")
    void aJavaPlayerReadsTheBook() {
        lore().open(diver);

        assertThat(opened).hasSize(1);
        Book book = opened.getFirst();
        assertThat(plain(book.title())).isEqualTo("The Drowned Chronicle");
        assertThat(plain(book.author())).isEqualTo("The Sea");
        assertThat(book.pages())
                .hasSize(messages.provider()
                        .getRawList("poseidon.lore.pages", "en")
                        .size());
        assertThat(plain(book.pages().get(2))).contains("Keep moving");
        verify(screen, never()).sendSimpleForm(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("A Bedrock player reads a form a page at a time, back and on, and closing opens nothing")
    void aBedrockPlayerTurnsPages() {
        bedrock = true;
        int pages = messages.provider().getRawList("poseidon.lore.pages", "en").size();

        lore().open(diver);

        assertThat(opened).isEmpty();
        assertThat(forms).hasSize(1);
        assertThat(forms.getFirst().title()).isEqualTo("The Drowned Chronicle");
        assertThat(forms.getFirst().body()).contains("born of the sea");
        assertThat(forms.getFirst().buttons()).containsExactly("Next page", "Close");

        forms.getLast().pick().accept(0);
        assertThat(forms.getLast().buttons()).containsExactly("Previous page", "Next page", "Close");
        assertThat(forms.getLast().body()).contains("Breathe");
        forms.getLast().pick().accept(0);
        assertThat(forms.getLast().body()).contains("born of the sea");

        for (int page = 1; page < pages; page++) {
            forms.getLast().pick().accept(forms.getLast().buttons().size() - 2);
        }
        assertThat(forms.getLast().buttons()).containsExactly("Previous page", "Close");
        int shown = forms.size();
        forms.getLast().pick().accept(1);
        forms.getLast().pick().accept(-1);
        assertThat(forms).hasSize(shown);
        verify(screen, times(shown)).sendSimpleForm(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("A player on an island of another mode, with no island, or whose mode cannot be read, is told so")
    void onlyPoseidonReads() {
        lore().open(farmer);
        assertThat(plain(farmer.nextComponentMessage())).contains("not a Poseidon island");

        PlayerMock stranger = createPlayer("Stranger");
        lore().open(stranger);
        assertThat(plain(stranger.nextComponentMessage())).contains("not a Poseidon island");

        PoseidonLore broken = new PoseidonLore(
                messages,
                scheduler,
                uuid -> Optional.of(new ProfileId(UUID.randomUUID())),
                profile -> {
                    throw new IllegalStateException("the database is gone");
                },
                player -> false,
                screen,
                (player, book) -> opened.add(book));
        broken.open(diver);
        assertThat(plain(diver.nextComponentMessage())).contains("not a Poseidon island");
        assertThat(opened).isEmpty();
    }

    @Test
    @DisplayName("Every language the plugin ships tells the lore, in as many pages as it writes")
    void everyLanguageTellsIt() {
        for (String language : messages.provider().getAvailableLocales()) {
            assertThat(messages.provider().getRawList("poseidon.lore.pages", language))
                    .describedAs("the pages in %s", language)
                    .isNotEmpty();
            assertThat(messages.provider().getRawList("poseidon.lore.pages", language))
                    .hasSameSizeAs(messages.provider().getRawList("poseidon.lore.pages", "en"));
        }
    }

    private static String plain(net.kyori.adventure.text.@org.jspecify.annotations.Nullable Component line) {
        return line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line);
    }
}
