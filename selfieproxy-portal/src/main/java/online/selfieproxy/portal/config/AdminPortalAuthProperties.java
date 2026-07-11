package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Selfie Proxy's own end-user login credentials (ADMIN_PORTAL_USERNAME,
 * ADMIN_PORTAL_PASSWORD in .env) -- unrelated to boringproxy's own user/token
 * system. Placeholder ahead of a future Keycloak/Authentik SSO integration.
 */
@ConfigurationProperties(prefix = "admin-portal")
public record AdminPortalAuthProperties(String username, String password) {
}
