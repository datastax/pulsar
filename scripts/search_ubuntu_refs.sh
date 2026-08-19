#!/bin/bash
#
# Script to search for Ubuntu 22.04 references in the repository
# This helps identify where Ubuntu 22.04 might be used
#

echo "=========================================="
echo "Searching for Ubuntu 22.04 References"
echo "=========================================="
echo ""

echo "=== Searching in Dockerfiles ==="
find . -type f -name "Dockerfile*" ! -path "*/node_modules/*" ! -path "*/.git/*" \
  -exec grep -Hn "ubuntu.*22\.04\|jammy\|FROM.*ubuntu" {} \; 2>/dev/null | head -20

echo ""
echo "=== Searching in YAML files ==="
find . -type f \( -name "*.yaml" -o -name "*.yml" \) ! -path "*/node_modules/*" ! -path "*/.git/*" \
  -exec grep -Hn "ubuntu.*22\.04\|jammy\|ubuntu-22" {} \; 2>/dev/null | head -20

echo ""
echo "=== Searching in Shell scripts ==="
find . -type f -name "*.sh" ! -path "*/node_modules/*" ! -path "*/.git/*" \
  -exec grep -Hn "ubuntu.*22\.04\|jammy" {} \; 2>/dev/null | head -20

echo ""
echo "=== Searching in Docker Compose files ==="
find . -type f -name "docker-compose*.yml" ! -path "*/node_modules/*" ! -path "*/.git/*" \
  -exec grep -Hn "ubuntu.*22\.04\|jammy" {} \; 2>/dev/null | head -20

echo ""
echo "=== Summary ==="
echo "If no results above, Ubuntu 22.04 is not referenced in the codebase."
echo "The vulnerabilities likely apply to infrastructure, not application code."

# Made with Bob
