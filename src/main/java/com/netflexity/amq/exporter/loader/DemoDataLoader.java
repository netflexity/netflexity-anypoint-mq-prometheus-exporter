package com.netflexity.amq.exporter.loader;

import com.netflexity.anypoint.common.client.AnypointAuthClient;
import com.netflexity.anypoint.common.config.AnypointConfig;
import com.netflexity.anypoint.common.model.Queue;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo Data Loader — equivalent to MuleSoft init:load + init:consume flows.
 * 
 * Publishes random messages to queues, then optionally consumes (purges) them,
 * creating realistic traffic patterns for Grafana/Datadog/Prometheus dashboards.
 * 
 * Supports two modes:
 *   - One-shot: POST /api/loader/load  (and /consume, /cycle)
 *   - Continuous: POST /api/loader/start (runs load/consume cycles on interval)
 * 
 * Only targets queues matching the configured prefix filter (default: all queues).
 */
@Component
@Slf4j
public class DemoDataLoader {

    private final WebClient webClient;
    private final AnypointConfig anypointConfig;
    private final AnypointAuthClient authClient;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loaderThread;

    /** Broker API uses regional endpoints, not the main anypoint.mulesoft.com */
    private static final Map<String, String> BROKER_URLS = Map.of(
            "us-east-1", "https://mq-us-east-1.anypoint.mulesoft.com",
            "us-west-2", "https://mq-us-west-2.anypoint.mulesoft.com",
            "eu-west-1", "https://mq-eu-west-1.anypoint.mulesoft.com",
            "ap-southeast-1", "https://mq-ap-southeast-1.anypoint.mulesoft.com",
            "ap-southeast-2", "https://mq-ap-southeast-2.anypoint.mulesoft.com",
            "eu-central-1", "https://mq-eu-central-1.anypoint.mulesoft.com",
            "ca-central-1", "https://mq-ca-central-1.anypoint.mulesoft.com"
    );

    public DemoDataLoader(WebClient webClient, AnypointConfig anypointConfig, AnypointAuthClient authClient) {
        this.webClient = webClient;
        this.anypointConfig = anypointConfig;
        this.authClient = authClient;
    }

    private String getBrokerUrl(String region) {
        return BROKER_URLS.getOrDefault(region, "https://mq-us-east-1.anypoint.mulesoft.com");
    }

    /**
     * Publish random messages to all matching queues in all environments/regions.
     *
     * @param queuePrefix Only target queues starting with this prefix (null = all queues)
     * @param minMessages Minimum messages per queue
     * @param maxMessages Maximum messages per queue
     * @return Summary of what was published
     */
    public Mono<LoadResult> load(String queuePrefix, int minMessages, int maxMessages) {
        LoadResult result = new LoadResult();
        result.setOperation("load");
        result.setStartTime(Instant.now());

        return Flux.fromIterable(anypointConfig.getEnvironments())
                .flatMap(env -> Flux.fromIterable(anypointConfig.getRegions())
                        .flatMap(region -> loadEnvironmentRegion(env, region, queuePrefix, minMessages, maxMessages, result)))
                .then(Mono.fromCallable(() -> {
                    result.setEndTime(Instant.now());
                    log.info("Load complete: {} messages published to {} queues in {}ms",
                            result.getTotalMessagesPublished(), result.getQueuesTargeted(),
                            Duration.between(result.getStartTime(), result.getEndTime()).toMillis());
                    return result;
                }));
    }

    /**
     * Consume (purge) all messages from matching queues — drains them to 0.
     * Non-FIFO queues: get + delete in parallel batches of 10.
     * FIFO queues: get + delete sequentially (AMQ requires ordering).
     */
    public Mono<LoadResult> consume(String queuePrefix) {
        LoadResult result = new LoadResult();
        result.setOperation("consume");
        result.setStartTime(Instant.now());

        return Flux.fromIterable(anypointConfig.getEnvironments())
                .flatMap(env -> Flux.fromIterable(anypointConfig.getRegions())
                        .flatMap(region -> consumeEnvironmentRegion(env, region, queuePrefix, result)))
                .then(Mono.fromCallable(() -> {
                    result.setEndTime(Instant.now());
                    log.info("Consume complete: {} messages consumed from {} queues in {}ms",
                            result.getTotalMessagesConsumed(), result.getQueuesTargeted(),
                            Duration.between(result.getStartTime(), result.getEndTime()).toMillis());
                    return result;
                }));
    }

    /**
     * Full cycle: load then consume with a delay between, creating a realistic traffic spike.
     */
    public Mono<LoadResult> cycle(String queuePrefix, int minMessages, int maxMessages, int delaySeconds) {
        return load(queuePrefix, minMessages, maxMessages)
                .delayElement(Duration.ofSeconds(delaySeconds))
                .flatMap(loadResult -> consume(queuePrefix)
                        .map(consumeResult -> {
                            loadResult.addConsumed(consumeResult.getTotalMessagesConsumed());
                            loadResult.setOperation("cycle");
                            loadResult.setEndTime(consumeResult.getEndTime());
                            return loadResult;
                        }));
    }

    /**
     * Start continuous load/consume cycles on an interval.
     */
    public boolean startContinuous(String queuePrefix, int minMessages, int maxMessages, 
                                    int delaySeconds, int intervalSeconds) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Continuous loader already running");
            return false;
        }

        loaderThread = new Thread(() -> {
            log.info("Starting continuous demo loader: interval={}s, delay={}s, msgs={}-{}", 
                    intervalSeconds, delaySeconds, minMessages, maxMessages);
            while (running.get()) {
                try {
                    cycle(queuePrefix, minMessages, maxMessages, delaySeconds).block();
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in continuous loader cycle: {}", e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
            log.info("Continuous demo loader stopped");
        }, "demo-data-loader");
        loaderThread.setDaemon(true);
        loaderThread.start();
        return true;
    }

    /**
     * Stop continuous loader.
     */
    public boolean stop() {
        if (running.compareAndSet(true, false)) {
            if (loaderThread != null) {
                loaderThread.interrupt();
            }
            return true;
        }
        return false;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Debug method: try publishing a single message and return the raw response or error.
     */
    public Mono<Map<String, Object>> testPublish(String queueId, String environmentName) {
        // Find the environment
        AnypointConfig.Environment env = anypointConfig.getEnvironments().stream()
                .filter(e -> e.getName().equalsIgnoreCase(environmentName))
                .findFirst()
                .orElse(null);

        if (env == null) {
            return Mono.just(Map.of("error", "Environment not found: " + environmentName,
                    "availableEnvironments", anypointConfig.getEnvironments().stream()
                            .map(AnypointConfig.Environment::getName).toList()));
        }

        String region = anypointConfig.getRegions().isEmpty() ? "us-east-1" : anypointConfig.getRegions().get(0);
        String brokerUrl = getBrokerUrl(region);

        List<Map<String, Object>> messages = List.of(Map.of(
                "headers", Map.of("messageId", UUID.randomUUID().toString(), "ttl", 120000),
                "properties", Map.of("source", "test"),
                "body", "{\"test\":true}"
        ));

        String url = String.format(
                "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages",
                brokerUrl, anypointConfig.getOrganizationId(), env.getId(), queueId);

        return authClient.getAccessToken()
                .flatMap(token -> webClient.put()
                        .uri(url)
                        .header("Authorization", token.getAuthorizationHeader())
                        .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                        .header("X-ANYPNT-ENV-ID", env.getId())
                        .bodyValue(messages)
                        .exchangeToMono(response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    Map<String, Object> result = new LinkedHashMap<>();
                                    result.put("url", url);
                                    result.put("brokerUrl", brokerUrl);
                                    result.put("region", region);
                                    result.put("environmentId", env.getId());
                                    result.put("environmentName", env.getName());
                                    result.put("queueId", queueId);
                                    result.put("statusCode", response.statusCode().value());
                                    result.put("responseBody", body);
                                    result.put("success", response.statusCode().is2xxSuccessful());
                                    return result;
                                })))
                .onErrorResume(e -> Mono.just(Map.of(
                        "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "url", url,
                        "brokerUrl", brokerUrl)));
    }

    // --- Internal methods ---

    private Mono<Void> loadEnvironmentRegion(AnypointConfig.Environment env, String region,
                                              String queuePrefix, int minMessages, int maxMessages,
                                              LoadResult result) {
        return listQueues(env.getId(), region)
                .filter(queue -> matchesPrefix(queue.getQueueId(), queuePrefix))
                .flatMap(queue -> {
                    int count = ThreadLocalRandom.current().nextInt(minMessages, maxMessages + 1);
                    boolean isFifo = Boolean.TRUE.equals(queue.getFifo());
                    return publishMessages(env.getId(), region, queue.getQueueId(), count, isFifo)
                            .doOnSuccess(published -> {
                                result.addPublished(published);
                                result.addQueue(queue.getQueueId());
                                log.debug("Published {} messages to queue {} (fifo={})", published, queue.getQueueId(), isFifo);
                            });
                }, 4)  // concurrency limit
                .then();
    }

    private Mono<Void> consumeEnvironmentRegion(AnypointConfig.Environment env, String region,
                                                  String queuePrefix, LoadResult result) {
        return listQueues(env.getId(), region)
                .filter(queue -> matchesPrefix(queue.getQueueId(), queuePrefix))
                .flatMap(queue -> {
                    boolean isFifo = Boolean.TRUE.equals(queue.getFifo());
                    return purgeQueue(env.getId(), region, queue.getQueueId(), isFifo)
                            .doOnSuccess(consumed -> {
                                result.addConsumed(consumed);
                                result.addQueue(queue.getQueueId());
                                log.debug("Consumed {} messages from queue {}", consumed, queue.getQueueId());
                            });
                }, 4)
                .then();
    }

    private Flux<Queue> listQueues(String environmentId, String region) {
        String url = String.format("%s/mq/admin/api/v1/organizations/%s/environments/%s/regions/%s/destinations",
                anypointConfig.getBaseUrl(),
                anypointConfig.getOrganizationId(),
                environmentId,
                region);

        return authClient.getAccessToken()
                .flatMapMany(token -> webClient.get()
                        .uri(url)
                        .header("Authorization", token.getAuthorizationHeader())
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<List<Queue>>() {})
                        .flatMapMany(Flux::fromIterable)
                        .filter(q -> "queue".equalsIgnoreCase(q.getType())));
    }

    /**
     * Publish N messages to a queue via the Anypoint MQ Broker API.
     * PUT /mq/broker/api/v1/organizations/{orgId}/environments/{envId}/regions/{region}/destinations/{queueId}/messages
     */
    private Mono<Integer> publishMessages(String environmentId, String region, String queueId, 
                                           int count, boolean isFifo) {
        AtomicInteger published = new AtomicInteger(0);

        return authClient.getAccessToken()
                .flatMap(token -> {
                    // Build all messages in a single batch (AMQ supports up to 10 per PUT)
                    return Flux.range(1, count)
                            .buffer(10)  // AMQ batch limit
                            .concatMap(batch -> {
                                List<Map<String, Object>> messages = new ArrayList<>();
                                for (int seq : batch) {
                                    Map<String, Object> headers = new LinkedHashMap<>();
                                    headers.put("messageId", UUID.randomUUID().toString());
                                    headers.put("ttl", 120000);
                                    if (!isFifo) {
                                        headers.put("deliveryDelay", 100 * seq);
                                    }

                                    Map<String, Object> properties = new LinkedHashMap<>();
                                    properties.put("loadSequence", String.valueOf(seq));
                                    properties.put("retry", false);
                                    properties.put("source", "demo-loader");

                                    Map<String, Object> msg = new LinkedHashMap<>();
                                    msg.put("headers", headers);
                                    msg.put("properties", properties);
                                    msg.put("body", String.format("{\"date\":\"%s\",\"sequence\":%d}", Instant.now(), seq));
                                    messages.add(msg);
                                }

                                String url = String.format(
                                        "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages",
                                        getBrokerUrl(region),
                                        anypointConfig.getOrganizationId(),
                                        environmentId,
                                        queueId);

                                return webClient.put()
                                        .uri(url)
                                        .header("Authorization", token.getAuthorizationHeader())
                                        .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                                        .header("X-ANYPNT-ENV-ID", environmentId)
                                        .bodyValue(messages)
                                        .retrieve()
                                        .onStatus(HttpStatusCode::isError, resp -> 
                                                resp.bodyToMono(String.class)
                                                        .flatMap(body -> Mono.error(new RuntimeException(
                                                                "Publish failed: " + resp.statusCode() + " " + body))))
                                        .bodyToMono(String.class)
                                        .doOnSuccess(resp -> published.addAndGet(batch.size()))
                                        .retryWhen(Retry.backoff(2, Duration.ofMillis(500)))
                                        .onErrorResume(e -> {
                                            log.warn("Failed to publish batch to {}: {}", queueId, e.getMessage());
                                            return Mono.empty();
                                        });
                            })
                            .then(Mono.fromCallable(published::get));
                });
    }

    /**
     * Purge a queue by consuming all messages, then acknowledging (deleting) them.
     * GET messages -> DELETE each message by lockId.
     * Drains until empty (no fixed round limit). Safety cap at 10,000 messages.
     */
    private Mono<Integer> purgeQueue(String environmentId, String region, String queueId, boolean isFifo) {
        AtomicInteger consumed = new AtomicInteger(0);
        int maxMessages = 10_000; // safety cap to prevent infinite loops

        return authClient.getAccessToken()
                .flatMap(token -> {
                    String getUrl = String.format(
                            "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages?batchSize=10&pollingTime=1000&lockTtl=30000",
                            getBrokerUrl(region),
                            anypointConfig.getOrganizationId(),
                            environmentId,
                            queueId);

                    // Drain until empty or safety cap reached
                    return Mono.defer(() -> webClient.get()
                                    .uri(getUrl)
                                    .header("Authorization", token.getAuthorizationHeader())
                                    .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                                    .header("X-ANYPNT-ENV-ID", environmentId)
                                    .retrieve()
                                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                                    .defaultIfEmpty(Collections.emptyList())
                                    .flatMap(messages -> {
                                        if (messages.isEmpty()) {
                                            return Mono.just(0); // signal done
                                        }
                                        return Flux.fromIterable(messages)
                                                .flatMap(msg -> deleteMessage(token.getAuthorizationHeader(),
                                                        environmentId, region, queueId, msg), isFifo ? 1 : 5)
                                                .doOnNext(v -> consumed.incrementAndGet())
                                                .then(Mono.just(messages.size()));
                                    }))
                            .repeat()
                            .takeWhile(count -> count > 0 && consumed.get() < maxMessages)
                            .then(Mono.fromCallable(() -> {
                                if (consumed.get() >= maxMessages) {
                                    log.warn("Safety cap reached for queue {} — consumed {} messages", queueId, consumed.get());
                                }
                                return consumed.get();
                            }));
                })
                .onErrorResume(e -> {
                    log.warn("Error purging queue {}: {}", queueId, e.getMessage());
                    return Mono.just(consumed.get());
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> deleteMessage(String authHeader, String environmentId, String region, 
                                      String queueId, Map<String, Object> message) {
        Map<String, Object> headers = (Map<String, Object>) message.getOrDefault("headers", Collections.emptyMap());
        String messageId = (String) headers.get("messageId");
        String lockId = (String) headers.get("lockId");

        if (messageId == null || lockId == null) {
            return Mono.empty();
        }

        String url = String.format(
                "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages/%s",
                getBrokerUrl(region),
                anypointConfig.getOrganizationId(),
                environmentId,
                queueId,
                messageId);

        return webClient.delete()
                .uri(url)
                .header("Authorization", authHeader)
                .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                .header("X-ANYPNT-ENV-ID", environmentId)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.debug("Failed to delete message {} from {}: {}", messageId, queueId, e.getMessage());
                    return Mono.empty();
                });
    }

    private boolean matchesPrefix(String queueId, String prefix) {
        if (prefix == null || prefix.isBlank()) return true;
        return queueId != null && queueId.toLowerCase().startsWith(prefix.toLowerCase());
    }

    // --- Result DTO ---

    @Data
    public static class LoadResult {
        private String operation;
        private Instant startTime;
        private Instant endTime;
        private final AtomicInteger totalMessagesPublished = new AtomicInteger(0);
        private final AtomicInteger totalMessagesConsumed = new AtomicInteger(0);
        private final AtomicInteger queuesTargeted = new AtomicInteger(0);
        private final List<String> queues = Collections.synchronizedList(new ArrayList<>());

        public void addPublished(int count) { totalMessagesPublished.addAndGet(count); }
        public void addConsumed(int count) { totalMessagesConsumed.addAndGet(count); }
        public void addQueue(String queueId) {
            if (!queues.contains(queueId)) {
                queues.add(queueId);
                queuesTargeted.incrementAndGet();
            }
        }

        public int getTotalMessagesPublished() { return totalMessagesPublished.get(); }
        public int getTotalMessagesConsumed() { return totalMessagesConsumed.get(); }
        public int getQueuesTargeted() { return queuesTargeted.get(); }
    }
}
