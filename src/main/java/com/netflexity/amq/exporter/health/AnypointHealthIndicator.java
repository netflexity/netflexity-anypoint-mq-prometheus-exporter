package com.netflexity.amq.exporter.health;

import com.netflexity.amq.exporter.client.AnypointAuthClient;
import com.netflexity.amq.exporter.config.AnypointConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health indicator for Anypoint Platform connectivity.
 * 
 * This component checks:
 * - Authentication configuration validity
 * - Ability to obtain access tokens
 * - Basic connectivity to Anypoint Platform
 * 
 * The health check results are exposed via /actuator/health
 */
@Component
@Slf4j
public class AnypointHealthIndicator implements HealthIndicator {

    private final AnypointAuthClient authClient;
    private final AnypointConfig anypointConfig;
    
    // Cache health status to avoid frequent API calls
    private Health lastHealthStatus;
    private long lastHealthCheck = 0;
    private static final long HEALTH_CACHE_DURATION_MS = 30_000; // 30 seconds

    public AnypointHealthIndicator(AnypointAuthClient authClient, AnypointConfig anypointConfig) {
        this.authClient = authClient;
        this.anypointConfig = anypointConfig;
        
        log.info("Initialized AnypointHealthIndicator");
    }

    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        
        // Return cached status if recent
        if (lastHealthStatus != null && (now - lastHealthCheck) < HEALTH_CACHE_DURATION_MS) {
            return lastHealthStatus;
        }
        
        try {
            Health health = performHealthCheck();
            lastHealthStatus = health;
            lastHealthCheck = now;
            return health;
            
        } catch (Exception e) {
            log.error("Health check failed with exception: {}", e.getMessage(), e);
            Health health = Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("cause", e.getClass().getSimpleName())
                    .withDetails(getBaseDetails())
                    .build();
            lastHealthStatus = health;
            lastHealthCheck = now;
            return health;
        }
    }

    /**
     * Perform the actual health check
     */
    private Health performHealthCheck() {
        Map<String, Object> details = getBaseDetails();
        
        // Check configuration validity
        if (!anypointConfig.getAuth().hasValidAuth()) {
            return Health.down()
                    .withDetail("reason", "No valid authentication configuration found")
                    .withDetail("suggestion", "Configure either username/password or clientId/clientSecret")
                    .withDetails(details)
                    .build();
        }
        
        // Check if scraping is enabled
        if (!anypointConfig.getScrape().isEnabled()) {
            return Health.up()
                    .withDetail("status", "Scraping disabled")
                    .withDetail("reason", "anypoint.scrape.enabled is false")
                    .withDetails(details)
                    .build();
        }
        
        // Test authentication
        try {
            boolean hasValidToken = testAuthentication();
            
            if (hasValidToken) {
                return Health.up()
                        .withDetail("authentication", "success")
                        .withDetail("token_valid", true)
                        .withDetails(details)
                        .build();
            } else {
                return Health.down()
                        .withDetail("authentication", "failed")
                        .withDetail("token_valid", false)
                        .withDetail("reason", "Unable to obtain valid access token")
                        .withDetails(details)
                        .build();
            }
            
        } catch (Exception e) {
            log.warn("Authentication test failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("authentication", "error")
                    .withDetail("error", e.getMessage())
                    .withDetail("reason", "Exception during authentication test")
                    .withDetails(details)
                    .build();
        }
    }

    /**
     * Test authentication by attempting to get an access token
     */
    private boolean testAuthentication() {
        try {
            return authClient.getAccessToken()
                    .timeout(Duration.ofSeconds(10))
                    .map(token -> token != null && token.isValid())
                    .onErrorReturn(false)
                    .block();
        } catch (Exception e) {
            log.debug("Authentication test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get base details about the configuration
     */
    private Map<String, Object> getBaseDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        
        details.put("baseUrl", anypointConfig.getBaseUrl());
        details.put("organizationId", maskSensitiveId(anypointConfig.getOrganizationId()));
        details.put("authMethod", getAuthMethod());
        details.put("environmentsCount", anypointConfig.getEnvironments().size());
        details.put("regionsCount", anypointConfig.getRegions().size());
        details.put("scrapeEnabled", anypointConfig.getScrape().isEnabled());
        details.put("scrapeIntervalSeconds", anypointConfig.getScrape().getIntervalSeconds());
        details.put("scrapePeriodSeconds", anypointConfig.getScrape().getPeriodSeconds());
        
        // Add environment details (masked)
        details.put("environments", anypointConfig.getEnvironments().stream()
                .map(env -> Map.of(
                        "id", maskSensitiveId(env.getId()),
                        "name", env.getName()
                ))
                .toList());
        
        details.put("regions", anypointConfig.getRegions());
        
        return details;
    }

    /**
     * Get the authentication method being used
     */
    private String getAuthMethod() {
        if (anypointConfig.getAuth().isConnectedAppAuth()) {
            return "connected_app";
        } else if (anypointConfig.getAuth().isUsernamePasswordAuth()) {
            return "username_password";
        } else {
            return "none_configured";
        }
    }

    /**
     * Mask sensitive IDs for health check output
     */
    private String maskSensitiveId(String id) {
        if (id == null || id.length() < 8) {
            return "***";
        }
        return id.substring(0, 4) + "***" + id.substring(id.length() - 4);
    }
}