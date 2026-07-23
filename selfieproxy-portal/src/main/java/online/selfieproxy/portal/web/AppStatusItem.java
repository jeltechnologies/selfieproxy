package online.selfieproxy.portal.web;

/**
 * A row's live status on the Applications list: whether it's reachable right now (see
 * DashboardController's onlineByAgent/hasDnsMismatch checks), the reason if not, and whether its
 * certificate is still a temporary self-signed one.
 */
public record AppStatusItem(String fqdn, boolean offline, String statusMessage, boolean certPending) {
}
