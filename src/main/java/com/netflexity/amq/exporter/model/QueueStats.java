package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;

/**
 * Represents statistics for a queue from the Anypoint MQ Stats API.
 * 
 * The Stats API returns arrays (time-series data points) for each metric.
 * We extract the last value from each array as the most recent reading.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueueStats {

    @JsonProperty("queueId")
    private String queueId;

    private Long messagesInQueue = 0L;
    private Long messagesInFlight = 0L;
    private Long messagesSent = 0L;
    private Long messagesReceived = 0L;
    private Long messagesAcked = 0L;
    private Long queueSize;
    private Double averageMessageSize;

    // Stats API returns arrays — extract last value from each
    @JsonSetter("messagesInQueue")
    public void setMessagesInQueue(Object value) {
        this.messagesInQueue = extractLong(value);
    }

    @JsonSetter("messagesInFlight")
    public void setMessagesInFlight(Object value) {
        this.messagesInFlight = extractLong(value);
    }

    @JsonSetter("messagesSent")
    public void setMessagesSent(Object value) {
        this.messagesSent = extractLong(value);
    }

    @JsonSetter("messagesReceived")
    public void setMessagesReceived(Object value) {
        this.messagesReceived = extractLong(value);
    }

    @JsonSetter("messagesAcked")
    public void setMessagesAcked(Object value) {
        this.messagesAcked = extractLong(value);
    }

    @JsonSetter("queueSize")
    public void setQueueSize(Object value) {
        this.queueSize = extractLong(value);
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