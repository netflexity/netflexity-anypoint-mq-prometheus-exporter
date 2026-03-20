<p align="center">
  <h1 align="center">Anypoint MQ Prometheus Exporter</h1>
  <p align="center">
    Real-time metrics and monitoring for MuleSoft Anypoint MQ — auto-discovers every org, environment, queue, and exchange.
  </p>
</p>

<p align="center">
  <a href="https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html"><img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white" alt="Java 17"></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3"></a>
  <a href="https://prometheus.io/"><img src="https://img.shields.io/badge/Prometheus-Exporter-E6522C?logo=prometheus&logoColor=white" alt="Prometheus"></a>
  <a href="https://grafana.com/"><img src="https://img.shields.io/badge/Grafana-Ready-F46800?logo=grafana&logoColor=white" alt="Grafana"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
</p>

---

## Overview

A turnkey Prometheus exporter for **MuleSoft Anypoint MQ**. Point it at your Anypoint Platform Connected App credentials and it will automatically discover all organizations, environments, queues, and exchanges — then expose production-grade metrics at `/actuator/prometheus`.

No manual configuration of queue names. No YAML lists to maintain. It just works.

## Architecture

```
+---------------------------------------------------------+
|                  Anypoint Platform APIs                  |
|  /accounts/api/me              (org discovery)          |
|  /accounts/api/organizations/  (env discovery)          |
|  /mq/admin/api/v1/             (queue/exchange list)    |
|  /mq/stats/api/v1/             (throughput + depth)     |
|  /audit/v2/                    (config change detection)|
|  mq-{region}.anypoint.mulesoft.com  (broker API)        |
+---------------------------------------------------------+
                          |
                          v
+---------------------------------------------------------+
|             AMQ Exporter  (Spring Boot 3)               |
|                                                         |
|  /actuator/prometheus   Prometheus metrics endpoint     |
|  /api/status            Discovered orgs & envs          |
|  /api/loader/*          Demo data loader                |
|  /api/health-scores     Queue health scores (Pro)       |
|  /api/monitors          Monitor definitions (Pro)       |
|                                                         |
|  +------------------+  +-----------------------------+  |
|  |  Auto-Discovery  |  |  Monitors Module (Pro)      |  |
|  |  - Orgs          |  |  - Queue depth alerts       |  |
|  |  - Environments  |  |  - DLQ detection            |  |
|  |  - Queues        |  |  - Throughput anomalies     |  |
|  |  - Exchanges     |  |  - Health scores (0-100)    |  |
|  +------------------+  +-----------------------------+  |
|                                                         |
|  +------------------+  +-----------------------------+  |
|  |  Demo Loader     |  |  Advanced Metrics           |  |
|  |  - Publish msgs  |  |  - Dequeue rate (QoS)       |  |
|  |  - Consume msgs  |  |  - Stale message detection  |  |
|  |  - Continuous     |  |  - Audit log monitoring     |  |
|  |  - Traffic spikes |  |  - Billing/usage tracking   |  |
|  +------------------+  +-----------------------------+  |
+---------------------------------------------------------+
                          | scrape /actuator/prometheus
                          v
+---------------------------------------------------------+
|                     Prometheus                          |
|               30-day retention, PromQL                  |
+---------------------------------------------------------+
                          | PromQL queries
                          v
+---------------------------------------------------------+
|                      Grafana                            |
|     70 panels - 8 alert rules - Full observability      |
+---------------------------------------------------------+
```

## Features

- **Zero-Config Discovery** — Automatically finds all orgs, environments, queues, and exchanges. Refreshes every 5 minutes.
- **Real-Time Queue Depth** — Uses the batch Stats API endpoint (`/queues?destinationIds=`) for instant `messages` and `inflightMessages` counts with zero lag.
- **Multi-Org Support** — Monitor queues across every organization and sub-org your Connected App can access. All metrics include an `org` label for filtering.
- **Dequeue Rate (QoS)** — `received/sent * 100` per queue. Detects consumers falling behind producers.
- **Usage & Billing Tracking** — 30-day aggregate usage (messages sent/received/acked, billable units, API requests) at both environment and org level.
- **Audit Log Monitoring** — Detects MQ configuration changes (queue/exchange created, deleted, modified) via the Anypoint Audit API.
- **Stale Message Detection** — Browses queue messages across scrape cycles, compares IDs — same message in consecutive scrapes = stale (stuck).
- **Demo Data Loader** — Built-in REST API to publish/consume messages for dashboard demos. One-shot or continuous mode.
- **Prometheus-Native** — Standard `/actuator/prometheus` endpoint via Micrometer. Drop-in compatible with any Prometheus scraper.
- **Pre-Built Grafana Dashboards** — 70 panels across 8 sections with 8 alert rules. Includes Organization, Environment, and Queue dropdown selectors.
- **Pre-Built Datadog Dashboards** — 19 widget groups with 11 production monitors.
- **Advanced Monitors (Pro)** — Health scores, queue depth alerts, DLQ detection, throughput anomaly detection.
- **Multi-Channel Alerting (Pro)** — Slack, PagerDuty, Email, Microsoft Teams, and generic Webhooks.
- **Works Everywhere** — Grafana, Datadog, New Relic, Dynatrace — anything that scrapes Prometheus metrics.
- **Docker Compose Included** — Full stack (Exporter + Prometheus + Grafana) in one command.
- **Railway-Ready** — Deploys as 3 Railway services for ~$15/month. [Setup guide](RAILWAY-SETUP.md)

## Quick Start

### 1. Get Anypoint Connected App Credentials

Anypoint Platform > Access Management > Connected Apps > **Create**:
- Type: *App acts on its own behalf (client credentials)*
- Scopes: `View Environment`, `View Organization`, `Anypoint MQ Admin`, `Anypoint MQ Stats`
- For the Demo Loader: also add `Messaging Contributor` scope

### 2. Run with Docker Compose

```bash
# Clone the repo
git clone https://github.com/netflexity/anypoint-mq-prometheus-exporter.git
cd anypoint-mq-prometheus-exporter

# Set your credentials
export ANYPOINT_CLIENT_ID=your-connected-app-client-id
export ANYPOINT_CLIENT_SECRET=your-connected-app-client-secret

# Start the full stack
docker-compose up -d
```

| Service    | URL                              |
|------------|----------------------------------|
| Exporter   | http://localhost:9101             |
| Prometheus | http://localhost:9090             |
| Grafana    | http://localhost:3000 (admin/admin) |

### 3. Verify

```bash
# Check discovered orgs and environments
curl http://localhost:9101/api/status

# View raw Prometheus metrics
curl http://localhost:9101/actuator/prometheus | grep anypoint_mq

# Check the demo data loader
curl http://localhost:9101/api/loader/status
```

## Metrics Reference

### Queue Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_queue_info` | Gauge | Queue metadata (value=1). Labels: `org`, `queue_name`, `environment`, `region`, `is_fifo`, `is_dlq`, `max_deliveries`, `ttl` |
| `anypoint_mq_queue_messages_in_queue` | Gauge | Messages waiting to be consumed (real-time via batch Stats API) |
| `anypoint_mq_queue_messages_in_flight` | Gauge | Messages currently being processed (real-time via batch Stats API) |
| `anypoint_mq_queue_messages_sent_total` | Gauge | Messages sent in the lookback window |
| `anypoint_mq_queue_messages_received_total` | Gauge | Messages received in the lookback window |
| `anypoint_mq_queue_messages_acked_total` | Gauge | Messages acknowledged in the lookback window |
| `anypoint_mq_queue_size_bytes` | Gauge | Queue size in bytes |
| `anypoint_mq_queue_dequeue_rate_percent` | Gauge | Dequeue rate: `(received / sent) * 100`. 100% = healthy, <100% = consumers falling behind |
| `anypoint_mq_queue_stale_messages` | Gauge | Number of stale messages detected (same messages seen across consecutive scrapes) |

### Exchange Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_exchange_messages_published_total` | Gauge | Messages published to the exchange |
| `anypoint_mq_exchange_messages_delivered_total` | Gauge | Messages delivered from the exchange to bound queues |

### Usage & Billing Metrics (per Environment)

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_usage_messages_sent_total` | Gauge | Total messages sent (30-day rolling) |
| `anypoint_mq_usage_messages_received_total` | Gauge | Total messages received (30-day rolling) |
| `anypoint_mq_usage_messages_acked_total` | Gauge | Total messages acknowledged (30-day rolling) |
| `anypoint_mq_usage_billable_units_total` | Gauge | Total billable units (30-day rolling) |
| `anypoint_mq_usage_api_requests_total` | Gauge | Total API requests (30-day rolling) |

### Usage & Billing Metrics (Org-Level)

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_org_usage_messages_sent_total` | Gauge | Org-level messages sent (30-day) |
| `anypoint_mq_org_usage_messages_received_total` | Gauge | Org-level messages received (30-day) |
| `anypoint_mq_org_usage_messages_acked_total` | Gauge | Org-level messages acknowledged (30-day) |
| `anypoint_mq_org_usage_billable_units_total` | Gauge | Org-level billable units (30-day) |
| `anypoint_mq_org_usage_api_requests_total` | Gauge | Org-level API requests (30-day) |

### Audit Log Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_audit_changes_total` | Gauge | Total MQ config changes detected |
| `anypoint_mq_audit_creates` | Gauge | Queues/exchanges created |
| `anypoint_mq_audit_deletes` | Gauge | Queues/exchanges deleted |
| `anypoint_mq_audit_updates` | Gauge | Queues/exchanges modified |

### Common Labels

All metrics include these labels:

| Label | Description |
|-------|-------------|
| `org` | Organization name (supports multi-org/sub-org setups) |
| `environment` | Environment name (Development, Production, etc.) |
| `region` | MQ region (us-east-1, eu-west-1, etc.) |
| `queue_name` / `exchange_name` | Destination name |

### How Metrics Are Collected

The exporter uses multiple complementary Anypoint MQ APIs:

- **Batch Stats API** (`/queues?destinationIds=q1,q2,q3`) — Returns real-time `messages` and `inflightMessages` for all queues in a single call. Zero lag. Used for queue depth gauges.
- **Per-Queue Stats API** (`/queues/{id}?startDate=...&endDate=...`) — Returns time-series throughput data (sent, received, acked) with a configurable lookback window (default 1 hour). Values are summed across all data points in the window.
- **Per-Exchange Stats API** (`/exchanges/{id}?startDate=...&endDate=...`) — Returns time-series publish/deliver data. Same lookback and summing behavior as queue throughput.
- **Usage Stats API** (`/mq/stats/api/v1/organizations/{orgId}[/environments/{envId}]`) — Returns 30-day aggregate usage (messages sent/received/acked, billable units, API requests).
- **Audit API** (`/audit/v2/organizations/{orgId}/query`) — Returns MQ config change events (create/delete/update). Filtered by `platform=mq`.
- **Broker API** (`mq-{region}.anypoint.mulesoft.com/api/v1/...`) — Used by the Demo Loader and Stale Message Detector to publish, consume, and browse messages.

## Demo Data Loader

The built-in loader creates realistic traffic patterns for dashboards. Essential for demos and evaluations.

> **Required scope:** Your Connected App needs `Messaging Contributor` in addition to the read-only scopes.

### One-Shot Operations

```bash
# Publish 1-10 random messages to every queue
curl -X POST http://localhost:9101/api/loader/load

# Publish to specific queues by prefix
curl -X POST "http://localhost:9101/api/loader/load?queuePrefix=order&minMessages=5&maxMessages=20"

# Consume (purge) all messages from all queues
curl -X POST http://localhost:9101/api/loader/consume

# Full cycle: publish → wait 60s → consume (creates a traffic spike)
curl -X POST "http://localhost:9101/api/loader/cycle?minMessages=5&maxMessages=15&delaySeconds=60"
```

### Continuous Mode

```bash
# Start continuous cycles every 5 minutes
curl -X POST "http://localhost:9101/api/loader/start?intervalSeconds=300&minMessages=3&maxMessages=10"

# Check if the loader is running
curl http://localhost:9101/api/loader/status

# Stop the loader
curl -X POST http://localhost:9101/api/loader/stop
```

### Debug / Troubleshoot

```bash
# Test a single publish to a specific queue — shows the actual HTTP status + error body
curl -X POST "http://localhost:9101/api/loader/test?queue=my-queue&environment=Sandbox"
```

### Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `queuePrefix` | *(all)* | Only target queues starting with this prefix |
| `minMessages` | `1` | Minimum messages per queue per cycle |
| `maxMessages` | `10` | Maximum messages per queue per cycle |
| `delaySeconds` | `60` | Seconds between load and consume in a cycle |
| `intervalSeconds` | `300` | Seconds between continuous mode cycles |

### How It Works

1. Lists all queues via the Admin API (across all configured environments and regions)
2. Publishes random JSON messages in batches of 10 (Broker API PUT)
3. Optionally consumes messages by GET + DELETE (acknowledges each message)
4. FIFO queues are handled sequentially; standard queues use parallel batches
5. Messages have a 2-minute TTL so they self-clean even without explicit consume

## Tester's Guide

### Prerequisites

1. **Anypoint Connected App** with these scopes:
   - `View Environment`, `View Organization` (discovery)
   - `Anypoint MQ Admin`, `Anypoint MQ Stats` (metrics)
   - `Messaging Contributor` (loader — optional but recommended for demos)

2. **Queues** — The exporter auto-discovers all queues. Create a few in your Anypoint MQ console if none exist.

### Step-by-Step Evaluation

#### 1. Start the Exporter

```bash
export ANYPOINT_CLIENT_ID=<your-client-id>
export ANYPOINT_CLIENT_SECRET=<your-client-secret>

# Option A: Docker Compose (includes Prometheus + Grafana)
docker-compose up -d

# Option B: Standalone JAR
mvn clean package -DskipTests
java -jar target/anypoint-mq-prometheus-exporter-*.jar
```

#### 2. Verify Discovery

```bash
# Should show your org, environments, and queue counts
curl http://localhost:9101/api/status | python3 -m json.tool

# Should show Anypoint auth status
curl http://localhost:9101/actuator/health | python3 -m json.tool
```

#### 3. Check Metrics

```bash
# Raw Prometheus metrics
curl -s http://localhost:9101/actuator/prometheus | grep anypoint_mq | head -30

# You should see: queue_info, messages_in_queue, messages_in_flight, etc.
```

#### 4. Load Demo Data

```bash
# Publish messages to generate dashboard traffic
curl -X POST "http://localhost:9101/api/loader/load?minMessages=5&maxMessages=15"

# Start continuous mode for a realistic demo
curl -X POST "http://localhost:9101/api/loader/start?intervalSeconds=120&minMessages=3&maxMessages=10"
```

#### 5. Explore Dashboards

- **Grafana**: http://localhost:3000 (admin/admin)
  - Import `grafana/dashboards/anypoint-mq-dashboard.json`
  - Use the org/environment/queue dropdowns at the top
  - Check sections: Overview, Queue Depth, Throughput, Exchange Activity, Usage, Billing, QoS, Audit

- **Datadog**: Import `datadog/dashboard.json`

#### 6. Verify Alert Rules

```bash
# Import Grafana alerts
./grafana/alerts/import-alerts.sh http://localhost:3000 <PROMETHEUS_UID>
```

8 pre-configured alert rules:

| Alert | Trigger | Severity |
|-------|---------|----------|
| Dead Letter Queue Growth | DLQ messages > 0 for 5 min | Critical |
| Queue Depth Spike | Messages > 1000 for 10 min | Warning |
| High In-Flight Messages | In-flight > 500 for 5 min | Warning |
| Scrape Errors | Errors increasing for 5 min | Info |
| Low Dequeue Rate | Rate < 50% for 5 min | Warning |
| Stale Messages Detected | Stale > 0 for 10 min | Critical |
| MQ Configuration Change | Any change detected | Info |
| Billable Unit Threshold | Billable units > 500K | Warning |

#### 7. Stop the Loader

```bash
curl -X POST http://localhost:9101/api/loader/stop
```

### What to Look For

- **Queue depth gauges** respond in real-time (no lag)
- **Throughput charts** show traffic waves when the loader runs
- **Dequeue rate** shows 100% when consumers keep up, drops when they fall behind
- **Usage/billing panels** show 30-day aggregate — useful for capacity planning
- **Audit section** lights up when someone creates/deletes/modifies a queue
- **Stale messages panel** shows stuck messages (if any exist)
- **All dropdown filters work** — org → environment → queue cascading

## Configuration

All settings can be overridden via environment variables or `application.yml`.

### Core Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `ANYPOINT_CLIENT_ID` | — | Connected App client ID (**required**) |
| `ANYPOINT_CLIENT_SECRET` | — | Connected App client secret (**required**) |
| `ANYPOINT_AUTO_DISCOVERY` | `true` | Auto-discover all orgs and environments |
| `ANYPOINT_ORG_ID` | *(auto)* | Root organization ID (auto-discovered if omitted) |
| `ANYPOINT_REGIONS` | `us-east-1` | Comma-separated MQ regions to scrape |
| `PORT` | `9101` | HTTP server port |

### Scrape Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `SCRAPE_INTERVAL_SECONDS` | `60` | How often to scrape metrics (seconds) |
| `anypoint.scrape.periodSeconds` | `600` | Stats API bucket granularity (seconds) |
| `anypoint.scrape.enabled` | `true` | Enable/disable metric collection |
| `STALE_CHECK_INTERVAL_MS` | `300000` | Stale message detection interval (milliseconds, default 5 min) |

### HTTP Client

| Variable | Default | Description |
|----------|---------|-------------|
| `anypoint.http.connectTimeoutSeconds` | `30` | HTTP connect timeout |
| `anypoint.http.readTimeoutSeconds` | `60` | HTTP read timeout |
| `anypoint.http.maxRetries` | `3` | Max retry attempts for failed API calls |

### Monitors (Pro)

| Variable | Default | Description |
|----------|---------|-------------|
| `ANYPOINT_MONITORS_ENABLED` | `true` | Enable health scores and alerting |
| `ANYPOINT_LICENSE_KEY` | — | Pro license key for monitors module |
| `anypoint.monitors.evaluationIntervalSeconds` | `60` | Monitor evaluation frequency |
| `anypoint.monitors.defaults.cooldownMinutes` | `15` | Alert cooldown to prevent notification storms |

### Notification Channels

| Variable | Description |
|----------|-------------|
| `SLACK_ENABLED` / `SLACK_WEBHOOK_URL` | Slack incoming webhook |
| `PAGERDUTY_ENABLED` / `PAGERDUTY_ROUTING_KEY` | PagerDuty Events API |
| `EMAIL_ENABLED` / `ALERT_EMAIL_TO` | Email notifications |
| `TEAMS_ENABLED` / `TEAMS_WEBHOOK_URL` | Microsoft Teams webhook |
| `WEBHOOK_ENABLED` / `WEBHOOK_URL` / `WEBHOOK_TOKEN` | Generic webhook with bearer auth |

## Grafana Dashboard

The included dashboard (`grafana/dashboards/anypoint-mq-dashboard.json`) provides **70 panels** across 8 sections:

| Section | Panels | Description |
|---------|--------|-------------|
| Overview | 4 | Total queues, messages, in-flight, exchanges |
| Queue Depth | 6 | Messages in queue/in-flight over time, per queue |
| Throughput | 6 | Sent, received, acked per queue |
| Exchange Activity | 4 | Published and delivered per exchange |
| Queue Inventory | 1 | Table with all queue metadata (FIFO, DLQ, TTL, max deliveries) |
| MQ API Usage (30-Day) | 6 | Gauges + bar chart + trend for sent/received/acked |
| Billing & API Requests | 3 | Billable units + API requests (env + org level) |
| Queue Health & QoS | 2 | Dequeue rate gauge + stale messages panel |
| Audit Log | 5 | Config changes total + creates/deletes/updates + trend |

### Template Variables (Dropdowns)

| Variable | Description |
|----------|-------------|
| `org` | Organization filter (multi-select, cascading) |
| `environment` | Environment filter (filtered by selected org) |
| `queue` | Queue filter (filtered by selected org + environment) |

### Import

**Option A** — Grafana UI: Dashboards > Import > Upload JSON > select `grafana/dashboards/anypoint-mq-dashboard.json`

**Option B** — API:
```bash
curl -X POST http://admin:password@localhost:3000/api/dashboards/db \
  -H "Content-Type: application/json" \
  -d @grafana/dashboards/anypoint-mq-dashboard.json
```

### Alert Rules

8 pre-configured Grafana alert rules. Import with one command:

```bash
./grafana/alerts/import-alerts.sh <GRAFANA_URL> <PROMETHEUS_DATASOURCE_UID> [admin] [password]
```

Configure notifications under **Alerting > Contact points** in Grafana (Slack, email, PagerDuty, etc.).

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/prometheus` | GET | Prometheus metrics (scrape target) |
| `/actuator/health` | GET | Application health check |
| `/api/status` | GET | Discovered orgs, environments, and config |
| `/api/discover` | POST | Trigger manual re-discovery |
| `/api/loader/load` | POST | Publish random messages to queues |
| `/api/loader/consume` | POST | Consume (purge) all messages from queues |
| `/api/loader/cycle` | POST | Load → delay → consume (traffic spike) |
| `/api/loader/start` | POST | Start continuous load/consume cycles |
| `/api/loader/stop` | POST | Stop continuous loader |
| `/api/loader/status` | GET | Check if continuous loader is running |
| `/api/loader/test` | POST | Debug: test single publish with full error output |
| `/api/health-scores` | GET | Queue health scores (Pro) |
| `/api/monitors` | GET | Monitor definitions (Pro) |

## Deployment

### Docker Compose (Recommended for dev/staging)

The included `docker-compose.yml` runs the full stack: Exporter, Prometheus (30-day retention), and Grafana with pre-provisioned dashboards.

### Railway (Recommended for production)

Deploy as 3 Railway services for ~$15/month. See the full [Railway Setup Guide](RAILWAY-SETUP.md) for step-by-step instructions.

### Standalone JAR

```bash
mvn clean package -DskipTests
java -jar target/anypoint-mq-prometheus-exporter-*.jar
```

## Datadog Integration

Already using Datadog? The exporter works with Datadog's built-in OpenMetrics check — zero additional code required.

### Datadog Agent + OpenMetrics

If the Datadog Agent runs alongside the exporter:

```yaml
# /etc/datadog-agent/conf.d/openmetrics.d/conf.yaml
instances:
  - openmetrics_endpoint: http://exporter-host:9101/actuator/prometheus
    namespace: anypoint_mq
    metrics:
      - anypoint_mq_queue_messages_in_queue
      - anypoint_mq_queue_messages_in_flight
      - anypoint_mq_queue_messages_sent_total
      - anypoint_mq_queue_messages_received_total
      - anypoint_mq_queue_messages_acked_total
      - anypoint_mq_queue_dequeue_rate_percent
      - anypoint_mq_queue_stale_messages
      - anypoint_mq_exchange_messages_published_total
      - anypoint_mq_exchange_messages_delivered_total
      - anypoint_mq_usage_billable_units_total
      - anypoint_mq_usage_api_requests_total
      - anypoint_mq_audit_changes_total
    tags:
      - service:anypoint-mq
      - env:production
```

All metrics appear with their full label set (`org`, `environment`, `queue_name`, `region`) for filtering, grouping, and alerting.

### Pre-built Datadog Dashboard

Import `datadog/dashboard.json` into Datadog: Dashboards > New Dashboard > Import.

19 widget groups covering queue depth, throughput, exchange activity, usage/billing, QoS, and audit log monitoring.

### Datadog Monitors

11 production-ready monitors in `datadog/monitors/`:

| Monitor | Trigger | Severity |
|---------|---------|----------|
| Queue Depth Critical | Depth > 10,000 | Critical |
| Dead Letter Queue Alert | DLQ > 0 | Critical |
| High In-Flight Messages | In-flight > 500 | Warning |
| Stale Queue (No Traffic) | Zero throughput | Warning |
| Throughput Drop | 50% drop vs previous period | Warning |
| Scrape Errors | Errors increasing | Info |
| Low Dequeue Rate | Rate < 50% | Warning |
| Stale Messages Detected | Stale > 0 | Critical |
| MQ Config Change | Any change | Info |
| Billable Unit Threshold | Units > 500K | Warning |

Import with: `./datadog/monitors/import-monitors.sh`

See [`datadog/README.md`](datadog/README.md) for details.

### New Relic & Dynatrace

Both support Prometheus remote write or OpenMetrics scraping:

- **New Relic**: Use the [Prometheus remote write integration](https://docs.newrelic.com/docs/infrastructure/prometheus-integrations/install-configure-remote-write/set-your-prometheus-remote-write-integration/) — add a `remote_write` block to your Prometheus config.
- **Dynatrace**: Use the [OpenMetrics extension](https://www.dynatrace.com/hub/detail/prometheus/) or ActiveGate Prometheus integration.

No changes to the exporter needed — standard Prometheus metrics.

## Free vs Pro

| Feature | Free | Pro |
|---------|:----:|:---:|
| Queue & exchange metrics | &#x2714; | &#x2714; |
| Real-time queue depth | &#x2714; | &#x2714; |
| Auto-discovery (multi-org) | &#x2714; | &#x2714; |
| Prometheus endpoint | &#x2714; | &#x2714; |
| Dequeue rate (QoS) | &#x2714; | &#x2714; |
| Usage & billing metrics | &#x2714; | &#x2714; |
| Demo data loader | &#x2714; | &#x2714; |
| Grafana dashboards + alerts | &#x2714; | &#x2714; |
| Datadog/New Relic/Dynatrace | &#x2714; | &#x2714; |
| Audit log monitoring | — | &#x2714; |
| Stale message detection | — | &#x2714; |
| Health scores (0-100) | — | &#x2714; |
| Queue depth monitors | — | &#x2714; |
| DLQ alerting | — | &#x2714; |
| Throughput anomaly detection | — | &#x2714; |
| Multi-channel notifications | — | &#x2714; |

## Shared Library

This exporter shares core infrastructure with the [Anypoint Metrics Prometheus Exporter](https://bitbucket.org/netflexity/anypoint-metrics-prometheus-exporter) via the [`anypoint-common`](https://bitbucket.org/netflexity/netflexity-anypoint-common) library:

- OAuth2 authentication with token caching
- Environment auto-discovery
- Monitor evaluation engine
- 5 notification channels (Slack, PagerDuty, Email, Teams, Webhook)
- REST API controllers, health indicators, license gating

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes
4. Push and open a Pull Request

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

<p align="center">
  Built by <a href="https://netflexity.com">Netflexity</a>
</p>
