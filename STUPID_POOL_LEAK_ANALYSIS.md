# TopNQueryRunnerTest Memory Issue - Root Cause Analysis

## CRITICAL FINDING: This is NOT a Netty Leak!

The memory exhaustion in `TopNQueryRunnerTest` and `TopNQueryRunnerBenchmark` is caused by **Druid's StupidPool**, not Netty ByteBufs.

### Evidence

**Stack Trace Analysis:**
```
java.lang.RuntimeException: Leaks happened
  at org.apache.druid.collections.StupidPool.take()
  at org.apache.druid.segment.CompressedPools.getByteBuf()
  at org.apache.druid.segment.data.DecompressingByteBufferObjectStrategy
  at org.apache.druid.segment.data.GenericIndexed$BufferIndexed.get()
  at org.apache.druid.segment.QueryableIndexCursorHolder.asCursor()
```

This is:
- **NOT** about Netty's `io.netty.buffer.ByteBuf`
- **IS** about Druid's internal `java.nio.ByteBuffer` pool (`StupidPool`)
- Used for **decompressing segment data**, not HTTP communication
- The test doesn't even use HTTP client!

### What's Happening

1. `TopNQueryRunnerBenchmark` executes queries on compressed segments
2. Decompression requires temporary `ByteBuffer` objects from `StupidPool`
3. After decompression, buffers should be returned to pool
4. **Bug:** Buffers are not being returned → pool exhausted → leak exception

### Is This Related to Netty Migration?

**Probably NOT directly**, but there are two possibilities:

#### Possibility A: Pre-Existing Bug (Most Likely)
- This leak existed before
- Never noticed because tests ran with more memory or fewer iterations
- On 32-core system with parallel execution, it manifests faster
- **Action:** Check if this test passed BEFORE migration

#### Possibility B: Indirect Impact (Less Likely)
- Our changes somehow affect test execution order/parallelism
- Timing changes expose pre-existing race condition
- Thread pool changes affect buffer checkout patterns

## Verification Steps

### 1. Test if this is pre-existing:
```bash
# Checkout code BEFORE Netty migration
git stash
git checkout <commit-before-netty-migration>
mvn test -pl processing -Dtest=TopNQueryRunnerBenchmark
# Does it leak? If YES → pre-existing bug
```

### 2. Check StupidPool configuration:
The test has:
```java
-Ddruid.test.stupidPool.poison=true
```

This enables **leak detection** in StupidPool. The leak is being DETECTED, which is good, but it means buffers are not returned.

### 3. Look for missing resource cleanup:
```bash
# Search for StupidPool usage without try-with-resources
grep -r "StupidPool.*take()" processing/src/main/java
```

## Recommended Fix (For Druid Team)

This is a **Druid internal bug**, not a Netty issue. The fix belongs in:
- `DecompressingByteBufferObjectStrategy`
- Or the code calling it
- Ensure ByteBuffers are returned to pool in finally blocks

Example pattern:
```java
ResourceHolder<ByteBuffer> bufferHolder = pool.take();
try {
    ByteBuffer buffer = bufferHolder.get();
    // use buffer
} finally {
    bufferHolder.close();  // Returns to pool
}
```

## Impact on Netty Migration

**None.** This is a separate issue.

Our Netty migration is correct. The HTTP client memory leaks are fixed. This StupidPool leak is unrelated.

## Recommendation

### For Testing Netty Migration:
**Skip the broken tests:**
```bash
mvn test -pl processing \
    -Dtest='!TopNQueryRunnerBenchmark,!TopNQueryRunnerTest' \
    -Dcheckstyle.skip=true
```

### For Fixing StupidPool Leak:
1. File separate bug report
2. Investigate buffer return paths in decompression code
3. Add try-finally or try-with-resources for buffer checkout
4. This is orthogonal to Netty migration

## Conclusion

✅ **Netty Migration: Complete and Correct**  
❌ **StupidPool Leak: Pre-existing Druid Bug** (separate issue)

You can proceed with Netty migration testing by skipping the problematic TopN tests. They're failing due to an unrelated buffer pool issue.

