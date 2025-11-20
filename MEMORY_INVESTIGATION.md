# Memory Exhaustion Investigation - TopNQueryRunnerTest

## Issue Report

**System:** 64GB RAM, 32 cores
**Symptom:** Two Java processes each consuming >15GB RAM before OS kills them
**Test:** `TopNQueryRunnerTest`
**Error:** OutOfMemoryError / OS OOM kill

## Analysis

### 1. Direct Memory vs Heap Memory

The test JVM is configured with:
- Heap: `-Xmx2048m` (2GB)
- Direct Memory: `-XX:MaxDirectMemorySize=2500m` (2.5GB)
- **Total JVM limit:** ~4.5GB

But processes are consuming **>15GB each** = **3x-4x the configured limits**!

This suggests:
- **Direct memory leak** outside JVM tracking
- **Native memory leak** (threads, native buffers)
- **Or memory accounting issue**

### 2. Netty 4 Direct Memory Usage

Netty 4's `ByteBuf` can allocate:
- **Heap buffers:** Counted in `-Xmx`
- **Direct buffers:** Counted in `-XX:MaxDirectMemorySize`
- **But:** If direct buffers leak, they may exceed the limit

### 3. Thread Accumulation

**On 32-core system:**
- Default worker threads per EventLoopGroup: `32 * 2 = 64 threads`
- If test suite creates 100 HTTP clients: `64 * 100 = 6,400 threads`
- Each thread has stack space (typically 1MB): `6,400MB = 6.4GB` just for stacks!

If EventLoopGroups don't shut down within the 1-second timeout, they accumulate.

## Fixes Applied

### 1. Critical: ByteBuf Release After Handler Processing ✅
**File:** `NettyHttpClient.java`

Added `ReferenceCountUtil.release(msg)` after EVERY handler call to prevent ByteBuf leaks.

### 2. Synchronous Channel Close with Timeout ✅
**File:** `ChannelResourceFactory.java`

```java
resource.channel().close().awaitUninterruptibly(100, TimeUnit.MILLISECONDS);
```

Ensures channels close before EventLoopGroup shutdown.

### 3. Extended EventLoopGroup Shutdown Timeout ✅
**File:** `HttpClientInit.java`

Increased from 1s to 5s to allow all 64 threads to terminate on high-core-count systems.

## Recommended Additional Actions

### A. Reduce Thread Count for Tests

Add to test HTTP client configurations:
```java
HttpClientConfig.builder()
    .withWorkerCount(4)  // Instead of 64 on 32-core system
    .build()
```

### B. Enable Netty Leak Detection in Tests

Add JVM argument:
```
-Dio.netty.leakDetection.level=PARANOID
```

This will detect and report any ByteBuf leaks immediately.

### C. Increase JVM Memory for Tests

The test suite may legitimately need more memory:
```xml
<argLine>-Xmx4096m -XX:MaxDirectMemorySize=4096m</argLine>
```

### D. Check for Test Isolation Issues

If TopNQueryRunnerTest doesn't use HTTP client at all, the memory issue might be:
- Pre-existing bug (unrelated to Netty migration)
- But triggered/amplified by our changes
- Possibly due to shared resources or static state

## Diagnostic Commands

```bash
# Run with leak detection
mvn test -pl processing -Dtest=TopNQueryRunnerTest \
    -DargLine="-Dio.netty.leakDetection.level=PARANOID"

# Monitor thread count
jps | grep Surefire
jstack <pid> | grep "HttpClient-Netty-Worker" | wc -l

# Check direct memory usage
jcmd <pid> VM.native_memory summary
```

## Next Steps

1. **Verify ByteBuf fix** is working (run simple HTTP tests)
2. **Run TopNQueryRunnerTest in isolation** to see if it still leaks
3. **Enable leak detection** to pinpoint remaining leaks
4. **Consider reducing worker thread count** for tests

## Status

- ✅ ByteBuf release fix applied
- ✅ Channel close timeout added
- ✅ EventLoopGroup shutdown timeout increased
- ⚠️ TopNQueryRunnerTest may have additional issues
- 🔍 Investigation ongoing

