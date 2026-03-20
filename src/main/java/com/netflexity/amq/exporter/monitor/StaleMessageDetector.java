package com.netflexity.amq.exporter.monitor;

import com.netflexity.anypoint.common.client.AnypointAuthClient;
import com.netflexity.anypoint.common.config.AnypointConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stale Message Detector — equivalent to MuleSoft q-qmsg-detect-stale-messages monitor.
 * 
 * How it works:
 * 1. Periodically browses messages from each queue (non-destructive: read + release locks)
 * 2. Remembers message IDs between scrapes
 * 3. If the same messageId appears in consecutive scrapes, it's "stale" — stuck in the queue
 * 4. Exposes stale message count as a Prometheus gauge per queue
 * 
 * This is a heavier operation than stats collection, so it runs at a slower cadence
 * (default every 5 minutes, configurable).
 * 
 * The detector uses the Broker API to peek at messages, then immediately releases
 * locks so messages remain available for real consumers.
 */
@Component
@Slf4j
public class StaleMessageDetector {

    private final WebClient webClient;
    private final AnypointConfig anypointConfig;
    private final AnypointAuthClient authClient;
    private final MeterRegistry meterRegistry;

    /** Broker API uses regional endpoints */
    private static final Map<String, String> BROKER_URLS = Map.of(
            "us-east-1", "https://mq-us-east-1.anypoint.mulesoft.com",
            "us-west-2", "https://mq-us-west-2.anypoint.mulesoft.com",
            "eu-west-1", "https://mq-eu-west-1.anypoint.mulesoft.com",
            "ap-southeast-1", "https://mq-ap-southeast-1.anypoint.mulesoft.com",
            "ap-southeast-2", "https://mq-ap-southeast-2.anypoint.mulesoft.com",
            "eu-central-1", "https://mq-eu-central-1.anypoint.mulesoft.com",
            "ca-central-1", "https://mq-ca-central-1.anypoint.mulesoft.com"
    );

    private String getBrokerUrl(String region) {
        return BROKER_URLS.getOrDefault(region, "https://mq-us-east-1.anypoint.mulesoft.com");
    }

    /** Previous scrape's message IDs per queue key: "envId_region_queueId" -> Set<messageId> */
    private final ConcurrentHashMap<String, Set<String>> previousMessageIds = new ConcurrentHashMap<>();

    /** Current stale counts per queue (for Prometheus gauges) */
    private final ConcurrentHashMap<String, AtomicLong> staleCountGauges = new ConcurrentHashMap<>();

    public StaleMessageDetector(WebClient webClient, AnypointConfig anypointConfig,
                                 AnypointAuthClient authClient, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.anypointConfig = anypointConfig;
        this.authClient = authClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Scheduled check — runs every 5 minutes by default.
     * Uses a separate schedule from the main metrics collector to avoid overloading the API.
     */
    @Scheduled(fixedDelayString = "${anypoint.scrape.staleCheckIntervalMs:300000}", initialDelay = 60000)
    public void detectStaleMessages() {
        if (!anypointConfig.getScrape().isEnabled()) {
            return;
        }

        log.debug("Starting stale message detection");

        Flux.fromIterable(anypointConfig.getEnvironments())
                .flatMap(env -> Flux.fromIterable(anypointConfig.getRegions())
                        .flatMap(region -> checkEnvironmentRegion(env, region)))
                .doOnComplete(() -> log.debug("Stale message detection completed"))
                .doOnError(e -> log.warn("Stale message detection failed: {}", e.getMessage()))
                .subscribe();
    }

    private Mono<Void> checkEnvironmentRegion(AnypointConfig.Environment env, String region) {
        String orgName = env.getOrgName() != null ? env.getOrgName() : "default";

        // List all queues
        String listUrl = String.format("%s/mq/admin/api/v1/organizations/%s/environments/%s/regions/%s/destinations",
                anypointConfig.getBaseUrl(),
                anypointConfig.getOrganizationId(),
                env.getId(),
                region);

        return authClient.getAccessToken()
                .flatMapMany(token -> webClient.get()
                        .uri(listUrl)
                        .header("Authorization", token.getAuthorizationHeader())
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, resp -> Mono.error(new RuntimeException("List failed: " + resp.statusCode())))
                        .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                        .flatMapMany(Flux::fromIterable)
                        .filter(dest -> "queue".equalsIgnoreCase((String) dest.get("type")))
                        .flatMap(dest -> {
                            String queueId = (String) dest.get("queueId");
                            if (queueId == null) return Mono.empty();
                            return checkQueueForStaleMessages(token.getAuthorizationHeader(), env, region, queueId, orgName);
                        }, 2))  // concurrency limit — don't hammer the API
                .then()
                .onErrorResume(e -> {
                    log.warn("Stale check failed for env {} region {}: {}", env.getName(), region, e.getMessage());
                    return Mono.empty();
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> checkQueueForStaleMessages(String authHeader, AnypointConfig.Environment env,
                                                    String region, String queueId, String orgName) {
        String queueKey = env.getId() + "_" + region + "_" + queueId;

        // Browse messages (non-destructive peek — we'll release locks after)
        String browseUrl = String.format(
                "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages?batchSize=10&pollingTime=1000&lockTtl=10000",
                getBrokerUrl(region),
                anypointConfig.getOrganizationId(),
                env.getId(),
                queueId);

        return webClient.get()
                .uri(browseUrl)
                .header("Authorization", authHeader)
                .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                .header("X-ANYPNT-ENV-ID", env.getId())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .defaultIfEmpty(Collections.emptyList())
                .flatMap(messages -> {
                    // Extract current message IDs
                    Set<String> currentIds = new HashSet<>();
                    List<Map<String, Object>> lockInfos = new ArrayList<>();
                    for (Map<String, Object> msg : messages) {
                        Map<String, Object> headers = (Map<String, Object>) msg.getOrDefault("headers", Collections.emptyMap());
                        String msgId = (String) headers.get("messageId");
                        String lockId = (String) headers.get("lockId");
                        if (msgId != null) currentIds.add(msgId);
                        if (msgId != null && lockId != null) {
                            lockInfos.add(Map.of("messageId", msgId, "lockId", lockId));
                        }
                    }

                    // Compare with previous scrape
                    Set<String> previousIds = previousMessageIds.getOrDefault(queueKey, Collections.emptySet());
                    Set<String> staleIds = new HashSet<>(currentIds);
                    staleIds.retainAll(previousIds);  // intersection = stale

                    // Update gauge
                    AtomicLong gauge = staleCountGauges.computeIfAbsent(queueKey, k -> {
                        AtomicLong g = new AtomicLong(0);
                        io.micrometer.core.instrument.Gauge.builder("anypoint_mq_queue_stale_messages", g, AtomicLong::get)
                                .description("Number of stale messages detected in queue (same messages across scrapes)")
                                .tag("org", orgName)
                                .tag("queue_name", queueId)
                                .tag("environment", env.getName())
                                .tag("region", region)
                                .register(meterRegistry);
                        return g;
                    });
                    gauge.set(staleIds.size());

                    if (!staleIds.isEmpty()) {
                        log.warn("Detected {} stale messages in queue {} (env={}, region={})",
                                staleIds.size(), queueId, env.getName(), region);
                    }

                    // Store current IDs for next scrape
                    previousMessageIds.put(queueKey, currentIds);

                    // Release locks so messages stay available for real consumers
                    return releaseLocks(authHeader, env.getId(), region, queueId, lockInfos);
                })
                .onErrorResume(e -> {
                    log.debug("Stale check failed for queue {}: {}", queueId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Release message locks in batches of 5 (AMQ limit per DELETE call).
     */
    private Mono<Void> releaseLocks(String authHeader, String environmentId, String region,
                                     String queueId, List<Map<String, Object>> lockInfos) {
        if (lockInfos.isEmpty()) return Mono.empty();

        String url = String.format(
                "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages/locks",
                getBrokerUrl(region),
                anypointConfig.getOrganizationId(),
                environmentId,
                queueId);

        // Batch into groups of 5 (AMQ limit)
        return Flux.fromIterable(lockInfos)
                .buffer(5)
                .concatMap(batch -> webClient.method(org.springframework.http.HttpMethod.DELETE)
                        .uri(url)
                        .header("Authorization", authHeader)
                        .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                        .header("X-ANYPNT-ENV-ID", environmentId)
                        .bodyValue(batch)
                        .retrieve()
                        .bodyToMono(Void.class)
                        .onErrorResume(e -> {
                            log.debug("Failed to release locks for queue {}: {}", queueId, e.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }
}
