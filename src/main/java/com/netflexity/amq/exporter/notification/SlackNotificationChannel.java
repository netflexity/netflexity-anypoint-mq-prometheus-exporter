package com.netflexity.amq.exporter.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slack notification channel implementation.
 * 
 * Sends alerts to Slack using incoming webhooks with formatted messages
 * and severity-based colors.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Slf4j
public class SlackNotificationChannel implements NotificationChannel {

    private final String name;
    private final String webhookUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SlackNotificationChannel(String name, String webhookUrl) {
        this.name = name;
        this.webhookUrl = webhookUrl;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void send(MonitorAlert alert) throws NotificationException {
        if (!isConfigured()) {
            throw new NotificationException("Slack channel not properly configured");
        }

        try {
            Map<String, Object> payload = buildSlackPayload(alert);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            
            restTemplate.postForEntity(webhookUrl, request, String.class);
            
            log.debug("Sent Slack notification for monitor {} to channel {}", alert.getMonitorName(), name);
            
        } catch (Exception e) {
            throw new NotificationException("Failed to send Slack notification: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "slack";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.trim().isEmpty();
    }

    /**
     * Build Slack webhook payload with rich formatting
     */
    private Map<String, Object> buildSlackPayload(MonitorAlert alert) {
        Map<String, Object> payload = new HashMap<>();
        
        // Main message
        payload.put("text", alert.getAlertTitle());
        
        // Rich attachment
        Map<String, Object> attachment = new HashMap<>();
        attachment.put("color", alert.getSeverityColor());
        attachment.put("title", String.format("Queue: %s", alert.getQueueName()));
        attachment.put("text", alert.getMessage());
        
        // Fields for structured data
        List<Map<String, Object>> fields = List.of(
            createField("Environment", alert.getEnvironmentName(), true),
            createField("Region", alert.getRegion(), true),
            createField("Current Value", String.format("%.1f", alert.getCurrentValue()), true),
            createField("Threshold", String.format("%.1f", alert.getThresholdValue()), true),
            createField("Triggered At", alert.getTriggeredAt().toString(), false)
        );
        attachment.put("fields", fields);
        
        // Footer
        attachment.put("footer", "Anypoint MQ Monitor");
        attachment.put("ts", System.currentTimeMillis() / 1000);
        
        payload.put("attachments", List.of(attachment));
        
        return payload;
    }

    /**
     * Create a Slack field object
     */
    private Map<String, Object> createField(String title, String value, boolean isShort) {
        Map<String, Object> field = new HashMap<>();
        field.put("title", title);
        field.put("value", value);
        field.put("short", isShort);
        return field;
    }
}