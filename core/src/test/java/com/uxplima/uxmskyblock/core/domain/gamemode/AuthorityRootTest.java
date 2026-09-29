package com.uxplima.uxmskyblock.core.domain.gamemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A root that is not an island is named by its provider and its key, and only an instance is held as one. */
class AuthorityRootTest {

    @Test
    @DisplayName("An instance is held under the instance provider, by its id")
    void anInstance() {
        GameModeInstanceId id = GameModeInstanceId.random();

        AuthorityRoot root = AuthorityRoot.instance(id);

        assertThat(root.scope()).isEqualTo(AuthorityRoot.Scope.GAME_MODE_INSTANCE);
        assertThat(root.providerId()).isEqualTo(AuthorityRoot.INSTANCE_PROVIDER);
        assertThat(root.key()).isEqualTo(id.value().toString());
    }

    @Test
    @DisplayName("A mode names its own roots, never under the instance provider, and never without a namespace")
    void aModeOwnedRoot() {
        assertThat(AuthorityRoot.modeOwned("tradewinds:vessel", "v-1").scope())
                .isEqualTo(AuthorityRoot.Scope.MODE_OWNED);
        assertThatThrownBy(() -> AuthorityRoot.modeOwned(AuthorityRoot.INSTANCE_PROVIDER, "v-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorityRoot.modeOwned("vessel", "v-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorityRoot.modeOwned("Trade:Vessel", "v-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorityRoot.modeOwned("tradewinds:vessel", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorityRoot.modeOwned("tradewinds:vessel", "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
