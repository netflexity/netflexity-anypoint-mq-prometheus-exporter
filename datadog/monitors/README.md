# Datadog Monitors

Production-ready Datadog monitors for Anypoint MQ are available in the **Pro** edition.

## Included Monitors (Pro)

| Monitor | Severity | Description |
|---------|----------|-------------|
| DLQ Alert | P1 | Dead letter queue has messages |
| Queue Depth Critical | P2 | Queue depth exceeds threshold |
| High In-Flight | P3 | Too many unacknowledged messages |
| Stale Queue | P3 | No consumer activity detected |
| Stale Messages | P3 | Messages sitting too long |
| Throughput Drop | P3 | Significant decrease in message flow |
| Low Dequeue Rate | P3 | Consumer falling behind producer |
| Billable Unit Threshold | P3 | MQ usage approaching billing limits |
| Configuration Change | P4 | Queue/exchange config modified |
| Scrape Errors | P4 | Exporter failing to collect metrics |

## Import

Pro monitors ship as individual JSON files with a one-command import script:

```bash
DD_API_KEY=xxx DD_APP_KEY=yyy ./import-monitors.sh
```

Learn more at [netflexity.com](https://netflexity.com)
