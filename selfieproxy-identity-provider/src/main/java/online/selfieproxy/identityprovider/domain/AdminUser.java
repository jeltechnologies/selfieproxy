package online.selfieproxy.identityprovider.domain;

/** Selfie Proxy's single persisted admin credential record -- see AdminUserStore. */
public record AdminUser(String username, String passwordHash, boolean mustChangePassword) {
}
