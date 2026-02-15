package com.netflexity.amq.exporter.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration for the Prometheus exporter application.
 * 
 * This configuration provides:
 * - WebClient bean configured for Anypoint API calls
 * - Micrometer metrics registry setup
 * - Custom metrics initialization
 */
@Configuration
@Slf4j
public class ExporterConfig {

    /**
     * Create a WebClient configured for calling Anypoint APIs
     */
    @Bean
    public WebClient webClient(AnypointConfig anypointConfig) {
        log.info("Configuring WebClient with timeouts: connect={}s, read={}s",
                anypointConfig.getHttp().getConnectTimeoutSeconds(),
                anypointConfig.getHttp().getReadTimeoutSeconds());

        // Create connection provider with custom settings
        ConnectionProvider connectionProvider = ConnectionProvider.builder("anypoint-mq-exporter")
                .maxConnections(20)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(60))
                .build();

        // Create HTTP client with timeouts and connection provider
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .connectTimeout(Duration.ofSeconds(anypointConfig.getHttp().getConnectTimeoutSeconds()))
                .responseTimeout(Duration.ofSeconds(anypointConfig.getHttp().getReadTimeoutSeconds()));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024)) // 1MB buffer
                .build();
    }

    /**
     * Create and register custom metrics for the exporter
     */
    @Bean
    public ExporterMetrics exporterMetrics(MeterRegistry meterRegistry) {
        log.info("Initializing custom metrics");
        return new ExporterMetrics(meterRegistry);
    }

    /**
     * Holder class for custom metrics used by the exporter
     */
    public static class ExporterMetrics {
        private final Timer scrapeTimer;
        private final Counter scrapeErrorCounter;
        private final AtomicLong lastScrapeTimestamp = new AtomicLong(0);

        public ExporterMetrics(MeterRegistry meterRegistry) {
            this.scrapeTimer = Timer.builder("anypoint_mq_scrape_duration_seconds")
                    .description("Time spent scraping Anypoint MQ metrics")
                    .register(meterRegistry);

            this.scrapeErrorCounter = Counter.builder("anypoint_mq_scrape_errors_total")
                    .description("Total number of scrape errors")
                    .register(meterRegistry);

            // Register gauge for last scrape timestamp
            Gauge.builder("anypoint_mq_last_scrape_timestamp_seconds")
                    .description("Unix timestamp of the last successful scrape")
                    .register(meterRegistry, this, metrics -> metrics.lastScrapeTimestamp.get());

            log.info("Custom metrics registered: scrape_duration_seconds, scrape_errors_total, last_scrape_timestamp_seconds");
        }

        public Timer.Sample startScrapeTimer() {
            return Timer.start();
        }

        public void recordScrapeTime(Timer.Sample sample) {
            sample.stop(scrapeTimer);
            lastScrapeTimestamp.set(System.currentTimeMillis() / 1000L);
        }

        public void incrementErrorCounter() {
            scrapeErrorCounter.increment();
        }

        public void incrementErrorCounter(String cause) {
            Counter.builder("anypoint_mq_scrape_errors_total")
                    .tag("cause", cause)
                    .register(scrapeTimer.getId().getMeterRegistry())
                    .increment();
        }
    }
}