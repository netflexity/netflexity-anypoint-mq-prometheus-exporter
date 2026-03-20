package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Response from Anypoint Audit API query endpoint.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditQueryResponse {

    @JsonProperty("data")
    private List<AuditLogEntry> data;

    @JsonProperty("total")
    private int total;
}
