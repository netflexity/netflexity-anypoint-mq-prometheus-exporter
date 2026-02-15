package com.netflexity.amq.exporter.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflexity.amq.exporter.monitor.MonitorDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * PagerDuty notification channel implementation.
 * 
 * Sends alerts to PagerDuty using Events API v2 with proper
 * severity mapping and incident deduplication.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Slf4j
public class PagerDutyNotificationChannel implements NotificationChannel {

    private static final String PAGERDUTY_EVENTS_URL = "https://events.pagerduty.com/v2/enqueue";

    private final String name;
    private final String routingKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PagerDutyNotificationChannel(String name, String routingKey) {
        this.name = name;
        this.routingKey = routingKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void send(MonitorAlert alert) throws NotificationException {
        if (!isConfigured()) {
            throw new NotificationException("PagerDuty channel not properly configured");
        }

        try {
            Map<String, Object> payload = buildPagerDutyPayload(alert);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            
            restTemplate.postForEntity(PAGERDUTY_EVENTS_URL, request, String.class);
            
            log.debug("Sent PagerDuty notification for monitor {} to routing key {}", alert.getMonitorName(), routingKey);
            
        } catch (Exception e) {
            throw new NotificationException("Failed to send PagerDuty notification: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "pagerduty";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isConfigured() {
        return routingKey != null && !routingKey.trim().isEmpty();
    }

    /**
     * Build PagerDuty Events API v2 payload
     */
    private Map<String, Object> buildPagerDutyPayload(MonitorAlert alert) {
        Map<String, Object> payload = new HashMap<>();
        
        payload.put("routing_key", routingKey);
        payload.put("event_action", "trigger");
        
        // Dedup key to group related alerts
        String dedupKey = String.format("amq-monitor-%s-%s-%s", 
            alert.getMonitorName(), alert.getQueueName(), alert.getEnvironmentName());
        payload.put("dedup_key", dedupKey);
        
        // Event payload
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("summary", alert.getAlertSummary());
        eventPayload.put("source", "anypoint-mq-exporter");
        eventPayload.put("severity", mapSeverityToPagerDuty(alert.getSeverity()));
        eventPayload.put("timestamp", alert.getTriggeredAt().toInstant(ZoneOffset.UTC).toString());
        eventPayload.put("component", "anypoint-mq");
        eventPayload.put("group", alert.getEnvironmentName());
        eventPayload.put("class", "queue-monitor");
        
        // Custom details
        Map<String, Object> customDetails = new HashMap<>();
        customDetails.put("monitor_name", alert.getMonitorName());
        customDetails.put("queue_name", alert.getQueueName());
        customDetails.put("environment", alert.getEnvironmentName());
        customDetails.put("region", alert.getRegion());
        customDetails.put("current_value", alert.getCurrentValue());
        customDetails.put("threshold_value", alert.getThresholdValue());
        customDetails.put("message", alert.getMessage());
        
        if (alert.getMetadata() != null) {
            customDetails.putAll(alert.getMetadata());
        }
        
        eventPayload.put("custom_details", customDetails);
        
        payload.put("payload", eventPayload);
        
        return payload;
    }

    /**
     * Map monitor severity to PagerDuty severity
     */
    private String mapSeverityToPagerDuty(MonitorDefinition.MonitorSeverity severity) {
        return switch (severity) {
            case INFO -> "info";
            case WARNING -> "warning";
            case CRITICAL -> "critical";
        };
    }
}