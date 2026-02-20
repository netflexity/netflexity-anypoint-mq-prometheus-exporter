<p align="center">
  <h1 align="center">Anypoint MQ Prometheus Exporter</h1>
  <p align="center">
    Real-time metrics & monitoring for MuleSoft Anypoint MQ — auto-discovers every org, environment, queue, and exchange.
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
┌──────────────────────────────────────────────────────┐
│                 Anypoint Platform APIs                │
│  /accounts/api/me              (org discovery)       │
│  /accounts/api/organizations/… (env discovery)       │
│  /mq/admin/api/v1/…           (queue/exchange list)  │
│  /mq/stats/api/v1/…           (metrics)              │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│            AMQ Exporter  (Spring Boot 3)             │
│                                                      │
│  /actuator/prometheus   Prometheus metrics endpoint  │
│  /api/status            Discovered orgs & envs       │
│  /api/health-scores     Queue health scores (Pro)    │
│  /api/monitors          Monitor definitions (Pro)    │
│                                                      │
│  ┌────────────────┐  ┌─────────────────────────────┐ │
│  │  Auto-Discovery│  │  Monitors Module (Pro)      │ │
│  │  • Orgs        │  │  • Queue depth alerts       │ │
│  │  • Environments│  │  • DLQ detection            │ │
│  │  • Queues      │  │  • Throughput anomalies     │ │
│  │  • Exchanges   │  │  • Health scores (0–100)    │ │
│  └────────────────┘  └─────────────────────────────┘ │
└──────────────────────┬───────────────────────────────┘
                       │ scrape /actuator/prometheus
                       ▼
┌──────────────────────────────────────────────────────┐
│                    Prometheus                        │
│              30-day retention, PromQL                │
└──────────────────────┬───────────────────────────────┘
                       │ PromQL queries
                       ▼
┌──────────────────────────────────────────────────────┐
│                     Grafana                          │
│    Pre-built dashboards • Alerts • Visualizations    │
└──────────────────────────────────────────────────────┘
```

## Features

- **Zero-Config Discovery** — Automatically finds all orgs, environments, queues, and exchanges. Refreshes every 5 minutes.
- **Prometheus-Native** — Standard `/actuator/prometheus` endpoint via Micrometer. Drop-in compatible with any Prometheus scraper.
- **Pre-Built Grafana Dashboards** — Queue depth, throughput, exchange activity, inventory table — ready to import.
- **Multi-Org Support** — Monitor queues across every organization and environment your Connected App can access.
- **Advanced Monitors (Pro)** — Health scores, queue depth alerts, DLQ detection, throughput anomaly detection.
- **Multi-Channel Alerting (Pro)** — Slack, PagerDuty, Email, Microsoft Teams, and generic Webhooks.
- **Works Everywhere** — Grafana, Datadog, New Relic, Dynatrace — anything that scrapes Prometheus metrics.
- **Docker Compose Included** — Full stack (Exporter + Prometheus + Grafana) in one command.
- **Railway-Ready** — Deploys as 3 Railway services for ~$15/month. [Setup guide →](RAILWAY-SETUP.md)

## Quick Start

### 1. Get Anypoint Connected App Credentials

Anypoint Platform → Access Management → Connected Apps → **Create**:
- Type: *App acts on its own behalf (client credentials)*
- Scopes: `View Environment`, `View Organization`, `Anypoint MQ Admin`, `Anypoint MQ Stats`

### 2. Run with Docker Compose

```bash
# Clone the repo
git clone https://bitbucket.org/netflexity/anypoint-mq-prometheus-exporter.git
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
| Grafana    | http://localhost:3000 (admin / netflexity2026) |

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
| `anypoint_mq_queue` | Gauge | Queue metadata (value=1). Labels include `is_fifo`, `is_dlq`, `max_deliveries`, `ttl`. |
| `anypoint_mq_queue_messages_in_queue` | Gauge | Messages waiting to be consumed |
| `anypoint_mq_queue_messages_in_flight` | Gauge | Messages currently being processed |
| `anypoint_mq_queue_messages_sent` | Gauge | Messages sent in the scrape period |
| `anypoint_mq_queue_messages_received` | Gauge | Messages received in the scrape period |
| `anypoint_mq_queue_messages_acked` | Gauge | Messages acknowledged in the scrape period |

### Exchange Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_exchange_messages_published` | Gauge | Messages published to the exchange |
| `anypoint_mq_exchange_messages_delivered` | Gauge | Messages delivered from the exchange |

### Exporter Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `anypoint_mq_last_scrape_timestamp_seconds` | Gauge | Unix timestamp of the last successful scrape |

All queue metrics carry labels: `environment`, `queue_name`, `region`.  
All exchange metrics carry labels: `environment`, `exchange_name`, `region`.

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
| `anypoint.scrape.intervalSeconds` | `60` | How often to scrape metrics (seconds) |
| `anypoint.scrape.periodSeconds` | `600` | Stats API lookback window (seconds) |
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
| `ANYPOINT_LICENSE_KEY` | — | Pro license key for monitors module |
| `anypoint.monitors.evaluationIntervalSeconds` | `60` | Monitor evaluation frequency |
| `anypoint.monitors.defaults.cooldownMinutes` | `15` | Alert cooldown to prevent notification storms |

### Notification Channels

| Variable | Default | Description |
|----------|---------|-------------|
| `SLACK_ENABLED` | `false` | Enable Slack notifications |
| `SLACK_WEBHOOK_URL` | — | Slack incoming webhook URL |
| `PAGERDUTY_ENABLED` | `false` | Enable PagerDuty notifications |
| `PAGERDUTY_ROUTING_KEY` | — | PagerDuty Events API routing key |
| `EMAIL_ENABLED` | `false` | Enable email notifications |
| `ALERT_EMAIL_TO` | — | Recipient email address |
| `TEAMS_ENABLED` | `false` | Enable Microsoft Teams notifications |
| `TEAMS_WEBHOOK_URL` | — | Teams incoming webhook URL |
| `WEBHOOK_ENABLED` | `false` | Enable generic webhook notifications |
| `WEBHOOK_URL` | — | Webhook endpoint URL |
| `WEBHOOK_TOKEN` | — | Bearer token for webhook auth |

## Screenshots

> 📸 *Coming soon — Grafana dashboard screenshots will be added here.*

<!--
![Dashboard Overview](docs/screenshots/dashboard-overview.png)
![Queue Depth](docs/screenshots/queue-depth.png)
![Throughput](docs/screenshots/throughput.png)
-->

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

Already using Datadog? No problem. The exporter works with Datadog's built-in OpenMetrics check — zero additional code required.

### Option A: Datadog Agent + OpenMetrics (Recommended)

If the Datadog Agent runs alongside the exporter (same host, Kubernetes, or Docker network):

1. **Deploy the exporter** (Docker, Railway, or standalone JAR)

2. **Configure the Datadog Agent** OpenMetrics check:

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
      - anypoint_mq_monitor_health_score
    tags:
      - service:anypoint-mq
      - env:production
```

3. **Restart the Datadog Agent:**

```bash
sudo systemctl restart datadog-agent
# or on Docker:
docker restart dd-agent
```

4. **Verify** in Datadog → Metrics Explorer → search `anypoint_mq`

All metrics appear with their full label set (`org_name`, `env_name`, `queue_name`, `region`) so you can filter, group, and alert on any dimension.

### Option B: Datadog Agent on Kubernetes (Helm)

Add annotations to the exporter pod:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: anypoint-mq-exporter
spec:
  template:
    metadata:
      annotations:
        ad.datadoghq.com/exporter.checks: |
          {
            "openmetrics": {
              "instances": [{
                "openmetrics_endpoint": "http://%%host%%:9101/actuator/prometheus",
                "namespace": "anypoint_mq",
                "metrics": ["anypoint_mq_.*"]
              }]
            }
          }
    spec:
      containers:
        - name: exporter
          image: your-registry/anypoint-mq-prometheus-exporter:latest
          ports:
            - containerPort: 9101
```

The Datadog Agent auto-discovers the pod and starts collecting metrics.

### Pre-built Datadog Dashboard

Import our dashboard JSON into Datadog:

1. Go to **Dashboards → New Dashboard → Import**
2. Paste the contents of [`datadog/dashboard.json`](datadog/dashboard.json)

The dashboard includes:
- **Queue Overview** — messages in queue, in-flight, throughput per queue
- **Exchange Overview** — publish/deliver rates per exchange
- **Health Scores** — monitor health across all queues (Pro)
- **Inventory** — all discovered orgs, environments, queues, and exchanges

### Datadog Monitors (Alerts)

Example monitor definitions you can import:

```json
{
  "name": "Anypoint MQ - Queue Depth Critical",
  "type": "metric alert",
  "query": "avg(last_5m):avg:anypoint_mq.anypoint_mq_queue_messages_in_queue{*} by {queue_name} > 10000",
  "message": "Queue {{queue_name.name}} has {{value}} messages backed up.\n\nCheck consumer health and processing rates.\n\n@slack-mulesoft-alerts",
  "tags": ["service:anypoint-mq", "team:integration"],
  "options": {
    "thresholds": { "critical": 10000, "warning": 5000 },
    "notify_no_data": false,
    "renotify_interval": 30
  }
}
```

```json
{
  "name": "Anypoint MQ - DLQ Growing",
  "type": "metric alert",
  "query": "avg(last_10m):avg:anypoint_mq.anypoint_mq_queue_messages_in_queue{queue_name:*-dlq} by {queue_name} > 0",
  "message": "Dead letter queue {{queue_name.name}} has {{value}} messages.\n\nFailed messages need investigation.\n\n@pagerduty-mulesoft",
  "tags": ["service:anypoint-mq", "severity:high"],
  "options": {
    "thresholds": { "critical": 1, "warning": 0 },
    "notify_no_data": false
  }
}
```

```json
{
  "name": "Anypoint MQ - Throughput Drop",
  "type": "metric alert",
  "query": "pct_change(avg(last_1h),last_1d):avg:anypoint_mq.anypoint_mq_queue_messages_received{*} by {queue_name} < -50",
  "message": "Queue {{queue_name.name}} throughput dropped >50% vs yesterday.\n\nPossible producer or connectivity issue.\n\n@slack-mulesoft-alerts",
  "tags": ["service:anypoint-mq"],
  "options": {
    "thresholds": { "critical": -50, "warning": -30 }
  }
}
```

### New Relic & Dynatrace

Both support Prometheus remote write or OpenMetrics scraping:

- **New Relic**: Use the [Prometheus remote write integration](https://docs.newrelic.com/docs/infrastructure/prometheus-integrations/install-configure-remote-write/set-your-prometheus-remote-write-integration/) — add a `remote_write` block to your Prometheus config pointing to New Relic's endpoint.
- **Dynatrace**: Use the [OpenMetrics extension](https://www.dynatrace.com/hub/detail/prometheus/) or ActiveGate Prometheus integration to scrape the exporter directly.

No changes to the exporter needed — it's standard Prometheus metrics.

## Free vs Pro

| Feature | Free | Pro |
|---------|:----:|:---:|
| Queue & exchange metrics | ✅ | ✅ |
| Auto-discovery | ✅ | ✅ |
| Prometheus endpoint | ✅ | ✅ |
| Grafana dashboards | ✅ | ✅ |
| Health scores | — | ✅ |
| Queue depth monitors | — | ✅ |
| DLQ alerting | — | ✅ |
| Throughput anomaly detection | — | ✅ |
| Multi-channel notifications | — | ✅ |

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Built by <a href="https://netflexity.com">Netflexity</a> · Powered by <a href="https://spring.io/projects/spring-boot">Spring Boot</a> & <a href="https://micrometer.io/">Micrometer</a>
</p>
