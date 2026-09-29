package com.uxplima.uxmskyblock.core.application.gamemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two providers for one creation action fail while the server starts, and a start that names an action
 * nobody provides builds nothing.
 *
 * <p>The game mode architecture names this test. A preset's start is a list of action names and a game
 * mode brings the providers; with two for one name it would be chance which one built an island.
 */
class CreationActionProviderCollisionTest {

    private final List<String> ran = new ArrayList<>();

    @Test
    @DisplayName("A second provider for a name is refused, however the name is written")
    void aSecondProviderIsRefused() {
        CreationActions<String> actions = new CreationActions<>();
        actions.register(provider("uxm:oneblock"));

        assertThatThrownBy(() -> actions.register(provider("uxm:oneblock")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uxm:oneblock");
        assertThatThrownBy(() -> actions.register(provider(" UXM:OneBlock ")))
                .describedAs("the name a preset writes is read without case or spaces")
                .isInstanceOf(IllegalStateException.class);

        actions.run(List.of("uxm:oneblock"), "island");
        assertThat(ran).describedAs("the first provider still answers").containsExactly("uxm:oneblock@island");
    }

    @Test
    @DisplayName("A start runs its actions in the order the preset lists them")
    void theListIsRunInOrder() {
        CreationActions<String> actions = new CreationActions<>();
        actions.register(provider("uxm:platform"));
        actions.register(provider("uxm:chest"));

        actions.run(List.of("uxm:chest", "UXM:PLATFORM"), "island");

        assertThat(ran).containsExactly("uxm:chest@island", "uxm:platform@island");
    }

    @Test
    @DisplayName("A start naming an unknown action fails before any action has run")
    void anUnknownActionBuildsNothing() {
        CreationActions<String> actions = new CreationActions<>();
        actions.register(provider("uxm:platform"));

        assertThat(actions.knowsAll(List.of("uxm:platform"))).isTrue();
        assertThat(actions.knowsAll(List.of("uxm:platform", "uxm:vessel"))).isFalse();
        assertThatThrownBy(() -> actions.run(List.of("uxm:platform", "uxm:vessel"), "island"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uxm:vessel");
        assertThat(ran).describedAs("no island is left half built").isEmpty();
    }

    @Test
    @DisplayName("An action with no name is refused")
    void anActionNeedsAName() {
        CreationActions<String> actions = new CreationActions<>();

        assertThatThrownBy(() -> actions.register(provider("  "))).isInstanceOf(IllegalArgumentException.class);
    }

    private CreationActionProvider<String> provider(String name) {
        return new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return name;
            }

            @Override
            public void apply(String context) {
                ran.add(name.trim().toLowerCase(java.util.Locale.ROOT) + "@" + context);
            }
        };
    }
}
