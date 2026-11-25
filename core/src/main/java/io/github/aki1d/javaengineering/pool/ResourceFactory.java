package io.github.aki1d.javaengineering.pool;

/**
 * Creates, validates and destroys pooled resources such as connections or parsers.
 *
 * <p>The pool depends on this interface rather than on concrete resource types (dependency
 * inversion), so the same pool works for any expensive object and is testable with a fake.
 */
public interface ResourceFactory<R> {

  /**
   * Creates a new resource. May be slow (for example opening a network connection).
   *
   * @throws Exception if the resource cannot be created; the pool wraps it in a {@link
   *     PoolException}
   */
  R create() throws Exception;

  /** Returns whether an idle resource is still usable, for example whether a socket is open. */
  boolean isValid(R resource);

  /** Releases the resource. Must not throw for a resource that is already broken. */
  void destroy(R resource);
}
