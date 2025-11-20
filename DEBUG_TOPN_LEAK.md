# URGENT: TopNQueryRunnerTest Memory Leak - Debugging Guide

## Problem Statement

**Vanilla Druid 31.0.2:** TopNQueryRunnerTest passes (166,144 tests in 80s)  
**After Netty Migration:** OutOfMemoryError, 15GB+ per JVM, test failures

**Critical:** This test doesn't use HTTP client, yet our migration breaks it!

## What We Changed

**In processing module:**
1. HTTP client files (NettyHttpClient, Request, etc.) - Netty 3 → Netty 4
2. Response handlers - ByteBuf API changes
3. pom.xml - Removed `io.netty:netty` (Netty 3), added Netty 4 modules
4. AppendableByteArrayInputStream - Minor fix (`& 0xff` for unsigned byte)

**None of these should affect TopNQueryRunnerTest!**

## Theories to Investigate

### Theory 1: EventLoopGroup Thread Leak (MOST LIKELY)
**Hypothesis:** EventLoopGroups created during test setup are not terminating

**Evidence:**
- On 32-core system, each HTTP client creates 64 threads
- If any test class creates HTTP client in `@BeforeClass` but doesn't clean up
- Threads accumulate across 166k test executions

**Test:**
```bash
# Monitor thread count during test run
while true; do 
    jps | grep Surefire | while read pid name; do
        echo "$(date) Threads: $(jstack $pid | grep HttpClient-Netty-Worker | wc -l)"
    done
    sleep 1
done
```

**Fix if confirmed:**
- Find tests creating HTTP clients without cleanup
- Or reduce default worker count for tests

### Theory 2: Netty Direct Memory Allocator
**Hypothesis:** Netty 4 changed default allocator settings globally

**Test:**
```bash
# Run with Netty pooled allocator disabled
mvn test -pl processing -Dtest=TopNQueryRunnerTest \
    -DargLine="-Dio.netty.allocator.type=unpooled"
```

### Theory 3: Class Loading / Static Initialization
**Hypothesis:** Loading Netty 4 classes affects JVM memory management

**Test:**
Check if just having Netty 4 on classpath (without using it) causes issues

### Theory 4: Dependency Conflict
**Hypothesis:** Netty 4 modules conflict with other libraries

**Check:**
```bash
mvn dependency:tree -pl processing | grep conflict
```

## Immediate Debugging Steps

### Step 1: Verify Netty 4 Isn't Loaded by TopN Tests
```bash
# Run with Netty leak detection
mvn test -pl processing -Dtest=TopNQueryRunnerTest \
    -DargLine="-Dio.netty.leakDetection.level=PARANOID -Xmx4096m" \
    2>&1 | grep -i "leak"
```

If NO Netty leaks reported → problem is elsewhere!

### Step 2: Check Thread Count
```bash
# Before test
jcmd <pid> Thread.print | grep HttpClient | wc -l

# After test  
jcmd <pid> Thread.print | grep HttpClient | wc -l
```

If thread count increases → EventLoopGroup leak

### Step 3: Bisect the Changes
Revert changes one by one to find culprit:
1. Revert EventLoopGroup shutdown changes
2. Revert ByteBuf release changes
3. Revert pom.xml Netty 4 upgrade

Find which revert fixes the issue.

## Current Fixes Applied

1. ✅ `ReferenceCountUtil.release(msg)` after handler calls
2. ✅ Channel close with 100ms timeout
3. ✅ EventLoopGroup shutdown with 2s wait
4. ✅ Changed `wrappedBuffer` back (to reduce memory copies)

## Next Actions Required

**From You:**
Please run these diagnostics to pinpoint the leak source:

```bash
# Test 1: Does TopN test use Netty at all?
mvn test -pl processing -Dtest=TopNQueryRunnerTest \
    -DargLine="-Dio.netty.leakDetection.level=PARANOID"
# Look for "LEAK: ByteBuf.release()" in output

# Test 2: Check dependency conflicts
mvn dependency:tree -pl processing > /tmp/deps.txt
grep -i netty /tmp/deps.txt
```

**Critical Question:**
When you run the vanilla version, are you running with the SAME:
- Java version?
- Maven settings?
- Surefire configuration?
- Number of parallel forks?

The 32-core system might be running tests differently (more parallelism) which could expose issues that didn't appear on smaller systems.

## My Recommendation

Since TopNQueryRunnerTest doesn't use HTTP client, and the leak is in StupidPool (not Netty), I suspect:

**The issue is test parallelization + resource limits**, not our Netty code.

Try running with:
```bash
mvn test -pl processing -DforkCount=1 -DreuseForks=false
```

This will reduce parallelism and might prevent the OOM.

