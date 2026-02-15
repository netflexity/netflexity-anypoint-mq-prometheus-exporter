package com.netflexity.amq.exporter.notification;

import com.netflexity.amq.exporter.monitor.MonitorDefinition;

/**
 * Interface for notification channels.
 * 
 * Implementations handle sending alerts through different channels
 * like Slack, PagerDuty, email, Microsoft Teams, etc.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
public interface NotificationChannel {

    /**
     * Send an alert through this notification channel
     * 
     * @param alert The alert to send
     * @throws NotificationException if sending fails
     */
    void send(MonitorAlert alert) throws NotificationException;

    /**
     * Get the type identifier for this channel
     * 
     * @return Channel type (e.g., "slack", "pagerduty", "email")
     */
    String getType();

    /**
     * Get the name of this channel instance
     * 
     * @return Channel instance name (e.g., "slack-ops", "email-alerts")
     */
    String getName();

    /**
     * Check if this channel is properly configured and ready to send alerts
     * 
     * @return true if channel is ready, false otherwise
     */
    default boolean isConfigured() {
        return true;
    }

    /**
     * Test the channel configuration by sending a test alert
     * 
     * @throws NotificationException if test fails
     */
    default void test() throws NotificationException {
        MonitorAlert testAlert = MonitorAlert.builder()
                .monitorName("test-monitor")
                .severity(MonitorDefinition.MonitorSeverity.INFO)
                .message("This is a test alert from Anypoint MQ Prometheus Exporter")
                .queueName("test-queue")
                .environmentName("test-environment")
                .region("test-region")
                .currentValue(42.0)
                .thresholdValue(100.0)
                .build();
        
        send(testAlert);
    }

    /**
     * Exception thrown when notification sending fails
     */
    class NotificationException extends Exception {
        public NotificationException(String message) {
            super(message);
        }
        
        public NotificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}