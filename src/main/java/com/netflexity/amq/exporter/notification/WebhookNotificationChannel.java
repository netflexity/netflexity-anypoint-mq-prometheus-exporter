package com.netflexity.amq.exporter.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic webhook notification channel implementation.
 * 
 * Sends alerts via HTTP POST to configurable URLs with custom
 * headers and JSON payload format.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Slf4j
public class WebhookNotificationChannel implements NotificationChannel {

    private final String name;
    private final String url;
    private final Map<String, String> headers;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WebhookNotificationChannel(String name, String url, Map<String, String> headers) {
        this.name = name;
        this.url = url;
        this.headers = headers != null ? headers : new HashMap<>();
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void send(MonitorAlert alert) throws NotificationException {
        if (!isConfigured()) {
            throw new NotificationException("Webhook channel not properly configured");
        }

        try {
            Map<String, Object> payload = buildWebhookPayload(alert);
            
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            
            // Add custom headers
            headers.forEach(httpHeaders::set);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, httpHeaders);
            
            restTemplate.postForEntity(url, request, String.class);
            
            log.debug("Sent webhook notification for monitor {} to URL {}", alert.getMonitorName(), url);
            
        } catch (Exception e) {
            throw new NotificationException("Failed to send webhook notification: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "webhook";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isConfigured() {
        return url != null && !url.trim().isEmpty();
    }

    /**
     * Build generic webhook JSON payload
     */
    private Map<String, Object> buildWebhookPayload(MonitorAlert alert) {
        Map<String, Object> payload = new HashMap<>();
        
        // Alert metadata
        payload.put("alert_type", "anypoint_mq_monitor");
        payload.put("timestamp", alert.getTriggeredAt().toString());
        payload.put("source", "anypoint-mq-prometheus-exporter");
        
        // Monitor information
        payload.put("monitor_name", alert.getMonitorName());
        payload.put("severity", alert.getSeverity().toString());
        payload.put("message", alert.getMessage());
        
        // Queue information
        payload.put("queue_name", alert.getQueueName());
        payload.put("environment", alert.getEnvironmentName());
        payload.put("region", alert.getRegion());
        
        // Metric values
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("current_value", alert.getCurrentValue());
        metrics.put("threshold_value", alert.getThresholdValue());
        payload.put("metrics", metrics);
        
        // Additional metadata
        if (alert.getMetadata() != null) {
            payload.put("metadata", alert.getMetadata());
        }
        
        // Formatted summary
        payload.put("summary", alert.getAlertSummary());
        
        return payload;
    }
}