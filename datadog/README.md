# Anypoint MQ — Datadog Integration

The Anypoint MQ Prometheus Exporter works with Datadog's OpenMetrics check to bring all MQ metrics into Datadog with zero additional code.

See the [main README](../README.md) for setup instructions (Datadog Agent OpenMetrics configuration, Kubernetes annotations, etc.).

---

## What Ships With This Integration

### Pre-built Dashboard

Import [`dashboard.json`](dashboard.json) via **Dashboards → New Dashboard → Import** or the API:

```bash
curl -X POST "https://api.datadoghq.com/api/v1/dashboard" \
  -H "Content-Type: application/json" \
  -H "DD-API-KEY: ${DD_API_KEY}" \
  -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" \
  -d @dashboard.json
```

**Dashboard sections:**

| Section | Panels | Description |
|---------|--------|-------------|
| **Overview** | 6 KPI tiles | Total queues, messages queued, in-flight, DLQ count, environments, exchanges |
| **Queue Depth & Backlog** | 4 panels | Top 10 depth timeseries (with warning/critical markers), depth distribution toplist, in-flight bars, 1-hour backlog change |
| **Throughput** | 5 panels | Sent/received/acked timeseries, sent-vs-acked overlay, unacknowledged gap area chart |
| **Dead Letter Queues** | 2 panels | DLQ depth over time, DLQ breakdown toplist (red conditional formatting) |
| **Exchanges** | 3 panels | Published/delivered timeseries, fan-out ratio (delivered/published) |
| **Queue Inventory** | 1 table | Full queue table with In Queue, In Flight, Sent, Received, Acked, Unacked columns + conditional formatting |
| **Exporter Health** | 4 panels | Scrape duration, scrape errors, uptime, JVM heap usage |

**Template variables:** Filter by `org`, `environment`, `region`, or `queue` — all dropdowns auto-populate from your metrics.

---

### Recommended Monitors

Six production-ready monitors in [`monitors/`](monitors/):

| Monitor | File | Severity | Trigger |
|---------|------|----------|---------|
| **Dead Letter Queue** | `dlq-alert.json` | P1 Critical | Any messages in DLQ |
| **Queue Depth Critical** | `queue-depth-critical.json` | P2 Critical | Queue > 10K messages (warn at 5K) |
| **High In-Flight** | `high-inflight.json` | P3 Warning | In-flight > 500 (warn at 250) |
| **Stale Queue** | `stale-queue.json` | P3 Warning | Zero acks for 30 minutes |
| **Throughput Drop** | `throughput-drop.json` | P3 Warning | 80%+ drop vs yesterday (warn at 50%) |
| **Scrape Errors** | `scrape-errors.json` | P4 Warning | 3+ scrape failures in 15 min |

#### Bulk Import

```bash
DD_API_KEY=xxx DD_APP_KEY=yyy ./monitors/import-monitors.sh
```

Or import one at a time:

```bash
curl -X POST "https://api.datadoghq.com/api/v1/monitor" \
  -H "Content-Type: application/json" \
  -H "DD-API-KEY: ${DD_API_KEY}" \
  -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" \
  -d @monitors/dlq-alert.json
```

All monitors include:
- Structured alert messages with context tables
- Recovery notifications
- Recommended routing (`@slack-mulesoft-alerts`, `@pagerduty-mulesoft`)
- `source:anypoint-mq-exporter` tag for easy filtering

---

## Setting Up Notifications

### Slack

1. Install the [Slack integration](https://docs.datadoghq.com/integrations/slack/) in Datadog
2. Monitors use `@slack-mulesoft-alerts` — map this to your channel

### PagerDuty

1. Install the [PagerDuty integration](https://docs.datadoghq.com/integrations/pagerduty/)
2. Critical monitors route to `@pagerduty-mulesoft`

### Email

Add `@email@yourcompany.com` to any monitor message — works out of the box.

### Recommended Routing

| Monitor | Slack | PagerDuty | Email |
|---------|:-----:|:---------:|:-----:|
| Dead Letter Queue | ✅ | ✅ | ✅ |
| Queue Depth Critical | ✅ | ✅ | — |
| High In-Flight | ✅ | — | — |
| Stale Queue | ✅ | — | ✅ |
| Throughput Drop | ✅ | — | — |
| Scrape Errors | ✅ | — | — |
