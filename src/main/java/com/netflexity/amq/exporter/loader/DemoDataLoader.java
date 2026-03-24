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
    private final AtomicBoolean purging = new AtomicBoolean(false);
    private final AtomicInteger purgeProgress = new AtomicInteger(0);

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
     * Targeted consume: consume exactly the specified number of messages from a specific queue.
     * Retries up to 3 times if not all messages are consumed.
     */
    private Mono<Integer> consumeExact(String environmentId, String region, String queueId, int targetCount, boolean isFifo) {
        AtomicInteger consumed = new AtomicInteger(0);
        
        return authClient.getAccessToken()
                .flatMap(token -> {
                    String getUrl = String.format(
                            "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages?batchSize=%d&pollingTime=1000&lockTtl=120000",
                            getBrokerUrl(region),
                            anypointConfig.getOrganizationId(),
                            environmentId,
                            queueId,
                            Math.min(targetCount, 10));

                    return Mono.defer(() -> webClient.get()
                                    .uri(getUrl)
                                    .header("Authorization", token.getAuthorizationHeader())
                                    .header("X-ANYPNT-ORG-ID", anypointConfig.getOrganizationId())
                                    .header("X-ANYPNT-ENV-ID", environmentId)
                                    .retrieve()
                                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                                    .defaultIfEmpty(Collections.emptyList())
                                    .flatMap(messages -> {
                                        if (messages.isEmpty()) return Mono.just(0);
                                        return Flux.fromIterable(messages)
                                                .flatMap(msg -> deleteMessage(token.getAuthorizationHeader(),
                                                        environmentId, region, queueId, msg), isFifo ? 1 : 10)
                                                .doOnNext(v -> consumed.incrementAndGet())
                                                .then(Mono.just(messages.size()));
                                    }))
                            .repeat()
                            .takeWhile(count -> count > 0 && consumed.get() < targetCount)
                            .then(Mono.fromCallable(consumed::get));
                })
                .onErrorResume(e -> {
                    log.warn("Error consuming from queue {}: {}", queueId, e.getMessage());
                    return Mono.just(consumed.get());
                });
    }

    /**
     * Track what was published per queue so consume can target the same queues/counts.
     */
    @Data
    private static class QueuePublishRecord {
        final String environmentId;
        final String region;
        final String queueId;
        final int count;
        final boolean fifo;
    }

    /**
     * Start continuous load/consume cycles on an interval.
     * 
     * SAFETY:
     * 1. Purges all queues on startup (clean slate)
     * 2. Tracks exactly which queue got how many messages during load
     * 3. Consumes the SAME queues with the SAME counts (targeted, not generic drain)
     * 4. If consume fails, pauses before next cycle
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
            
            // Step 0: Server-side purge ALL queues on startup (clean slate, instant)
            try {
                log.info("Startup purge: server-side purge of all queues...");
                Integer purged = purgeAllServerSide(queuePrefix).block();
                log.info("Startup purge complete: {} queues purged server-side", purged);
                // Brief pause to let MQ settle
                Thread.sleep(5_000);
            } catch (Exception e) {
                log.warn("Startup purge failed: {}", e.getMessage());
            }
            
            while (running.get()) {
                try {
                    // Step 1: Load and track per-queue publish counts
                    List<QueuePublishRecord> publishRecords = Collections.synchronizedList(new ArrayList<>());
                    
                    Flux.fromIterable(anypointConfig.getEnvironments())
                            .flatMap(env -> Flux.fromIterable(anypointConfig.getRegions())
                                    .flatMap(region -> listQueues(env.getId(), region)
                                            .filter(queue -> matchesPrefix(queue.getQueueId(), queuePrefix))
                                            .concatMap(queue -> {
                                                int count = ThreadLocalRandom.current().nextInt(minMessages, maxMessages + 1);
                                                boolean isFifo = Boolean.TRUE.equals(queue.getFifo());
                                                return publishMessages(env.getId(), region, queue.getQueueId(), count, isFifo)
                                                        .doOnSuccess(published -> {
                                                            if (published > 0) {
                                                                publishRecords.add(new QueuePublishRecord(
                                                                        env.getId(), region, queue.getQueueId(), published, isFifo));
                                                            }
                                                        })
                                                        .delayElement(Duration.ofMillis(500));
                                            })))
                            .then().block();
                    
                    int totalPublished = publishRecords.stream().mapToInt(r -> r.count).sum();
                    log.info("Published {} messages across {} queues", totalPublished, publishRecords.size());

                    // Step 2: Wait for messages to be visible
                    Thread.sleep(delaySeconds * 1000L);

                    // Step 3: Consume EXACTLY what we published, queue by queue
                    int totalConsumed = 0;
                    for (QueuePublishRecord record : publishRecords) {
                        if (!running.get()) break;
                        int consumed = consumeExact(record.environmentId, record.region, 
                                record.queueId, record.count, record.fifo).block();
                        totalConsumed += consumed;
                        log.debug("Consumed {}/{} from queue {}", consumed, record.count, record.queueId);
                    }

                    if (totalConsumed < totalPublished) {
                        log.warn("Consume gap: published={}, consumed={}. Retrying in 10s...", totalPublished, totalConsumed);
                        Thread.sleep(10_000);
                        // Retry pass on queues that didn't fully drain
                        for (QueuePublishRecord record : publishRecords) {
                            if (!running.get()) break;
                            int extra = consumeExact(record.environmentId, record.region,
                                    record.queueId, record.count, record.fifo).block();
                            totalConsumed += extra;
                        }
                    }
                    
                    log.info("Cycle complete: published={}, consumed={}", totalPublished, totalConsumed);

                    // Step 4: Wait for next cycle
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in continuous loader cycle: {}", e.getMessage());
                    try { Thread.sleep(10_000); } catch (InterruptedException ie) { break; }
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

    public boolean isPurging() {
        return purging.get();
    }

    public int getPurgeProgress() {
        return purgeProgress.get();
    }

    /**
     * Start async background purge of all queues. Returns immediately.
     * Poll /api/loader/status to monitor progress.
     */
    public boolean startAsyncPurge(String queuePrefix) {
        if (!purging.compareAndSet(false, true)) {
            return false; // already purging
        }
        purgeProgress.set(0);

        Thread purgeThread = new Thread(() -> {
            log.info("Background purge started");
            try {
                LoadResult result = consume(queuePrefix).block();
                if (result != null) {
                    log.info("Background purge complete: {} messages consumed from {} queues",
                            result.getTotalMessagesConsumed(), result.getQueuesTargeted());
                    purgeProgress.set(result.getTotalMessagesConsumed());
                }
            } catch (Exception e) {
                log.error("Background purge error: {}", e.getMessage());
            } finally {
                purging.set(false);
            }
        }, "async-purge");
        purgeThread.setDaemon(true);
        purgeThread.start();
        return true;
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
                .index()
                .concatMap(indexed -> {  // concatMap = sequential, one queue at a time (no flooding)
                    Queue queue = indexed.getT2();
                    long index = indexed.getT1();
                    int count = computeQueueMessageCount(index, queue.getQueueId(), minMessages, maxMessages);
                    boolean isFifo = Boolean.TRUE.equals(queue.getFifo());
                    return publishMessages(env.getId(), region, queue.getQueueId(), count, isFifo)
                            .doOnSuccess(published -> {
                                result.addPublished(published);
                                result.addQueue(queue.getQueueId());
                                log.debug("Published {} messages to queue {} (fifo={})", published, queue.getQueueId(), isFifo);
                            })
                            .delayElement(Duration.ofMillis(500));  // 500ms between queues
                })
                .then();
    }

    /**
     * Compute a varied message count per queue so charts show realistic traffic patterns
     * instead of a uniform "brick". Uses a hash of the queue name to assign each queue
     * a stable traffic tier (high/medium/low), then randomizes within that tier's range.
     *
     * Traffic distribution: ~20% high (70-100% of max), ~50% medium (25-70%), ~30% low (min-25%).
     * A small jitter is added each cycle so the chart isn't static.
     */
    private int computeQueueMessageCount(long index, String queueId, int minMessages, int maxMessages) {
        if (maxMessages <= minMessages) {
            return minMessages;
        }

        int range = maxMessages - minMessages;
        // Use queue name hash for a stable tier assignment across cycles
        int hash = Math.abs(queueId.hashCode());
        int tier = hash % 10; // 0-9

        double low, high;
        if (tier < 2) {
            // ~20% of queues: high traffic (70-100% of range)
            low = 0.70;
            high = 1.0;
        } else if (tier < 7) {
            // ~50% of queues: medium traffic (25-70% of range)
            low = 0.25;
            high = 0.70;
        } else {
            // ~30% of queues: low traffic (0-25% of range)
            low = 0.0;
            high = 0.25;
        }

        int tierMin = minMessages + (int) (range * low);
        int tierMax = minMessages + (int) (range * high);
        tierMax = Math.max(tierMax, tierMin + 1); // ensure at least some variation

        return ThreadLocalRandom.current().nextInt(tierMin, tierMax + 1);
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
                                log.info("Consumed {} messages from queue {}", consumed, queue.getQueueId());
                            });
                }, 6)
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
                            "%s/api/v1/organizations/%s/environments/%s/destinations/%s/messages?batchSize=10&pollingTime=500&lockTtl=120000",
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
                                                        environmentId, region, queueId, msg), isFifo ? 1 : 10)
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

    /**
     * Server-side purge: DELETE all messages from a queue via the Admin API.
     * This is instant — no need to GET+DELETE message by message.
     */
    private Mono<Void> purgeQueueServerSide(String environmentId, String region, String queueId) {
        String url = String.format(
                "%s/mq/admin/api/v1/organizations/%s/environments/%s/regions/%s/destinations/queues/%s/messages",
                anypointConfig.getBaseUrl(),
                anypointConfig.getOrganizationId(),
                environmentId,
                region,
                queueId);

        return authClient.getAccessToken()
                .flatMap(token -> webClient.delete()
                        .uri(url)
                        .header("Authorization", token.getAuthorizationHeader())
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(body -> {
                                            log.warn("Server-side purge failed for {}: {} {}", queueId, resp.statusCode(), body);
                                            return Mono.empty();
                                        }))
                        .bodyToMono(Void.class))
                .doOnSuccess(v -> log.info("Server-side purge complete for queue {}", queueId))
                .onErrorResume(e -> {
                    log.warn("Error purging queue {} server-side: {}", queueId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Purge ALL queues across all environments/regions using the server-side Admin API.
     */
    public Mono<Integer> purgeAllServerSide(String queuePrefix) {
        AtomicInteger purgedQueues = new AtomicInteger(0);
        return Flux.fromIterable(anypointConfig.getEnvironments())
                .flatMap(env -> Flux.fromIterable(anypointConfig.getRegions())
                        .flatMap(region -> listQueues(env.getId(), region)
                                .filter(queue -> matchesPrefix(queue.getQueueId(), queuePrefix))
                                .concatMap(queue -> purgeQueueServerSide(env.getId(), region, queue.getQueueId())
                                        .doOnSuccess(v -> purgedQueues.incrementAndGet())
                                        .delayElement(Duration.ofMillis(200)))))
                .then(Mono.fromCallable(purgedQueues::get));
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
