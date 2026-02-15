package com.netflexity.amq.exporter.monitor;

import com.netflexity.amq.exporter.model.QueueStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core monitor evaluation engine.
 * 
 * Evaluates monitor definitions against current and historical metrics
 * to determine if alert conditions are met.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Component
@Slf4j
public class MonitorEvaluator {

    private final Map<String, MonitorState> monitorStates = new ConcurrentHashMap<>();

    /**
     * Evaluate a monitor definition against queue statistics
     */
    public MonitorResult evaluate(MonitorDefinition monitor, QueueStats queueStats, String environmentName) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        // Get or create monitor state
        String stateKey = monitor.getName() + ":" + queueName + ":" + environmentName + ":" + region;
        MonitorState state = monitorStates.computeIfAbsent(stateKey, 
            k -> new MonitorState(monitor.getName(), queueName));

        try {
            return evaluateByType(monitor, queueStats, environmentName, state);
        } catch (Exception e) {
            log.error("Error evaluating monitor {} for queue {}: {}", monitor.getName(), queueName, e.getMessage());
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region, 0, 0);
        }
    }

    /**
     * Evaluate monitor based on its type
     */
    private MonitorResult evaluateByType(MonitorDefinition monitor, QueueStats queueStats, 
                                         String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();

        return switch (monitor.getType()) {
            case QUEUE_DEPTH -> evaluateQueueDepth(monitor, queueStats, environmentName, state);
            case DLQ_ALERT -> evaluateDlqAlert(monitor, queueStats, environmentName, state);
            case THROUGHPUT_DROP -> evaluateThroughputDrop(monitor, queueStats, environmentName, state);
            case THROUGHPUT_SPIKE -> evaluateThroughputSpike(monitor, queueStats, environmentName, state);
            case QUEUE_HEALTH -> evaluateQueueHealth(monitor, queueStats, environmentName, state);
            case CUSTOM -> evaluateCustom(monitor, queueStats, environmentName, state);
        };
    }

    /**
     * Evaluate queue depth threshold
     */
    private MonitorResult evaluateQueueDepth(MonitorDefinition monitor, QueueStats queueStats, 
                                             String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        long currentDepth = queueStats.getMessagesInQueue() != null ? queueStats.getMessagesInQueue() : 0L;
        double threshold = monitor.getThreshold();
        
        // Add current value to state for trend analysis
        state.addValue(currentDepth);
        
        boolean triggered = evaluateCondition(currentDepth, threshold, monitor.getCondition());
        
        if (triggered) {
            state.markTriggered();
            String message = String.format("Queue depth %d %s threshold %,.0f", 
                currentDepth, getConditionDescription(monitor.getCondition()), threshold);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("messagesInFlight", queueStats.getMessagesInFlight());
            metadata.put("messagesSent", queueStats.getMessagesSent());
            metadata.put("messagesReceived", queueStats.getMessagesReceived());
            
            return MonitorResult.triggered(monitor.getName(), queueName, environmentName, region,
                currentDepth, threshold, message, monitor.getSeverity(), metadata);
        } else {
            state.resetConsecutiveTriggers();
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                currentDepth, threshold);
        }
    }

    /**
     * Evaluate DLQ alert (any messages in dead letter queue)
     */
    private MonitorResult evaluateDlqAlert(MonitorDefinition monitor, QueueStats queueStats, 
                                           String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        // Only trigger for DLQ queues with messages
        if (!queueStats.getQueue().isDeadLetterQueue()) {
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region, 0, 0);
        }
        
        long messagesInDlq = queueStats.getMessagesInQueue() != null ? queueStats.getMessagesInQueue() : 0L;
        double threshold = monitor.getThreshold();
        
        state.addValue(messagesInDlq);
        
        boolean triggered = evaluateCondition(messagesInDlq, threshold, monitor.getCondition());
        
        if (triggered) {
            state.markTriggered();
            String message = String.format("Dead letter queue has %d messages", messagesInDlq);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("isDlq", true);
            metadata.put("maxDeliveries", queueStats.getQueue().getMaxDeliveries());
            
            return MonitorResult.triggered(monitor.getName(), queueName, environmentName, region,
                messagesInDlq, threshold, message, monitor.getSeverity(), metadata);
        } else {
            state.resetConsecutiveTriggers();
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                messagesInDlq, threshold);
        }
    }

    /**
     * Evaluate throughput drop
     */
    private MonitorResult evaluateThroughputDrop(MonitorDefinition monitor, QueueStats queueStats, 
                                                 String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        long currentThroughput = queueStats.getMessagesReceived() != null ? queueStats.getMessagesReceived() : 0L;
        state.addValue(currentThroughput);
        
        // Need historical data for trend analysis
        if (state.getPreviousValues().size() < 2) {
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                currentThroughput, monitor.getThreshold());
        }
        
        Double recentAvg = state.getRecentAverage(monitor.getEvaluationWindowMinutes());
        Double baselineAvg = state.getBaselineAvg();
        
        if (recentAvg == null || baselineAvg == null || baselineAvg == 0) {
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                currentThroughput, monitor.getThreshold());
        }
        
        double percentChange = ((recentAvg - baselineAvg) / baselineAvg) * 100;
        double threshold = monitor.getThreshold();
        
        boolean triggered = percentChange <= threshold; // Negative threshold for drops
        
        if (triggered) {
            state.markTriggered();
            String message = String.format("Throughput dropped by %.1f%% (current: %.1f, baseline: %.1f)",
                Math.abs(percentChange), recentAvg, baselineAvg);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("percentChange", percentChange);
            metadata.put("recentAverage", recentAvg);
            metadata.put("baselineAverage", baselineAvg);
            
            return MonitorResult.triggered(monitor.getName(), queueName, environmentName, region,
                percentChange, threshold, message, monitor.getSeverity(), metadata);
        } else {
            state.resetConsecutiveTriggers();
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                percentChange, threshold);
        }
    }

    /**
     * Evaluate throughput spike
     */
    private MonitorResult evaluateThroughputSpike(MonitorDefinition monitor, QueueStats queueStats, 
                                                  String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        long currentThroughput = queueStats.getMessagesReceived() != null ? queueStats.getMessagesReceived() : 0L;
        state.addValue(currentThroughput);
        
        // Need historical data for trend analysis
        if (state.getPreviousValues().size() < 2) {
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                currentThroughput, monitor.getThreshold());
        }
        
        Double recentAvg = state.getRecentAverage(monitor.getEvaluationWindowMinutes());
        Double baselineAvg = state.getBaselineAvg();
        
        if (recentAvg == null || baselineAvg == null || baselineAvg == 0) {
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                currentThroughput, monitor.getThreshold());
        }
        
        double percentChange = ((recentAvg - baselineAvg) / baselineAvg) * 100;
        double threshold = monitor.getThreshold();
        
        boolean triggered = percentChange >= threshold; // Positive threshold for spikes
        
        if (triggered) {
            state.markTriggered();
            String message = String.format("Throughput spiked by %.1f%% (current: %.1f, baseline: %.1f)",
                percentChange, recentAvg, baselineAvg);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("percentChange", percentChange);
            metadata.put("recentAverage", recentAvg);
            metadata.put("baselineAverage", baselineAvg);
            
            return MonitorResult.triggered(monitor.getName(), queueName, environmentName, region,
                percentChange, threshold, message, monitor.getSeverity(), metadata);
        } else {
            state.resetConsecutiveTriggers();
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                percentChange, threshold);
        }
    }

    /**
     * Evaluate queue health score (0-100)
     */
    private MonitorResult evaluateQueueHealth(MonitorDefinition monitor, QueueStats queueStats, 
                                              String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        double healthScore = calculateQueueHealthScore(queueStats, state);
        double threshold = monitor.getThreshold();
        
        state.addValue(healthScore);
        
        boolean triggered = evaluateCondition(healthScore, threshold, monitor.getCondition());
        
        if (triggered) {
            state.markTriggered();
            String message = String.format("Queue health score %.1f %s threshold %.1f",
                healthScore, getConditionDescription(monitor.getCondition()), threshold);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("healthScore", healthScore);
            metadata.put("messagesInQueue", queueStats.getMessagesInQueue());
            metadata.put("messagesInFlight", queueStats.getMessagesInFlight());
            metadata.put("isDlq", queueStats.getQueue().isDeadLetterQueue());
            
            return MonitorResult.triggered(monitor.getName(), queueName, environmentName, region,
                healthScore, threshold, message, monitor.getSeverity(), metadata);
        } else {
            state.resetConsecutiveTriggers();
            return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region,
                healthScore, threshold);
        }
    }

    /**
     * Evaluate custom monitor (placeholder for future extensibility)
     */
    private MonitorResult evaluateCustom(MonitorDefinition monitor, QueueStats queueStats, 
                                         String environmentName, MonitorState state) {
        String queueName = queueStats.getQueue().getSanitizedQueueName();
        String region = queueStats.getQueue().getRegion();
        
        // Custom monitors not implemented yet
        log.warn("Custom monitor type not implemented: {}", monitor.getName());
        return MonitorResult.notTriggered(monitor.getName(), queueName, environmentName, region, 0, 0);
    }

    /**
     * Calculate composite queue health score (0-100)
     */
    private double calculateQueueHealthScore(QueueStats queueStats, MonitorState state) {
        double score = 100.0;
        
        long messagesInQueue = queueStats.getMessagesInQueue() != null ? queueStats.getMessagesInQueue() : 0L;
        long messagesInFlight = queueStats.getMessagesInFlight() != null ? queueStats.getMessagesInFlight() : 0L;
        long messagesReceived = queueStats.getMessagesReceived() != null ? queueStats.getMessagesReceived() : 0L;
        
        // Penalize high queue depth (reduce score based on depth ratio)
        if (messagesInQueue > 0) {
            double depthPenalty = Math.min(20, Math.log10(messagesInQueue + 1) * 5);
            score -= depthPenalty;
        }
        
        // Penalize DLQ presence
        if (queueStats.getQueue().isDeadLetterQueue() && messagesInQueue > 0) {
            score -= 30;
        }
        
        // Penalize consumer lag (high in-flight vs received ratio)
        if (messagesReceived > 0) {
            double lagRatio = (double) messagesInFlight / messagesReceived;
            if (lagRatio > 0.1) { // More than 10% in-flight
                score -= Math.min(25, lagRatio * 50);
            }
        }
        
        // Penalize throughput instability (high variance)
        if (state.getBaselineStdDev() != null && state.getBaselineAvg() != null && state.getBaselineAvg() > 0) {
            double coefficientOfVariation = state.getBaselineStdDev() / state.getBaselineAvg();
            if (coefficientOfVariation > 0.5) { // High variability
                score -= Math.min(15, coefficientOfVariation * 20);
            }
        }
        
        return Math.max(0, score);
    }

    /**
     * Evaluate condition based on operator
     */
    private boolean evaluateCondition(double currentValue, double threshold, MonitorDefinition.ThresholdCondition condition) {
        return switch (condition) {
            case GT -> currentValue > threshold;
            case LT -> currentValue < threshold;
            case GTE -> currentValue >= threshold;
            case LTE -> currentValue <= threshold;
            case EQ -> Math.abs(currentValue - threshold) < 0.001; // Use small epsilon for double comparison
            case PCT_CHANGE -> false; // Handled separately in throughput monitors
        };
    }

    /**
     * Get human-readable condition description
     */
    private String getConditionDescription(MonitorDefinition.ThresholdCondition condition) {
        return switch (condition) {
            case GT -> "exceeds";
            case LT -> "below";
            case GTE -> "at or above";
            case LTE -> "at or below";
            case EQ -> "equals";
            case PCT_CHANGE -> "changed by";
        };
    }

    /**
     * Get monitor state for a specific monitor and queue
     */
    public MonitorState getMonitorState(String monitorName, String queueName, String environmentName, String region) {
        String stateKey = monitorName + ":" + queueName + ":" + environmentName + ":" + region;
        return monitorStates.get(stateKey);
    }

    /**
     * Clear all monitor states (for testing or reset)
     */
    public void clearStates() {
        monitorStates.clear();
    }
}