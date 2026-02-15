package com.netflexity.amq.exporter.config;

import com.netflexity.amq.exporter.monitor.MonitorDefinition;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the monitoring framework.
 * 
 * Configures monitor definitions, notification channels, and
 * evaluation settings for the advanced monitoring system.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Configuration
@ConfigurationProperties(prefix = "anypoint.monitors")
@Data
@Validated
public class MonitorConfig {

    /**
     * Whether monitoring is enabled
     */
    private boolean enabled = false;

    /**
     * Evaluation interval in seconds
     */
    @Min(10)
    private int evaluationIntervalSeconds = 60;

    /**
     * Default settings for monitors
     */
    @Valid
    @NotNull
    private Defaults defaults = new Defaults();

    /**
     * Monitor definitions
     */
    @Valid
    private List<MonitorDefinition> definitions = List.of();

    /**
     * Notification configuration
     */
    @Valid
    @NotNull
    private Notifications notifications = new Notifications();

    @Data
    public static class Defaults {
        /**
         * Default cooldown period in minutes
         */
        @Min(0)
        private int cooldownMinutes = 15;

        /**
         * Default evaluation window in minutes
         */
        @Min(1)
        private int evaluationWindowMinutes = 5;
    }

    @Data
    public static class Notifications {
        /**
         * Notification channel configurations
         */
        @Valid
        private List<ChannelConfig> channels = List.of();
    }

    @Data
    public static class ChannelConfig {
        /**
         * Channel name (unique identifier)
         */
        @NotEmpty
        private String name;

        /**
         * Channel type (slack, pagerduty, email, teams, webhook)
         */
        @NotEmpty
        private String type;

        /**
         * Webhook URL (for slack, teams, webhook types)
         */
        private String webhookUrl;

        /**
         * PagerDuty routing key
         */
        private String routingKey;

        /**
         * Email recipient
         */
        private String to;

        /**
         * Email sender (from address)
         */
        private String from;

        /**
         * Webhook URL (alternative name for consistency)
         */
        private String url;

        /**
         * Custom headers for webhook channels
         */
        private Map<String, String> headers;

        /**
         * Whether this channel is enabled
         */
        private boolean enabled = true;
    }

    /**
     * Apply default values to monitor definitions
     */
    public void applyDefaults() {
        if (definitions != null) {
            definitions.forEach(monitor -> {
                if (monitor.getCooldownMinutes() <= 0) {
                    monitor.setCooldownMinutes(defaults.getCooldownMinutes());
                }
                if (monitor.getEvaluationWindowMinutes() <= 0) {
                    monitor.setEvaluationWindowMinutes(defaults.getEvaluationWindowMinutes());
                }
            });
        }
    }

    /**
     * Get enabled monitor definitions
     */
    public List<MonitorDefinition> getEnabledDefinitions() {
        if (definitions == null) {
            return List.of();
        }
        return definitions.stream()
                .filter(MonitorDefinition::isEnabled)
                .toList();
    }

    /**
     * Get enabled notification channels
     */
    public List<ChannelConfig> getEnabledChannels() {
        if (notifications == null || notifications.getChannels() == null) {
            return List.of();
        }
        return notifications.getChannels().stream()
                .filter(ChannelConfig::isEnabled)
                .toList();
    }

    /**
     * Find monitor definition by name
     */
    public MonitorDefinition findMonitorByName(String name) {
        if (definitions == null || name == null) {
            return null;
        }
        return definitions.stream()
                .filter(monitor -> name.equals(monitor.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find channel configuration by name
     */
    public ChannelConfig findChannelByName(String name) {
        if (notifications == null || notifications.getChannels() == null || name == null) {
            return null;
        }
        return notifications.getChannels().stream()
                .filter(channel -> name.equals(channel.getName()))
                .findFirst()
                .orElse(null);
    }
}