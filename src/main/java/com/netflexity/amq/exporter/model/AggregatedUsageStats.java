package com.netflexity.amq.exporter.model;

import lombok.Data;

import java.util.List;

/**
 * Aggregated MQ API usage statistics — summed across all daily data points.
 * Built from the array of UsageStats returned by the Stats API.
 */
@Data
public class AggregatedUsageStats {

    private long apiRequestCount = 0L;
    private long messageReceiptCount = 0L;
    private long billableUnitCount = 0L;
    private long messageByteCount = 0L;

    /**
     * Sum an array of daily UsageStats into a single aggregate.
     */
    public static AggregatedUsageStats fromDailyStats(List<UsageStats> dailyStats) {
        AggregatedUsageStats agg = new AggregatedUsageStats();
        if (dailyStats != null) {
            for (UsageStats day : dailyStats) {
                agg.apiRequestCount += day.getApiRequestCount();
                agg.messageReceiptCount += day.getMessageReceiptCount();
                agg.billableUnitCount += day.getBillableUnitCount();
                agg.messageByteCount += day.getMessageByteCount();
            }
        }
        return agg;
    }

    public String toSafeString() {
        return "AggregatedUsageStats{receipts=" + messageReceiptCount + ", billableUnits=" + billableUnitCount 
                + ", apiRequests=" + apiRequestCount + ", bytes=" + messageByteCount + "}";
    }
}
