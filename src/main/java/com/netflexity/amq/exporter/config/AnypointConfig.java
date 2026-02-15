package com.netflexity.amq.exporter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Configuration properties for Anypoint MQ connection and scraping settings.
 * 
 * This configuration supports:
 * - Multiple authentication methods (username/password or Connected App)
 * - Multiple environments and regions
 * - Configurable scraping intervals
 * - HTTP client settings
 */
@Configuration
@ConfigurationProperties(prefix = "anypoint")
@Data
@Validated
public class AnypointConfig {

    /**
     * Base URL for Anypoint Platform API calls
     */
    @NotEmpty
    private String baseUrl = "https://anypoint.mulesoft.com";

    /**
     * Authentication configuration
     */
    @Valid
    @NotNull
    private Auth auth = new Auth();

    /**
     * Organization ID for the Anypoint account
     */
    @NotEmpty
    private String organizationId;

    /**
     * List of environments to monitor
     */
    @Valid
    @NotEmpty
    private List<Environment> environments;

    /**
     * List of regions to monitor (e.g., us-east-1, us-west-2)
     */
    @NotEmpty
    private List<String> regions;

    /**
     * Scraping configuration
     */
    @Valid
    @NotNull
    private Scrape scrape = new Scrape();

    /**
     * HTTP client configuration
     */
    @Valid
    @NotNull
    private Http http = new Http();

    @Data
    public static class Auth {
        /**
         * Username for username/password authentication
         */
        private String username;

        /**
         * Password for username/password authentication
         */
        private String password;

        /**
         * Client ID for Connected App authentication
         */
        private String clientId;

        /**
         * Client Secret for Connected App authentication
         */
        private String clientSecret;

        /**
         * Check if username/password authentication is configured
         */
        public boolean isUsernamePasswordAuth() {
            return username != null && !username.trim().isEmpty() &&
                   password != null && !password.trim().isEmpty();
        }

        /**
         * Check if Connected App authentication is configured
         */
        public boolean isConnectedAppAuth() {
            return clientId != null && !clientId.trim().isEmpty() &&
                   clientSecret != null && !clientSecret.trim().isEmpty();
        }

        /**
         * Check if any authentication method is configured
         */
        public boolean hasValidAuth() {
            return isUsernamePasswordAuth() || isConnectedAppAuth();
        }
    }

    @Data
    public static class Environment {
        /**
         * Environment ID (UUID)
         */
        @NotEmpty
        private String id;

        /**
         * Environment name for labeling
         */
        @NotEmpty
        private String name;
    }

    @Data
    public static class Scrape {
        /**
         * Interval between scrapes in seconds
         */
        @Min(10)
        private int intervalSeconds = 60;

        /**
         * Period for stats query in seconds (how far back to look)
         */
        @Min(300)
        private int periodSeconds = 600;

        /**
         * Whether scraping is enabled
         */
        private boolean enabled = true;
    }

    @Data
    public static class Http {
        /**
         * Connection timeout in seconds
         */
        @Min(1)
        private int connectTimeoutSeconds = 30;

        /**
         * Read timeout in seconds
         */
        @Min(1)
        private int readTimeoutSeconds = 60;

        /**
         * Maximum number of retries for failed requests
         */
        @Min(0)
        private int maxRetries = 3;
    }
}