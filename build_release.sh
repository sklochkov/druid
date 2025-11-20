#!/bin/bash
# Netty 4 Migration - Complete Release Build Script
# This script builds the entire Druid distribution with Netty 4.1.128.Final

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

# Verify Netty 4 version
echo ""
echo "Netty 4 version in use:"
mvn dependency:tree | grep "io.netty:netty" | head -5

echo ""
echo "========================================="
echo "Build artifacts are in:"
echo "  distribution/target/"
echo "========================================="

