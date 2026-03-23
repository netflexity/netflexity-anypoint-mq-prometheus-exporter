package com.netflexity.amq.exporter.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST API for the Demo Data Loader.
 * 
 * Equivalent to MuleSoft init:load + init:consume flows, but HTTP-triggered.
 * Use this to populate queues with demo traffic so dashboards look alive.
 * 
 * Endpoints:
 *   POST /api/loader/load      — publish random messages to queues
 *   POST /api/loader/consume   — purge (consume+ack) all messages from queues
 *   POST /api/loader/cycle     — load, wait, consume (full traffic spike)
 *   POST /api/loader/start     — start continuous load/consume on interval
 *   POST /api/loader/stop      — stop continuous loader
 *   GET  /api/loader/status    — check if continuous loader is running
 */
@RestController
@RequestMapping("/api/loader")
@Slf4j
public class LoaderController {

    private final DemoDataLoader loader;

    public LoaderController(DemoDataLoader loader) {
        this.loader = loader;
    }

    /**
     * Publish random messages to all matching queues.
     * 
     * @param queuePrefix Only target queues with this prefix (null = all)
     * @param minMessages Min messages per queue (default 1)
     * @param maxMessages Max messages per queue (default 2)
     */
    @PostMapping("/load")
    public Mono<ResponseEntity<DemoDataLoader.LoadResult>> load(
            @RequestParam(required = false) String queuePrefix,
            @RequestParam(defaultValue = "1") int minMessages,
            @RequestParam(defaultValue = "2") int maxMessages) {
        
        log.info("Load requested: prefix={}, range={}-{}", queuePrefix, minMessages, maxMessages);
        return loader.load(queuePrefix, minMessages, maxMessages)
                .map(ResponseEntity::ok);
    }

    /**
     * Consume (purge) all messages from matching queues.
     * 
     * @param queuePrefix Only target queues with this prefix (null = all)
     */
    @PostMapping("/consume")
    public Mono<ResponseEntity<DemoDataLoader.LoadResult>> consume(
            @RequestParam(required = false) String queuePrefix) {
        
        log.info("Consume requested: prefix={}", queuePrefix);
        return loader.consume(queuePrefix)
                .map(ResponseEntity::ok);
    }

    /**
     * Full cycle: load → delay → consume.
     * Creates a visible traffic spike on dashboards.
     *
     * @param queuePrefix Only target queues with this prefix (null = all)
     * @param minMessages Min messages per queue (default 1)
     * @param maxMessages Max messages per queue (default 2)
     * @param delaySeconds Seconds between load and consume (default 30)
     */
    @PostMapping("/cycle")
    public Mono<ResponseEntity<DemoDataLoader.LoadResult>> cycle(
            @RequestParam(required = false) String queuePrefix,
            @RequestParam(defaultValue = "1") int minMessages,
            @RequestParam(defaultValue = "2") int maxMessages,
            @RequestParam(defaultValue = "30") int delaySeconds) {
        
        log.info("Cycle requested: prefix={}, range={}-{}, delay={}s", queuePrefix, minMessages, maxMessages, delaySeconds);
        return loader.cycle(queuePrefix, minMessages, maxMessages, delaySeconds)
                .map(ResponseEntity::ok);
    }

    /**
     * Start continuous load/consume cycles.
     *
     * @param queuePrefix Only target queues with this prefix (null = all)
     * @param minMessages Min messages per queue per cycle (default 1)
     * @param maxMessages Max messages per queue per cycle (default 2)
     * @param delaySeconds Delay between load and consume within each cycle (default 30)
     * @param intervalSeconds Interval between cycles (default 600 = 10 minutes)
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(required = false) String queuePrefix,
            @RequestParam(defaultValue = "1") int minMessages,
            @RequestParam(defaultValue = "2") int maxMessages,
            @RequestParam(defaultValue = "30") int delaySeconds,
            @RequestParam(defaultValue = "600") int intervalSeconds) {
        
        boolean started = loader.startContinuous(queuePrefix, minMessages, maxMessages, delaySeconds, intervalSeconds);
        
        if (started) {
            log.info("Continuous loader started: prefix={}, range={}-{}, delay={}s, interval={}s",
                    queuePrefix, minMessages, maxMessages, delaySeconds, intervalSeconds);
            return ResponseEntity.ok(Map.of(
                    "status", "started",
                    "queuePrefix", queuePrefix != null ? queuePrefix : "*",
                    "messagesRange", minMessages + "-" + maxMessages,
                    "delaySeconds", delaySeconds,
                    "intervalSeconds", intervalSeconds));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "already_running",
                    "message", "Continuous loader is already running. Stop it first."));
        }
    }

    /**
     * Stop the continuous loader.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop() {
        boolean stopped = loader.stop();
        if (stopped) {
            return ResponseEntity.ok(Map.of("status", "stopped"));
        } else {
            return ResponseEntity.ok(Map.of("status", "not_running"));
        }
    }

    /**
     * Check if the continuous loader is running.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "running", loader.isRunning()));
    }

    /**
     * Emergency: Stop the loader AND purge all queues in one call.
     */
    @PostMapping("/stop-and-purge")
    public Mono<ResponseEntity<Map<String, Object>>> stopAndPurge(
            @RequestParam(required = false) String queuePrefix) {
        loader.stop();
        log.warn("Emergency stop-and-purge requested");
        return loader.consume(queuePrefix)
                .map(result -> ResponseEntity.ok(Map.of(
                        "loaderStopped", true,
                        "messagesConsumed", result.getTotalMessagesConsumed(),
                        "queuesTargeted", result.getQueuesTargeted())));
    }

    /**
     * Debug: Try publishing a single message to a specific queue and return the raw result/error.
     */
    @PostMapping("/test")
    public Mono<ResponseEntity<Map<String, Object>>> test(
            @RequestParam(defaultValue = "nfx-mq-loader-q") String queue,
            @RequestParam(defaultValue = "Sandbox") String environment) {
        return loader.testPublish(queue, environment)
                .map(result -> ResponseEntity.ok(result));
    }
}
