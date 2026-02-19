package com.netflexity.amq.exporter.web;

import com.netflexity.amq.exporter.config.AnypointConfig;
import com.netflexity.amq.exporter.discovery.EnvironmentDiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for viewing discovered organizations, environments,
 * and current exporter status.
 */
@RestController
@RequestMapping("/api")
public class DiscoveryController {

    private final EnvironmentDiscoveryService discoveryService;
    private final AnypointConfig anypointConfig;

    public DiscoveryController(EnvironmentDiscoveryService discoveryService, AnypointConfig anypointConfig) {
        this.discoveryService = discoveryService;
        this.anypointConfig = anypointConfig;
    }

    /**
     * Get current discovery status and monitored environments
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("autoDiscovery", anypointConfig.isAutoDiscovery());
        status.put("discoveryComplete", discoveryService.isDiscoveryComplete());
        status.put("organizations", discoveryService.getDiscoveredOrgs().values().stream()
            .map(org -> Map.of("id", org.getId(), "name", org.getName()))
            .collect(Collectors.toList()));
        status.put("environments", anypointConfig.getEnvironments().stream()
            .map(env -> Map.of("id", env.getId(), "name", env.getName()))
            .collect(Collectors.toList()));
        status.put("regions", anypointConfig.getRegions());
        status.put("scrapeIntervalSeconds", anypointConfig.getScrape().getIntervalSeconds());
        status.put("statsPeriodSeconds", anypointConfig.getScrape().getPeriodSeconds());
        return ResponseEntity.ok(status);
    }

    /**
     * Trigger a manual re-discovery of orgs and environments
     */
    @PostMapping("/discover")
    public Mono<ResponseEntity<Map<String, Object>>> triggerDiscovery() {
        return discoveryService.discover()
            .then(Mono.fromCallable(() -> {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "ok");
                result.put("organizations", discoveryService.getDiscoveredOrgs().size());
                result.put("environments", discoveryService.getDiscoveredEnvironments().size());
                result.put("details", discoveryService.getDiscoveredEnvironments().stream()
                    .map(env -> Map.of("id", env.getId(), "name", env.getName()))
                    .collect(Collectors.toList()));
                return ResponseEntity.ok(result);
            }));
    }
}
