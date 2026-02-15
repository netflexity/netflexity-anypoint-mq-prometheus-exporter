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
 * Microsoft Teams notification channel implementation.
 * 
 * Sends alerts to Microsoft Teams using incoming webhooks with
 * adaptive card formatting for rich visual presentation.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Slf4j
public class TeamsNotificationChannel implements NotificationChannel {

    private final String name;
    private final String webhookUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public TeamsNotificationChannel(String name, String webhookUrl) {
        this.name = name;
        this.webhookUrl = webhookUrl;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void send(MonitorAlert alert) throws NotificationException {
        if (!isConfigured()) {
            throw new NotificationException("Teams channel not properly configured");
        }

        try {
            Map<String, Object> payload = buildTeamsPayload(alert);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            
            restTemplate.postForEntity(webhookUrl, request, String.class);
            
            log.debug("Sent Teams notification for monitor {} to channel {}", alert.getMonitorName(), name);
            
        } catch (Exception e) {
            throw new NotificationException("Failed to send Teams notification: " + e.getMessage(), e);
        }
    }

    @Override
    public String getType() {
        return "teams";
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
     * Build Microsoft Teams adaptive card payload
     */
    private Map<String, Object> payload(MonitorAlert alert) {
        Map<String, Object> payload = new HashMap<>();
        
        payload.put("@type", "MessageCard");
        payload.put("@context", "http://schema.org/extensions");
        payload.put("themeColor", getSeverityColorHex(alert));
        payload.put("summary", alert.getAlertSummary());
        
        // Title and text
        payload.put("title", alert.getAlertTitle());
        payload.put("text", alert.getMessage());
        
        // Sections with facts
        Map<String, Object> section = new HashMap<>();
        section.put("activityTitle", "Queue Monitor Alert");
        section.put("activitySubtitle", String.format("Environment: %s | Region: %s", 
            alert.getEnvironmentName(), alert.getRegion()));
        
        // Facts (key-value pairs)
        List<Map<String, Object>> facts = List.of(
            createFact("Queue Name", alert.getQueueName()),
            createFact("Monitor", alert.getMonitorName()),
            createFact("Severity", alert.getSeverity().toString()),
            createFact("Current Value", String.format("%.1f", alert.getCurrentValue())),
            createFact("Threshold", String.format("%.1f", alert.getThresholdValue())),
            createFact("Triggered At", alert.getTriggeredAt().toString())
        );
        section.put("facts", facts);
        
        payload.put("sections", List.of(section));
        
        return payload;
    }

    /**
     * Build Teams payload (corrected method name)
     */
    private Map<String, Object> buildTeamsPayload(MonitorAlert alert) {
        return payload(alert);
    }

    /**
     * Create a Teams fact object
     */
    private Map<String, Object> createFact(String name, String value) {
        Map<String, Object> fact = new HashMap<>();
        fact.put("name", name);
        fact.put("value", value);
        return fact;
    }

    /**
     * Get severity color in hex format for Teams
     */
    private String getSeverityColorHex(MonitorAlert alert) {
        return switch (alert.getSeverity()) {
            case INFO -> "36a64f";     // Green
            case WARNING -> "ff9500";  // Orange
            case CRITICAL -> "ff0000"; // Red
        };
    }
}