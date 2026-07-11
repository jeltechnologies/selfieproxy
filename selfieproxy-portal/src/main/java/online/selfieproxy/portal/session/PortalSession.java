package online.selfieproxy.portal.session;

/**
 * What Selfie Proxy keeps in HttpSession after a successful login.
 * Selfie Proxy runs with a single boringproxy user, so isAdmin is always
 * true; the record keeps that field for now to minimize churn if BoringProxy
 * user-scoped permissions are reintroduced later.
 */
public record PortalSession(String owner, boolean isAdmin) {
}
