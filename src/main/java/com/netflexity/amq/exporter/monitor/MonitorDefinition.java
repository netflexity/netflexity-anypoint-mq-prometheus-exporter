package com.netflexity.amq.exporter.monitor;

import lombok.Data;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Monitor definition configuration POJO.
 * 
 * Defines the configuration for a monitor including its type, target queues,
 * thresholds, evaluation windows, and notification settings.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Data
public class MonitorDefinition {

    /**
     * Unique name for this monitor
     */
    @NotEmpty
    private String name;

    /**
     * Human-readable description of what this monitor does
     */
    private String description;

    /**
     * Whether this monitor is enabled
     */
    private boolean enabled = true;

    /**
     * Type of monitor
     */
    @NotNull
    private MonitorType type;

    /**
     * Queue name pattern to monitor (supports wildcards like order-*)
     */
    @NotEmpty
    private String target;

    /**
     * Threshold condition operator
     */
    @NotNull
    private ThresholdCondition condition;

    /**
     * Threshold value for comparison
     */
    private double threshold;

    /**
     * Evaluation window in minutes (how far back to look for trend analysis)
     */
    @Min(1)
    private int evaluationWindowMinutes = 5;

    /**
     * Cooldown period in minutes (don't re-alert within this window)
     */
    @Min(0)
    private int cooldownMinutes = 15;

    /**
     * Severity level for this monitor
     */
    @NotNull
    private MonitorSeverity severity = MonitorSeverity.WARNING;

    /**
     * List of notification channel names to send alerts to
     */
    private List<String> notifications;

    /**
     * Monitor types supported by the system
     */
    public enum MonitorType {
        QUEUE_DEPTH,
        DLQ_ALERT,
        THROUGHPUT_DROP,
        THROUGHPUT_SPIKE,
        QUEUE_HEALTH,
        CUSTOM
    }

    /**
     * Threshold condition operators
     */
    public enum ThresholdCondition {
        GT,      // Greater than
        LT,      // Less than
        GTE,     // Greater than or equal
        LTE,     // Less than or equal
        EQ,      // Equal
        PCT_CHANGE  // Percentage change (for trend monitoring)
    }

    /**
     * Monitor severity levels
     */
    public enum MonitorSeverity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * Check if the target pattern matches a queue name
     */
    public boolean matchesQueue(String queueName) {
        if (target == null || queueName == null) {
            return false;
        }
        
        // Convert glob pattern to regex
        String pattern = target
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        
        return queueName.matches(pattern);
    }

    /**
     * Get the monitor key for tracking state
     */
    public String getMonitorKey() {
        return name;
    }
}