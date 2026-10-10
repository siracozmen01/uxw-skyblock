package com.uxplima.uxmskyblock.core.application.gamemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An action that spreads its work over ticks, a pasted structure, holds back the actions after it until it is
 * done, and the next one runs on the thread the start hands it to, not on whichever finished the work.
 */
class AnActionThatFinishesLaterHoldsTheRestTest {

    private final List<String> ran = new ArrayList<>();
    private final List<Runnable> handedBack = new ArrayList<>();
    private final Executor region = handedBack::add;

    @Test
    @DisplayName("Actions done at once run one after another at once, and the start is done when they are")
    void actionsDoneAtOnce() {
        CreationActions<String> actions = new CreationActions<>();
        actions.register(now("uxm:platform"));
        actions.register(now("uxm:chest"));

        CompletableFuture<Void> start = actions.runThen(List.of("uxm:platform", "uxm:chest"), "island", region);

        assertThat(start).isCompleted();
        assertThat(ran).containsExactly("uxm:platform", "uxm:chest");
        assertThat(handedBack)
                .describedAs("nothing waited, so nothing was handed back")
                .isEmpty();
    }

    @Test
    @DisplayName("An action finishing later holds the next until it is done, then hands the next to the place's thread")
    void anActionFinishingLater() {
        CompletableFuture<Void> paste = new CompletableFuture<>();
        CreationActions<String> actions = new CreationActions<>();
        actions.register(later("uxm:schematic", paste));
        actions.register(now("uxm:chest"));

        CompletableFuture<Void> start = actions.runThen(List.of("uxm:schematic", "uxm:chest"), "island", region);

        assertThat(ran).containsExactly("uxm:schematic");
        assertThat(start).isNotDone();
        paste.complete(null);
        assertThat(ran).describedAs("not on the thread that finished the paste").containsExactly("uxm:schematic");
        assertThat(handedBack).hasSize(1);
        handedBack.removeFirst().run();
        assertThat(ran).containsExactly("uxm:schematic", "uxm:chest");
        assertThat(start).isCompleted();
    }

    @Test
    @DisplayName("The last action finishing later ends the start when it is done, with nothing handed back")
    void theLastActionFinishingLater() {
        CompletableFuture<Void> paste = new CompletableFuture<>();
        CreationActions<String> actions = new CreationActions<>();
        actions.register(now("uxm:platform"));
        actions.register(later("uxm:schematic", paste));

        CompletableFuture<Void> start = actions.runThen(List.of("uxm:platform", "uxm:schematic"), "island", region);
        paste.complete(null);

        assertThat(start).isCompleted();
        assertThat(handedBack).isEmpty();
    }

    @Test
    @DisplayName("An action that fails stops the start, and says why")
    void aFailingActionStops() {
        CompletableFuture<Void> paste = new CompletableFuture<>();
        CreationActions<String> actions = new CreationActions<>();
        actions.register(later("uxm:schematic", paste));
        actions.register(now("uxm:chest"));
        actions.register(new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return "uxm:broken";
            }

            @Override
            public void apply(String context) {
                throw new IllegalStateException("no room");
            }
        });

        CompletableFuture<Void> broken = actions.runThen(List.of("uxm:broken", "uxm:chest"), "island", region);
        CompletableFuture<Void> failed = actions.runThen(List.of("uxm:schematic", "uxm:chest"), "island", region);
        paste.completeExceptionally(new IllegalStateException("the file is gone"));

        assertThat(broken).isCompletedExceptionally();
        assertThat(failed).isCompletedExceptionally();
        assertThat(handedBack).isEmpty();
        assertThat(ran).containsExactly("uxm:schematic");
    }

    @Test
    @DisplayName("A start naming an unknown action fails before any action has run")
    void anUnknownActionRunsNothing() {
        CreationActions<String> actions = new CreationActions<>();
        actions.register(now("uxm:platform"));

        assertThatThrownBy(() -> actions.runThen(List.of("uxm:platform", "uxm:vessel"), "island", region))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ran).isEmpty();
    }

    private CreationActionProvider<String> now(String id) {
        return new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return id;
            }

            @Override
            public void apply(String context) {
                ran.add(id);
            }
        };
    }

    private CreationActionProvider<String> later(String id, CompletableFuture<Void> done) {
        return new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return id;
            }

            @Override
            public void apply(String context) {
                ran.add(id);
            }

            @Override
            public CompletableFuture<Void> applyThen(String context) {
                apply(context);
                return done;
            }
        };
    }
}
