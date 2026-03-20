package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Represents MQ API usage statistics from the Stats API.
 * 
 * Endpoint: GET /mq/stats/api/v1/organizations/{orgId}/environments/{envId}
 *           ?startDate=...&endDate=...&period=1day
 * 
 * Returns time-series usage data (messages sent, received, acked) at the
 * organization or environment level — aggregate across all queues.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsageStats {

    private long messagesSent = 0L;
    private long messagesReceived = 0L;
    private long messagesAcked = 0L;
    private long billableUnitCount = 0L;
    private long apiRequestCount = 0L;

    @JsonSetter("messagesSent")
    public void setMessagesSent(Object value) {
        this.messagesSent = sumTimeSeries(value);
    }

    @JsonSetter("messagesReceived")
    public void setMessagesReceived(Object value) {
        this.messagesReceived = sumTimeSeries(value);
    }

    @JsonSetter("messagesAcked") 
    public void setMessagesAcked(Object value) {
        this.messagesAcked = sumTimeSeries(value);
    }

    @JsonSetter("billableUnitCount")
    public void setBillableUnitCount(Object value) {
        this.billableUnitCount = sumTimeSeries(value);
    }

    @JsonSetter("apiRequestCount")
    public void setApiRequestCount(Object value) {
        this.apiRequestCount = sumTimeSeries(value);
    }

    /**
     * Sum all data points in a time-series array.
     * The Stats API can return arrays of numbers or arrays of {value: N} objects.
     */
    @SuppressWarnings("unchecked")
    private static long sumTimeSeries(Object value) {
        if (value instanceof List<?> list) {
            long sum = 0;
            for (Object item : list) {
                if (item instanceof Number n) {
                    sum += n.longValue();
                } else if (item instanceof Map) {
                    Object v = ((Map<String, Object>) item).get("value");
                    if (v instanceof Number n) sum += n.longValue();
                }
            }
            return sum;
        } else if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    public String toSafeString() {
        return "UsageStats{sent=" + messagesSent + ", received=" + messagesReceived + ", acked=" + messagesAcked 
                + ", billableUnits=" + billableUnitCount + ", apiRequests=" + apiRequestCount + "}";
    }

    @Override
    public String toString() {
        return toSafeString();
    }
}
