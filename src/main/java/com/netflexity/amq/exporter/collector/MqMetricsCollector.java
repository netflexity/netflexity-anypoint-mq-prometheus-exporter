package com.netflexity.amq.exporter.collector;

import com.netflexity.amq.exporter.client.AnypointMqClient;
import com.netflexity.anypoint.common.config.AnypointConfig;
import com.netflexity.anypoint.common.config.ExporterConfig;
import com.netflexity.amq.exporter.model.*;
import com.netflexity.anypoint.common.model.Queue;
import com.netflexity.anypoint.common.model.QueueStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects metrics from Anypoint MQ and registers them with Micrometer.
 * 
 * This component runs scheduled scrapes of the Anypoint MQ Stats API
 * and maintains Prometheus metrics that are exposed via /actuator/prometheus.
 * 
 * Features:
 * - Scheduled metric collection
 * - Multi-environment and multi-region support
 * - Error resilience (failed scrapes don't stop the collector)
 * - Comprehensive queue and exchange metrics
 * - Dead letter queue detection and monitoring
 */
@Component
@Slf4j
public class MqMetricsCollector {

    private final AnypointMqClient mqClient;
    private final AnypointConfig anypointConfig;
    private final MeterRegistry meterRegistry;
    private final ExporterConfig.ExporterMetrics exporterMetrics;

    // Metrics storage - using ConcurrentHashMap for thread safety
    private final ConcurrentHashMap<String, AtomicLong> queueMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> exchangeMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueInfo> queueInfoMetrics = new ConcurrentHashMap<>();
    
    // Current queue stats for monitor integration
    private final ConcurrentHashMap<String, QueueStats> currentQueueStats = new ConcurrentHashMap<>();

    public MqMetricsCollector(AnypointMqClient mqClient, 
                              AnypointConfig anypointConfig, 
                              MeterRegistry meterRegistry,
                              ExporterConfig.ExporterMetrics exporterMetrics) {
        this.mqClient = mqClient;
        this.anypointConfig = anypointConfig;
        this.meterRegistry = meterRegistry;
        this.exporterMetrics = exporterMetrics;
        
        log.info("Initialized MqMetricsCollector with {} environments and {} regions",
                anypointConfig.getEnvironments().size(), anypointConfig.getRegions().size());
    }

    /**
     * Initialize metrics collection after application startup
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeMetrics() {
        if (!anypointConfig.getScrape().isEnabled()) {
            log.info("Metrics collection is disabled");
            return;
        }
        
        log.info("Starting initial metrics collection...");
        collectMetrics();
    }

    /**
     * Scheduled metrics collection
     * Uses fixedDelayString to read interval from configuration
     */
    @Scheduled(fixedDelayString = "${anypoint.scrape.intervalSeconds:60}000", initialDelayString = "${anypoint.scrape.intervalSeconds:60}000")
    public void scheduledCollection() {
        if (!anypointConfig.getScrape().isEnabled()) {
            return;
        }
        
        log.debug("Starting scheduled metrics collection");
        collectMetrics();
    }

    /**
     * Main metrics collection method
     */
    private void collectMetrics() {
        Timer.Sample sample = exporterMetrics.startScrapeTimer();
        
        try {
            // Process all environments and regions + org-level usage
            Mono<Void> envMetrics = Flux.fromIterable(anypointConfig.getEnvironments())
                    .flatMap(environment -> 
                            Flux.fromIterable(anypointConfig.getRegions())
                                    .flatMap(region -> collectEnvironmentRegionMetrics(environment, region))
                    )
                    .then();

            Mono<Void> orgUsage = collectOrgUsageMetrics();
            Mono<Void> auditMetrics = collectAuditLogMetrics();

            Mono.when(envMetrics, orgUsage, auditMetrics)
                    .doOnSuccess(v -> {
                        exporterMetrics.recordScrapeTime(sample);
                        log.info("Metrics collection completed successfully");
                    })
                    .doOnError(error -> {
                        exporterMetrics.incrementErrorCounter("collection_failed");
                        log.error("Metrics collection failed: {}", error.getMessage(), error);
                    })
                    .subscribe();
            
        } catch (Exception e) {
            exporterMetrics.incrementErrorCounter("unexpected_error");
            log.error("Unexpected error during metrics collection: {}", e.getMessage(), e);
        }
    }

    /**
     * Collect metrics for a specific environment and region
     */
    private Mono<Void> collectEnvironmentRegionMetrics(AnypointConfig.Environment environment, String region) {
        log.debug("Collecting metrics for environment {} ({}) in region {}", environment.getName(), environment.getId(), region);
        
        // Collect queue metrics, exchange metrics, and usage stats in parallel
        Mono<Void> queueMetrics = collectQueueMetrics(environment, region);
        Mono<Void> exchangeMetrics = collectExchangeMetrics(environment, region);
        Mono<Void> usageMetrics = collectUsageMetrics(environment);
        
        return Mono.when(queueMetrics, exchangeMetrics, usageMetrics)
                .doOnSuccess(v -> log.debug("Completed metrics collection for environment {} in region {}", environment.getName(), region))
                .doOnError(error -> {
                    exporterMetrics.incrementErrorCounter("environment_failed");
                    log.warn("Failed to collect metrics for environment {} in region {}: {}", environment.getName(), region, error.getMessage());
                })
                .onErrorResume(throwable -> Mono.empty()); // Continue with other environments even if one fails
    }

    /**
     * Collect queue metrics for an environment and region.
     * 1. List all queues (Admin API)
     * 2. Batch-fetch real-time depth for all queues (Stats API ?destinationIds=)
     * 3. Per-queue: get throughput stats and merge with real-time depth
     */
    private Mono<Void> collectQueueMetrics(AnypointConfig.Environment environment, String region) {
        return mqClient.listQueues(environment.getId(), region)
                .collectList()
                .flatMap(queues -> {
                    if (queues.isEmpty()) return Mono.empty();
                    
                    // Batch-fetch real-time depth for all queues in one call
                    java.util.List<String> queueIds = queues.stream()
                            .map(Queue::getQueueId)
                            .collect(java.util.stream.Collectors.toList());
                    
                    String orgName = environment.getOrgName() != null ? environment.getOrgName() : "default";
                    return mqClient.getBatchQueueDepth(environment.getId(), region, queueIds)
                            .flatMap(depthMap -> Flux.fromIterable(queues)
                                    .flatMap(queue -> collectSingleQueueMetrics(queue, environment.getName(), orgName, depthMap)
                                            .onErrorResume(error -> {
                                                exporterMetrics.incrementErrorCounter("queue_stats_failed");
                                                log.warn("Failed to collect stats for queue {}: {}", queue.getQueueName(), error.getMessage());
                                                return Mono.empty();
                                            }))
                                    .then());
                })
                .doOnError(error -> {
                    exporterMetrics.incrementErrorCounter("queue_list_failed");
                    log.warn("Failed to list queues for environment {} in region {}: {}", environment.getName(), region, error.getMessage());
                });
    }

    /**
     * Collect metrics for a single queue.
     * Merges real-time depth from batch call with throughput from per-queue Stats API.
     */
    private Mono<Void> collectSingleQueueMetrics(Queue queue, String environmentName, String orgName, java.util.Map<String, QueueStats> depthMap) {
        return mqClient.getQueueStats(queue.getEnvironment(), queue.getRegion(), queue.getQueueId(), anypointConfig.getScrape().getPeriodSeconds())
                .doOnNext(stats -> {
                    // Override with real-time depth from batch call
                    QueueStats depth = depthMap.get(queue.getQueueId());
                    if (depth != null) {
                        stats.setMessagesInQueue(depth.getMessagesInQueue());
                        stats.setMessagesInFlight(depth.getMessagesInFlight());
                    }
                    stats.setQueue(queue);
                    updateQueueMetrics(stats, environmentName, orgName);
                    updateQueueInfoMetrics(queue, environmentName, orgName);
                })
                .then();
    }

    /**
     * Collect exchange metrics for an environment and region
     */
    private Mono<Void> collectExchangeMetrics(AnypointConfig.Environment environment, String region) {
        String orgName = environment.getOrgName() != null ? environment.getOrgName() : "default";
        return mqClient.listExchanges(environment.getId(), region)
                .flatMap(exchange -> collectSingleExchangeMetrics(exchange, environment.getName(), orgName)
                        .onErrorResume(error -> {
                            exporterMetrics.incrementErrorCounter("exchange_stats_failed");
                            log.warn("Failed to collect stats for exchange {}: {}", exchange.getExchangeName(), error.getMessage());
                            return Mono.empty();
                        }))
                .then()
                .doOnError(error -> {
                    exporterMetrics.incrementErrorCounter("exchange_list_failed");
                    log.warn("Failed to list exchanges for environment {} in region {}: {}", environment.getName(), region, error.getMessage());
                });
    }

    /**
     * Collect metrics for a single exchange
     */
    private Mono<Void> collectSingleExchangeMetrics(Exchange exchange, String environmentName, String orgName) {
        return mqClient.getExchangeStats(exchange.getEnvironment(), exchange.getRegion(), exchange.getExchangeId(), anypointConfig.getScrape().getPeriodSeconds())
                .doOnNext(stats -> {
                    stats.setExchange(exchange);
                    updateExchangeMetrics(stats, environmentName, orgName);
                })
                .then();
    }

    /**
     * Collect MQ audit log metrics — counts of config changes (create/delete/update)
     * detected in the last scrape interval. Maps to MuleSoft audit-detect-mq-changes monitor.
     */
    private Mono<Void> collectAuditLogMetrics() {
        // Look back 2x the scrape interval to avoid missing events between scrapes
        int lookbackMinutes = Math.max((anypointConfig.getScrape().getIntervalSeconds() * 2) / 60, 5);

        return mqClient.queryMqAuditLogs(lookbackMinutes)
                .collectList()
                .doOnNext(entries -> {
                    String orgName = anypointConfig.getOrganizationId();
                    if (!anypointConfig.getEnvironments().isEmpty()) {
                        String envOrgName = anypointConfig.getEnvironments().get(0).getOrgName();
                        if (envOrgName != null) orgName = envOrgName;
                    }

                    // Count by action type
                    long creates = entries.stream().filter(e -> "Create".equalsIgnoreCase(e.getAction())).count();
                    long deletes = entries.stream().filter(e -> "Delete".equalsIgnoreCase(e.getAction())).count();
                    long updates = entries.stream().filter(e -> "Update".equalsIgnoreCase(e.getAction())).count();

                    updateGaugeMetric("anypoint_mq_audit_changes_total",
                            (long) entries.size(), "org", orgName);
                    updateGaugeMetric("anypoint_mq_audit_creates",
                            creates, "org", orgName);
                    updateGaugeMetric("anypoint_mq_audit_deletes",
                            deletes, "org", orgName);
                    updateGaugeMetric("anypoint_mq_audit_updates",
                            updates, "org", orgName);

                    if (!entries.isEmpty()) {
                        log.info("MQ audit log: {} changes detected (creates={}, deletes={}, updates={}) in last {}m",
                                entries.size(), creates, deletes, updates, lookbackMinutes);
                    } else {
                        log.debug("No MQ audit log changes in last {}m", lookbackMinutes);
                    }
                })
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to collect audit log metrics: {}", error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Collect org-level MQ API usage statistics (aggregate across all environments).
     */
    private Mono<Void> collectOrgUsageMetrics() {
        return mqClient.getOrgUsageStats(30)
                .doOnNext(stats -> {
                    String orgName = anypointConfig.getOrganizationId();
                    // Use first environment's orgName if available, else orgId
                    if (!anypointConfig.getEnvironments().isEmpty()) {
                        String envOrgName = anypointConfig.getEnvironments().get(0).getOrgName();
                        if (envOrgName != null) orgName = envOrgName;
                    }
                    updateGaugeMetric("anypoint_mq_org_usage_messages_sent_total",
                            stats.getMessagesSent(), "org", orgName);
                    updateGaugeMetric("anypoint_mq_org_usage_messages_received_total",
                            stats.getMessagesReceived(), "org", orgName);
                    updateGaugeMetric("anypoint_mq_org_usage_messages_acked_total",
                            stats.getMessagesAcked(), "org", orgName);
                    updateGaugeMetric("anypoint_mq_org_usage_billable_units_total",
                            stats.getBillableUnitCount(), "org", orgName);
                    updateGaugeMetric("anypoint_mq_org_usage_api_requests_total",
                            stats.getApiRequestCount(), "org", orgName);
                    log.info("Updated org-level usage metrics: {}", stats.toSafeString());
                })
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to collect org usage metrics: {}", error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Collect MQ API usage statistics for an environment.
     * Calls the Stats API usage endpoint (30-day lookback, period=1day).
     */
    private Mono<Void> collectUsageMetrics(AnypointConfig.Environment environment) {
        String orgName = environment.getOrgName() != null ? environment.getOrgName() : "default";
        return mqClient.getUsageStats(environment.getId(), 30)
                .doOnNext(stats -> {
                    updateGaugeMetric("anypoint_mq_usage_messages_sent_total",
                            stats.getMessagesSent(),
                            "org", orgName, "environment", environment.getName());
                    updateGaugeMetric("anypoint_mq_usage_messages_received_total",
                            stats.getMessagesReceived(),
                            "org", orgName, "environment", environment.getName());
                    updateGaugeMetric("anypoint_mq_usage_messages_acked_total",
                            stats.getMessagesAcked(),
                            "org", orgName, "environment", environment.getName());
                    updateGaugeMetric("anypoint_mq_usage_billable_units_total",
                            stats.getBillableUnitCount(),
                            "org", orgName, "environment", environment.getName());
                    updateGaugeMetric("anypoint_mq_usage_api_requests_total",
                            stats.getApiRequestCount(),
                            "org", orgName, "environment", environment.getName());
                    log.info("Updated usage metrics for environment {}: {}", environment.getName(), stats.toSafeString());
                })
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to collect usage metrics for environment {}: {}", environment.getName(), error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Update queue metrics in the registry
     */
    private void updateQueueMetrics(QueueStats stats, String environmentName, String orgName) {
        String queueName = stats.getQueue().getSanitizedQueueName();
        String region = stats.getQueue().getRegion();
        
        // Store current queue stats for monitor integration
        String statsKey = String.format("%s_%s_%s_%s", orgName, queueName, environmentName, region);
        currentQueueStats.put(statsKey, stats);
        
        // Register or update gauge metrics
        updateGaugeMetric("anypoint_mq_queue_messages_in_flight", 
                stats.getMessagesInFlight() != null ? stats.getMessagesInFlight() : 0L,
                "org", orgName, "queue_name", queueName, "environment", environmentName, "region", region);
                
        updateGaugeMetric("anypoint_mq_queue_messages_in_queue",
                stats.getMessagesInQueue() != null ? stats.getMessagesInQueue() : 0L,
                "org", orgName, "queue_name", queueName, "environment", environmentName, "region", region);
                
        updateGaugeMetric("anypoint_mq_queue_messages_sent_total",
                stats.getMessagesSent() != null ? stats.getMessagesSent() : 0L,
                "org", orgName, "queue_name", queueName, "environment", environmentName, "region", region);
                
        updateGaugeMetric("anypoint_mq_queue_messages_received_total",
                stats.getMessagesReceived() != null ? stats.getMessagesReceived() : 0L,
                "org", orgName, "queue_name", queueName, "environment", environmentName, "region", region);
                
        updateGaugeMetric("anypoint_mq_queue_messages_acked_total",
                stats.getMessagesAcked() != null ? stats.getMessagesAcked() : 0L,
                "org", orgName, "queue_name", queueName, "environment", environmentName, "region", region);

        if (stats.getQueueSize() != null) {
            updateGaugeMetric("anypoint_mq_queue_size_bytes",
                    stats.getQueueSize(),
                    "queue_name", queueName, "environment", environmentName, "region", region);
        }

        // Dequeue rate: (received / sent) * 100 — key QoS metric from MuleSoft monitors
        long sent = stats.getMessagesSent() != null ? stats.getMessagesSent() : 0L;
        long received = stats.getMessagesReceived() != null ? stats.getMessagesReceived() : 0L;
        long dequeueRate = sent > 0 ? Math.round(((double) received / sent) * 100.0) : 100L;
        updateGaugeMetric("anypoint_mq_queue_dequeue_rate_percent",
                dequeueRate,
                "org", orgName, "queue_name", queueName, "environment", environmentName, "region", region);
        
        log.debug("Updated metrics for queue {}: in_queue={}, in_flight={}, sent={}, received={}, acked={}",
                queueName, stats.getMessagesInQueue(), stats.getMessagesInFlight(), 
                stats.getMessagesSent(), stats.getMessagesReceived(), stats.getMessagesAcked());
    }

    /**
     * Update queue info metrics (metadata)
     */
    private void updateQueueInfoMetrics(Queue queue, String environmentName, String orgName) {
        String key = String.format("%s_%s_%s_%s", orgName, queue.getSanitizedQueueName(), environmentName, queue.getRegion());
        
        QueueInfo info = new QueueInfo();
        info.queueName = queue.getSanitizedQueueName();
        info.environment = environmentName;
        info.region = queue.getRegion();
        info.orgName = orgName;
        info.isDlq = queue.isDeadLetterQueue();
        info.isFifo = queue.getFifo() != null ? queue.getFifo() : false;
        info.maxDeliveries = queue.getMaxDeliveries() != null ? queue.getMaxDeliveries() : 0;
        info.ttl = queue.getDefaultTtl() != null ? queue.getDefaultTtl() : 0L;
        
        queueInfoMetrics.put(key, info);
        
        // Register gauge for queue info
        Gauge.builder("anypoint_mq_queue_info", info, queueInfo -> 1.0)
                .description("Queue metadata information")
                .tag("org", info.orgName)
                .tag("queue_name", info.queueName)
                .tag("environment", info.environment)
                .tag("region", info.region)
                .tag("is_dlq", String.valueOf(info.isDlq))
                .tag("is_fifo", String.valueOf(info.isFifo))
                .tag("max_deliveries", String.valueOf(info.maxDeliveries))
                .tag("ttl", String.valueOf(info.ttl))
                .register(meterRegistry);
    }

    /**
     * Update exchange metrics in the registry
     */
    private void updateExchangeMetrics(ExchangeStats stats, String environmentName, String orgName) {
        String exchangeName = stats.getExchange().getSanitizedExchangeName();
        String region = stats.getExchange().getRegion();
        
        updateGaugeMetric("anypoint_mq_exchange_messages_published_total",
                stats.getMessagesPublished() != null ? stats.getMessagesPublished() : 0L,
                "org", orgName, "exchange_name", exchangeName, "environment", environmentName, "region", region);
                
        updateGaugeMetric("anypoint_mq_exchange_messages_delivered_total",
                stats.getMessagesDelivered() != null ? stats.getMessagesDelivered() : 0L,
                "org", orgName, "exchange_name", exchangeName, "environment", environmentName, "region", region);
        
        log.debug("Updated metrics for exchange {}: published={}, delivered={}",
                exchangeName, stats.getMessagesPublished(), stats.getMessagesDelivered());
    }

    /**
     * Helper method to update gauge metrics
     */
    private void updateGaugeMetric(String metricName, long value, String... tags) {
        String key = metricName + "_" + String.join("_", tags);
        
        queueMetrics.computeIfAbsent(key, k -> {
            AtomicLong atomicValue = new AtomicLong(value);
            
            // Build gauge with tags
            Gauge.Builder<AtomicLong> builder = Gauge.builder(metricName, atomicValue, AtomicLong::get)
                    .description(getMetricDescription(metricName));
                    
            for (int i = 0; i < tags.length; i += 2) {
                if (i + 1 < tags.length) {
                    builder.tag(tags[i], tags[i + 1]);
                }
            }
            
            builder.register(meterRegistry);
            return atomicValue;
        }).set(value);
    }

    /**
     * Get metric description based on metric name
     */
    private String getMetricDescription(String metricName) {
        return switch (metricName) {
            case "anypoint_mq_queue_messages_in_flight" -> "Messages currently being processed";
            case "anypoint_mq_queue_messages_in_queue" -> "Messages waiting in queue";
            case "anypoint_mq_queue_messages_sent_total" -> "Total messages sent to queue";
            case "anypoint_mq_queue_messages_received_total" -> "Total messages received from queue";
            case "anypoint_mq_queue_messages_acked_total" -> "Total messages acknowledged";
            case "anypoint_mq_queue_size_bytes" -> "Queue size in bytes";
            case "anypoint_mq_exchange_messages_published_total" -> "Total messages published to exchange";
            case "anypoint_mq_exchange_messages_delivered_total" -> "Total messages delivered from exchange";
            case "anypoint_mq_queue_dequeue_rate_percent" -> "Queue dequeue rate (received/sent * 100)";
            case "anypoint_mq_usage_messages_sent_total" -> "Total MQ API messages sent (30-day usage)";
            case "anypoint_mq_usage_messages_received_total" -> "Total MQ API messages received (30-day usage)";
            case "anypoint_mq_usage_messages_acked_total" -> "Total MQ API messages acknowledged (30-day usage)";
            case "anypoint_mq_usage_billable_units_total" -> "Total MQ billable units (30-day usage)";
            case "anypoint_mq_usage_api_requests_total" -> "Total MQ API requests (30-day usage)";
            case "anypoint_mq_org_usage_messages_sent_total" -> "Org-level MQ messages sent (30-day)";
            case "anypoint_mq_org_usage_messages_received_total" -> "Org-level MQ messages received (30-day)";
            case "anypoint_mq_org_usage_messages_acked_total" -> "Org-level MQ messages acknowledged (30-day)";
            case "anypoint_mq_org_usage_billable_units_total" -> "Org-level MQ billable units (30-day)";
            case "anypoint_mq_org_usage_api_requests_total" -> "Org-level MQ API requests (30-day)";
            case "anypoint_mq_audit_changes_total" -> "Total MQ config changes detected in audit log";
            case "anypoint_mq_audit_creates" -> "MQ destinations created (from audit log)";
            case "anypoint_mq_audit_deletes" -> "MQ destinations deleted (from audit log)";
            case "anypoint_mq_audit_updates" -> "MQ destinations updated (from audit log)";
            default -> "Anypoint MQ metric";
        };
    }

    /**
     * Get current queue stats (for monitor integration)
     */
    public Map<String, QueueStats> getCurrentQueueStats() {
        return Map.copyOf(currentQueueStats);
    }

    /**
     * Helper class to store queue metadata
     */
    private static class QueueInfo {
        String orgName;
        String queueName;
        String environment;
        String region;
        boolean isDlq;
        boolean isFifo;
        int maxDeliveries;
        long ttl;
    }
}