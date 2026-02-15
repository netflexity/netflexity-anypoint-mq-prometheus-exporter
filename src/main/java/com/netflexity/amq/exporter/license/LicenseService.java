package com.netflexity.amq.exporter.license;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * License validation service for gating advanced features.
 * 
 * Provides simple license checking to enable/disable advanced monitoring
 * features like alerts, notifications, health scores, and REST API.
 * 
 * @author Netflexity
 * @version 1.0.0
 */
@Service
@Slf4j
public class LicenseService {

    private static final String SHARED_SECRET = "anypoint-mq-exporter-pro-2024";
    
    @Value("${anypoint.license.key:}")
    private String licenseKey;

    private boolean validLicense = false;
    private LicenseTier licenseTier = LicenseTier.FREE;

    /**
     * License tiers
     */
    public enum LicenseTier {
        FREE,
        PRO
    }

    @PostConstruct
    public void initialize() {
        validateLicense();
        
        if (validLicense) {
            log.info("Valid {} license detected - advanced features enabled", licenseTier);
        } else {
            log.warn("No valid license found - running in FREE tier (raw metrics only)");
            log.warn("To enable advanced monitoring features, provide a valid license key via anypoint.license.key");
        }
    }

    /**
     * Check if there's a valid license
     */
    public boolean hasValidLicense() {
        return validLicense;
    }

    /**
     * Get the current license tier
     */
    public LicenseTier getLicenseTier() {
        return licenseTier;
    }

    /**
     * Check if monitors feature is available
     */
    public boolean isMonitorsEnabled() {
        return licenseTier == LicenseTier.PRO;
    }

    /**
     * Check if notifications feature is available
     */
    public boolean isNotificationsEnabled() {
        return licenseTier == LicenseTier.PRO;
    }

    /**
     * Check if health scores feature is available
     */
    public boolean isHealthScoresEnabled() {
        return licenseTier == LicenseTier.PRO;
    }

    /**
     * Check if REST API is available
     */
    public boolean isRestApiEnabled() {
        return licenseTier == LicenseTier.PRO;
    }

    /**
     * Validate the provided license key
     */
    private void validateLicense() {
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            validLicense = false;
            licenseTier = LicenseTier.FREE;
            return;
        }

        try {
            // Simple hash-based validation (not cryptographically secure, but sufficient for MVP)
            String expectedHash = generateLicenseHash("PRO");
            
            if (licenseKey.equals(expectedHash)) {
                validLicense = true;
                licenseTier = LicenseTier.PRO;
                return;
            }

            // Check for other tiers if needed in future
            log.warn("Invalid license key provided: {}", licenseKey.substring(0, Math.min(8, licenseKey.length())) + "...");
            validLicense = false;
            licenseTier = LicenseTier.FREE;

        } catch (Exception e) {
            log.error("Error validating license: {}", e.getMessage());
            validLicense = false;
            licenseTier = LicenseTier.FREE;
        }
    }

    /**
     * Generate license hash for a given tier
     * This is a simple implementation - in production you'd use proper JWT tokens or similar
     */
    private String generateLicenseHash(String tier) {
        try {
            String data = SHARED_SECRET + ":" + tier + ":2024";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Get upgrade message for free tier users
     */
    public String getUpgradeMessage() {
        return "This feature requires a PRO license. Contact sales@netflexity.com to upgrade.";
    }

    /**
     * Create license response for REST API
     */
    public LicenseInfo getLicenseInfo() {
        return new LicenseInfo(licenseTier, validLicense, isMonitorsEnabled(), 
                              isNotificationsEnabled(), isHealthScoresEnabled(), isRestApiEnabled());
    }

    /**
     * License information DTO
     */
    public record LicenseInfo(
            LicenseTier tier,
            boolean valid,
            boolean monitorsEnabled,
            boolean notificationsEnabled,
            boolean healthScoresEnabled,
            boolean restApiEnabled
    ) {}

    /**
     * Generate a PRO license key (for testing/demo purposes)
     * In production, this would be done by a separate licensing system
     */
    public String generateProLicense() {
        return generateLicenseHash("PRO");
    }
}