package online.selfieproxy.identityprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Selfie Proxy's single hardcoded admin user (ADMIN_PORTAL_USERNAME,
 * ADMIN_PORTAL_PASSWORD in .env) -- relocated verbatim from
 * selfieproxy-portal's AdminPortalAuthProperties. This is the only account
 * this OIDC Identity Provider knows about; no user database, no
 * registration.
 */
@ConfigurationProperties(prefix = "admin-portal")
public record AdminAuthProperties(String username, String password) {
}
