# CRITICAL FIX NEEDED: Timer Not Starting

## Root Cause Found!

**JankyServersTest hangs** because:
1. Test creates `Lifecycle` but **NEVER calls `lifecycle.start()`**
2. Timer never starts (timer.start() only called in lifecycle start handler)
3. ReadTimeoutHandler has no working timer
4. Timeout never fires
5. Request hangs forever waiting for response from silent server

## Why This Worked in Netty 3

Netty 3's HashedWheelTimer may have auto-started or had different initialization behavior.

## Production Impact

If any production code creates HTTP clients without starting the lifecycle:
- **Timeouts won't work**
- **Requests can hang forever**
- **This explains compaction stalls!**

## The Fix

### Option 1: Auto-Start Timer (Safest)

Make timer start immediately, not wait for lifecycle:

```java
// In HttpClientInit.createClient()
final HashedWheelTimer timer = new HashedWheelTimer(...);
timer.start();  // Start immediately

lifecycle.addMaybeStartHandler(
    new Lifecycle.Handler() {
        @Override
        public void start() {
            // Already started
        }
        
        @Override
        public void stop() {
            timer.stop();
        }
    }
);
```

### Option 2: Fix Tests

Add `lifecycle.start()` to all tests. But this doesn't help if production code also forgets to start.

### Option 3: Make Timer Optional for Timeouts

Don't require timer for timeouts - use EventLoop's scheduler:
```java
// In NettyHttpClient, use channel's EventLoop instead of external timer
channel.eventLoop().schedule(() -> {
    // Timeout logic
}, timeout, TimeUnit.MILLISECONDS);
```

## Recommendation

**Implement Option 1** - auto-start the timer. It's the safest and ensures timeouts always work.

This is CRITICAL for production - without working timeouts, hung connections can cascade and cause the exact compaction stalls you're seeing!

