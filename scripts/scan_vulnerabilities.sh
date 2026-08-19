#!/bin/bash
#
# Script to scan Docker images for vulnerabilities using Trivy
# Install Trivy: https://github.com/aquasecurity/trivy
#

echo "=========================================="
echo "Vulnerability Scanning with Trivy"
echo "=========================================="
echo ""

# Check if trivy is installed
if ! command -v trivy &> /dev/null; then
    echo "ERROR: Trivy is not installed."
    echo ""
    echo "Install Trivy:"
    echo "  macOS: brew install trivy"
    echo "  Linux: https://aquasecurity.github.io/trivy/latest/getting-started/installation/"
    echo ""
    exit 1
fi

echo "=== Scanning Alpine 3.21 Base Image ==="
trivy image --severity HIGH,CRITICAL alpine:3.21

echo ""
echo "=== Scanning Pulsar Image (if available) ==="
if docker images | grep -q "apachepulsar/pulsar"; then
    trivy image --severity HIGH,CRITICAL apachepulsar/pulsar:latest
else
    echo "Pulsar image not found. Pull it with:"
    echo "  docker pull apachepulsar/pulsar:latest"
fi

echo ""
echo "=========================================="
echo "Scan Complete"
echo "=========================================="
echo ""
echo "To scan for all severities:"
echo "  trivy image alpine:3.21"
echo ""
echo "To generate JSON report:"
echo "  trivy image -f json -o report.json alpine:3.21"

# Made with Bob
