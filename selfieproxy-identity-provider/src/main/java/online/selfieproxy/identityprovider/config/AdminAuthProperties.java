package online.selfieproxy.identityprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One-time bootstrap values (ADMIN_PORTAL_USERNAME/ADMIN_PORTAL_BOOTSTRAP_PASSWORD
 * in .env) that AdminUserStore consumes to seed the persisted admin record on
 * first boot only. Once that record exists, the live username and password
 * are whatever's stored there (rotated via the Users page's admin row, or the
 * forced first-login password change), not these values -- see AdminUserStore,
 * LoginController and InternalUsersController.
 */
@ConfigurationProperties(prefix = "admin-portal")
public record AdminAuthProperties(String username, String bootstrapPassword) {
}
