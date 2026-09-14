package com.uxplima.uxmskyblock.core.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServerNodeIdTest {

    @Test
    @DisplayName("Creates valid ServerNodeId with non-blank string")
    void createsValidServerNodeId() {
        ServerNodeId node = ServerNodeId.of("skyblock-backend-1");
        assertThat(node.value()).isEqualTo("skyblock-backend-1");
        assertThat(node.toString()).isEqualTo("skyblock-backend-1");
    }

    @Test
    @DisplayName("Rejects null in constructor")
    // Explicitly testing defensive null rejection under @NullMarked requires passing null
    @SuppressWarnings("NullAway")
    void rejectsNullInConstructor() {
        assertThatThrownBy(() -> new ServerNodeId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("Rejects blank node identifier")
    void rejectsBlank() {
        assertThatThrownBy(() -> ServerNodeId.of("")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ServerNodeId.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
