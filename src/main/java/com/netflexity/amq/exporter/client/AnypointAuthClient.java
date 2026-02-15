package com.netflexity.amq.exporter.client;

import com.netflexity.amq.exporter.config.AnypointConfig;
import com.netflexity.amq.exporter.model.AuthToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for authenticating with Anypoint Platform and managing access tokens.
 * 
 * Supports two authentication methods:
 * 1. Username/password authentication
 * 2. Connected App authentication (client_id/client_secret)
 * 
 * Automatically handles token refresh and caching.
 */
@Component
@Slf4j
public class AnypointAuthClient {

    private final WebClient webClient;
    private final AnypointConfig anypointConfig;
    private final AtomicReference<AuthToken> cachedToken = new AtomicReference<>();

    public AnypointAuthClient(WebClient webClient, AnypointConfig anypointConfig) {
        this.webClient = webClient;
        this.anypointConfig = anypointConfig;
        
        log.info("Initialized AnypointAuthClient with base URL: {}", anypointConfig.getBaseUrl());
        
        if (anypointConfig.getAuth().isConnectedAppAuth()) {
            log.info("Configured for Connected App authentication");
        } else if (anypointConfig.getAuth().isUsernamePasswordAuth()) {
            log.info("Configured for username/password authentication");
        } else {
            log.warn("No authentication method configured! Please set either username/password or clientId/clientSecret");
        }
    }

    /**
     * Get a valid access token, refreshing if necessary
     *
     * @return Mono containing a valid AuthToken
     */
    public Mono<AuthToken> getAccessToken() {
        AuthToken current = cachedToken.get();
        
        // If we have a valid token, return it
        if (current != null && current.isValid()) {
            log.debug("Using cached token, expires in {} seconds", current.getSecondsUntilExpiry());
            return Mono.just(current);
        }

        // Otherwise, authenticate and get a new token
        log.info("Requesting new access token");
        return authenticate()
                .doOnSuccess(token -> {
                    cachedToken.set(token);
                    log.info("Successfully obtained access token, expires in {} seconds", token.getSecondsUntilExpiry());
                })
                .doOnError(error -> {
                    log.error("Failed to obtain access token: {}", error.getMessage(), error);
                    cachedToken.set(null);
                });
    }

    /**
     * Authenticate with Anypoint Platform and get an access token
     */
    private Mono<AuthToken> authenticate() {
        if (!anypointConfig.getAuth().hasValidAuth()) {
            return Mono.error(new IllegalStateException("No valid authentication configuration found"));
        }

        if (anypointConfig.getAuth().isConnectedAppAuth()) {
            return authenticateWithConnectedApp();
        } else {
            return authenticateWithUsernamePassword();
        }
    }

    /**
     * Authenticate using Connected App credentials (client_id/client_secret)
     */
    private Mono<AuthToken> authenticateWithConnectedApp() {
        log.debug("Authenticating with Connected App");
        
        String tokenUrl = anypointConfig.getBaseUrl() + "/accounts/api/v2/oauth2/token";
        
        return webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("client_id", anypointConfig.getAuth().getClientId())
                        .with("client_secret", anypointConfig.getAuth().getClientSecret())
                        .with("grant_type", "client_credentials"))
                .retrieve()
                .onStatus(HttpStatus::isError, response -> 
                    response.bodyToMono(String.class)
                            .doOnNext(body -> log.error("Authentication failed with status {}: {}", response.statusCode(), body))
                            .then(Mono.error(new RuntimeException("Authentication failed with status: " + response.statusCode()))))
                .bodyToMono(AuthToken.class)
                .retryWhen(Retry.backoff(anypointConfig.getHttp().getMaxRetries(), Duration.ofSeconds(1))
                        .filter(throwable -> !(throwable instanceof WebClientResponseException) ||
                                !((WebClientResponseException) throwable).getStatusCode().is4xxClientError())
                        .doBeforeRetry(retrySignal -> log.warn("Retrying authentication, attempt {}", retrySignal.totalRetries() + 1)))
                .timeout(Duration.ofSeconds(anypointConfig.getHttp().getReadTimeoutSeconds()));
    }

    /**
     * Authenticate using username/password credentials
     */
    private Mono<AuthToken> authenticateWithUsernamePassword() {
        log.debug("Authenticating with username/password");
        
        String loginUrl = anypointConfig.getBaseUrl() + "/accounts/login";
        
        return webClient.post()
                .uri(loginUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(new LoginRequest(
                        anypointConfig.getAuth().getUsername(),
                        anypointConfig.getAuth().getPassword())))
                .retrieve()
                .onStatus(HttpStatus::isError, response -> 
                    response.bodyToMono(String.class)
                            .doOnNext(body -> log.error("Login failed with status {}: {}", response.statusCode(), body))
                            .then(Mono.error(new RuntimeException("Login failed with status: " + response.statusCode()))))
                .bodyToMono(AuthToken.class)
                .retryWhen(Retry.backoff(anypointConfig.getHttp().getMaxRetries(), Duration.ofSeconds(1))
                        .filter(throwable -> !(throwable instanceof WebClientResponseException) ||
                                !((WebClientResponseException) throwable).getStatusCode().is4xxClientError())
                        .doBeforeRetry(retrySignal -> log.warn("Retrying login, attempt {}", retrySignal.totalRetries() + 1)))
                .timeout(Duration.ofSeconds(anypointConfig.getHttp().getReadTimeoutSeconds()));
    }

    /**
     * Clear the cached token (forces re-authentication on next request)
     */
    public void clearToken() {
        log.info("Clearing cached access token");
        cachedToken.set(null);
    }

    /**
     * Get the current cached token status (for health checks)
     */
    public boolean hasValidToken() {
        AuthToken current = cachedToken.get();
        return current != null && current.isValid();
    }

    /**
     * Request body for username/password authentication
     */
    private static class LoginRequest {
        public final String username;
        public final String password;

        LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}