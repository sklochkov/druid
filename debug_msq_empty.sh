#!/bin/bash
# MSQ Empty Result Debugger
# Run this on your broker/coordinator to diagnose why MSQ returns empty results

set -euo pipefail

echo "=== MSQ Empty Result Diagnostic ==="
echo ""

# Test 1: Simple aggregation query
echo "Test 1: Running simple COUNT query via MSQ..."
result=$(curl -s -X POST http://localhost:8082/druid/v2/sql \
    -H 'Content-Type: application/json' \
    -d '{"query": "SELECT COUNT(*) as cnt FROM your_datasource LIMIT 1"}')

echo "Result: $result"
count=$(echo "$result" | jq -r '.[0].cnt // "EMPTY"')

if [ "$count" = "EMPTY" ]; then
    echo "✗ Query returned empty - ISSUE CONFIRMED"
else
    echo "✓ Query returned: $count"
fi

echo ""

# Test 2: Check if it's MSQ-specific or all SQL
echo "Test 2: Running same query with native SQL (not MSQ)..."
result_native=$(curl -s -X POST http://localhost:8082/druid/v2/sql \
    -H 'Content-Type: application/json' \
    -d '{"query": "SELECT COUNT(*) as cnt FROM your_datasource LIMIT 1", "context": {"useApproximateCountDistinct": false}}')

echo "Native result: $result_native"
count_native=$(echo "$result_native" | jq -r '.[0].cnt // "EMPTY"')

if [ "$count" = "EMPTY" ] && [ "$count_native" != "EMPTY" ]; then
    echo "✗ MSQ returns empty but native works - MSQ-specific issue!"
elif [ "$count" = "EMPTY" ] && [ "$count_native" = "EMPTY" ]; then
    echo "? Both return empty - might be data source issue"
else
    echo "? Both return data - issue might be intermittent"
fi

echo ""

# Test 3: Check recent MSQ task logs
echo "Test 3: Checking recent MSQ task logs for Netty errors..."

# Find recent MSQ controller tasks
recent_tasks=$(curl -s http://localhost:8081/druid/indexer/v1/completeTasks?n=10 | \
    jq -r '.[] | select(.type == "query_controller") | .id' | head -5)

if [ -z "$recent_tasks" ]; then
    echo "No recent MSQ tasks found"
else
    echo "Recent MSQ tasks:"
    echo "$recent_tasks"
    echo ""
    
    for task_id in $recent_tasks; do
        echo "Checking logs for task: $task_id"
        
        # Check for Netty errors in task logs
        task_log=$(curl -s "http://localhost:8081/druid/indexer/v1/task/$task_id/log" || echo "")
        
        if echo "$task_log" | grep -qi "channelexception\|bytebuf.*leak\|httpcontent.*empty"; then
            echo "  ✗ Found Netty-related errors:"
            echo "$task_log" | grep -i "channelexception\|bytebuf\|httpcontent" | head -5
        else
            echo "  ✓ No obvious Netty errors"
        fi
        
        # Check for empty responses
        if echo "$task_log" | grep -qi "empty.*response\|no.*data.*worker\|0.*bytes.*received"; then
            echo "  ⚠ Found empty response indicators:"
            echo "$task_log" | grep -i "empty.*response\|no.*data\|0.*bytes" | head -5
        fi
        
        echo ""
    done
fi

echo ""

# Test 4: Check for ReferenceCountUtil issues
echo "Test 4: Checking for ByteBuf release issues..."
if grep -r "LEAK:.*ByteBuf" /path/to/druid/log/*.log 2>/dev/null | head -1; then
    echo "✗ ByteBuf leaks detected - our release() calls may be wrong!"
else
    echo "✓ No ByteBuf leaks detected"
fi

echo ""
echo "=== Diagnostic Complete ==="
echo ""
echo "If MSQ returns empty but native SQL works:"
echo "  → Issue is in MSQ worker HTTP communication (Netty 4 related)"
echo "  → Check SketchResponseHandler and worker client code"
echo ""
echo "If both return empty:"
echo "  → Not MSQ-specific, check data source and segments"
echo ""
echo "Next steps:"
echo "  1. Enable DEBUG logging: log4j.logger.org.apache.druid.msq=DEBUG"
echo "  2. Run MSQ query and check controller/worker logs"
echo "  3. Look for 'handleResponse' and 'handleChunk' calls"
echo "  4. Verify ByteBuf content is being read"

