package com.netflexity.amq.exporter.monitor;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Result of a monitor evaluation.
 * 
 * Contains the evaluation outcome, current and threshold values,
 * alert message, and metadata for further processing.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Data
@Builder
public class MonitorResult {

    /**
     * Name of the monitor that was evaluated
     */
    private String monitorName;

    /**
     * Queue name that triggered the monitor (if applicable)
     */
    private String queueName;

    /**
     * Environment name where the queue exists
     */
    private String environmentName;

    /**
     * Region where the queue exists
     */
    private String region;

    /**
     * Whether the monitor was triggered (alert condition met)
     */
    private boolean triggered;

    /**
     * Current value that was compared against threshold
     */
    private double currentValue;

    /**
     * Threshold value used for comparison
     */
    private double thresholdValue;

    /**
     * Human-readable message describing the alert condition
     */
    private String message;

    /**
     * Severity level of the alert
     */
    private MonitorDefinition.MonitorSeverity severity;

    /**
     * When this evaluation occurred
     */
    @Builder.Default
    private LocalDateTime evaluatedAt = LocalDateTime.now();

    /**
     * Additional metadata about the evaluation
     */
    private Map<String, Object> metadata;

    /**
     * Create a triggered result
     */
    public static MonitorResult triggered(String monitorName, String queueName, String environmentName, String region,
                                          double currentValue, double thresholdValue, String message,
                                          MonitorDefinition.MonitorSeverity severity, Map<String, Object> metadata) {
        return MonitorResult.builder()
                .monitorName(monitorName)
                .queueName(queueName)
                .environmentName(environmentName)
                .region(region)
                .triggered(true)
                .currentValue(currentValue)
                .thresholdValue(thresholdValue)
                .message(message)
                .severity(severity)
                .metadata(metadata)
                .build();
    }

    /**
     * Create a non-triggered result
     */
    public static MonitorResult notTriggered(String monitorName, String queueName, String environmentName, String region,
                                             double currentValue, double thresholdValue) {
        return MonitorResult.builder()
                .monitorName(monitorName)
                .queueName(queueName)
                .environmentName(environmentName)
                .region(region)
                .triggered(false)
                .currentValue(currentValue)
                .thresholdValue(thresholdValue)
                .build();
    }
}