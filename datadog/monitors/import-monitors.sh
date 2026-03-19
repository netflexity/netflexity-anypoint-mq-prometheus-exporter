#!/bin/bash
# Import all Anypoint MQ monitors into Datadog
#
# Usage:
#   DD_API_KEY=xxx DD_APP_KEY=yyy ./import-monitors.sh
#   DD_API_KEY=xxx DD_APP_KEY=yyy DD_SITE=datadoghq.eu ./import-monitors.sh
#
# Requires: curl, jq (optional, for pretty output)

set -euo pipefail

SITE="${DD_SITE:-datadoghq.com}"
API_URL="https://api.${SITE}/api/v1/monitor"

if [ -z "${DD_API_KEY:-}" ] || [ -z "${DD_APP_KEY:-}" ]; then
  echo "Error: DD_API_KEY and DD_APP_KEY must be set"
  echo "Usage: DD_API_KEY=xxx DD_APP_KEY=yyy $0"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CREATED=0
FAILED=0

for monitor_file in "$SCRIPT_DIR"/*.json; do
  [ -f "$monitor_file" ] || continue
  
  name=$(python3 -c "import json; print(json.load(open('$monitor_file')).get('name','unknown'))" 2>/dev/null || echo "$monitor_file")
  
  echo -n "  Importing: $name ... "
  
  response=$(curl -s -w "\n%{http_code}" -X POST "$API_URL" \
    -H "Content-Type: application/json" \
    -H "DD-API-KEY: ${DD_API_KEY}" \
    -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" \
    -d @"$monitor_file")
  
  http_code=$(echo "$response" | tail -1)
  body=$(echo "$response" | head -n -1)
  
  if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
    monitor_id=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "?")
    echo "✅ Created (ID: $monitor_id)"
    CREATED=$((CREATED + 1))
  else
    echo "❌ Failed (HTTP $http_code)"
    echo "    $body" | head -1
    FAILED=$((FAILED + 1))
  fi
done

echo ""
echo "Done: $CREATED created, $FAILED failed"
