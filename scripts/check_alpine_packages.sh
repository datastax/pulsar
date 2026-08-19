#!/bin/bash
#
# Script to check Alpine Linux package versions in Pulsar Docker image
# This helps verify if the Ubuntu 22.04 CVEs affect Alpine packages
#

echo "=========================================="
echo "Checking Alpine 3.21 Package Versions"
echo "=========================================="
echo ""

docker run --rm alpine:3.21 sh -c '
  echo "=== GCC and C++ Libraries ==="
  apk info gcc libgcc libstdc++ 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== ncurses ==="
  apk info ncurses-libs 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== pcre2 ==="
  apk info pcre2 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== shadow ==="
  apk info shadow 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== wget ==="
  apk info wget 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== tar ==="
  apk info tar 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== libgcrypt ==="
  apk info libgcrypt 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== zstd ==="
  apk info zstd-libs 2>/dev/null || echo "Not installed"
  echo ""
  
  echo "=== All Installed Packages ==="
  apk list --installed | wc -l
  echo "packages installed"
'

echo ""
echo "=========================================="
echo "Checking Pulsar Docker Image"
echo "=========================================="
echo ""

# Check if Pulsar image exists locally
if docker images | grep -q "apachepulsar/pulsar"; then
  echo "Checking packages in Pulsar image..."
  docker run --rm localhost/pulsar-local:alpine-test sh -c '
    echo "=== Base Image Info ==="
    cat /etc/os-release 2>/dev/null || echo "Cannot read os-release"
    echo ""
    
    echo "=== Installed Packages ==="
    apk list --installed 2>/dev/null | head -20
  '
else
  echo "Pulsar image not found locally. Pull it with:"
  echo "  docker pull apachepulsar/pulsar:latest"
fi

# Made with Bob
