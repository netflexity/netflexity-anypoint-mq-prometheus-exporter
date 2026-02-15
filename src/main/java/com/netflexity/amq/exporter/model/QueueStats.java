package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Represents statistics for a queue from the Anypoint MQ Stats API.
 * 
 * These statistics are collected over a time period and provide insights
 * into queue performance and message flow.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueueStats {

    /**
     * Queue identifier
     */
    @JsonProperty("queueId")
    private String queueId;

    /**
     * Number of messages currently waiting in the queue
     */
    @JsonProperty("messagesInQueue")
    private Long messagesInQueue = 0L;

    /**
     * Number of messages currently being processed (in-flight)
     */
    @JsonProperty("messagesInFlight")
    private Long messagesInFlight = 0L;

    /**
     * Total number of messages sent to the queue during the period
     */
    @JsonProperty("messagesSent")
    private Long messagesSent = 0L;

    /**
     * Total number of messages received from the queue during the period
     */
    @JsonProperty("messagesReceived")
    private Long messagesReceived = 0L;

    /**
     * Total number of messages acknowledged during the period
     */
    @JsonProperty("messagesAcked")
    private Long messagesAcked = 0L;

    /**
     * Queue size in bytes (if available)
     */
    @JsonProperty("queueSize")
    private Long queueSize;

    /**
     * Average message size in bytes (if available)
     */
    @JsonProperty("averageMessageSize")
    private Double averageMessageSize;

    /**
     * Associated queue metadata (set by the collector)
     */
    private Queue queue;

    /**
     * Timestamp when these stats were collected
     */
    private Long timestamp = System.currentTimeMillis();

    /**
     * Get the total number of messages in the queue system (in queue + in flight)
     *
     * @return sum of messages in queue and messages in flight
     */
    public Long getTotalMessages() {
        return (messagesInQueue != null ? messagesInQueue : 0L) + 
               (messagesInFlight != null ? messagesInFlight : 0L);
    }

    /**
     * Calculate message throughput (sent per period)
     * This can be used to derive rate metrics in Prometheus
     *
     * @param periodSeconds the stats collection period in seconds
     * @return messages per second sent rate
     */
    public Double getMessagesSentRate(int periodSeconds) {
        if (messagesSent == null || periodSeconds <= 0) {
            return 0.0;
        }
        return messagesSent.doubleValue() / periodSeconds;
    }

    /**
     * Calculate message receive rate (received per period)
     *
     * @param periodSeconds the stats collection period in seconds
     * @return messages per second received rate
     */
    public Double getMessagesReceivedRate(int periodSeconds) {
        if (messagesReceived == null || periodSeconds <= 0) {
            return 0.0;
        }
        return messagesReceived.doubleValue() / periodSeconds;
    }

    /**
     * Calculate message acknowledgment rate (acked per period)
     *
     * @param periodSeconds the stats collection period in seconds
     * @return messages per second acknowledgment rate
     */
    public Double getMessagesAckedRate(int periodSeconds) {
        if (messagesAcked == null || periodSeconds <= 0) {
            return 0.0;
        }
        return messagesAcked.doubleValue() / periodSeconds;
    }

    /**
     * Check if there are any messages in dead letter queue status
     * This is useful for alerting on DLQ issues
     *
     * @return true if this appears to be a DLQ with messages
     */
    public boolean hasDeadLetterMessages() {
        return queue != null && queue.isDeadLetterQueue() && getTotalMessages() > 0;
    }

    /**
     * Get a safe representation for logging (without sensitive data)
     *
     * @return string representation for logging
     */
    public String toSafeString() {
        return "QueueStats{" +
                "queueId='" + queueId + '\'' +
                ", messagesInQueue=" + messagesInQueue +
                ", messagesInFlight=" + messagesInFlight +
                ", messagesSent=" + messagesSent +
                ", messagesReceived=" + messagesReceived +
                ", messagesAcked=" + messagesAcked +
                ", queueSize=" + queueSize +
                ", totalMessages=" + getTotalMessages() +
                '}';
    }

    @Override
    public String toString() {
        return toSafeString();
    }
}