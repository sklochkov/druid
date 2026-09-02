#!/bin/bash
# Netty 4 Migration - Complete Release Build Script
# This script builds the entire Druid distribution with Netty 4.2.16.Final

set -e  # Exit on error

echo "========================================="
echo "Druid Netty 4 Migration - Release Build"
echo "========================================="

# Build all modules
echo "Building all modules..."
mvn clean install \
    -B \
    -T 1C \
    -Dmaven.test.skip=true \
    -Dcheckstyle.skip=true \
    -Dpmd.skip=true \
    -Drat.skip=true \
    -Dspotbugs.skip=true \
    -DskipITs \
    -Pdist

echo ""
echo "✅ BUILD COMPLETE!"
echo ""
echo "Verification:"
echo "-------------"

# Verify no Netty 3
echo "Checking for Netty 3 dependencies..."
if mvn dependency:tree | grep "org.jboss.netty" > /dev/null; then
    echo "⚠️  WARNING: Netty 3 dependencies still found!"
    mvn dependency:tree | grep "org.jboss.netty"
else
    echo "✅ No Netty 3 dependencies found"
fi

# Verify all unshaded Netty 4 modules converge on 4.2.16.Final
echo ""
echo "Checking Netty 4 dependency convergence..."
NON_CONVERGED_NETTY=$(mvn dependency:tree -Dincludes=io.netty -Dstyle.color=never \
    | grep -E "io\.netty:netty-[^:]+:[^:]+(:[^:]+)?:4\." \
    | grep -v ":4.2.16.Final:" || true)
if [ -n "$NON_CONVERGED_NETTY" ]; then
    echo "❌ Netty 4 dependencies outside 4.2.16.Final found:"
    echo "$NON_CONVERGED_NETTY"
    exit 1
fi
echo "✅ All unshaded Netty 4 dependencies use 4.2.16.Final"

echo ""
echo "========================================="
echo "Build artifacts are in:"
echo "  distribution/target/"
echo "========================================="

