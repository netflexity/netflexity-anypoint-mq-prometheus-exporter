package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;

/**
 * Represents statistics for an exchange from the Anypoint MQ Stats API.
 * 
 * The Stats API returns arrays (time-series data points) for each metric.
 * We extract the last value from each array as the most recent reading.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeStats {

    @JsonProperty("exchangeId")
    private String exchangeId;

    private Long messagesPublished = 0L;
    private Long messagesDelivered = 0L;
    private Double averageMessageSize;

    @JsonSetter("messagesPublished")
    public void setMessagesPublished(Object value) {
        this.messagesPublished = extractLong(value);
    }

    @JsonSetter("messagesDelivered")
    public void setMessagesDelivered(Object value) {
        this.messagesDelivered = extractLong(value);
    }

    @JsonSetter("averageMessageSize")
    public void setAverageMessageSize(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object last = list.get(list.size() - 1);
            this.averageMessageSize = last instanceof Number n ? n.doubleValue() : 0.0;
        } else if (value instanceof Number n) {
            this.averageMessageSize = n.doubleValue();
        }
    }

    private static Long extractLong(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object last = list.get(list.size() - 1);
            return last instanceof Number n ? n.longValue() : 0L;
        } else if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

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
        return (double) delivered / messagesPublished.doubleValue();
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