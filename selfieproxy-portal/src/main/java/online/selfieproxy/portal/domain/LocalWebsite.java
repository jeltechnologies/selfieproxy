package online.selfieproxy.portal.domain;

/**
 * A static website Selfie Proxy itself hosts and serves -- entirely separate
 * from the Homelab/Exposed App concept: there's no user-run address behind
 * it, no protocol/TLS-mode choice, just a domain pointed at the shared
 * selfieproxy-local-websites container via the hidden "This Server" homelab. See
 * LocalWebsiteController/StaticSiteProvisioner.
 *
 * @param label  the subdomain label suffixed with {@link #domain} to form the FQDN
 * @param domain which registered domain (the primary domain or a secondary one, see DomainService) label is suffixed onto
 */
public record LocalWebsite(String label, String domain) {

	public String fqdn() {
		return label + "." + domain;
	}
}
