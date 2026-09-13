package com.uxplima.uxmskyblock.core.domain.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultTest {

    @Test
    @DisplayName("ok(value) wraps success value and provides query methods")
    void okWithValue() {
        Result<String, Integer> result = Result.ok("hello");

        assertThat(result.isOk()).isTrue();
        assertThat(result.isErr()).isFalse();
        assertThat(result.orElseThrow()).isEqualTo("hello");
        assertThat(result.asValue()).contains("hello");
        assertThat(result.asError()).isEmpty();
        assertThat(result.errorOrNull()).isNull();

        assertThatThrownBy(result::errorOrThrow)
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("result is ok, not err");
    }

    @Test
    @DisplayName("ok() wraps Unit.INSTANCE for void-shaped success outcomes")
    void okWithUnit() {
        Result<Unit, String> result = Result.ok();

        assertThat(result.isOk()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo(Unit.INSTANCE);
        assertThat(result.asValue()).contains(Unit.INSTANCE);
    }

    @Test
    @DisplayName("err(error) wraps failure error and provides query methods")
    void errWithError() {
        Result<String, Integer> result = Result.err(404);

        assertThat(result.isOk()).isFalse();
        assertThat(result.isErr()).isTrue();
        assertThat(result.errorOrThrow()).isEqualTo(404);
        assertThat(result.asError()).contains(404);
        assertThat(result.asValue()).isEmpty();
        assertThat(result.errorOrNull()).isEqualTo(404);

        assertThatThrownBy(result::orElseThrow)
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("result is err, not ok");
    }

    @Test
    @DisplayName("rejects null values in factories and records")
    @SuppressWarnings("NullAway")
    void rejectsNull() {
        assertThatThrownBy(() -> Result.ok(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");

        assertThatThrownBy(() -> Result.err(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("error must not be null");
    }

    @Test
    @DisplayName("map transforms success value and leaves error intact")
    void mapTransformsSuccess() {
        Result<Integer, String> ok = Result.ok(21);
        Result<Integer, String> mappedOk = ok.map(x -> x * 2);

        assertThat(mappedOk.isOk()).isTrue();
        assertThat(mappedOk.orElseThrow()).isEqualTo(42);

        Result<Integer, String> err = Result.err("failed");
        Result<Integer, String> mappedErr = err.map(x -> x * 2);

        assertThat(mappedErr.isErr()).isTrue();
        assertThat(mappedErr.errorOrThrow()).isEqualTo("failed");
    }

    @Test
    @DisplayName("mapErr transforms error and leaves success intact")
    void mapErrTransformsError() {
        Result<String, Integer> ok = Result.ok("unchanged");
        Result<String, String> mappedOk = ok.mapErr(code -> "code-" + code);

        assertThat(mappedOk.isOk()).isTrue();
        assertThat(mappedOk.orElseThrow()).isEqualTo("unchanged");

        Result<String, Integer> err = Result.err(500);
        Result<String, String> mappedErr = err.mapErr(code -> "code-" + code);

        assertThat(mappedErr.isErr()).isTrue();
        assertThat(mappedErr.errorOrThrow()).isEqualTo("code-500");
    }

    @Test
    @DisplayName("supports exhaustive pattern matching via sealed permits")
    void patternMatchingExhaustiveness() {
        Result<String, Integer> ok = Result.ok("success");
        String message =
                switch (ok) {
                    case Result.Ok<String, Integer> success -> "VALUE:" + success.value();
                    case Result.Err<String, Integer> failure -> "ERROR:" + failure.error();
                };

        assertThat(message).isEqualTo("VALUE:success");
    }
}
