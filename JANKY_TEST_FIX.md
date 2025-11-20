# JankyServersTest Hanging Issue - Analysis and Fix

## Problem
The `JankyServersTest` hangs indefinitely after the Netty 3 to Netty 4 migration.

## Root Cause
**EventLoopGroup Shutdown Behavior:**
- Netty 3's `ClientBootstrap.releaseExternalResources()` was relatively quick
- Netty 4's `EventLoopGroup.shutdownGracefully()` has a default quiet period (2s) and timeout (15s)
- This causes `lifecycle.stop()` to wait/block longer than expected

## Fixes Applied

### 1. Fast EventLoopGroup Shutdown
Changed shutdown to use 0 quiet period and 100ms timeout:
```java
workerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
```

### 2. Synchronous Channel Close
Ensure channels close immediately before EventLoopGroup shutdown:
```java
resource.channel().close().awaitUninterruptibly();
```

### 3. Connect Timeout
Added explicit connect timeout to prevent hanging on bad connections:
```java
.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
```

## Alternative Solutions

If tests still hang, consider:

### Option A: Make EventLoopGroup Shared
Create a single shared `EventLoopGroup` for all test clients instead of per-client:
- Reduces overhead
- Shutdown only once at end of test class

### Option B: Use shutdownNow() 
For tests only, use immediate shutdown:
```java
workerGroup.shutdownNow(); // Netty 4.1+
```

### Option C: Increase Test Timeouts
If the behavior is correct but just slower, increase test timeouts accordingly.

## Verification Needed
Run `JankyServersTest` to verify it completes within reasonable time (~5-10 seconds per test).

## Files Modified
- `HttpClientInit.java` - EventLoopGroup shutdown logic
- `ChannelResourceFactory.java` - Synchronous channel close

