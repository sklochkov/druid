# Critical: Java 17 cgroup CPU Detection Issue

## Problem Identified

Java 17 with cgroup v1 has a bug where:
- ✅ Detects CPU quota correctly (`Effective CPU Count: 2`)
- ❌ But `availableProcessors()` still returns **96** (host CPU count)

This causes Netty to create **96 × 2 = 192 worker threads** even in a 2-CPU container!

## Evidence

```
Operating System Metrics:
    Provider: cgroupv1
    Effective CPU Count: 2              ← Correctly detected
    List of Effective Processors, 96 total  ← But returns 96!
```

## The Fix

**Reduced MAX_WORKER_THREADS from 16 to 8:**

```java
private static final int MAX_WORKER_THREADS = 8;
```

This ensures even if JVM returns 96 cores, we only create 8 worker threads per client.

## Additional Fixes for Indexing Tasks

Add to your **middleManager/indexer configuration**:

```properties
# In runtime.properties or via environment
druid.indexer.runner.javaOpts=-Xmx2g \
    -XX:MaxDirectMemorySize=512m \
    -Dio.netty.allocator.type=unpooled \
    -Dio.netty.noPreferDirect=true \
    -Dio.netty.maxDirectMemory=0
```

**What these do:**
- `allocator.type=unpooled` - Don't pre-allocate direct memory chunks
- `noPreferDirect=true` - Use heap ByteBufs when possible
- `maxDirectMemory=0` - Minimize Netty's direct memory usage
- `XX:MaxDirectMemorySize=512m` - JVM-level limit on direct memory

## Why This Matters

**Before fix (2-CPU container, Java 17 bug):**
- JVM sees: 96 processors
- Creates: 96 × 2 = 192 threads (before our cap)
- With cap of 16: 16 threads
- With cap of 8: 8 threads ✅

**Memory savings:**
- 192 threads: ~192MB (stacks) + direct memory pools
- 8 threads: ~8MB (stacks) + minimal direct memory

## For CI/Build

In your Dockerfile, add:
```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=2"
```

This forces Java to use 2 processors regardless of what cgroup detection reports.

## Commit This Fix

```bash
git add processing/src/main/java/org/apache/druid/java/util/http/client/HttpClientConfig.java
git commit -m "Reduce Netty worker thread cap to 8 due to Java 17 cgroup detection issue

Java 17 with cgroup v1 reports 96 processors even in 2-CPU containers,
causing excessive thread creation. Reduce cap from 16 to 8 to prevent
OOM in memory-constrained indexing tasks."
git push
```

This should resolve your exit code 137 issues!

