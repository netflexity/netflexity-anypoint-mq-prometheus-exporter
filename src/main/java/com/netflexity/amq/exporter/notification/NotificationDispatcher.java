package com.netflexity.amq.exporter.notification;

import com.netflexity.amq.exporter.config.MonitorConfig;
import com.netflexity.amq.exporter.monitor.MonitorDefinition;
import com.netflexity.amq.exporter.monitor.MonitorResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notification dispatcher that routes alerts to configured channels.
 * 
 * Handles notification delivery, failure recovery, and metrics tracking
 * for all notification channels.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(prefix = "anypoint.monitors", name = "enabled", havingValue = "true")
@Slf4j
public class NotificationDispatcher {

    private final MonitorConfig monitorConfig;
    private final Map<String, NotificationChannel> channels;
    private final Counter notificationsSentCounter;
    private final Counter notificationsFailedCounter;

    public NotificationDispatcher(MonitorConfig monitorConfig, List<NotificationChannel> channelList, MeterRegistry meterRegistry) {
        this.monitorConfig = monitorConfig;
        // Build channel map by name
        this.channels = new ConcurrentHashMap<>();
        if (channelList != null) {
            channelList.forEach(channel -> channels.put(channel.getName(), channel));
        }

        // Initialize metrics
        this.notificationsSentCounter = Counter.builder("anypoint_mq_monitor_notifications_total")
                .description("Total number of notifications sent")
                .register(meterRegistry);

        this.notificationsFailedCounter = Counter.builder("anypoint_mq_monitor_notifications_failed_total")
                .description("Total number of failed notifications")
                .register(meterRegistry);

        log.info("NotificationDispatcher initialized with {} channels: {}", 
                channels.size(), channels.keySet());
    }

    /**
     * Dispatch alert to configured notification channels
     */
    public void dispatch(MonitorResult result) {
        if (!result.isTriggered()) {
            log.debug("Skipping notification for non-triggered monitor: {}", result.getMonitorName());
            return;
        }

        MonitorAlert alert = MonitorAlert.fromMonitorResult(result);
        
        // Find the monitor definition to get notification channels
        // Note: This should be injected with MonitorConfig, but for now we'll handle it differently
        List<String> notificationChannels = getNotificationChannels(result.getMonitorName());
        
        if (notificationChannels == null || notificationChannels.isEmpty()) {
            log.debug("No notification channels configured for monitor: {}", result.getMonitorName());
            return;
        }

        int successCount = 0;
        int failureCount = 0;

        for (String channelName : notificationChannels) {
            NotificationChannel channel = channels.get(channelName);
            
            if (channel == null) {
                log.warn("Notification channel '{}' not found for monitor '{}'", channelName, result.getMonitorName());
                failureCount++;
                continue;
            }

            if (!channel.isConfigured()) {
                log.warn("Notification channel '{}' is not properly configured", channelName);
                failureCount++;
                continue;
            }

            try {
                channel.send(alert);
                successCount++;
                
                notificationsSentCounter.increment(
                    "monitor_name", result.getMonitorName(),
                    "channel", channelName,
                    "channel_type", channel.getType(),
                    "status", "success"
                );
                
                log.debug("Successfully sent notification for monitor '{}' via channel '{}'", 
                    result.getMonitorName(), channelName);
                
            } catch (Exception e) {
                failureCount++;
                
                notificationsFailedCounter.increment(
                    "monitor_name", result.getMonitorName(),
                    "channel", channelName,
                    "channel_type", channel.getType(),
                    "error", e.getClass().getSimpleName()
                );
                
                log.error("Failed to send notification for monitor '{}' via channel '{}': {}", 
                    result.getMonitorName(), channelName, e.getMessage(), e);
            }
        }

        log.info("Notification dispatch completed for monitor '{}': {} successful, {} failed", 
                result.getMonitorName(), successCount, failureCount);
    }

    /**
     * Test a specific notification channel
     */
    public void testChannel(String channelName) throws NotificationChannel.NotificationException {
        NotificationChannel channel = channels.get(channelName);
        
        if (channel == null) {
            throw new NotificationChannel.NotificationException("Channel '" + channelName + "' not found");
        }

        if (!channel.isConfigured()) {
            throw new NotificationChannel.NotificationException("Channel '" + channelName + "' is not configured");
        }

        channel.test();
        log.info("Successfully tested notification channel: {}", channelName);
    }

    /**
     * Get available notification channels
     */
    public Map<String, NotificationChannel> getChannels() {
        return Map.copyOf(channels);
    }

    /**
     * Get notification channels for a monitor
     */
    private List<String> getNotificationChannels(String monitorName) {
        MonitorDefinition monitor = monitorConfig.findMonitorByName(monitorName);
        if (monitor == null) {
            log.warn("Monitor definition not found: {}", monitorName);
            return List.of();
        }
        
        return monitor.getNotifications() != null ? monitor.getNotifications() : List.of();
    }
}