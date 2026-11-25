package io.github.aki1d.javaengineering.pipeline;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Result of processing one item: either a value or the error that prevented it.
 *
 * <p>In a batch, one bad item should not hide the results of the other thousand. Returning an
 * {@code Outcome} per item keeps failures as data that callers handle explicitly, typically with an
 * exhaustive {@code switch}:
 *
 * <pre>{@code
 * switch (outcome) {
 *   case Outcome.Success<Invoice>(var invoice) -> send(invoice);
 *   case Outcome.Failure<Invoice>(var error) -> deadLetter(error);
 * }
 * }</pre>
 */
public sealed interface Outcome<T> {

  record Success<T>(T value) implements Outcome<T> {}

  record Failure<T>(Throwable error) implements Outcome<T> {
    public Failure {
      Objects.requireNonNull(error, "error");
    }
  }

  static <T> Outcome<T> success(T value) {
    return new Success<>(value);
  }

  /** Wraps an error, unwrapping the {@link CompletionException} layers added by futures. */
  static <T> Outcome<T> failure(Throwable error) {
    Throwable cause = error;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return new Failure<>(cause);
  }

  default boolean isSuccess() {
    return this instanceof Success<T>;
  }
}
