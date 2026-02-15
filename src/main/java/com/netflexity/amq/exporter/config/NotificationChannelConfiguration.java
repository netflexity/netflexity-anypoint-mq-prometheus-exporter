package com.netflexity.amq.exporter.config;

import com.netflexity.amq.exporter.notification.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for notification channels.
 * 
 * Creates notification channel instances based on application configuration
 * and makes them available for dependency injection.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Configuration
@ConditionalOnProperty(prefix = "anypoint.monitors", name = "enabled", havingValue = "true")
@Slf4j
public class NotificationChannelConfiguration {

    private final MonitorConfig monitorConfig;
    private final JavaMailSender mailSender;

    public NotificationChannelConfiguration(MonitorConfig monitorConfig, 
                                           JavaMailSender mailSender) {
        this.monitorConfig = monitorConfig;
        this.mailSender = mailSender;
    }

    /**
     * Create notification channel instances from configuration
     */
    @Bean
    public List<NotificationChannel> notificationChannels() {
        List<NotificationChannel> channels = new ArrayList<>();

        for (MonitorConfig.ChannelConfig channelConfig : monitorConfig.getEnabledChannels()) {
            try {
                NotificationChannel channel = createChannel(channelConfig);
                if (channel != null) {
                    channels.add(channel);
                    log.info("Created {} notification channel: {}", channel.getType(), channel.getName());
                }
            } catch (Exception e) {
                log.error("Failed to create notification channel '{}' of type '{}': {}", 
                    channelConfig.getName(), channelConfig.getType(), e.getMessage(), e);
            }
        }

        log.info("Initialized {} notification channels", channels.size());
        return channels;
    }

    /**
     * Create a notification channel instance based on configuration
     */
    private NotificationChannel createChannel(MonitorConfig.ChannelConfig config) {
        return switch (config.getType().toLowerCase()) {
            case "slack" -> createSlackChannel(config);
            case "pagerduty" -> createPagerDutyChannel(config);
            case "email" -> createEmailChannel(config);
            case "teams" -> createTeamsChannel(config);
            case "webhook" -> createWebhookChannel(config);
            default -> {
                log.warn("Unknown notification channel type: {}", config.getType());
                yield null;
            }
        };
    }

    /**
     * Create Slack notification channel
     */
    private NotificationChannel createSlackChannel(MonitorConfig.ChannelConfig config) {
        if (config.getWebhookUrl() == null || config.getWebhookUrl().trim().isEmpty()) {
            log.warn("Slack channel '{}' missing webhook URL", config.getName());
            return null;
        }
        return new SlackNotificationChannel(config.getName(), config.getWebhookUrl());
    }

    /**
     * Create PagerDuty notification channel
     */
    private NotificationChannel createPagerDutyChannel(MonitorConfig.ChannelConfig config) {
        if (config.getRoutingKey() == null || config.getRoutingKey().trim().isEmpty()) {
            log.warn("PagerDuty channel '{}' missing routing key", config.getName());
            return null;
        }
        return new PagerDutyNotificationChannel(config.getName(), config.getRoutingKey());
    }

    /**
     * Create email notification channel
     */
    private NotificationChannel createEmailChannel(MonitorConfig.ChannelConfig config) {
        if (config.getTo() == null || config.getTo().trim().isEmpty()) {
            log.warn("Email channel '{}' missing recipient address", config.getName());
            return null;
        }

        String fromEmail = config.getFrom();
        if (fromEmail == null || fromEmail.trim().isEmpty()) {
            fromEmail = "noreply@netflexity.com"; // Default sender
        }

        return new EmailNotificationChannel(config.getName(), config.getTo(), fromEmail, mailSender);
    }

    /**
     * Create Microsoft Teams notification channel
     */
    private NotificationChannel createTeamsChannel(MonitorConfig.ChannelConfig config) {
        String webhookUrl = config.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            log.warn("Teams channel '{}' missing webhook URL", config.getName());
            return null;
        }
        return new TeamsNotificationChannel(config.getName(), webhookUrl);
    }

    /**
     * Create generic webhook notification channel
     */
    private NotificationChannel createWebhookChannel(MonitorConfig.ChannelConfig config) {
        String url = config.getUrl() != null ? config.getUrl() : config.getWebhookUrl();
        if (url == null || url.trim().isEmpty()) {
            log.warn("Webhook channel '{}' missing URL", config.getName());
            return null;
        }
        return new WebhookNotificationChannel(config.getName(), url, config.getHeaders());
    }
}