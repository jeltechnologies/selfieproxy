package online.selfieproxy.identityprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * issuerUrl mirrors OIDC_ISSUER_URL, the same env var boringproxy reads for
 * its -oidc-issuer flag -- not to be confused with SsoProperties' own
 * issuer-url (this server's own issuer identity). Blank by default (this
 * bundled server is the real IdP), non-blank when an operator has swapped in
 * an external IdP instead. See AdminUserStore.
 */
@ConfigurationProperties(prefix = "oidc")
public record OidcProperties(String issuerUrl) {

	public boolean isExternal() {
		return issuerUrl != null && !issuerUrl.isBlank();
	}
}
