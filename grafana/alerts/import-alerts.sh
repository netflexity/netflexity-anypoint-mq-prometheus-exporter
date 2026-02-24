#!/bin/bash
# Import Anypoint MQ alert rules into Grafana Unified Alerting
#
# Usage: ./import-alerts.sh <GRAFANA_URL> <DATASOURCE_UID> [USER] [PASSWORD]
# Example: ./import-alerts.sh http://localhost:3000 prometheus admin admin
#
# Prerequisites: curl, Grafana 9+ with Unified Alerting enabled

set -e

GRAFANA_URL="${1:?Usage: $0 <GRAFANA_URL> <DATASOURCE_UID> [USER] [PASSWORD]}"
DS_UID="${2:?Provide Prometheus datasource UID (find via Grafana > Connections > Data sources)}"
USER="${3:-admin}"
PASS="${4:-admin}"

echo "=== Anypoint MQ - Grafana Alert Rules ==="
echo "Target: $GRAFANA_URL"
echo "Datasource UID: $DS_UID"
echo ""

# Create folder
echo "Creating folder..."
curl -sf -X POST "$GRAFANA_URL/api/folders" \
  -u "$USER:$PASS" \
  -H "Content-Type: application/json" \
  -d '{"uid":"amq-alerts","title":"Anypoint MQ Alerts"}' > /dev/null 2>&1 || echo "  (folder exists)"

create_alert() {
  local TITLE="$1" EXPR="$2" THRESHOLD="$3" DURATION="$4" SEVERITY="$5" SUMMARY="$6"

  HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    "$GRAFANA_URL/api/v1/provisioning/alert-rules" \
    -u "$USER:$PASS" \
    -H "Content-Type: application/json" \
    -H "X-Disable-Provenance: true" \
    -d "{
      \"folderUID\": \"amq-alerts\",
      \"ruleGroup\": \"MQ Monitors\",
      \"title\": \"$TITLE\",
      \"condition\": \"C\",
      \"data\": [
        {\"refId\":\"A\",\"relativeTimeRange\":{\"from\":600,\"to\":0},\"datasourceUid\":\"$DS_UID\",\"model\":{\"expr\":\"$EXPR\",\"refId\":\"A\",\"intervalMs\":60000,\"maxDataPoints\":43200}},
        {\"refId\":\"B\",\"relativeTimeRange\":{\"from\":600,\"to\":0},\"datasourceUid\":\"__expr__\",\"model\":{\"type\":\"reduce\",\"expression\":\"A\",\"reducer\":\"last\",\"refId\":\"B\"}},
        {\"refId\":\"C\",\"relativeTimeRange\":{\"from\":600,\"to\":0},\"datasourceUid\":\"__expr__\",\"model\":{\"type\":\"threshold\",\"expression\":\"B\",\"conditions\":[{\"evaluator\":{\"type\":\"gt\",\"params\":[$THRESHOLD]}}],\"refId\":\"C\"}}
      ],
      \"for\": \"$DURATION\",
      \"labels\": {\"severity\": \"$SEVERITY\"},
      \"annotations\": {\"summary\": \"$SUMMARY\"},
      \"noDataState\": \"OK\",
      \"execErrState\": \"OK\"
    }")

  if [ "$HTTP" = "201" ]; then
    echo "  ✅ $TITLE"
  else
    echo "  ⚠️  $TITLE (HTTP $HTTP - may already exist)"
  fi
}

echo "Creating alert rules..."

create_alert "Dead Letter Queue Growth" \
  "anypoint_mq_queue_messages_in_queue{queue_name=~\\\".*dead.*|.*dlq.*|.*DLQ.*\\\"}" \
  0 "5m" "critical" "Dead letter queue has messages"

create_alert "Queue Depth Spike (>1000)" \
  "anypoint_mq_queue_messages_in_queue" \
  1000 "10m" "warning" "Queue depth exceeds 1000 messages"

create_alert "High In-Flight Messages (>500)" \
  "anypoint_mq_queue_messages_in_flight" \
  500 "5m" "warning" "Consumers struggling - high in-flight count"

create_alert "Scrape Errors" \
  "increase(anypoint_mq_scrape_errors_total[15m])" \
  0 "5m" "info" "Exporter scrape errors detected"

echo ""
echo "Done! View alerts: $GRAFANA_URL/alerting/list"
echo "Set up notifications: $GRAFANA_URL/alerting/notifications"
