package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Represents a queue in Anypoint MQ.
 * 
 * This model corresponds to the response from the Anypoint MQ Admin API
 * for listing destinations (queues).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Queue {

    /**
     * Unique identifier for the queue
     */
    @JsonProperty("queueId")
    private String queueId;

    /**
     * Human-readable name of the queue
     */
    @JsonProperty("queueName")
    private String queueName;

    /**
     * Type of queue (should be "queue")
     */
    @JsonProperty("type")
    private String type;

    /**
     * Whether this is a FIFO queue
     */
    @JsonProperty("fifo")
    private Boolean fifo = false;

    /**
     * Default Time To Live for messages in milliseconds
     */
    @JsonProperty("defaultTtl")
    private Long defaultTtl;

    /**
     * Default lock Time To Live in milliseconds
     */
    @JsonProperty("defaultLockTtl")
    private Long defaultLockTtl;

    /**
     * Maximum number of delivery attempts before sending to DLQ
     */
    @JsonProperty("maxDeliveries")
    private Integer maxDeliveries;

    /**
     * Dead Letter Queue ID if configured
     */
    @JsonProperty("defaultDeadLetterQueueId")
    private String defaultDeadLetterQueueId;

    /**
     * Whether the queue is encrypted
     */
    @JsonProperty("encrypted")
    private Boolean encrypted = false;

    /**
     * Region where the queue is located
     */
    private String region;

    /**
     * Environment where the queue is located
     */
    private String environment;

    /**
     * Check if this queue is a Dead Letter Queue for another queue
     *
     * @return true if this appears to be a DLQ based on naming or configuration
     */
    public boolean isDeadLetterQueue() {
        if (queueName == null) {
            return false;
        }
        
        String lowerName = queueName.toLowerCase();
        return lowerName.contains("dlq") || 
               lowerName.contains("dead-letter") || 
               lowerName.contains("deadletter") ||
               lowerName.endsWith("-dead") ||
               lowerName.endsWith("-dl");
    }

    /**
     * Check if this queue has a Dead Letter Queue configured
     *
     * @return true if defaultDeadLetterQueueId is set
     */
    public boolean hasDeadLetterQueue() {
        return defaultDeadLetterQueueId != null && !defaultDeadLetterQueueId.trim().isEmpty();
    }

    /**
     * Get a safe queue name for use in metrics labels
     * Replaces problematic characters with underscores
     *
     * @return sanitized queue name
     */
    public String getSanitizedQueueName() {
        // MQ Admin API uses queueId as the queue name
        String name = queueName != null ? queueName : queueId;
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    @Override
    public String toString() {
        return "Queue{" +
                "queueId='" + queueId + '\'' +
                ", queueName='" + queueName + '\'' +
                ", type='" + type + '\'' +
                ", fifo=" + fifo +
                ", maxDeliveries=" + maxDeliveries +
                ", hasDeadLetterQueue=" + hasDeadLetterQueue() +
                ", isDeadLetterQueue=" + isDeadLetterQueue() +
                ", region='" + region + '\'' +
                ", environment='" + environment + '\'' +
                '}';
    }
}