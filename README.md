<p align="center">
  <h1 align="center">Anypoint MQ Prometheus Exporter</h1>
  <p align="center">
    Real-time metrics and monitoring for MuleSoft Anypoint MQ - auto-discovers every org, environment, queue, and exchange.
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

A turnkey Prometheus exporter for **MuleSoft Anypoint MQ**. Point it at your Anypoint Platform Connected App credentials and it will automatically discover all organizations, environments, queues, and exchanges - then expose production-grade metrics at `/actuator/prometheus`.

No manual configuration of queue names. No YAML lists to maintain. It just works.

## Architecture

```
+---------------------------------------------------------+
|                  Anypoint Platform APIs                  |
|  /accounts/api/me              (org discovery)          |
|  /accounts/api/organizations/  (env discovery)          |
|  /mq/admin/api/v1/             (queue/exchange list)    |
|  /mq/stats/api/v1/             (throughput + depth)     |
+---------------------------------------------------------+
                          |
                          v
+---------------------------------------------------------+
|             AMQ Exporter  (Spring Boot 3)               |
|                                                         |
|  /actuator/prometheus   Prometheus metrics endpoint     |
|  /api/status            Discovered orgs & envs          |
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
|     Pre-built dashboards - Alerts - Visualizations      |
+---------------------------------------------------------+
```

## Features

- **Zero-Config Discovery** - Automatically finds all orgs, environments, queues, and exchanges. Refreshes every 5 minutes.
- **Real-Time Queue Depth** - Uses the batch Stats API endpoint (`/queues?destinationIds=`) for instant `messages` and `inflightMessages` counts with zero lag.
- **Multi-Org Support** - Monitor queues across every organization and sub-org your Connected App can access. All metrics include an `org` label for filtering.
- **Prometheus-Native** - Standard `/actuator/prometheus` endpoint via Micrometer. Drop-in compatible with any Prometheus scraper.
- **Pre-Built Grafana Dashboards** - Queue depth, throughput, exchange activity, inventory table, and 4 pre-configured alert rules. Includes Organization, Environment, and Queue dropdown selectors.
- **Advanced Monitors (Pro)** - Health scores, queue depth alerts, DLQ detection, throughput anomaly detection.
- **Multi-Channel Alerting (Pro)** - Slack, PagerDuty, Email, Microsoft Teams, and generic Webhooks.
- **Works Everywhere** - Grafana, Datadog, New Relic, Dynatrace - anything that scrapes Prometheus metrics.
- **Docker Compose Included** - Full stack (Exporter + Prometheus + Grafana) in one command.
- **Railway-Ready** - Deploys as 3 Railway services for ~$15/month. [Setup guide](RAILWAY-SETUP.md)

## Quick Start

### 1. Get Anypoint Connected App Credentials

Anypoint Platform > Access Management > Connected Apps > **Create**:
- Type: *App acts on its own behalf (client credentials)*
- Scopes: `View Environment`, `View Organization`, `Anypoint MQ Admin`, `Anypoint MQ Stats`

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

### Exchange Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_exchange_messages_published_total` | Gauge | Messages published to the exchange |
| `anypoint_mq_exchange_messages_delivered_total` | Gauge | Messages delivered from the exchange to bound queues |

### Common Labels

All metrics include these labels:

| Label | Description |
|-------|-------------|
| `org` | Organization name (supports multi-org/sub-org setups) |
| `environment` | Environment name (Development, Production, etc.) |
| `region` | MQ region (us-east-1, eu-west-1, etc.) |
| `queue_name` / `exchange_name` | Destination name |

### How Metrics Are Collected

The exporter uses two complementary Anypoint MQ APIs:

- **Batch Stats API** (`/queues?destinationIds=q1,q2,q3`) - Returns real-time `messages` and `inflightMessages` for all queues in a single call. Zero lag. Used for queue depth gauges.
- **Per-Queue Stats API** (`/queues/{id}?startDate=...&endDate=...`) - Returns time-series throughput data (sent, received, acked) with a configurable lookback window (default 1 hour). Values are summed across all data points in the window.
- **Per-Exchange Stats API** (`/exchanges/{id}?startDate=...&endDate=...`) - Returns time-series publish/deliver data. Same lookback and summing behavior as queue throughput.

## Configuration

All settings can be overridden via environment variables or `application.yml`.

### Core Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `ANYPOINT_CLIENT_ID` | - | Connected App client ID (**required**) |
| `ANYPOINT_CLIENT_SECRET` | - | Connected App client secret (**required**) |
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
| `ANYPOINT_LICENSE_KEY` | - | Pro license key for monitors module |
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

The included dashboard (`grafana/dashboards/anypoint-mq-dashboard.json`) provides:

- **Overview stats** - Total queues, total messages, total in-flight, total exchanges
- **Queue depth over time** - Messages in queue per queue
- **In-flight messages** - Messages being processed per queue
- **Throughput charts** - Sent, received, acknowledged per queue
- **Exchange activity** - Published and delivered per exchange
- **Queue inventory table** - All queues with metadata (FIFO, DLQ, TTL, max deliveries)

### Template Variables (Dropdowns)

| Variable | Description |
|----------|-------------|
| `org` | Organization filter (multi-select, cascading) |
| `environment` | Environment filter (filtered by selected org) |
| `queue` | Queue filter (filtered by selected org + environment) |

### Import

**Option A** - Grafana UI: Dashboards > Import > Upload JSON > select `grafana/dashboards/anypoint-mq-dashboard.json`

**Option B** - API:
```bash
curl -X POST http://admin:password@localhost:3000/api/dashboards/db \
  -H "Content-Type: application/json" \
  -d @grafana/dashboards/anypoint-mq-dashboard.json
```

### Alert Rules

Pre-configured Grafana alert rules are included. Import with one command:

```bash
./grafana/alerts/import-alerts.sh <GRAFANA_URL> <PROMETHEUS_DATASOURCE_UID> [admin] [password]
```

| Alert | Threshold | Duration | Severity |
|-------|-----------|----------|----------|
| Dead Letter Queue Growth | Messages > 0 | 5 min | Critical |
| Queue Depth Spike | Messages > 1000 | 10 min | Warning |
| High In-Flight Messages | In-flight > 500 | 5 min | Warning |
| Scrape Errors | Errors increasing | 5 min | Info |

Configure notifications under **Alerting > Contact points** in Grafana (Slack, email, PagerDuty, etc.).

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/prometheus` | GET | Prometheus metrics (scrape target) |
| `/actuator/health` | GET | Application health check |
| `/api/status` | GET | Discovered orgs, environments, and config |
| `/api/discover` | POST | Trigger manual re-discovery |
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

Already using Datadog? The exporter works with Datadog's built-in OpenMetrics check - zero additional code required.

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
      - anypoint_mq_queue_messages_sent
      - anypoint_mq_queue_messages_received
      - anypoint_mq_queue_messages_acked
      - anypoint_mq_exchange_messages_published
      - anypoint_mq_exchange_messages_delivered
    tags:
      - service:anypoint-mq
      - env:production
```

All metrics appear with their full label set (`org`, `environment`, `queue_name`, `region`) for filtering, grouping, and alerting.

### Pre-built Datadog Dashboard

Import `datadog/dashboard.json` into Datadog: Dashboards > New Dashboard > Import.

### Datadog Monitors

See [`datadog/README.md`](datadog/README.md) for 5 production-ready monitor definitions with JSON snippets, bulk import scripts, and notification routing guidance.

### New Relic & Dynatrace

Both support Prometheus remote write or OpenMetrics scraping:

- **New Relic**: Use the [Prometheus remote write integration](https://docs.newrelic.com/docs/infrastructure/prometheus-integrations/install-configure-remote-write/set-your-prometheus-remote-write-integration/) - add a `remote_write` block to your Prometheus config.
- **Dynatrace**: Use the [OpenMetrics extension](https://www.dynatrace.com/hub/detail/prometheus/) or ActiveGate Prometheus integration.

No changes to the exporter needed - standard Prometheus metrics.

## Free vs Pro

| Feature | Free | Pro |
|---------|:----:|:---:|
| Queue & exchange metrics | Y | Y |
| Real-time queue depth | Y | Y |
| Auto-discovery (multi-org) | Y | Y |
| Prometheus endpoint | Y | Y |
| Grafana dashboards + alerts | Y | Y |
| Datadog/New Relic/Dynatrace | Y | Y |
| Health scores (0-100) | - | Y |
| Queue depth monitors | - | Y |
| DLQ alerting | - | Y |
| Throughput anomaly detection | - | Y |
| Multi-channel notifications | - | Y |

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

Apache License 2.0 - see [LICENSE](LICENSE) for details.

---

<p align="center">
  Built by <a href="https://netflexity.com">Netflexity</a>
</p>
