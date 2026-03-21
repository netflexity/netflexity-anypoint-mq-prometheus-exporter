package com.netflexity.amq.exporter.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.*;

/**
 * Auto-provisions Datadog dashboard and monitors on application startup.
 * 
 * Reads monitor definitions from datadog/monitors/*.json and dashboard from
 * datadog/dashboard.json (bundled in JAR). Checks Datadog for existing
 * resources and creates any that are missing.
 * 
 * Activate with:
 *   datadog.api-key=xxx
 *   datadog.app-key=yyy
 *   datadog.monitors.auto-provision=true
 * 
 * Optional:
 *   datadog.site=datadoghq.com (default)
 *   datadog.monitors.auto-update=true (update existing monitors to match definitions)
 */
@Component
@ConditionalOnProperty(name = "datadog.monitors.auto-provision", havingValue = "true")
@Slf4j
public class DatadogMonitorProvisioner {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${datadog.api-key:}")
    private String apiKey;

    @Value("${datadog.app-key:}")
    private String appKey;

    @Value("${datadog.site:datadoghq.com}")
    private String site;

    @Value("${datadog.monitors.auto-update:false}")
    private boolean autoUpdate;

    @Value("${datadog.monitors.tag-prefix:source:anypoint-mq-exporter}")
    private String tagPrefix;

    @Value("${datadog.dashboard.title:Anypoint MQ — Queue & Exchange Monitoring}")
    private String dashboardTitle;

    public DatadogMonitorProvisioner(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        if (apiKey.isBlank() || appKey.isBlank()) {
            log.warn("Datadog auto-provisioning enabled but API/APP keys not configured. Skipping.");
            return;
        }

        log.info("Datadog auto-provisioning started (site: {}, auto-update: {})", site, autoUpdate);

        provisionDashboard();
        provisionMonitors();
    }

    // --- Dashboard Provisioning ---

    private void provisionDashboard() {
        try {
            // Check if dashboard already exists by title
            String existingId = findDashboardByTitle(dashboardTitle);
            if (existingId != null) {
                log.info("Datadog dashboard already exists: '{}' (id: {})", dashboardTitle, existingId);
                return;
            }

            // Load dashboard from classpath
            Map<String, Object> dashboard = loadDashboardDefinition();
            if (dashboard == null) {
                log.warn("No dashboard definition found at datadog/dashboard.json");
                return;
            }

            // Create dashboard
            String id = createDashboard(dashboard);
            if (id != null) {
                log.info("Created Datadog dashboard: '{}' (id: {})", dashboardTitle, id);
            }
        } catch (Exception e) {
            log.error("Failed to provision Datadog dashboard: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String findDashboardByTitle(String title) {
        try {
            String url = String.format("https://api.%s/api/v1/dashboard", site);
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> dashboards = (List<Map<String, Object>>) response.getBody().get("dashboards");
                if (dashboards != null) {
                    for (Map<String, Object> db : dashboards) {
                        if (title.equals(db.get("title"))) {
                            return (String) db.get("id");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query existing dashboards: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> loadDashboardDefinition() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource("classpath:datadog/dashboard.json");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> dashboard = objectMapper.readValue(is, new TypeReference<>() {});
                    // Strip read-only fields
                    dashboard.remove("id");
                    dashboard.remove("author_handle");
                    dashboard.remove("author_name");
                    dashboard.remove("created_at");
                    dashboard.remove("modified_at");
                    dashboard.remove("url");
                    return dashboard;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load dashboard definition: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String createDashboard(Map<String, Object> dashboard) {
        try {
            String url = String.format("https://api.%s/api/v1/dashboard", site);
            String body = objectMapper.writeValueAsString(dashboard);

            HttpEntity<String> entity = new HttpEntity<>(body, buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("id");
            }
        } catch (Exception e) {
            log.error("Failed to create dashboard: {}", e.getMessage());
        }
        return null;
    }

    // --- Monitor Provisioning ---

    private void provisionMonitors() {
        try {
            List<Map<String, Object>> definitions = loadMonitorDefinitions();
            if (definitions.isEmpty()) {
                log.warn("No monitor definitions found in datadog/monitors/");
                return;
            }

            Map<String, Long> existingMonitors = getExistingMonitors();

            int created = 0, updated = 0, skipped = 0;

            for (Map<String, Object> definition : definitions) {
                String name = (String) definition.get("name");
                if (name == null) continue;

                Long existingId = existingMonitors.get(name);

                if (existingId == null) {
                    if (createMonitor(definition)) {
                        created++;
                        log.info("Created Datadog monitor: {}", name);
                    }
                } else if (autoUpdate) {
                    if (updateMonitor(existingId, definition)) {
                        updated++;
                        log.info("Updated Datadog monitor: {} (id: {})", name, existingId);
                    }
                } else {
                    skipped++;
                    log.debug("Monitor already exists, skipping: {} (id: {})", name, existingId);
                }
            }

            log.info("Datadog monitor provisioning complete: {} created, {} updated, {} skipped (of {} total)",
                    created, updated, skipped, definitions.size());

        } catch (Exception e) {
            log.error("Failed to provision Datadog monitors: {}", e.getMessage(), e);
        }
    }

    /**
     * Load monitor JSON definitions from classpath (bundled in JAR under datadog/monitors/).
     * Skips all-monitors.json and import-monitors.sh.
     */
    private List<Map<String, Object>> loadMonitorDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:datadog/monitors/*.json");

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || filename.equals("all-monitors.json")) continue;

                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> monitor = objectMapper.readValue(is, new TypeReference<>() {});
                    // Strip runtime-only fields that shouldn't be sent on create
                    monitor.remove("id");
                    monitor.remove("created");
                    monitor.remove("modified");
                    monitor.remove("creator");
                    monitor.remove("org_id");
                    monitor.remove("overall_state");
                    monitor.remove("overall_state_modified");
                    monitor.remove("matching_downtimes");
                    monitor.remove("multi");
                    monitor.remove("restricted_roles");

                    // Ensure our source tag is present
                    ensureTag(monitor, tagPrefix);

                    definitions.add(monitor);
                    log.debug("Loaded monitor definition: {} from {}", monitor.get("name"), filename);
                } catch (Exception e) {
                    log.warn("Failed to parse monitor definition {}: {}", filename, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan for monitor definitions: {}", e.getMessage());
        }
        return definitions;
    }

    /**
     * Get existing Datadog monitors tagged with our source tag.
     * Returns map of monitor name -> monitor ID.
     */
    private Map<String, Long> getExistingMonitors() {
        Map<String, Long> monitors = new HashMap<>();
        try {
            String url = String.format("https://api.%s/api/v1/monitor?tags=%s",
                    site, tagPrefix);

            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, entity, List.class);

            if (response.getBody() != null) {
                for (Object item : response.getBody()) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> monitor = (Map<String, Object>) item;
                        String name = (String) monitor.get("name");
                        Number id = (Number) monitor.get("id");
                        if (name != null && id != null) {
                            monitors.put(name, id.longValue());
                        }
                    }
                }
            }
            log.debug("Found {} existing monitors with tag '{}'", monitors.size(), tagPrefix);
        } catch (Exception e) {
            log.warn("Failed to query existing monitors: {}", e.getMessage());
        }
        return monitors;
    }

    private boolean createMonitor(Map<String, Object> definition) {
        try {
            String url = String.format("https://api.%s/api/v1/monitor", site);
            String body = objectMapper.writeValueAsString(definition);

            HttpEntity<String> entity = new HttpEntity<>(body, buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Failed to create monitor '{}': {}", definition.get("name"), e.getMessage());
            return false;
        }
    }

    private boolean updateMonitor(Long monitorId, Map<String, Object> definition) {
        try {
            String url = String.format("https://api.%s/api/v1/monitor/%d", site, monitorId);
            String body = objectMapper.writeValueAsString(definition);

            HttpEntity<String> entity = new HttpEntity<>(body, buildHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Failed to update monitor '{}' (id: {}): {}", definition.get("name"), monitorId, e.getMessage());
            return false;
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("DD-API-KEY", apiKey);
        headers.set("DD-APPLICATION-KEY", appKey);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private void ensureTag(Map<String, Object> monitor, String tag) {
        List<String> tags = (List<String>) monitor.get("tags");
        if (tags == null) {
            tags = new ArrayList<>();
            monitor.put("tags", tags);
        }
        if (!tags.contains(tag)) {
            tags.add(tag);
        }
    }
}
