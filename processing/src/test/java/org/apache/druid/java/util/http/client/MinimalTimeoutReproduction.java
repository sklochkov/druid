package org.apache.druid.java.util.http.client;

import io.netty.handler.timeout.ReadTimeoutException;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.lifecycle.Lifecycle;
import org.apache.druid.java.util.http.client.response.StatusResponseHandler;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.joda.time.Duration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Minimal reproduction case for ReadTimeoutHandler not firing issue.
 * 
 * This test creates a "silent server" that accepts connections but never sends a response.
 * Expected: Client should timeout after 100ms with ReadTimeoutException
 * Actual (BUG): Client hangs indefinitely
 * 
 * To run:
 *   mvn test-compile -pl processing
 *   mvn exec:java -pl processing -Dexec.mainClass="org.apache.druid.java.util.http.client.MinimalTimeoutReproduction"
 */
public class MinimalTimeoutReproduction
{
  public static void main(String[] args) throws Exception
  {
    System.out.println("=== Netty 4 ReadTimeout Reproduction Test ===");
    
    // Setup: Create a server that accepts connections but never responds
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final ServerSocket silentServer = new ServerSocket(0);
    final int port = silentServer.getLocalPort();
    
    System.out.println("Silent server listening on port: " + port);
    
    exec.submit(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        try (Socket clientSocket = silentServer.accept()) {
          // Accept connection but never send response
          // Just keep connection open
          Thread.sleep(Long.MAX_VALUE);
        } catch (Exception e) {
          // Suppress
        }
      }
    });
    
    // Test: Create HTTP client with 100ms read timeout
    final Lifecycle lifecycle = new Lifecycle();
    try {
      System.out.println("\nCreating HTTP client with 100ms read timeout...");
      final HttpClientConfig config = HttpClientConfig.builder()
          .withReadTimeout(new Duration(100))
          .build();
      
      final HttpClient client = HttpClientInit.createClient(config, lifecycle);
      
      System.out.println("Starting lifecycle...");
      lifecycle.start();  // Explicitly start
      
      System.out.println("Making HTTP request to silent server...");
      System.out.println("Expected: Should timeout after 100ms with ReadTimeoutException");
      
      final long startTime = System.currentTimeMillis();
      
      final Future<StatusResponseHolder> future = client.go(
          new Request(
              io.netty.handler.codec.http.HttpMethod.GET,
              new URL(StringUtils.format("http://localhost:%d/", port))
          ),
          StatusResponseHandler.getInstance()
      );
      
      Throwable caughtException = null;
      try {
        // Wait up to 5 seconds for the request (it should timeout after 100ms)
        StatusResponseHolder response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("ERROR: Request succeeded when it should have timed out!");
      } catch (java.util.concurrent.TimeoutException e) {
        System.out.println("ERROR: future.get() timed out after 5s - ReadTimeoutHandler never fired!");
        caughtException = e;
      } catch (ExecutionException e) {
        caughtException = e.getCause();
      }
      
      final long elapsed = System.currentTimeMillis() - startTime;
      System.out.println("\nElapsed time: " + elapsed + "ms");
      
      if (caughtException instanceof ReadTimeoutException) {
        System.out.println("SUCCESS: Got ReadTimeoutException as expected!");
        System.out.println("Exception: " + caughtException.getMessage());
      } else if (caughtException != null) {
        System.out.println("UNEXPECTED EXCEPTION: " + caughtException.getClass().getName());
        System.out.println("Message: " + caughtException.getMessage());
        caughtException.printStackTrace();
      } else {
        System.out.println("BUG: No exception thrown - timeout did not fire!");
      }
      
      System.out.println("\nStopping lifecycle...");
      lifecycle.stop();
      System.out.println("Lifecycle stopped");
      
    } finally {
      exec.shutdownNow();
      silentServer.close();
      System.out.println("\nTest cleanup complete");
    }
    
    System.out.println("\n=== Test Complete ===");
    System.exit(0);
  }
}

