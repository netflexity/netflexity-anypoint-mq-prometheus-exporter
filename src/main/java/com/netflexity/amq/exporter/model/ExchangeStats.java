package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Represents statistics for an exchange from the Anypoint MQ Stats API.
 * 
 * Exchange statistics focus on message publishing and delivery patterns
 * in publish-subscribe scenarios.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeStats {

    /**
     * Exchange identifier
     */
    @JsonProperty("exchangeId")
    private String exchangeId;

    /**
     * Total number of messages published to the exchange during the period
     */
    @JsonProperty("messagesPublished")
    private Long messagesPublished = 0L;

    /**
     * Total number of messages delivered from the exchange to bound queues during the period
     */
    @JsonProperty("messagesDelivered")
    private Long messagesDelivered = 0L;

    /**
     * Average message size in bytes (if available)
     */
    @JsonProperty("averageMessageSize")
    private Double averageMessageSize;

    /**
     * Associated exchange metadata (set by the collector)
     */
    private Exchange exchange;

    /**
     * Timestamp when these stats were collected
     */
    private Long timestamp = System.currentTimeMillis();

    /**
     * Calculate message publishing rate (published per period)
     * This can be used to derive rate metrics in Prometheus
     *
     * @param periodSeconds the stats collection period in seconds
     * @return messages per second publishing rate
     */
    public Double getMessagesPublishedRate(int periodSeconds) {
        if (messagesPublished == null || periodSeconds <= 0) {
            return 0.0;
        }
        return messagesPublished.doubleValue() / periodSeconds;
    }

    /**
     * Calculate message delivery rate (delivered per period)
     *
     * @param periodSeconds the stats collection period in seconds
     * @return messages per second delivery rate
     */
    public Double getMessagesDeliveredRate(int periodSeconds) {
        if (messagesDelivered == null || periodSeconds <= 0) {
            return 0.0;
        }
        return messagesDelivered.doubleValue() / periodSeconds;
    }

    /**
     * Calculate delivery efficiency (delivered/published ratio)
     * This can indicate if messages are being successfully routed
     *
     * @return ratio of delivered to published messages, or 0.0 if no messages published
     */
    public Double getDeliveryEfficiency() {
        if (messagesPublished == null || messagesPublished == 0L) {
            return 0.0;
        }
        
        long delivered = messagesDelivered != null ? messagesDelivered : 0L;
        return delivered.doubleValue() / messagesPublished.doubleValue();
    }

    /**
     * Get a safe representation for logging (without sensitive data)
     *
     * @return string representation for logging
     */
    public String toSafeString() {
        return "ExchangeStats{" +
                "exchangeId='" + exchangeId + '\'' +
                ", messagesPublished=" + messagesPublished +
                ", messagesDelivered=" + messagesDelivered +
                ", deliveryEfficiency=" + String.format("%.2f", getDeliveryEfficiency()) +
                '}';
    }

    @Override
    public String toString() {
        return toSafeString();
    }
}