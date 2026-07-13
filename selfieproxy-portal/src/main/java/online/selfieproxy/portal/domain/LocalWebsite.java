package online.selfieproxy.portal.domain;

/**
 * A static website Selfie Proxy itself hosts and serves -- entirely separate
 * from the Homelab/Exposed App concept: there's no user-run address behind
 * it, no protocol/TLS-mode choice, just a domain pointed at the shared
 * selfieproxy-local-websites container via the hidden "This Server" homelab. See
 * LocalWebsiteController/StaticSiteProvisioner.
 *
 * @param domain    the label suffixed with the shared DOMAIN for the default (non-ownDomain) mode; when ownDomain is true this holds the complete FQDN verbatim instead
 * @param ownDomain true if domain already holds a complete domain the user owns (eg. "www.jeltechnologies.com"), rather than a label to suffix with the shared DOMAIN
 */
public record LocalWebsite(String domain, boolean ownDomain) {
}
