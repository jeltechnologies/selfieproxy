package online.selfieproxy.portal.domain;

/**
 * A static website Selfie Proxy itself hosts and serves, or a domain-level
 * redirect it issues instead -- entirely separate from the Homelab/Exposed
 * App concept: there's no user-run address behind either mode, just a domain
 * pointed at the shared selfieproxy-local-websites container via the hidden
 * "This Server" homelab. See LocalWebsiteController/StaticSiteProvisioner.
 *
 * @param label      the subdomain label suffixed with {@link #domain} to form the FQDN, or blank/null to serve the site at the bare domain itself (apex)
 * @param domain     which registered domain (the primary domain or a secondary one, see DomainService) label is suffixed onto
 * @param redirectTo blank/null for the default content mode (files uploaded through the portal); a bare {@code scheme://host} to instead have this domain issue an HTTP 301 to it (see RedirectUrlValidator)
 */
public record LocalWebsite(String label, String domain, String redirectTo) {

	public String fqdn() {
		return label == null || label.isBlank() ? domain : label + "." + domain;
	}

	public boolean isRedirect() {
		return redirectTo != null && !redirectTo.isBlank();
	}
}
