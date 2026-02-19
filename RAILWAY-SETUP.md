# Anypoint MQ Prometheus Exporter - Complete Deployment Guide

## Overview

The Anypoint MQ Prometheus Exporter is a monitoring stack that collects metrics from MuleSoft's Anypoint MQ messaging service and exposes them for visualization and alerting. It consists of three services:

1. **Exporter** - Java/Spring Boot app that connects to Anypoint Platform APIs, collects queue/exchange metrics, and exposes them in Prometheus format
2. **Prometheus** - Time-series database that scrapes the exporter every 60 seconds and stores historical metrics
3. **Grafana** - Dashboard and visualization platform that queries Prometheus and displays real-time and historical charts

## Architecture

```
  Anypoint Platform APIs
  ├── /accounts/api/me                    (org discovery)
  ├── /accounts/api/organizations/{id}/environments  (env discovery)
  ├── /mq/admin/api/v1/.../destinations   (queue/exchange inventory)
  └── /mq/stats/api/v1/.../queues/{id}    (queue/exchange metrics)
          |
          v
  ┌─────────────────────────┐
  │   AMQ Exporter (Java)   │  Scrapes Anypoint MQ APIs every 60s
  │   /actuator/prometheus  │  Exposes metrics in Prometheus format
  │   /api/status           │  Shows discovered orgs/environments
  │   /api/discover         │  Triggers manual re-discovery
  │   /api/health-scores    │  Monitor health scores (when enabled)
  └────────────┬────────────┘
               │ scrape every 60s
               v
  ┌─────────────────────────┐
  │      Prometheus         │  Stores time-series data (30 day retention)
  │   /targets              │  Shows scrape targets and status
  │   /graph                │  Built-in query UI
  └────────────┬────────────┘
               │ PromQL queries
               v
  ┌─────────────────────────┐
  │        Grafana          │  Dashboards, alerts, visualizations
  │   /login                │  Web UI (admin/password)
  │   /d/anypoint-mq-monitoring  │  Main dashboard
  └─────────────────────────┘
```

## How It Works

### Auto-Discovery
On startup, the exporter automatically discovers all organizations and environments:
1. Authenticates via Connected App OAuth2 (`/accounts/api/v2/oauth2/token`)
2. Calls `/accounts/api/me` to get the root org and all member orgs (with names)
3. For each org, calls `/accounts/api/organizations/{orgId}/environments` to list environments
4. Populates the config with discovered environments
5. Refreshes every 5 minutes to pick up new environments

### Metrics Collection
Every 60 seconds (configurable), the exporter:
1. For each org + environment + region combination:
   - Lists all queues via MQ Admin API (`/mq/admin/api/v1/.../destinations`)
   - Lists all exchanges (filters client-side since API ignores `?type=` param)
   - Fetches stats for each queue (messages in queue, in flight, sent, received, acked)
   - Fetches stats for each exchange (messages published, delivered)
2. Updates Prometheus gauges with the latest values
3. Records scrape timestamp

### Metrics Exposed
All metrics are at `/actuator/prometheus`:

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `anypoint_mq_queue` | Gauge | environment, queue_name, region, is_fifo, is_dlq, max_deliveries, ttl | Queue metadata (value=1) |
| `anypoint_mq_queue_messages_in_queue` | Gauge | environment, queue_name, region | Messages waiting to be consumed |
| `anypoint_mq_queue_messages_in_flight` | Gauge | environment, queue_name, region | Messages currently being processed |
| `anypoint_mq_queue_messages_sent` | Gauge | environment, queue_name, region | Messages sent (in scrape period) |
| `anypoint_mq_queue_messages_received` | Gauge | environment, queue_name, region | Messages received (in scrape period) |
| `anypoint_mq_queue_messages_acked` | Gauge | environment, queue_name, region | Messages acknowledged (in scrape period) |
| `anypoint_mq_exchange_messages_published` | Gauge | environment, exchange_name, region | Messages published to exchange |
| `anypoint_mq_exchange_messages_delivered` | Gauge | environment, exchange_name, region | Messages delivered from exchange |
| `anypoint_mq_last_scrape_timestamp_seconds` | Gauge | - | Unix timestamp of last successful scrape |

### Advanced Monitors (Optional)
When enabled (`ANYPOINT_MONITORS_ENABLED=true`), the exporter also runs:
- **Queue depth monitors** - Alert when queue depth exceeds threshold
- **DLQ monitors** - Alert on dead letter queue activity
- **Throughput monitors** - Detect drops or spikes in message throughput
- **Health scores** - 0-100 health score per queue, available at `/api/health-scores`
- **Notifications** - Slack, PagerDuty, Email, Microsoft Teams, Webhooks

---

## Railway Deployment

### Live URLs

| Service | URL |
|---------|-----|
| Exporter | `https://nfx-anypoint-mq-exporter-production.up.railway.app` |
| Prometheus | `https://nfx-anypoint-mq-prometheus-production.up.railway.app` |
| Grafana | `https://nfx-grafana-production.up.railway.app` |

### Service 1: Exporter

**Type:** Deploy from repo (Bitbucket `netflexity/anypoint-mq-prometheus-exporter` or GitHub `netflexity/anypoint-mq-prometheus-exporter`)

**Builder:** Dockerfile (uses root `Dockerfile` - multi-stage Maven build -> JRE runtime)

**Environment Variables:**

| Variable | Value | Required | Description |
|----------|-------|----------|-------------|
| `ANYPOINT_CLIENT_ID` | `f9ae81ed...` | Yes | Connected App client ID (Platform OAuth, NOT MQ broker creds) |
| `ANYPOINT_CLIENT_SECRET` | `(secret)` | Yes | Connected App client secret |
| `ANYPOINT_AUTO_DISCOVERY` | `true` | No (default: true) | Auto-discover all orgs and environments |
| `ANYPOINT_ORG_ID` | `def13399-...` | No | Root org ID (auto-discovered if omitted) |
| `ANYPOINT_MONITORS_ENABLED` | `true` | No (default: true) | Enable health score monitors |
| `ANYPOINT_REGIONS` | `us-east-1` | No (default: us-east-1) | Comma-separated MQ regions |
| `PORT` | (Railway auto-injects) | - | HTTP port for Spring Boot |

**Networking:** Railway auto-assigns port. Generate public domain.

**Key Endpoints:**
- `/actuator/prometheus` - Prometheus metrics
- `/actuator/health` - Health check
- `/api/status` - Discovered orgs, environments, scrape config
- `POST /api/discover` - Trigger manual re-discovery
- `/api/health-scores` - Queue health scores (requires monitors enabled)
- `/api/monitors` - Monitor configuration (requires monitors enabled)

**Important Notes:**
- Uses Connected App credentials (Platform OAuth), NOT MQ broker credentials
- Auth flow: `POST /accounts/api/v2/oauth2/token` with client_credentials grant
- Stats API returns arrays (time-series), not single values - the exporter extracts the last element
- Stats API date format requires millisecond precision (`.000Z`)
- MQ Admin API `?type=` filter doesn't work - exporter filters client-side
- Queue/exchange names fall back to `queueId`/`exchangeId` when name field is null

### Service 2: Prometheus

**Type:** Docker Image: `prom/prometheus:latest`

**Custom Start Command:**
```
sh -c 'printf "global:\n  scrape_interval: 60s\nscrape_configs:\n  - job_name: amq\n    metrics_path: /actuator/prometheus\n    scheme: https\n    static_configs:\n      - targets: [\"nfx-anypoint-mq-exporter-production.up.railway.app\"]\n" > /etc/prometheus/prometheus.yml && exec /bin/prometheus --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.retention.time=30d --web.listen-address=:${PORT}'
```

**Environment Variables:**

| Variable | Value | Required | Description |
|----------|-------|----------|-------------|
| `PORT` | `9090` | Yes | HTTP port |

**Networking:** Port `9090`. Generate public domain.

**Volume (optional):** Mount at `/prometheus` for data persistence across redeploys.

**Key Endpoints:**
- `/targets` - Shows scrape targets and their status (UP/DOWN)
- `/graph` - Built-in query explorer
- `/api/v1/query?query=...` - PromQL query API
- `/api/v1/targets` - Target status as JSON

**Why not deploy from repo:** Railway ignores `Root Directory` setting for monorepos and always picks the root `Dockerfile` (Java exporter). The inline start command approach is simpler and more reliable.

### Service 3: Grafana

**Type:** Docker Image: `grafana/grafana:latest`

**Environment Variables:**

| Variable | Value | Required | Description |
|----------|-------|----------|-------------|
| `GF_SECURITY_ADMIN_USER` | `admin` | No (default: admin) | Admin username |
| `GF_SECURITY_ADMIN_PASSWORD` | `(your password)` | Yes | Admin password |
| `GF_SERVER_HTTP_PORT` | `8080` | Yes | HTTP port (must match networking config) |
| `GF_SERVER_ROOT_URL` | `https://nfx-grafana-production.up.railway.app/` | If needed | Required if Grafana shows "failed to load application files" |

**Networking:** Port `8080` (must match `GF_SERVER_HTTP_PORT`). Generate public domain.

**Volume (recommended):** Mount at `/var/lib/grafana` to persist dashboards and settings across redeploys.

**Setup after deploy:**
1. Login at `https://nfx-grafana-production.up.railway.app` with admin credentials
2. Connections -> Data Sources -> Add -> Prometheus
   - Name: `nfx-prometheus`
   - URL: `https://nfx-anypoint-mq-prometheus-production.up.railway.app`
   - Save & Test (should say "Successfully queried the Prometheus API")
3. Dashboards -> Import -> Upload JSON -> select `grafana/dashboards/anypoint-mq-dashboard.json`
4. Select the `nfx-prometheus` data source -> Import
5. Verify: environment and queue dropdowns should populate, panels should show data

**Grafana Dashboard Panels:**

| Section | Panels | Description |
|---------|--------|-------------|
| Overview | 4 stat panels | Total queues, messages in queue, messages in flight, total exchanges |
| Queue Depth | 2 time series | Messages in queue + in flight over time, per queue/environment |
| Throughput | 3 bar charts | Messages sent, received, acknowledged per scrape period |
| Exchanges | 2 time series | Messages published + delivered per exchange |
| Queue Inventory | 1 table | All queues with FIFO/DLQ flags, TTL, max deliveries |
| Exporter Health | 1 stat | Last scrape timestamp |

**Dashboard Variables:**
- `environment` - Multi-select dropdown, auto-populated from metric labels
- `queue` - Multi-select dropdown, filtered by selected environment

---

## Anypoint Platform Setup

### Connected App (Required)

The exporter authenticates via a Connected App with client_credentials grant.

1. Anypoint Platform -> Access Management -> Connected Apps -> Create
2. Type: **App acts on its own behalf (client credentials)**
3. Grant scopes:
   - `View Environment` (for auto-discovery)
   - `View Organization` (for auto-discovery)
   - `Anypoint MQ Admin` (to list queues/exchanges)
   - `Anypoint MQ Stats` (to read metrics)
4. Copy `client_id` and `client_secret` into Railway env vars

**Important:** These are Platform OAuth credentials, NOT MQ broker credentials. MQ broker creds (the ones you get from MQ -> Client Apps) use a different auth flow and won't work with the exporter.

---

## Local Development

```bash
# Set credentials
export ANYPOINT_CLIENT_ID=your-connected-app-client-id
export ANYPOINT_CLIENT_SECRET=your-connected-app-client-secret

# Option 1: Docker Compose (all 3 services)
docker-compose up -d
# Exporter: http://localhost:9101
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000 (admin / netflexity2026)

# Option 2: Just the exporter (Maven)
mvn spring-boot:run
# http://localhost:9101/actuator/prometheus
# http://localhost:9101/api/status
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Exporter: `JavaMailSender` bean not found | Monitors enabled but no SMTP configured | Already fixed: `@Autowired(required=false)`. Email channel skipped gracefully |
| Exporter: Queue names show as "unknown" | MQ Admin API returns `queueName: null` | Already fixed: falls back to `queueId` |
| Exporter: Stats return 0 for all metrics | No traffic in the environment | Normal - metrics reflect actual MQ activity |
| Exporter: `NotificationChannelConfiguration` error | `JavaMailSender` required but no SMTP | Already fixed: made optional |
| Prometheus: YAML parse error | Indentation issues in start command | Use the `printf` one-liner from this doc |
| Prometheus: Target shows DOWN | Wrong exporter hostname | Update start command with correct URL |
| Grafana: "Failed to load application files" | Missing `GF_SERVER_ROOT_URL` | Set to full public URL |
| Grafana: Panels show "No data" | Datasource not configured on panels | Edit dashboard JSON: replace datasource UID with `nfx-prometheus` |
| Grafana: Variables empty | Variables pointing to wrong datasource | Dashboard Settings -> Variables -> set datasource to `nfx-prometheus` |
| Railway: Build uses wrong Dockerfile | Railway ignores `Root Directory` for monorepos | Use Docker Image for Prometheus/Grafana instead of repo deploy |
| Stats API: array vs scalar | API returns time-series arrays | Already fixed: `@JsonSetter` extracts last value |
| Admin API: `?type=` filter ignored | Known API bug | Already fixed: filter client-side by `type` field |

---

## Cost Estimate (Railway)

| Service | Plan | Est. Monthly |
|---------|------|-------------|
| Exporter | Starter | ~$5 (low CPU, ~256MB RAM) |
| Prometheus | Starter | ~$5 + storage |
| Grafana | Starter | ~$5 |
| **Total** | | **~$15/month** |

Add a Volume for Prometheus ($0.25/GB/month) to persist data across redeploys.
