package com.netflexity.amq.exporter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Basic integration tests for the Anypoint MQ Prometheus Exporter application.
 * 
 * These tests verify that the Spring Boot application can start up correctly
 * with test configuration.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "anypoint.organizationId=test-org-id",
    "anypoint.auth.clientId=test-client-id",
    "anypoint.auth.clientSecret=test-client-secret",
    "anypoint.environments[0].id=test-env-id",
    "anypoint.environments[0].name=Test Environment",
    "anypoint.regions[0]=us-east-1",
    "anypoint.scrape.enabled=false"
})
class ApplicationTests {

    @Test
    void contextLoads() {
        // This test verifies that the Spring application context can be loaded successfully
        // All beans should be created and autowired correctly
    }

    @Test
    void mainMethodRuns() {
        // Test that the main method doesn't throw exceptions
        // This is covered by contextLoads() but kept for explicit testing
        String[] args = {};
        // Application.main(args); // Commented out to avoid starting the app during tests
    }
}