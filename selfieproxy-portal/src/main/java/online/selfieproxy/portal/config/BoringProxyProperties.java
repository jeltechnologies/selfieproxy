package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * domain/adminSubdomain/portalSubdomain/authSubdomain come from the same .env
 * boringproxy itself uses (DOMAIN, REVERSE_PROXY_LISTENER_SUBDOMAIN,
 * SELFPROXY_ADMIN_DOMAIN, SELFPROXY_AUTH_DOMAIN) -- see application.properties.
 */
@ConfigurationProperties(prefix = "boringproxy")
public record BoringProxyProperties(String domain, String adminSubdomain, String portalSubdomain,
		String authSubdomain) {

	public String adminDomain() {
		return adminSubdomain + "." + domain;
	}

	public String portalDomain() {
		return portalSubdomain + "." + domain;
	}

	public String restBaseUrl() {
		return "https://" + adminDomain() + "/rest";
	}

	public String fqdn(String subdomain) {
		return subdomain + "." + domain;
	}
}
