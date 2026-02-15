package com.netflexity.amq.exporter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Anypoint MQ Prometheus Exporter.
 * 
 * This Spring Boot application polls MuleSoft Anypoint MQ Stats API
 * and exposes queue and exchange metrics in Prometheus format.
 * 
 * Features:
 * - Prometheus metrics at /actuator/prometheus
 * - Health checks at /actuator/health
 * - Scheduled metrics collection
 * - Multi-environment and multi-region support
 * - Authentication via username/password or Connected App
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@Slf4j
public class Application {

    public static void main(String[] args) {
        log.info("Starting Anypoint MQ Prometheus Exporter...");
        SpringApplication.run(Application.class, args);
        log.info("Anypoint MQ Prometheus Exporter started successfully!");
    }
}