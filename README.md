# Anypoint MQ Prometheus Exporter

A production-ready Prometheus exporter for MuleSoft Anypoint MQ that collects queue and exchange metrics from the Anypoint MQ Stats API and exposes them in Prometheus format.

## Features

- **Comprehensive Metrics**: Queue depth, message rates, DLQ monitoring, and exchange statistics
- **Multi-Environment**: Monitor multiple Anypoint environments and regions simultaneously
- **Authentication**: Supports both username/password and Connected App authentication
- **Production Ready**: Health checks, error handling, retry logic, and comprehensive logging
- **Pre-built Dashboard**: Grafana dashboard with queue monitoring, alerting, and performance metrics
- **Docker Support**: Ready-to-run Docker containers with docker-compose setup
- **Monitoring Stack**: Includes Prometheus and Grafana for complete monitoring solution

## Quick Start

### Using Docker Compose (Recommended)

1. **Clone the repository**:
   ```bash
   git clone https://github.com/netflexity/anypoint-mq-prometheus-exporter.git
   cd anypoint-mq-prometheus-exporter
   ```

2. **Configure environment variables**:
   ```bash
   cp .env.example .env
   # Edit .env with your Anypoint Platform credentials
   ```

3. **Start the stack**:
   ```bash
   docker-compose up -d
   ```

4. **Access the services**:
   - **Grafana**: http://localhost:3000 (admin/admin)
   - **Prometheus**: http://localhost:9090
   - **Exporter Metrics**: http://localhost:9101/actuator/prometheus
   - **Health Check**: http://localhost:9101/actuator/health

### Manual Installation

1. **Requirements**:
   - Java 17+
   - Maven 3.6+

2. **Build and run**:
   ```bash
   mvn clean package
   java -jar target/anypoint-mq-prometheus-exporter-1.0.0.jar
   ```

## Configuration

### Environment Variables

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `ANYPOINT_ORG_ID` | Anypoint Organization ID | Yes | - |
| `ANYPOINT_ENV_ID` | Environment ID to monitor | Yes | - |
| `ANYPOINT_ENV_NAME` | Environment name for labeling | No | `Production` |
| `ANYPOINT_CLIENT_ID` | Connected App Client ID | Yes* | - |
| `ANYPOINT_CLIENT_SECRET` | Connected App Client Secret | Yes* | - |
| `ANYPOINT_USERNAME` | Username for login | Yes* | - |
| `ANYPOINT_PASSWORD` | Password for login | Yes* | - |

*Either Connected App credentials OR username/password required.

### Application Configuration

Create `application.yml` or use environment variables:

```yaml
anypoint:
  baseUrl: https://anypoint.mulesoft.com
  organizationId: ${ANYPOINT_ORG_ID}
  auth:
    # Option 1: Connected App (Recommended)
    clientId: ${ANYPOINT_CLIENT_ID}
    clientSecret: ${ANYPOINT_CLIENT_SECRET}
    # Option 2: Username/Password
    username: ${ANYPOINT_USERNAME}
    password: ${ANYPOINT_PASSWORD}
  environments:
    - id: ${ANYPOINT_ENV_ID}
      name: ${ANYPOINT_ENV_NAME:Production}
  regions:
    - us-east-1
    - eu-west-1
  scrape:
    intervalSeconds: 60
    periodSeconds: 600
```

## Setting Up Anypoint Connected App

### Create Connected App (Recommended)

1. **Access Anypoint Platform**:
   - Go to https://anypoint.mulesoft.com
   - Navigate to **Access Management** > **Connected Apps**

2. **Create New Connected App**:
   - Click **Create App**
   - **Name**: `Prometheus Exporter`
   - **Grant Types**: Select **Client Credentials**
   - **Scopes**: 
     - `read:stats` - Read MQ statistics
     - `read:destinations` - List queues and exchanges

3. **Get Credentials**:
   - Note the **Client ID** and **Client Secret**
   - Use these in your configuration

4. **Set Environment Variables**:
   ```bash
   export ANYPOINT_CLIENT_ID="your-client-id"
   export ANYPOINT_CLIENT_SECRET="your-client-secret"
   export ANYPOINT_ORG_ID="your-org-id"
   export ANYPOINT_ENV_ID="your-env-id"
   ```

## Metrics Exposed

### Queue Metrics

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `anypoint_mq_queue_messages_in_flight` | Gauge | Messages being processed | queue_name, environment, region |
| `anypoint_mq_queue_messages_in_queue` | Gauge | Messages waiting in queue | queue_name, environment, region |
| `anypoint_mq_queue_messages_sent_total` | Counter | Total messages sent | queue_name, environment, region |
| `anypoint_mq_queue_messages_received_total` | Counter | Total messages received | queue_name, environment, region |
| `anypoint_mq_queue_messages_acked_total` | Counter | Total messages acknowledged | queue_name, environment, region |
| `anypoint_mq_queue_size_bytes` | Gauge | Queue size in bytes | queue_name, environment, region |

### Exchange Metrics

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `anypoint_mq_exchange_messages_published_total` | Counter | Messages published to exchange | exchange_name, environment, region |
| `anypoint_mq_exchange_messages_delivered_total` | Counter | Messages delivered from exchange | exchange_name, environment, region |

### Metadata Metrics

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `anypoint_mq_queue_info` | Gauge | Queue metadata (value=1) | queue_name, environment, region, is_dlq, is_fifo, max_deliveries, ttl |
| `anypoint_mq_scrape_duration_seconds` | Gauge | Time taken to scrape metrics | - |
| `anypoint_mq_scrape_errors_total` | Counter | Total scrape errors | cause |
| `anypoint_mq_last_scrape_timestamp_seconds` | Gauge | Unix timestamp of last scrape | - |

## Grafana Dashboard

The included Grafana dashboard provides:

- **Queue Depth Monitoring**: Real-time queue and in-flight message counts
- **Message Throughput**: Send/receive rates over time
- **Dead Letter Queue Alerts**: Visual alerts for DLQ messages
- **Top Queues by Depth**: Identify problematic queues
- **Environment Filtering**: Multi-environment support with dropdown
- **Performance Metrics**: Scrape duration and error tracking

### Dashboard Features

- **Auto-refresh**: Updates every 30 seconds
- **Time Range Selector**: Last 1 hour by default
- **Environment Variables**: Filter by environment and region
- **Alert Thresholds**: Configurable warning levels
- **Responsive Design**: Works on desktop and mobile

## Deployment

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: anypoint-mq-exporter
spec:
  replicas: 1
  selector:
    matchLabels:
      app: anypoint-mq-exporter
  template:
    metadata:
      labels:
        app: anypoint-mq-exporter
    spec:
      containers:
      - name: exporter
        image: netflexity/anypoint-mq-prometheus-exporter:1.0.0
        ports:
        - containerPort: 9101
        env:
        - name: ANYPOINT_ORG_ID
          valueFrom:
            secretKeyRef:
              name: anypoint-secrets
              key: org-id
        - name: ANYPOINT_CLIENT_ID
          valueFrom:
            secretKeyRef:
              name: anypoint-secrets
              key: client-id
        - name: ANYPOINT_CLIENT_SECRET
          valueFrom:
            secretKeyRef:
              name: anypoint-secrets
              key: client-secret
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 9101
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 9101
          initialDelaySeconds: 10
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: anypoint-mq-exporter
spec:
  selector:
    app: anypoint-mq-exporter
  ports:
  - port: 9101
    targetPort: 9101
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: anypoint-mq-exporter
spec:
  selector:
    matchLabels:
      app: anypoint-mq-exporter
  endpoints:
  - port: http
    path: /actuator/prometheus
```

### Prometheus Configuration

```yaml
scrape_configs:
  - job_name: 'anypoint-mq-exporter'
    static_configs:
      - targets: ['anypoint-mq-exporter:9101']
    scrape_interval: 60s
    metrics_path: /actuator/prometheus
```

## Integration with Other Tools

### Datadog

Add Prometheus integration to scrape the exporter:

```yaml
# datadog-agent/conf.d/prometheus.yaml
instances:
  - prometheus_url: http://anypoint-mq-exporter:9101/actuator/prometheus
    namespace: anypoint_mq
    metrics:
      - anypoint_mq_queue_*
      - anypoint_mq_exchange_*
```

### New Relic

Use the Prometheus OpenMetrics integration:

```yaml
# newrelic-prometheus-configurator.yml
transformations:
  - description: "Anypoint MQ metrics"
    rename_attributes:
      queue_name: "queue"
      environment: "env"
```

### Dynatrace

Configure a custom device for the exporter endpoint:

1. Go to **Settings** > **Monitoring** > **Monitored technologies**
2. Add custom device: `anypoint-mq-exporter:9101`
3. Set metrics path: `/actuator/prometheus`

## Troubleshooting

### Common Issues

1. **Authentication Failed**
   ```
   ERROR: Authentication failed with status 401
   ```
   - Verify your Connected App credentials
   - Check organization ID and environment ID
   - Ensure the Connected App has required permissions

2. **No Metrics Appearing**
   ```
   INFO: Completed metrics collection successfully
   ```
   - Check if queues exist in the specified environment/region
   - Verify network connectivity to Anypoint Platform
   - Review logs for specific error messages

3. **High Memory Usage**
   ```
   OutOfMemoryError
   ```
   - Reduce scrape frequency (`anypoint.scrape.intervalSeconds`)
   - Limit environments and regions being monitored
   - Increase container memory limits

### Debug Logging

Enable debug logging in `application.yml`:

```yaml
logging:
  level:
    com.netflexity.amq.exporter: DEBUG
    org.springframework.web.reactive: DEBUG
```

### Health Check Endpoints

- **Health**: `GET /actuator/health`
- **Metrics**: `GET /actuator/prometheus`
- **Info**: `GET /actuator/info`

## Development

### Building from Source

```bash
# Clone repository
git clone https://github.com/netflexity/anypoint-mq-prometheus-exporter.git
cd anypoint-mq-prometheus-exporter

# Build with Maven
mvn clean package

# Run tests
mvn test

# Build Docker image
docker build -t anypoint-mq-exporter:dev .
```

### Running Tests

```bash
# Unit tests
mvn test

# Integration tests
mvn verify
```

### Code Structure

```
src/main/java/com/netflexity/amq/exporter/
├── Application.java                 # Main application class
├── config/
│   ├── AnypointConfig.java         # Configuration properties
│   └── ExporterConfig.java         # Metrics and WebClient config
├── client/
│   ├── AnypointAuthClient.java     # Authentication handling
│   └── AnypointMqClient.java       # MQ API client
├── collector/
│   └── MqMetricsCollector.java     # Scheduled metrics collection
├── health/
│   └── AnypointHealthIndicator.java # Health check implementation
└── model/
    ├── AuthToken.java              # Authentication token model
    ├── Queue.java                  # Queue model
    ├── QueueStats.java             # Queue statistics model
    ├── Exchange.java               # Exchange model
    └── ExchangeStats.java          # Exchange statistics model
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Run tests and ensure they pass
6. Submit a pull request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

- **Issues**: [GitHub Issues](https://github.com/netflexity/anypoint-mq-prometheus-exporter/issues)
- **Documentation**: [Wiki](https://github.com/netflexity/anypoint-mq-prometheus-exporter/wiki)
- **Discussions**: [GitHub Discussions](https://github.com/netflexity/anypoint-mq-prometheus-exporter/discussions)

## Changelog

### Version 1.0.0
- Initial release
- Queue and exchange metrics collection
- Multi-environment support
- Authentication with Connected Apps
- Docker and Kubernetes deployment support
- Pre-built Grafana dashboard
- Comprehensive health checks and error handling