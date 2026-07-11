package online.selfieproxy.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * domain/adminSubdomain/portalSubdomain come from the same .env boringproxy
 * itself uses (DOMAIN, BORING_PROXY_ADMIN_SUBDOMAIN, SELFIEPROXY_SUBDOMAIN)
 * -- see application.properties.
 */
@ConfigurationProperties(prefix = "boringproxy")
public record BoringProxyProperties(String domain, String adminSubdomain, String portalSubdomain) {

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
