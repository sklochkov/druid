# CRITICAL PRODUCTION ISSUE: EventLoopGroup Shutdown Hangs

## Problem Confirmed

**JankyServersTest:** Hangs indefinitely (timeout after 180s)  
**Production:** Compaction operations stall, require coordinator restart

**Root Cause:** EventLoopGroup.terminationFuture().await() hangs when channels won't close.

## The Issue

When an HTTP request is made to a server that:
- Accepts connection but never responds (JankyServersTest silent server)
- Is slow to respond (production compaction endpoints under load)
- Has network issues

The channel stays open. When we try to shut down:
1. `pool.close()` tries to close channels
2. `channel.close().await(5s)` times out for stuck channels
3. `workerGroup.terminationFuture().await()` hangs forever waiting for channels to close
4. **DEADLOCK**: Can't shutdown until channels close, but channels won't close

## Why This Breaks Production

In compaction scenarios:
1. Compaction task makes HTTP request to historical node
2. Network issue or slow response causes connection to hang
3. When task completes/fails, it tries to cleanup HTTP client
4. `lifecycle.stop()` hangs waiting for EventLoopGroup
5. **Compaction task appears to stall**
6. Requires restart to kill the hung threads

## The Fix We Need

We need to **force close channels** that won't close gracefully:

```java
@Override
public void close(ChannelFuture resource) {
    Channel channel = resource.channel();
    
    // Try graceful close first
    ChannelFuture closeFuture = channel.close();
    
    try {
        if (!closeFuture.await(5, TimeUnit.SECONDS)) {
            log.warn("Channel did not close within 5 seconds, may have hung connection");
            
            // Force close by disconnecting
            channel.disconnect();
            channel.unsafe().closeForcibly();  // Nuclear option
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

But `unsafe().closeForcibly()` might not be public API.

## Alternative: Timeout-Based Approach

Instead of waiting indefinitely, accept that some channels won't close:

```java
// In HttpClientInit.stop()
workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);

// Don't wait forever - timeout and continue
boolean terminated = workerGroup.terminationFuture().await(10, TimeUnit.SECONDS);

if (!terminated) {
    log.error("EventLoopGroup did not terminate. " +
              "Orphaned threads will be cleaned up by JVM. " +
              "Check for hung network connections.");
    // Continue - don't block shutdown
    // Daemon threads will be cleaned up when JVM exits
}
```

This accepts resource leaks but prevents hangs.

## Recommendation for Production

**Immediate:** Revert to non-blocking shutdown to prevent hangs:

```java
workerGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
// Don't wait - let threads cleanup asynchronously
```

Accept that threads may accumulate, but at least compactions won't stall.

**Long-term:** Implement request timeouts at application level to prevent hung connections from occurring in the first place.

## Testing Needed

We need to determine which is worse for production:
- **Option A:** Hangs (current) - compactions stall, requires restarts
- **Option B:** Leaks - threads accumulate over time, eventual OOM but gradual

For your use case, **Option B is probably better** since you can monitor and restart before OOM, but hung compactions are immediate operational issues.

