package online.selfieproxy.portal.domain;

/**
 * A static website Selfie Proxy itself hosts and serves -- entirely separate
 * from the Homelab/Exposed App concept: there's no user-run address behind
 * it, no protocol/TLS-mode choice, just a domain (a subdomain of the shared
 * DOMAIN, or one the user owns elsewhere) pointed at the shared
 * selfieproxy-local-websites container via the hidden "This Server" homelab. See
 * LocalWebsiteController/StaticSiteProvisioner.
 */
public record LocalWebsite(String domain) {
}
