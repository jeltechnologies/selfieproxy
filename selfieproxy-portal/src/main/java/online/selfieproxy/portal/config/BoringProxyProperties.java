package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * primaryDomain/adminSubdomain/portalSubdomain/authSubdomain come from the same
 * .env boringproxy itself uses (PRIMARY_DOMAIN, REVERSE_PROXY_LISTENER_SUBDOMAIN,
 * SELFPROXY_ADMIN_DOMAIN, SELFPROXY_AUTH_DOMAIN) -- see application.properties.
 * The primary domain can never be changed or removed -- it's needed to reach the
 * portal/identity-provider before any secondary domain (see DomainStore) exists.
 */
@ConfigurationProperties(prefix = "boringproxy")
public record BoringProxyProperties(String primaryDomain, String adminSubdomain, String portalSubdomain,
		String authSubdomain) {

	public String adminDomain() {
		return adminSubdomain + "." + primaryDomain;
	}

	public String portalDomain() {
		return portalSubdomain + "." + primaryDomain;
	}

	public String restBaseUrl() {
		return "https://" + adminDomain() + "/rest";
	}
}
