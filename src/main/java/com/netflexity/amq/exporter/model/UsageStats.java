package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Represents a single day's MQ API usage statistics from the Stats API.
 * 
 * Endpoint: GET /mq/stats/api/v1/organizations/{orgId}/environments/{envId}
 *           ?startDate=...&endDate=...&period=1day
 * 
 * Returns a JSON ARRAY of daily data points:
 * [
 *   { "timestamp": "2026-03-01T00:00Z", "apiRequestCount": 42, "messageReceiptCount": 100, 
 *     "billableUnitCount": 50, "messageByteCount": 8192 },
 *   ...
 * ]
 * 
 * Field mapping from API:
 * - apiRequestCount → total API requests
 * - messageReceiptCount → messages received/consumed
 * - billableUnitCount → billable units (MuleSoft billing metric)
 * - messageByteCount → total bytes of messages
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsageStats {

    private String timestamp;
    private long apiRequestCount = 0L;
    private long messageReceiptCount = 0L;
    private long billableUnitCount = 0L;
    private long messageByteCount = 0L;

    public String toSafeString() {
        return "UsageStats{receipts=" + messageReceiptCount + ", billableUnits=" + billableUnitCount 
                + ", apiRequests=" + apiRequestCount + ", bytes=" + messageByteCount + "}";
    }

    @Override
    public String toString() {
        return toSafeString();
    }
}
