package com.netflexity.amq.exporter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Represents an OAuth2 access token from Anypoint Platform.
 * 
 * This token is used to authenticate API calls to Anypoint MQ Stats API.
 * Tokens have an expiry time and should be refreshed before expiration.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthToken {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType = "Bearer";

    @JsonProperty("expires_in")
    private Long expiresIn;

    @JsonProperty("scope")
    private String scope;

    /**
     * The time when this token was created/received
     */
    private Instant createdAt = Instant.now();

    /**
     * Check if the token is expired or will expire within the buffer time
     *
     * @param bufferMinutes Number of minutes before actual expiry to consider token expired
     * @return true if token should be refreshed
     */
    public boolean isExpiredOrExpiring(int bufferMinutes) {
        if (expiresIn == null || accessToken == null || accessToken.isEmpty()) {
            return true;
        }

        Instant expiryTime = createdAt.plus(expiresIn, ChronoUnit.SECONDS);
        Instant bufferTime = Instant.now().plus(bufferMinutes, ChronoUnit.MINUTES);

        return bufferTime.isAfter(expiryTime);
    }

    /**
     * Check if the token is valid (not null and not expired)
     *
     * @return true if token is valid and not expiring within 5 minutes
     */
    public boolean isValid() {
        return !isExpiredOrExpiring(5);
    }

    /**
     * Get the Authorization header value
     *
     * @return "Bearer {access_token}" or null if token is invalid
     */
    public String getAuthorizationHeader() {
        if (accessToken == null || accessToken.isEmpty()) {
            return null;
        }
        return tokenType + " " + accessToken;
    }

    /**
     * Get the time remaining until token expiry
     *
     * @return seconds until expiry, or 0 if expired/invalid
     */
    public long getSecondsUntilExpiry() {
        if (expiresIn == null) {
            return 0;
        }

        Instant expiryTime = createdAt.plus(expiresIn, ChronoUnit.SECONDS);
        long secondsUntilExpiry = ChronoUnit.SECONDS.between(Instant.now(), expiryTime);

        return Math.max(0, secondsUntilExpiry);
    }

    @Override
    public String toString() {
        return "AuthToken{" +
                "tokenType='" + tokenType + '\'' +
                ", expiresIn=" + expiresIn +
                ", scope='" + scope + '\'' +
                ", createdAt=" + createdAt +
                ", valid=" + isValid() +
                ", secondsUntilExpiry=" + getSecondsUntilExpiry() +
                '}';
    }
}