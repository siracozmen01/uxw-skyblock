package com.uxplima.uxmskyblock.core.domain.result;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * A sealed outcome representing either a success value of type {@code T} or a domain error of type
 * {@code E}.
 *
 * <p>Used internally by domain and application services for expected business rule failures instead
 * of throwing expensive exceptions, enabling exhaustive compile-time pattern matching.
 *
 * @param <T> the success value type
 * @param <E> the modelled error type
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(Objects.requireNonNull(value, "value must not be null"));
    }

    static <E> Result<Unit, E> ok() {
        return new Ok<>(Unit.INSTANCE);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(Objects.requireNonNull(error, "error must not be null"));
    }

    boolean isOk();

    default boolean isErr() {
        return !isOk();
    }

    T orElseThrow();

    E errorOrThrow();

    Optional<T> asValue();

    Optional<E> asError();

    @Nullable default E errorOrNull() {
        return asError().orElse(null);
    }

    <U> Result<U, E> map(Function<? super T, ? extends U> mapper);

    <F> Result<T, F> mapErr(Function<? super E, ? extends F> mapper);

    record Ok<T, E>(T value) implements Result<T, E> {

        public Ok {
            Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public T orElseThrow() {
            return value;
        }

        @Override
        public E errorOrThrow() {
            throw new NoSuchElementException("result is ok, not err");
        }

        @Override
        public Optional<T> asValue() {
            return Optional.of(value);
        }

        @Override
        public Optional<E> asError() {
            return Optional.empty();
        }

        @Override
        public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper, "mapper must not be null");
            return Result.ok(mapper.apply(value));
        }

        @Override
        // Casting is sound because Ok carries only value of type T and zero instances of E
        @SuppressWarnings("unchecked")
        public <F> Result<T, F> mapErr(Function<? super E, ? extends F> mapper) {
            Objects.requireNonNull(mapper, "mapper must not be null");
            return (Result<T, F>) this;
        }
    }

    record Err<T, E>(E error) implements Result<T, E> {

        public Err {
            Objects.requireNonNull(error, "error must not be null");
        }

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public T orElseThrow() {
            throw new NoSuchElementException("result is err, not ok");
        }

        @Override
        public E errorOrThrow() {
            return error;
        }

        @Override
        public Optional<T> asValue() {
            return Optional.empty();
        }

        @Override
        public Optional<E> asError() {
            return Optional.of(error);
        }

        @Override
        // Casting is sound because Err carries only error of type E and zero instances of T
        @SuppressWarnings("unchecked")
        public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper, "mapper must not be null");
            return (Result<U, E>) this;
        }

        @Override
        public <F> Result<T, F> mapErr(Function<? super E, ? extends F> mapper) {
            Objects.requireNonNull(mapper, "mapper must not be null");
            return Result.err(mapper.apply(error));
        }
    }
}
