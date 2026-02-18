package com.netflexity.amq.exporter.monitor;

import com.netflexity.amq.exporter.collector.MqMetricsCollector;
import com.netflexity.amq.exporter.config.AnypointConfig;
import com.netflexity.amq.exporter.config.MonitorConfig;
import com.netflexity.amq.exporter.license.LicenseService;
import com.netflexity.amq.exporter.model.QueueStats;
import com.netflexity.amq.exporter.notification.NotificationDispatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled monitor runner.
 * 
 * Runs monitor evaluations on a scheduled basis, dispatches notifications
 * for triggered monitors, and maintains Prometheus metrics for monitoring health.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(prefix = "anypoint.monitors", name = "enabled", havingValue = "true")
@Slf4j
public class MonitorScheduler {

    private final MonitorConfig monitorConfig;
    private final MonitorEvaluator monitorEvaluator;
    private final NotificationDispatcher notificationDispatcher;
    private final LicenseService licenseService;
    private final MeterRegistry meterRegistry;
    private final MqMetricsCollector metricsCollector;

    // Metrics storage for Prometheus
    private final Map<String, AtomicLong> monitorMetrics = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> healthScoreMetrics = new ConcurrentHashMap<>();
    private final Counter monitorEvaluationsCounter;
    private final Counter monitorNotificationsCounter;

    public MonitorScheduler(MonitorConfig monitorConfig,
                            MonitorEvaluator monitorEvaluator,
                            NotificationDispatcher notificationDispatcher,
                            LicenseService licenseService,
                            MeterRegistry meterRegistry,
                            MqMetricsCollector metricsCollector) {
        this.monitorConfig = monitorConfig;
        this.monitorEvaluator = monitorEvaluator;
        this.notificationDispatcher = notificationDispatcher;
        this.licenseService = licenseService;
        this.meterRegistry = meterRegistry;
        this.metricsCollector = metricsCollector;

        // Initialize counters
        this.monitorEvaluationsCounter = Counter.builder("anypoint_mq_monitor_evaluations_total")
                .description("Total number of monitor evaluations")
                .register(meterRegistry);

        this.monitorNotificationsCounter = Counter.builder("anypoint_mq_monitor_notifications_total")
                .description("Total number of monitor notifications sent")
                .register(meterRegistry);

        log.info("MonitorScheduler initialized with {} monitor definitions",
                monitorConfig.getEnabledDefinitions().size());
    }

    /**
     * Scheduled monitor evaluation
     */
    @Scheduled(fixedDelayString = "${anypoint.monitors.evaluationIntervalSeconds:60}000")
    public void runMonitorEvaluations() {
        if (!monitorConfig.isEnabled()) {
            log.debug("Monitors are disabled, skipping evaluation");
            return;
        }

        if (!licenseService.hasValidLicense()) {
            log.debug("No valid license for monitors feature, skipping evaluation");
            return;
        }

        try {
            List<MonitorDefinition> enabledMonitors = monitorConfig.getEnabledDefinitions();
            log.debug("Starting monitor evaluations for {} monitors", enabledMonitors.size());

            int triggeredCount = 0;
            int notificationsSent = 0;

            for (MonitorDefinition monitor : enabledMonitors) {
                // Evaluate monitor against all matching queues
                List<MonitorResult> results = evaluateMonitor(monitor);
                
                for (MonitorResult result : results) {
                    // Update Prometheus metrics
                    updateMonitorMetrics(result);
                    
                    if (result.isTriggered()) {
                        triggeredCount++;
                        
                        // Check cooldown and dispatch notification
                        if (shouldSendNotification(monitor, result)) {
                            notificationDispatcher.dispatch(result);
                            notificationsSent++;
                            
                            // Mark as notified
                            MonitorState state = monitorEvaluator.getMonitorState(
                                result.getMonitorName(), 
                                result.getQueueName(), 
                                result.getEnvironmentName(), 
                                result.getRegion()
                            );
                            if (state != null) {
                                state.markNotified();
                            }
                        }
                    }
                }
            }

            log.info("Monitor evaluation completed: {} monitors evaluated, {} triggered, {} notifications sent",
                    enabledMonitors.size(), triggeredCount, notificationsSent);

        } catch (Exception e) {
            log.error("Error during monitor evaluation: {}", e.getMessage(), e);
        }
    }

    /**
     * Evaluate a single monitor against all matching queues
     */
    private List<MonitorResult> evaluateMonitor(MonitorDefinition monitor) {
        Map<String, QueueStats> allQueueStats = metricsCollector.getCurrentQueueStats();
        return allQueueStats.values().stream()
                .filter(qs -> monitor.matchesQueue(qs.getQueue().getSanitizedQueueName()))
                .map(qs -> {
                    try {
                        // Find environment name for this queue
                        String environmentName = findEnvironmentName(qs);
                        MonitorResult result = monitorEvaluator.evaluate(monitor, qs, environmentName);
                        
                        // Record evaluation
                        monitorEvaluationsCounter.increment();
                        
                        return result;
                    } catch (Exception e) {
                        log.error("Error evaluating monitor {} for queue {}: {}", 
                            monitor.getName(), qs.getQueue().getSanitizedQueueName(), e.getMessage());
                        return null;
                    }
                })
                .filter(result -> result != null)
                .toList();
    }

    /**
     * Update Prometheus metrics for monitor results
     */
    private void updateMonitorMetrics(MonitorResult result) {
        String metricKey = String.format("%s_%s_%s_%s", 
            result.getMonitorName(), result.getQueueName(), 
            result.getEnvironmentName(), result.getRegion());

        // Monitor triggered gauge
        monitorMetrics.computeIfAbsent(metricKey, k -> {
            AtomicLong atomicValue = new AtomicLong(0);
            Gauge.builder("anypoint_mq_monitor_triggered", atomicValue, AtomicLong::get)
                    .description("Monitor trigger status (1 if triggered, 0 if OK)")
                    .tag("monitor_name", result.getMonitorName())
                    .tag("queue_name", result.getQueueName())
                    .tag("environment", result.getEnvironmentName())
                    .tag("region", result.getRegion())
                    .tag("severity", result.getSeverity() != null ? result.getSeverity().toString() : "UNKNOWN")
                    .register(meterRegistry);
            return atomicValue;
        }).set(result.isTriggered() ? 1 : 0);

        // Last triggered timestamp
        if (result.isTriggered()) {
            String timestampKey = "last_triggered_" + result.getMonitorName();
            monitorMetrics.computeIfAbsent(timestampKey, k -> {
                AtomicLong atomicValue = new AtomicLong(0);
                Gauge.builder("anypoint_mq_monitor_last_triggered_timestamp", atomicValue, AtomicLong::get)
                        .description("Unix timestamp when monitor was last triggered")
                        .tag("monitor_name", result.getMonitorName())
                        .register(meterRegistry);
                return atomicValue;
            }).set(System.currentTimeMillis() / 1000);
        }

        // Queue health score (for QUEUE_HEALTH monitors)
        if (result.getMetadata() != null && result.getMetadata().containsKey("healthScore")) {
            String healthKey = String.format("health_%s_%s_%s", 
                result.getQueueName(), result.getEnvironmentName(), result.getRegion());
            
            double healthScore = (Double) result.getMetadata().get("healthScore");
            
            healthScoreMetrics.computeIfAbsent(healthKey, k -> {
                AtomicLong atomicValue = new AtomicLong(0);
                Gauge.builder("anypoint_mq_queue_health_score", atomicValue, value -> value.get() / 100.0)
                        .description("Queue health score (0-100)")
                        .tag("queue_name", result.getQueueName())
                        .tag("environment", result.getEnvironmentName())
                        .tag("region", result.getRegion())
                        .register(meterRegistry); // Convert to 0-1 range
                return atomicValue;
            }).set((long) (healthScore * 100)); // Store as integer for precision
        }
    }

    /**
     * Check if notification should be sent (cooldown logic)
     */
    private boolean shouldSendNotification(MonitorDefinition monitor, MonitorResult result) {
        MonitorState state = monitorEvaluator.getMonitorState(
            result.getMonitorName(), 
            result.getQueueName(), 
            result.getEnvironmentName(), 
            result.getRegion()
        );
        
        if (state == null) {
            return true; // First time trigger, send notification
        }
        
        return !state.isInCooldown(monitor.getCooldownMinutes());
    }

    /**
     * Find environment name for a queue (lookup in config)
     */
    private String findEnvironmentName(QueueStats queueStats) {
        // This should be improved to actually map environment IDs to names
        // For now, return a default or try to extract from queue metadata
        return queueStats.getQueue().getEnvironment() != null ? 
            queueStats.getQueue().getEnvironment() : "unknown";
    }

    /**
     * Get current monitor status for API endpoints
     */
    public Map<String, MonitorResult> getCurrentMonitorStatus() {
        Map<String, MonitorResult> status = new ConcurrentHashMap<>();
        
        for (MonitorDefinition monitor : monitorConfig.getEnabledDefinitions()) {
            List<MonitorResult> results = evaluateMonitor(monitor);
            for (MonitorResult result : results) {
                String key = String.format("%s:%s:%s:%s", 
                    result.getMonitorName(), result.getQueueName(),
                    result.getEnvironmentName(), result.getRegion());
                status.put(key, result);
            }
        }
        
        return status;
    }
}