# Railway Deployment Guide - Anypoint MQ Monitoring Stack

## Architecture

```
Anypoint MQ Stats API
        |
  [AMQ Exporter] :9101  ->  /actuator/prometheus
        |
  [Prometheus]   :9090  ->  scrapes exporter every 60s
        |
  [Grafana]      :3000  ->  queries Prometheus, shows dashboards
```

## Step 1: Exporter (already deployed)

URL: `https://nfx-anypoint-mq-exporter-production.up.railway.app`

## Step 2: Deploy Prometheus

1. Railway -> New Service -> **Docker Image**: `prom/prometheus:latest`
2. Add env var: `PORT=9090`
3. Set **Custom Start Command**:
   ```
   sh -c 'printf "global:\n  scrape_interval: 60s\nscrape_configs:\n  - job_name: amq\n    metrics_path: /actuator/prometheus\n    scheme: https\n    static_configs:\n      - targets: [\"nfx-anypoint-mq-exporter-production.up.railway.app\"]\n" > /etc/prometheus/prometheus.yml && exec /bin/prometheus --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.retention.time=30d --web.listen-address=:${PORT}'
   ```
4. Networking: set port to `9090`
5. Optionally add a **Volume** at `/prometheus` for data persistence

## Step 3: Deploy Grafana

1. Railway -> New Service -> Docker Image: `grafana/grafana:latest`
2. Add env vars:
   ```
   GF_SECURITY_ADMIN_USER=admin
   GF_SECURITY_ADMIN_PASSWORD=<your-password>
   GF_SERVER_HTTP_PORT=${PORT}
   ```
3. Add a **Volume**: mount path `/var/lib/grafana`
4. After deploy, open the Grafana URL
5. Add Data Source:
   - Type: Prometheus
   - URL: `https://<your-prometheus-railway-url>`
   - Save & Test
6. Import Dashboard:
   - Dashboards -> Import -> Upload JSON
   - Pick `grafana/dashboards/anypoint-mq-dashboard.json` from this repo
   - Select the Prometheus data source
   - Import

## Step 4: Verify

- Exporter: `/actuator/prometheus` shows metrics
- Exporter: `/api/status` shows discovered orgs/envs
- Prometheus: `/targets` shows exporter as UP
- Grafana: Dashboard shows queue data

## Local Development

```bash
# Set credentials
export ANYPOINT_CLIENT_ID=your-client-id
export ANYPOINT_CLIENT_SECRET=your-client-secret
export ANYPOINT_ORG_ID=your-org-id

# Start all 3 services
docker-compose up -d

# Access:
# Exporter: http://localhost:9101
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000 (admin / netflexity2026)
```

## Environment Variables

### Exporter
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| ANYPOINT_CLIENT_ID | Yes | - | Connected App client ID |
| ANYPOINT_CLIENT_SECRET | Yes | - | Connected App client secret |
| ANYPOINT_ORG_ID | No | auto | Organization ID (auto-discovered) |
| ANYPOINT_AUTO_DISCOVERY | No | true | Auto-discover orgs/environments |
| ANYPOINT_MONITORS_ENABLED | No | true | Enable health score monitors |
| SLACK_ENABLED | No | false | Enable Slack notifications |
| SLACK_WEBHOOK_URL | No | - | Slack incoming webhook URL |

### Grafana
| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| GF_SECURITY_ADMIN_USER | No | admin | Admin username |
| GF_SECURITY_ADMIN_PASSWORD | Yes | - | Admin password |
| GF_SERVER_HTTP_PORT | No | 3000 | HTTP port (Railway sets via $PORT) |
