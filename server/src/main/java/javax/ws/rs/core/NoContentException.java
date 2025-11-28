/*
 * Minimal stub for JAX-RS NoContentException to maintain compatibility with Jersey 1.x.
 */
package javax.ws.rs.core;

/**
 * Stub implementation to satisfy dependencies that expect the JAX-RS 2.0 NoContentException
 * class while running against the JSR-311 (JAX-RS 1.x) API and Jersey 1.x runtime.
 */
public class NoContentException extends RuntimeException
{
  private static final long serialVersionUID = 1L;

  public NoContentException()
  {
    super();
  }

  public NoContentException(String message)
  {
    super(message);
  }

  public NoContentException(String message, Throwable cause)
  {
    super(message, cause);
  }

  public NoContentException(Throwable cause)
  {
    super(cause);
  }
}
