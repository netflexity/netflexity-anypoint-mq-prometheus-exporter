package com.netflexity.amq.exporter.notification;

import com.netflexity.amq.exporter.monitor.MonitorDefinition;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Alert payload for monitor notifications.
 * 
 * Contains all information needed to send notifications through
 * various channels like Slack, PagerDuty, email, etc.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Data
@Builder
public class MonitorAlert {

    /**
     * Name of the monitor that triggered
     */
    private String monitorName;

    /**
     * Severity level of the alert
     */
    private MonitorDefinition.MonitorSeverity severity;

    /**
     * Human-readable alert message
     */
    private String message;

    /**
     * Queue name that triggered the alert
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
     * Current value that triggered the alert
     */
    private double currentValue;

    /**
     * Threshold value that was exceeded
     */
    private double thresholdValue;

    /**
     * When the alert was triggered
     */
    @Builder.Default
    private LocalDateTime triggeredAt = LocalDateTime.now();

    /**
     * Additional metadata about the alert
     */
    private Map<String, Object> metadata;

    /**
     * Get alert color based on severity (for UI/notification formatting)
     */
    public String getSeverityColor() {
        return switch (severity) {
            case INFO -> "#36a64f";     // Green
            case WARNING -> "#ff9500";  // Orange
            case CRITICAL -> "#ff0000"; // Red
        };
    }

    /**
     * Get severity emoji for notifications
     */
    public String getSeverityEmoji() {
        return switch (severity) {
            case INFO -> "ℹ️";
            case WARNING -> "⚠️";
            case CRITICAL -> "🚨";
        };
    }

    /**
     * Get alert title for notifications
     */
    public String getAlertTitle() {
        return String.format("%s %s Alert: %s", 
            getSeverityEmoji(), 
            severity.toString(), 
            monitorName);
    }

    /**
     * Get formatted alert summary
     */
    public String getAlertSummary() {
        return String.format("[%s] %s in %s/%s: %s", 
            severity, 
            queueName, 
            environmentName, 
            region, 
            message);
    }

    /**
     * Create alert from monitor result
     */
    public static MonitorAlert fromMonitorResult(com.netflexity.amq.exporter.monitor.MonitorResult result) {
        return MonitorAlert.builder()
                .monitorName(result.getMonitorName())
                .severity(result.getSeverity())
                .message(result.getMessage())
                .queueName(result.getQueueName())
                .environmentName(result.getEnvironmentName())
                .region(result.getRegion())
                .currentValue(result.getCurrentValue())
                .thresholdValue(result.getThresholdValue())
                .triggeredAt(result.getEvaluatedAt())
                .metadata(result.getMetadata())
                .build();
    }
}