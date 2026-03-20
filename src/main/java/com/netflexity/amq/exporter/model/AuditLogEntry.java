package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Represents a single audit log entry from the Anypoint Audit API.
 * 
 * Endpoint: POST /audit/v2/organizations/{orgId}/query
 * 
 * Used to detect MQ configuration changes: queue/exchange created, deleted, modified.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditLogEntry {

    @JsonProperty("id")
    private String id;

    @JsonProperty("action")
    private String action;  // Create, Delete, Update

    @JsonProperty("objectName")
    private String objectName;

    @JsonProperty("objectId")
    private String objectId;

    @JsonProperty("objectType")
    private String objectType;

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("userName")
    private String userName;

    @JsonProperty("userEmail")
    private String userEmail;

    @JsonProperty("createdAt")
    private String createdAt;

    @JsonProperty("environmentName")
    private String environmentName;

    @JsonProperty("payload")
    private AuditPayload payload;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuditPayload {
        @JsonProperty("objectName")
        private String objectName;

        @JsonProperty("action")
        private String action;

        @JsonProperty("objectType")
        private String objectType;
    }
}
