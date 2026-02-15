package com.netflexity.amq.exporter.monitor;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Tracks state for a monitor instance.
 * 
 * Maintains historical data and state tracking needed for
 * cooldown periods, trend analysis, and baseline calculations.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Data
public class MonitorState {

    /**
     * Monitor name this state belongs to
     */
    private String monitorName;

    /**
     * Queue name this state tracks (if queue-specific)
     */
    private String queueName;

    /**
     * Last time this monitor was triggered
     */
    private LocalDateTime lastTriggeredAt;

    /**
     * Last time a notification was sent for this monitor
     */
    private LocalDateTime lastNotifiedAt;

    /**
     * Number of consecutive times this monitor has been triggered
     */
    private int consecutiveTriggeredCount = 0;

    /**
     * Ring buffer of previous values for trend analysis
     * Limited to last N values to prevent memory growth
     */
    private Queue<Double> previousValues = new LinkedList<>();

    /**
     * Calculated baseline average from historical data
     */
    private Double baselineAvg;

    /**
     * Calculated baseline standard deviation from historical data
     */
    private Double baselineStdDev;

    /**
     * Maximum number of previous values to store
     */
    private static final int MAX_PREVIOUS_VALUES = 100;

    /**
     * Constructor for monitor state
     */
    public MonitorState(String monitorName, String queueName) {
        this.monitorName = monitorName;
        this.queueName = queueName;
    }

    /**
     * Default constructor
     */
    public MonitorState() {
    }

    /**
     * Add a new value to the historical data
     */
    public void addValue(double value) {
        previousValues.offer(value);
        
        // Limit the size of the ring buffer
        while (previousValues.size() > MAX_PREVIOUS_VALUES) {
            previousValues.poll();
        }
        
        // Recalculate baseline statistics
        recalculateBaseline();
    }

    /**
     * Check if the monitor is in cooldown period
     */
    public boolean isInCooldown(int cooldownMinutes) {
        if (lastNotifiedAt == null) {
            return false;
        }
        
        return lastNotifiedAt.plusMinutes(cooldownMinutes).isAfter(LocalDateTime.now());
    }

    /**
     * Mark the monitor as triggered
     */
    public void markTriggered() {
        lastTriggeredAt = LocalDateTime.now();
        consecutiveTriggeredCount++;
    }

    /**
     * Mark notification as sent
     */
    public void markNotified() {
        lastNotifiedAt = LocalDateTime.now();
    }

    /**
     * Reset consecutive triggered count (when monitor stops triggering)
     */
    public void resetConsecutiveTriggers() {
        consecutiveTriggeredCount = 0;
    }

    /**
     * Get the average of recent values within a window
     */
    public Double getRecentAverage(int windowSize) {
        if (previousValues.isEmpty()) {
            return null;
        }
        
        Double[] values = previousValues.toArray(new Double[0]);
        int startIndex = Math.max(0, values.length - windowSize);
        
        double sum = 0;
        int count = 0;
        
        for (int i = startIndex; i < values.length; i++) {
            sum += values[i];
            count++;
        }
        
        return count > 0 ? sum / count : null;
    }

    /**
     * Calculate percentage change compared to baseline
     */
    public Double getPercentageChangeFromBaseline(double currentValue) {
        if (baselineAvg == null || baselineAvg == 0) {
            return null;
        }
        
        return ((currentValue - baselineAvg) / baselineAvg) * 100;
    }

    /**
     * Check if current value is an outlier based on standard deviation
     */
    public boolean isOutlier(double currentValue, double sigmaThreshold) {
        if (baselineAvg == null || baselineStdDev == null) {
            return false;
        }
        
        double zScore = Math.abs(currentValue - baselineAvg) / baselineStdDev;
        return zScore > sigmaThreshold;
    }

    /**
     * Get the state key for storage
     */
    public String getStateKey() {
        if (queueName != null) {
            return monitorName + ":" + queueName;
        }
        return monitorName;
    }

    /**
     * Recalculate baseline statistics from previous values
     */
    private void recalculateBaseline() {
        if (previousValues.isEmpty()) {
            baselineAvg = null;
            baselineStdDev = null;
            return;
        }
        
        // Calculate average
        double sum = 0;
        int count = 0;
        for (Double value : previousValues) {
            sum += value;
            count++;
        }
        baselineAvg = sum / count;
        
        // Calculate standard deviation
        double varianceSum = 0;
        for (Double value : previousValues) {
            varianceSum += Math.pow(value - baselineAvg, 2);
        }
        baselineStdDev = Math.sqrt(varianceSum / count);
    }
}