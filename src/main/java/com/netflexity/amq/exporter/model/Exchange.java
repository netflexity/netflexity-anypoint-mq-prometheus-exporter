package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Represents an exchange in Anypoint MQ.
 * 
 * Exchanges are used for publish-subscribe messaging patterns
 * where messages are published to the exchange and delivered to
 * bound queues based on routing rules.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Exchange {

    /**
     * Unique identifier for the exchange
     */
    @JsonProperty("exchangeId")
    private String exchangeId;

    /**
     * Human-readable name of the exchange
     */
    @JsonProperty("exchangeName")
    private String exchangeName;

    /**
     * Type of exchange (should be "exchange")
     */
    @JsonProperty("type")
    private String type;

    /**
     * Whether the exchange is encrypted
     */
    @JsonProperty("encrypted")
    private Boolean encrypted = false;

    /**
     * Region where the exchange is located
     */
    private String region;

    /**
     * Environment where the exchange is located
     */
    private String environment;

    /**
     * Get a safe exchange name for use in metrics labels
     * Replaces problematic characters with underscores
     *
     * @return sanitized exchange name
     */
    public String getSanitizedExchangeName() {
        String name = exchangeName != null ? exchangeName : exchangeId;
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    @Override
    public String toString() {
        return "Exchange{" +
                "exchangeId='" + exchangeId + '\'' +
                ", exchangeName='" + exchangeName + '\'' +
                ", type='" + type + '\'' +
                ", encrypted=" + encrypted +
                ", region='" + region + '\'' +
                ", environment='" + environment + '\'' +
                '}';
    }
}