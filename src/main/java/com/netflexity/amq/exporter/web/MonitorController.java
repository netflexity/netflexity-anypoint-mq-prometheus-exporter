package com.netflexity.amq.exporter.web;

import com.netflexity.amq.exporter.config.MonitorConfig;
import com.netflexity.amq.exporter.license.LicenseService;
import com.netflexity.amq.exporter.monitor.MonitorDefinition;
import com.netflexity.amq.exporter.monitor.MonitorResult;
import com.netflexity.amq.exporter.monitor.MonitorScheduler;
import com.netflexity.amq.exporter.notification.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for monitor management.
 * 
 * Provides endpoints for viewing monitor status, health scores,
 * and testing notifications (PRO license required).
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "anypoint.monitors", name = "enabled", havingValue = "true")
@Slf4j
public class MonitorController {

    private final MonitorConfig monitorConfig;
    private final MonitorScheduler monitorScheduler;
    private final NotificationDispatcher notificationDispatcher;
    private final LicenseService licenseService;

    public MonitorController(MonitorConfig monitorConfig, 
                            MonitorScheduler monitorScheduler,
                            NotificationDispatcher notificationDispatcher,
                            LicenseService licenseService) {
        this.monitorConfig = monitorConfig;
        this.monitorScheduler = monitorScheduler;
        this.notificationDispatcher = notificationDispatcher;
        this.licenseService = licenseService;
    }

    /**
     * Get license information
     */
    @GetMapping("/license")
    public ResponseEntity<LicenseService.LicenseInfo> getLicenseInfo() {
        return ResponseEntity.ok(licenseService.getLicenseInfo());
    }

    /**
     * List all monitor definitions and current state
     */
    @GetMapping("/monitors")
    public ResponseEntity<?> getMonitors() {
        if (!licenseService.isRestApiEnabled()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "REST API requires PRO license", 
                               "message", licenseService.getUpgradeMessage()));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("monitors", monitorConfig.getEnabledDefinitions());
        response.put("currentStatus", monitorScheduler.getCurrentMonitorStatus());
        response.put("evaluationInterval", monitorConfig.getEvaluationIntervalSeconds());
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Get specific monitor status
     */
    @GetMapping("/monitors/{name}")
    public ResponseEntity<?> getMonitor(@PathVariable String name) {
        if (!licenseService.isRestApiEnabled()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "REST API requires PRO license", 
                               "message", licenseService.getUpgradeMessage()));
        }

        MonitorDefinition monitor = monitorConfig.findMonitorByName(name);
        if (monitor == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("definition", monitor);
        
        // Find current status for this monitor
        Map<String, MonitorResult> allStatus = monitorScheduler.getCurrentMonitorStatus();
        Map<String, MonitorResult> monitorStatus = allStatus.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(name + ":"))
                .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
        
        response.put("currentStatus", monitorStatus);
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Get monitor evaluation history (placeholder - would need persistent storage)
     */
    @GetMapping("/monitors/{name}/history")
    public ResponseEntity<?> getMonitorHistory(@PathVariable String name, 
                                              @RequestParam(defaultValue = "24") int hours) {
        if (!licenseService.isRestApiEnabled()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "REST API requires PRO license", 
                               "message", licenseService.getUpgradeMessage()));
        }

        MonitorDefinition monitor = monitorConfig.findMonitorByName(name);
        if (monitor == null) {
            return ResponseEntity.notFound().build();
        }

        // Placeholder response - in a real implementation, this would query
        // a persistent store of monitor evaluation history
        Map<String, Object> response = new HashMap<>();
        response.put("monitor", name);
        response.put("hours", hours);
        response.put("history", List.of()); // Empty for now
        response.put("message", "History storage not implemented yet");
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Test-fire a monitor notification
     */
    @PostMapping("/monitors/{name}/test")
    public ResponseEntity<?> testMonitor(@PathVariable String name) {
        if (!licenseService.isNotificationsEnabled()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Notifications require PRO license", 
                               "message", licenseService.getUpgradeMessage()));
        }

        MonitorDefinition monitor = monitorConfig.findMonitorByName(name);
        if (monitor == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            // Create a test monitor result
            MonitorResult testResult = MonitorResult.triggered(
                monitor.getName(),
                "test-queue",
                "test-environment", 
                "test-region",
                999.0,
                monitor.getThreshold(),
                "This is a test notification from the REST API",
                monitor.getSeverity(),
                Map.of("test", true, "triggered_by", "api")
            );

            // Dispatch the test notification
            notificationDispatcher.dispatch(testResult);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("monitor", name);
            response.put("message", "Test notification sent");
            response.put("channels", monitor.getNotifications());
            response.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error testing monitor {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send test notification", 
                               "message", e.getMessage()));
        }
    }

    /**
     * Get queue health scores for all queues
     */
    @GetMapping("/health-scores")
    public ResponseEntity<?> getHealthScores() {
        if (!licenseService.isHealthScoresEnabled()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Health scores require PRO license", 
                               "message", licenseService.getUpgradeMessage()));
        }

        // Get current monitor status and extract health scores
        Map<String, MonitorResult> allStatus = monitorScheduler.getCurrentMonitorStatus();
        Map<String, Object> healthScores = new HashMap<>();
        
        allStatus.values().stream()
                .filter(result -> result.getMetadata() != null && 
                                result.getMetadata().containsKey("healthScore"))
                .forEach(result -> {
                    String key = String.format("%s:%s:%s", 
                        result.getQueueName(), result.getEnvironmentName(), result.getRegion());
                    healthScores.put(key, Map.of(
                        "queueName", result.getQueueName(),
                        "environment", result.getEnvironmentName(),
                        "region", result.getRegion(),
                        "healthScore", result.getMetadata().get("healthScore"),
                        "lastEvaluated", result.getEvaluatedAt()
                    ));
                });

        Map<String, Object> response = new HashMap<>();
        response.put("healthScores", healthScores);
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Get health score for a specific queue
     */
    @GetMapping("/health-scores/{queueName}")
    public ResponseEntity<?> getQueueHealthScore(@PathVariable String queueName) {
        if (!licenseService.isHealthScoresEnabled()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Health scores require PRO license", 
                               "message", licenseService.getUpgradeMessage()));
        }

        // Find health score for the specified queue
        Map<String, MonitorResult> allStatus = monitorScheduler.getCurrentMonitorStatus();
        
        Map<String, Object> queueHealthScores = allStatus.values().stream()
                .filter(result -> queueName.equals(result.getQueueName()) &&
                                result.getMetadata() != null &&
                                result.getMetadata().containsKey("healthScore"))
                .collect(HashMap::new, 
                        (map, result) -> {
                            String key = result.getEnvironmentName() + ":" + result.getRegion();
                            map.put(key, Map.of(
                                "environment", result.getEnvironmentName(),
                                "region", result.getRegion(),
                                "healthScore", result.getMetadata().get("healthScore"),
                                "breakdown", getHealthScoreBreakdown(result),
                                "lastEvaluated", result.getEvaluatedAt()
                            ));
                        }, 
                        HashMap::putAll);

        if (queueHealthScores.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("queueName", queueName);
        response.put("healthScores", queueHealthScores);
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Get health score breakdown details
     */
    private Map<String, Object> getHealthScoreBreakdown(MonitorResult result) {
        Map<String, Object> breakdown = new HashMap<>();
        
        if (result.getMetadata() != null) {
            breakdown.put("messagesInQueue", result.getMetadata().get("messagesInQueue"));
            breakdown.put("messagesInFlight", result.getMetadata().get("messagesInFlight"));
            breakdown.put("isDlq", result.getMetadata().get("isDlq"));
        }
        
        breakdown.put("explanation", "Health score based on queue depth, DLQ presence, consumer lag, and throughput stability");
        
        return breakdown;
    }
}