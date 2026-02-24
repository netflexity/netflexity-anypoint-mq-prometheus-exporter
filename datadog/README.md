# Anypoint MQ — Datadog Integration

The Anypoint MQ Prometheus Exporter works with Datadog's OpenMetrics check to bring all MQ metrics into Datadog with zero additional code.

See the [main README](../README.md) for setup instructions (Datadog Agent OpenMetrics configuration, Kubernetes annotations, etc.).

---

## Recommended Monitors

Import these monitor definitions via the [Datadog Monitors API](https://docs.datadoghq.com/api/latest/monitors/#create-a-monitor):

```bash
curl -X POST "https://api.datadoghq.com/api/v1/monitor" \
  -H "Content-Type: application/json" \
  -H "DD-API-KEY: ${DD_API_KEY}" \
  -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" \
  -d @monitor.json
```

### 1. Dead Letter Queue Alert

Messages in a DLQ mean failed processing that needs immediate attention.

```json
{
  "name": "Anypoint MQ - Dead Letter Queue Has Messages",
  "type": "metric alert",
  "query": "avg(last_5m):sum:anypoint_mq_queue_messages_in_queue{queue_name:*dead*} > 0",
  "message": "🔴 Dead letter queue **{{queue_name.name}}** in **{{environment.name}}** has **{{value}}** messages.\n\nFailed messages need investigation. Check consumer logs for processing errors.\n\n{{#is_alert}}@pagerduty-mulesoft{{/is_alert}}\n{{#is_warning}}@slack-mulesoft-alerts{{/is_warning}}",
  "tags": ["service:anypoint-mq", "severity:critical", "team:integration"],
  "options": {
    "thresholds": { "critical": 0 },
    "notify_no_data": false,
    "renotify_interval": 30,
    "evaluation_delay": 60,
    "include_tags": true
  }
}
```

### 2. Queue Depth Threshold

Detects queues with large backlogs that may indicate consumer issues or traffic spikes.

```json
{
  "name": "Anypoint MQ - Queue Depth Exceeds 1000",
  "type": "metric alert",
  "query": "avg(last_10m):max:anypoint_mq_queue_messages_in_queue{*} by {queue_name,environment} > 1000",
  "message": "🟡 Queue **{{queue_name.name}}** in **{{environment.name}}** has **{{value}}** messages (threshold: 1000).\n\nCheck consumer health and processing rates. Consider scaling consumers.\n\n@slack-mulesoft-alerts",
  "tags": ["service:anypoint-mq", "severity:warning", "team:integration"],
  "options": {
    "thresholds": { "critical": 1000, "warning": 500 },
    "notify_no_data": false,
    "renotify_interval": 60,
    "evaluation_delay": 60,
    "include_tags": true
  }
}
```

### 3. Consumer Lag (High In-Flight)

High in-flight messages indicate consumers are fetching but struggling to process.

```json
{
  "name": "Anypoint MQ - High In-Flight Messages",
  "type": "metric alert",
  "query": "avg(last_5m):max:anypoint_mq_queue_messages_in_flight{*} by {queue_name} > 500",
  "message": "🟡 Queue **{{queue_name.name}}** has **{{value}}** in-flight messages (threshold: 500).\n\nConsumers are fetching messages but may be struggling to acknowledge them. Check for:\n- Slow downstream services\n- Consumer errors/timeouts\n- Resource exhaustion\n\n@slack-mulesoft-alerts",
  "tags": ["service:anypoint-mq", "severity:warning", "team:integration"],
  "options": {
    "thresholds": { "critical": 500, "warning": 250 },
    "notify_no_data": false,
    "renotify_interval": 30,
    "evaluation_delay": 60,
    "include_tags": true
  }
}
```

### 4. No Consumer Activity

Queue has messages but zero acknowledgments — consumers may be down.

```json
{
  "name": "Anypoint MQ - No Consumer Activity (Stale Queue)",
  "type": "metric alert",
  "query": "sum(last_30m):sum:anypoint_mq_queue_messages_acked{*} by {queue_name} == 0",
  "message": "🟡 Queue **{{queue_name.name}}** has had zero acknowledgments for 30 minutes.\n\nIf this queue has messages, consumers may be down or disconnected.\n\nCheck:\n- Consumer application health\n- Network connectivity to Anypoint MQ\n- Consumer deployment status\n\n@slack-mulesoft-alerts",
  "tags": ["service:anypoint-mq", "severity:warning", "team:integration"],
  "options": {
    "thresholds": { "critical": 0 },
    "notify_no_data": false,
    "renotify_interval": 60,
    "evaluation_delay": 60,
    "include_tags": true
  }
}
```

### 5. Scrape Health

Exporter is encountering errors collecting metrics from Anypoint Platform.

```json
{
  "name": "Anypoint MQ - Exporter Scrape Errors",
  "type": "metric alert",
  "query": "avg(last_15m):sum:anypoint_mq_scrape_errors_total{*} > 0",
  "message": "ℹ️ Anypoint MQ exporter is experiencing scrape errors.\n\nCheck:\n- Exporter application logs\n- Anypoint Platform connectivity and API status\n- Connected App credentials validity\n\n@slack-mulesoft-alerts",
  "tags": ["service:anypoint-mq", "severity:info", "team:integration"],
  "options": {
    "thresholds": { "critical": 0 },
    "notify_no_data": false,
    "renotify_interval": 120,
    "evaluation_delay": 60,
    "include_tags": true
  }
}
```

### Bulk Import

Save all monitors to a file and import them in one script:

```bash
#!/bin/bash
# import-monitors.sh
for f in monitors/*.json; do
  echo "Importing $f..."
  curl -s -X POST "https://api.datadoghq.com/api/v1/monitor" \
    -H "Content-Type: application/json" \
    -H "DD-API-KEY: ${DD_API_KEY}" \
    -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" \
    -d @"$f"
  echo ""
done
```

---

## Setting Up Notifications

### Slack

1. Go to **Monitors → Manage Monitors → (select monitor) → Edit**
2. In the message body, add `@slack-<channel-name>` (e.g., `@slack-mulesoft-alerts`)
3. Or configure a [Slack integration](https://docs.datadoghq.com/integrations/slack/) in Datadog first

### PagerDuty

1. Install the [PagerDuty integration](https://docs.datadoghq.com/integrations/pagerduty/) in Datadog
2. Map Datadog services to PagerDuty services
3. In monitor messages, use `@pagerduty-<service-name>` to route alerts
4. Critical alerts (DLQ, scrape failures) should go to PagerDuty; warnings to Slack

### Email

1. In the monitor message body, add `@<email-address>` (e.g., `@oncall@yourcompany.com`)
2. Datadog sends email notifications automatically — no additional setup needed

### Recommended Routing

| Monitor | Slack | PagerDuty | Email |
|---------|:-----:|:---------:|:-----:|
| Dead Letter Queue | ✅ | ✅ (critical) | ✅ |
| Queue Depth | ✅ | — | — |
| High In-Flight | ✅ | — | — |
| No Consumer Activity | ✅ | — | ✅ |
| Scrape Errors | ✅ | — | — |

---

## Pre-built Dashboard

Import [`datadog/dashboard.json`](dashboard.json) into Datadog for a complete monitoring view. See the [main README](../README.md#datadog-integration) for details.
