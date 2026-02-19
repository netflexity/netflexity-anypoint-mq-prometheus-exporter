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

URL: `https://anypoint-mq-prometheus-exporter-production.up.railway.app`

## Step 2: Deploy Prometheus

1. Railway -> New Service -> Docker Image: `prom/prometheus:latest`
2. Add a **Volume**: mount path `/prometheus`
3. Set **Start Command**:
   ```
   --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.retention.time=30d --web.enable-lifecycle --web.listen-address=:${PORT}
   ```
4. You need to provide prometheus.yml. Easiest: create a small repo with just the config, or use Railway's config file feature.

### Prometheus Config (prometheus.yml)

```yaml
global:
  scrape_interval: 60s

scrape_configs:
  - job_name: 'anypoint-mq-exporter'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['anypoint-mq-prometheus-exporter-production.up.railway.app']
    scheme: https
```

### Alternative: Deploy Prometheus from this repo

Create a new Railway service pointing to this repo, with:
- **Dockerfile Path**: `prometheus/Dockerfile`
- Railway will build and deploy it

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
